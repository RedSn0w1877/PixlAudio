package com.theveloper.pixelplay.data.tais

import android.content.Context
import android.os.Build
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import timber.log.Timber

data class TaisRuntimeStatus(
    val backend: String = "Not loaded",
    val acceleratorConfirmed: Boolean = false,
    val lastInferenceMs: Long = 0L,
    val inferenceCount: Long = 0L,
    val detail: String = "Run an instrumental render or the model benchmark to inspect this device.",
    val model: String = "MDX-Net vocal separation"
)

data class TaisModelInfo(
    val inputShape: IntArray,
    val outputShape: IntArray,
    val inputBytes: Int,
    val outputBytes: Int
)

/**
 * Shared serialization for stem/lyric work. Google Tensor executes only a validated AOT
 * graph through LiteRT's vendor dispatch runtime. The first input also runs on CPU and
 * must pass numerical parity before the TPU is reported active. CPU retries preserve
 * playback/rendering when the accelerator, compiled graph, or device driver fails.
 */
@Singleton
class TaisAiEngine @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    class ModelNotBundledException(assetPath: String) :
        IllegalStateException("TAIS model not bundled: $assetPath")

    private val interpreters = mutableMapOf<String, Interpreter>()
    private val lock = Mutex()
    private val executionLock = Mutex()
    private var npuChecked = false
    private var npuRunner: TaisNpuRunner? = null
    private var npuFile: File? = null
    private var npuManifest: TaisNpuModelContract.Manifest? = null
    private var npuValidated = false
    private var npuDetail = "CPU inference is active on this device."
    private val _runtimeStatus = MutableStateFlow(TaisRuntimeStatus())
    val runtimeStatus = _runtimeStatus.asStateFlow()

    suspend fun <T> runExclusive(block: suspend () -> T): T = executionLock.withLock { block() }

    /** Metadata can be read without keeping a second CPU model resident beside the TPU. */
    suspend fun prepareModel(assetPath: String): TaisModelInfo = withContext(Dispatchers.IO) {
        lock.withLock {
            if (assetPath == STEM_ASSET && !npuChecked) {
                npuChecked = true
                if (isTensorG5()) prepareNpu()
            }
            if (assetPath == STEM_ASSET && npuFile != null) {
                return@withLock TaisModelInfo(
                    TaisNpuModelContract.SHAPE.toIntArray(), TaisNpuModelContract.SHAPE.toIntArray(),
                    TaisNpuModelContract.MODEL_BYTES, TaisNpuModelContract.MODEL_BYTES
                )
            }
            val cpu = cpuModel(assetPath)
            _runtimeStatus.update { it.copy(backend = "CPU · XNNPACK", acceleratorConfirmed = false, detail = npuDetail) }
            TaisModelInfo(cpu.getInputTensor(0).shape(), cpu.getOutputTensor(0).shape(),
                cpu.getInputTensor(0).numBytes(), cpu.getOutputTensor(0).numBytes())
        }
    }

    /** Caller holds [lock] or the shared [executionLock]. */
    private fun cpuModel(assetPath: String): Interpreter = interpreters.getOrPut(assetPath) {
        val model = try { loadModelFileFromAssets(assetPath) }
        catch (_: java.io.FileNotFoundException) { throw ModelNotBundledException(assetPath) }
        Interpreter(model, Interpreter.Options().apply {
            setNumThreads(4)
            setUseXNNPACK(true)
        })
    }

    private suspend fun prepareNpu() {
        try {
            val manifests = context.assets.list("tais").orEmpty().toSet()
            val assetModelAvailable = NPU_ASSET_NAME in manifests && NPU_MANIFEST_NAME in manifests
            val externalDir = File(context.filesDir, COMPILED_MODEL_DIRECTORY)
            val externalModel = File(externalDir, NPU_ASSET_NAME)
            val externalManifest = File(externalDir, NPU_MANIFEST_NAME)
            val externalModelAvailable = externalModel.isFile && externalModel.length() > 0L &&
                externalManifest.isFile && externalManifest.length() > 0L
            if (!assetModelAvailable && !externalModelAvailable) {
                npuDetail = "Tensor G5 detected. The TPU runtime is included, but no compiler-produced separation model is available. CPU is active."
                return
            }
            // Keep the bundled model as the release path, while accepting a model delivered by
            // a future signed model pack. Private app storage is used so an arbitrary external
            // file cannot be selected by another app; the manifest/hash/source checks below still
            // gate every artifact before LiteRT opens it.
            val json = if (assetModelAvailable) {
                context.assets.open(NPU_MANIFEST).bufferedReader().use { it.readText() }
            } else {
                externalManifest.readText()
            }
            val sourceHash = assetHash(STEM_ASSET)
            val compiledHash = if (assetModelAvailable) assetHash(NPU_ASSET) else fileHash(externalModel)
            val manifest = TaisNpuModelContract.parseAndValidate(json, compiledHash, sourceHash)
            currentCoroutineContext().ensureActive()
            val compiledFile = if (assetModelAvailable) materializeCompiledAsset(compiledHash) else externalModel
            currentCoroutineContext().ensureActive()
            npuFile = compiledFile
            npuManifest = manifest
            npuDetail = "Tensor G5 compiled model verified. Its first inference must match the CPU reference before TPU execution is confirmed."
            _runtimeStatus.update { it.copy(backend = "Tensor G5 TPU · awaiting validation", acceleratorConfirmed = false, detail = npuDetail) }
        } catch (cancelled: CancellationException) {
            npuChecked = false
            throw cancelled
        } catch (error: Exception) {
            disableNpu("TPU initialization failed: " + error.message.orEmpty().take(240))
        } catch (error: LinkageError) {
            disableNpu("TPU driver/runtime unavailable: " + error.message.orEmpty().take(240))
        }
    }

    /** The same chunk is retained on CPU if TPU initialization, execution, or parity fails. */
    suspend fun runInference(assetPath: String, input: ByteBuffer, output: ByteBuffer) {
        prepareModel(assetPath)
        currentCoroutineContext().ensureActive()
        val started = SystemClock.elapsedRealtime()
        val compiledFile = npuFile.takeIf { assetPath == STEM_ASSET }
        if (compiledFile == null) {
            runCpu(assetPath, input, output)
        } else {
            // Do not keep the two native model arenas resident during reference inference.
            // A failed CPU reference propagates instead of publishing a partial buffer.
            val reference = if (!npuValidated) {
                closeNpuRunner()
                ByteBuffer.allocateDirect(output.capacity()).order(ByteOrder.nativeOrder()).also {
                    try {
                        runCpu(assetPath, input, it)
                        currentCoroutineContext().ensureActive()
                    } finally {
                        closeCpuModel(assetPath)
                    }
                }
            } else null
            try {
                currentCoroutineContext().ensureActive()
                val runner = npuRunner ?: TaisNpuRunner.open(context, compiledFile).also { npuRunner = it }
                currentCoroutineContext().ensureActive()
                runner.run(input, output)
                currentCoroutineContext().ensureActive()
                TaisNpuModelContract.validateOutput(output)
                if (reference != null) {
                    val parity = TaisNpuModelContract.compareOutputs(reference, output, npuManifest!!.precision)
                    check(parity.passed) { "TPU/CPU numerical parity failed: " + parity.detail }
                    currentCoroutineContext().ensureActive()
                    npuValidated = true
                    npuDetail = "Google Tensor dispatch executed successfully and passed CPU numerical parity. " +
                        "Compiled partitions use the TPU; remaining operations may use CPU. " + parity.detail
                    _runtimeStatus.update { it.copy(backend = "Tensor G5 TPU · LiteRT", acceleratorConfirmed = true, detail = npuDetail) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                disableNpu("TPU inference rejected; CPU retained this chunk: " + error.message.orEmpty().take(240))
                currentCoroutineContext().ensureActive()
                if (reference != null) copyBuffer(reference, output) else runCpu(assetPath, input, output)
            } catch (error: LinkageError) {
                disableNpu("TPU runtime failed; CPU retained this chunk: " + error.message.orEmpty().take(240))
                currentCoroutineContext().ensureActive()
                if (reference != null) copyBuffer(reference, output) else runCpu(assetPath, input, output)
            }
        }
        currentCoroutineContext().ensureActive()
        _runtimeStatus.update { it.copy(lastInferenceMs = SystemClock.elapsedRealtime() - started, inferenceCount = it.inferenceCount + 1) }
    }

    private fun runCpu(assetPath: String, input: ByteBuffer, output: ByteBuffer) {
        input.rewind()
        output.clear()
        cpuModel(assetPath).run(input, output)
        output.rewind()
        if (assetPath == STEM_ASSET) TaisNpuModelContract.validateOutput(output)
    }

    private fun disableNpu(reason: String) {
        closeNpuRunner()
        npuFile = null
        npuManifest = null
        npuValidated = false
        npuDetail = reason
        _runtimeStatus.update { it.copy(backend = "CPU · XNNPACK", acceleratorConfirmed = false, detail = reason) }
        Timber.tag(TAG).w(reason)
    }

    private fun closeNpuRunner() {
        val runner = npuRunner
        npuRunner = null
        runCatching { runner?.close() }.onFailure { Timber.tag(TAG).w(it, "TPU cleanup failed") }
    }

    private fun closeCpuModel(assetPath: String) {
        val interpreter = interpreters.remove(assetPath)
        runCatching { interpreter?.close() }.onFailure { Timber.tag(TAG).w(it, "CPU model cleanup failed") }
    }

    /** Explicit settings action: nonzero synthetic input, warm-up, then measured inference. */
    suspend fun benchmarkStemModel(): TaisRuntimeStatus = withContext(Dispatchers.Default) {
        runExclusive {
            val hadAudioParity = npuValidated
            var completed = false
            try {
                val info = prepareModel(STEM_ASSET)
                val input = ByteBuffer.allocateDirect(info.inputBytes).order(ByteOrder.nativeOrder())
                val output = ByteBuffer.allocateDirect(info.outputBytes).order(ByteOrder.nativeOrder())
                // Deterministic varied values exercise the graph more than an all-zero input.
                var index = 0
                while (input.remaining() >= 4) input.putFloat(kotlin.math.sin(index++ * 0.017).toFloat() * 0.05f)
                runInference(STEM_ASSET, input, output)
                runInference(STEM_ASSET, input, output)
                completed = true
                _runtimeStatus.update { it.copy(detail = npuDetail + " Synthetic benchmark completed; audio quality still requires listening.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                _runtimeStatus.update { it.copy(detail = "Model benchmark failed: " + error.message.orEmpty().take(240)) }
            } finally {
                // Synthetic data can verify dispatch and timing but cannot skip the first
                // real-audio reference comparison. Preserve any earlier audio validation.
                if (!hadAudioParity) {
                    npuValidated = false
                    if (completed && npuFile != null) {
                        npuDetail = "Synthetic dispatch benchmark completed. The first real audio chunk still requires CPU numerical parity."
                        _runtimeStatus.update { it.copy(detail = npuDetail + " Audio quality still requires listening.") }
                    }
                }
            }
            _runtimeStatus.value
        }
    }

    suspend fun unloadModel(assetPath: String) = executionLock.withLock {
        lock.withLock {
            closeCpuModel(assetPath)
            if (assetPath == STEM_ASSET) resetNpu()
        }
    }

    suspend fun releaseAll() = executionLock.withLock {
        lock.withLock {
            interpreters.keys.toList().forEach(::closeCpuModel)
            resetNpu()
        }
    }

    private fun resetNpu() {
        closeNpuRunner()
        npuFile = null
        npuManifest = null
        npuChecked = false
        npuValidated = false
        npuDetail = "CPU inference is active on this device."
        _runtimeStatus.value = TaisRuntimeStatus()
    }

    private fun assetHash(path: String): String = context.assets.open(path).use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun materializeCompiledAsset(hash: String): File {
        val dir = File(context.filesDir, "tais_compiled_models").apply { mkdirs() }
        val target = File(dir, hash + ".tflite")
        if (target.isFile && fileHash(target) == hash) return target
        val temporary = File.createTempFile("tensor_g5_", ".part", dir)
        try {
            context.assets.open(NPU_ASSET).use { input ->
                temporary.outputStream().use { output -> input.copyTo(output); output.fd.sync() }
            }
            check(fileHash(temporary) == hash) { "Compiled model copy failed checksum verification" }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return target
        } finally { temporary.delete() }
    }

    private fun fileHash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun copyBuffer(source: ByteBuffer, destination: ByteBuffer) {
        destination.clear()
        destination.put(source.duplicate().apply { clear() })
        destination.rewind()
    }

    private fun isTensorG5() = Build.VERSION.SDK_INT >= 31 &&
        Build.MANUFACTURER.equals("Google", ignoreCase = true) && Build.SOC_MODEL.contains("G5", ignoreCase = true)

    private fun loadModelFileFromAssets(assetPath: String): MappedByteBuffer =
        context.assets.openFd(assetPath).use { asset ->
            FileInputStream(asset.fileDescriptor).use { input ->
                input.channel.map(FileChannel.MapMode.READ_ONLY, asset.startOffset, asset.declaredLength)
            }
        }

    private companion object {
        const val TAG = "TaisAiEngine"
        const val STEM_ASSET = "tais/stem_separation.tflite"
        const val NPU_ASSET_NAME = "stem_separation_tensor_g5.tflite"
        const val NPU_MANIFEST_NAME = "stem_separation_tensor_g5.json"
        const val NPU_ASSET = "tais/stem_separation_tensor_g5.tflite"
        const val NPU_MANIFEST = "tais/stem_separation_tensor_g5.json"
        const val COMPILED_MODEL_DIRECTORY = "tais_compiled_models"
    }
}
