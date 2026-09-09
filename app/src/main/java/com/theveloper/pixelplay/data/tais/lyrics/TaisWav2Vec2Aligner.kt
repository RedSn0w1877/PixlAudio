package com.theveloper.pixelplay.data.tais.lyrics

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.FloatBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import timber.log.Timber

/**
 * English wav2vec2 acoustic alignment. Bounded overlapping audio windows produce a single
 * frame timeline; global CTC matches the complete text without guessed lyric boundaries.
 * NNAPI is requested with CPU fallback; requesting a provider does not prove NPU execution.
 */
@Singleton
class TaisWav2Vec2Aligner @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    data class WordTiming(val word: String, val startMs: Int, val endMs: Int)

    private val env: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val sessionLock = Mutex()
    private var cachedSession: OrtSession? = null

    private val vocab: Map<Char, Int> by lazy { loadVocab() }
    private val blankId = 0 // "<pad>" doubles as the CTC blank token, per the model's config (pad_token_id = 0).

    /** Infer audio in windows, then align every known word across the complete timeline. */
    suspend fun align(
        samples: FloatArray,
        words: List<String>,
        onWindowProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ): List<WordTiming> = withContext(Dispatchers.Default) {
        if (words.isEmpty()) return@withContext emptyList()
        val target = buildExtendedTarget(words)
        if (target.tokenIds.size <= 1) return@withContext emptyList()
        val windows = CtcAlignmentCore.windows(samples.size)
        if (windows.isEmpty()) return@withContext emptyList()
        val emissions = ArrayList<FloatArray>(windows.last().endFrame)
        windows.forEachIndexed { index, window ->
            currentCoroutineContext().ensureActive()
            val local = runModel(samples.copyOfRange(window.inputStartSample, window.inputEndSample))
            val first = window.localFirstFrame
            check(local.size >= first + window.keptFrames) {
                "The acoustic model returned incomplete audio frames. Existing lyrics were kept."
            }
            for (frame in first until first + window.keptFrames) emissions.add(local[frame])
            onWindowProgress(index + 1, windows.size)
        }
        val coroutineContext = currentCoroutineContext()
        val path = CtcAlignmentCore.align(emissions.toTypedArray(), target.tokenIds, blankId) {
            coroutineContext.ensureActive()
        } ?: return@withContext emptyList()
        val peaks = FloatArray(target.tokenIds.size)
        path.forEachIndexed { frame, state ->
            if (target.tokenIds[state] != blankId) {
                peaks[state] = maxOf(peaks[state], exp(emissions[frame][target.tokenIds[state]]))
            }
        }
        val evidence = target.wordSymbolRanges.mapNotNull { range ->
            range?.let { (it.first..it.last step 2).map { state -> peaks[state] }.average().toFloat() }
        }
        Timber.tag(TAG).d("Global alignment: %d words, mean acoustic evidence %.3f", words.size, evidence.average())
        check(CtcAlignmentCore.acceptsWordEvidence(evidence)) {
            "The model could not confidently match the vocals. Existing lyrics were kept; try finding synced lyrics."
        }
        wordTimingsFromPath(path, target, words)
    }

    // ---------------------------------------------------------------------------------------
    // Model execution
    // ---------------------------------------------------------------------------------------

    private suspend fun getSession(): OrtSession = sessionLock.withLock {
        cachedSession?.let { return it }
        // Loading via a file path (not a byte[]) matters here, not just as an optimization: this
        // model is ~378MB, and a single contiguous Java byte[] that size blows straight through
        // Android's ~512MB per-app Java heap ceiling once the transient copy overhead of reading
        // it is counted too (confirmed on-device: OutOfMemoryError on exactly this allocation).
        // Handing ONNX Runtime a path instead lets its native code load the model off the Java
        // heap entirely, with no such ceiling.
        val modelPath = ensureModelFileOnDisk().absolutePath

        val session = try {
            OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(4)
                opts.addNnapi()
                env.createSession(modelPath, opts).also {
                    Timber.tag(TAG).d("wav2vec2 loaded with NNAPI requested; actual delegation depends on the driver")
                }
            }
        } catch (e: Exception) {
            // Same story as TaisAiEngine's TFLite NNAPI fallback: not every device/driver accepts
            // every op, so a rejection here means "retry on CPU," not "give up."
            Timber.tag(TAG).w(e, "NNAPI EP unavailable for wav2vec2 aligner, falling back to CPU")
            OrtSession.SessionOptions().use { opts ->
                opts.setIntraOpNumThreads(4)
                env.createSession(modelPath, opts)
            }
        }
        cachedSession = session
        session
    }

    /** Copies the model asset to internal storage on first use (streamed, not read into memory — see [getSession]) and reuses it on every later call/launch. Re-copies if the cached file's size doesn't match the asset, which also self-heals a partial copy left behind by a killed process. */
    private fun ensureModelFileOnDisk(): java.io.File {
        val expectedSize = context.assets.openFd(MODEL_ASSET).use { it.length }
        val outFile = java.io.File(context.filesDir, MODEL_FILE_NAME)
        if (outFile.exists() && outFile.length() == expectedSize) return outFile

        val tempFile = java.io.File(context.filesDir, "$MODEL_FILE_NAME.tmp")
        context.assets.open(MODEL_ASSET).use { input ->
            tempFile.outputStream().use { output -> input.copyTo(output) }
        }
        tempFile.renameTo(outFile)
        return outFile
    }

    /** Runs the acoustic model once over [samplesIn] and returns per-frame log-probabilities (already log-softmaxed) over the 32-token vocab. */
    private suspend fun runModel(samplesIn: FloatArray): Array<FloatArray> {
        val session = getSession()
        val normalized = normalize(samplesIn)
        val shape = longArrayOf(1, normalized.size.toLong())

        OnnxTensor.createTensor(env, FloatBuffer.wrap(normalized), shape).use { inputTensor ->
            session.run(mapOf(INPUT_NAME to inputTensor)).use { result ->
                val output = result.get(OUTPUT_NAME).orElse(null) as? OnnxTensor
                    ?: return emptyArray()
                @Suppress("UNCHECKED_CAST")
                val logits = output.value as Array<Array<FloatArray>> // [1][frames][vocabSize]
                return Array(logits[0].size) { logSoftmax(logits[0][it]) }
            }
        }
    }

    private fun normalize(samples: FloatArray): FloatArray {
        var mean = 0.0
        for (v in samples) mean += v
        mean /= samples.size
        var variance = 0.0
        for (v in samples) {
            val d = v - mean
            variance += d * d
        }
        variance /= samples.size
        val std = sqrt(variance + 1e-7)
        return FloatArray(samples.size) { ((samples[it] - mean) / std).toFloat() }
    }

    private fun logSoftmax(logits: FloatArray): FloatArray {
        var max = Float.NEGATIVE_INFINITY
        for (v in logits) if (v > max) max = v
        var sum = 0.0
        for (v in logits) sum += exp((v - max).toDouble())
        val logSumExp = ln(sum).toFloat() + max
        return FloatArray(logits.size) { logits[it] - logSumExp }
    }

    // ---------------------------------------------------------------------------------------
    // Tokenizing known lyric text into the model's char-level CTC vocab.
    // ---------------------------------------------------------------------------------------

    /** [tokenIds] is the CTC-extended target sequence: blank, sym1, blank, sym2, blank, ..., blank (length = 2*symbolCount + 1). [wordSymbolRanges] maps each input word back to its slice of *extended*-sequence indices — null for a word with no alignable characters (pure digits/punctuation). */
    private class ExtendedTarget(val tokenIds: IntArray, val wordSymbolRanges: List<IntRange?>)

    private fun buildExtendedTarget(words: List<String>): ExtendedTarget {
        val symbols = mutableListOf<Int>()
        val wordSymbolRanges = arrayOfNulls<IntRange>(words.size)
        var previousWordEmitted = false
        for (i in words.indices) {
            val chars = words[i].uppercase().filter { it == '\'' || it in 'A'..'Z' }
            if (chars.isEmpty()) continue
            if (previousWordEmitted) symbols.add(vocab.getValue(WORD_BOUNDARY_CHAR))
            val start = symbols.size
            for (c in chars) symbols.add(vocab[c] ?: continue)
            if (symbols.size == start) continue // every char in this word was somehow still unmapped
            wordSymbolRanges[i] = start until symbols.size
            previousWordEmitted = true
        }

        val extended = IntArray(symbols.size * 2 + 1) // defaults to 0 == blankId
        for (i in symbols.indices) extended[2 * i + 1] = symbols[i]
        val extendedRanges = wordSymbolRanges.map { r -> r?.let { (2 * it.first + 1)..(2 * it.last + 1) } }
        return ExtendedTarget(extended, extendedRanges)
    }

    // ---------------------------------------------------------------------------------------
    // CTC forced alignment — frame-synchronous Viterbi over the extended target sequence. Same
    // construction as torchaudio.functional.forced_align: a target symbol can only be reached
    // from itself, the symbol before it, or two symbols before it (skipping a blank), and that
    // last skip is only legal when it wouldn't merge two identical consecutive real symbols.
    // ---------------------------------------------------------------------------------------

    private fun wordTimingsFromPath(path: IntArray, target: ExtendedTarget, words: List<String>): List<WordTiming> {
        val firstFrame = HashMap<Int, Int>()
        val lastFrame = HashMap<Int, Int>()
        for (t in path.indices) {
            val s = path[t]
            if (target.tokenIds[s] == blankId) continue
            firstFrame.putIfAbsent(s, t)
            lastFrame[s] = t
        }

        val out = ArrayList<WordTiming>(words.size)
        var lastKnownEndFrame = 0
        for (i in words.indices) {
            val range = target.wordSymbolRanges[i]
            if (range == null) {
                out.add(WordTiming(words[i], frameToMs(lastKnownEndFrame), frameToMs(lastKnownEndFrame)))
                continue
            }
            val startFrame = range.mapNotNull { firstFrame[it] }.minOrNull() ?: lastKnownEndFrame
            val endFrame = range.mapNotNull { lastFrame[it] }.maxOrNull() ?: startFrame
            lastKnownEndFrame = endFrame
            out.add(WordTiming(words[i], frameToMs(startFrame), frameToMs(endFrame)))
        }
        return out
    }

    private fun frameToMs(frame: Int): Int = (frame * MS_PER_FRAME).toInt()

    private fun loadVocab(): Map<Char, Int> {
        val json = context.assets.open(VOCAB_ASSET).use { it.reader().readText() }
        val obj = JSONObject(json)
        val map = HashMap<Char, Int>()
        obj.keys().forEach { key ->
            if (key.length == 1) map[key[0]] = obj.getInt(key)
        }
        return map
    }

    private companion object {
        private const val TAG = "TaisWav2Vec2Aligner"
        private const val MODEL_ASSET = "tais/wav2vec2_base_960h_fp32.onnx"
        private const val MODEL_FILE_NAME = "wav2vec2_base_960h_fp32.onnx"
        private const val VOCAB_ASSET = "tais/wav2vec2_vocab.json"
        private const val INPUT_NAME = "input_values"
        private const val OUTPUT_NAME = "logits"
        private const val WORD_BOUNDARY_CHAR = '|'
        // 16000 Hz / 320x conv stride (5*2*2*2*2*2*2) = 50Hz frame rate = 20ms/frame, exactly.
        private const val MS_PER_FRAME = 20.0
    }
}
