package com.theveloper.pixelplay.data.tais

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The AOT model must preserve the existing four-plane, NCHW STFT contract.
 * A manifest validates a packaged artifact's identity; it does not prove hardware execution.
 * The runtime must separately observe a successful invocation of the Google dispatch graph.
 */
object TaisNpuModelContract {
    const val TARGET_SOC = "Tensor_G5"
    const val SOURCE_SHA256 = "5ef47e3b3bafa14357532c0a3f6c5f18444d94b6efe3fd62b3d13f80051f1e58"
    const val LITERT_VERSION = "2.2.0"
    const val SIGNATURE_KEY = "serving_default"
    const val INPUT_NAME = "args_0"
    const val OUTPUT_NAME = "output_0"
    const val MODEL_ELEMENTS = 3_145_728
    const val MODEL_BYTES = 12_582_912
    val SHAPE: List<Int> = listOf(1, 4, 3072, 256)

    private val json = Json { ignoreUnknownKeys = true }
    private val sha256Pattern = Regex("^[0-9a-fA-F]{64}$")

    @Serializable
    data class TensorSpec(val dtype: String, val shape: List<Int>, val byteSize: Int, val name: String)

    @Serializable
    data class Manifest(
        val schemaVersion: Int,
        val target: String,
        val manufacturer: String,
        val signatureKey: String,
        val sourceSha256: String,
        val compiledSha256: String,
        val litertVersion: String,
        val dispatchOpCount: Int,
        val partitionCount: Int,
        val input: TensorSpec,
        val output: TensorSpec,
        val precision: String = "unknown",
        val compilerVersion: String? = null,
        val customOpCodes: List<String> = emptyList(),
        val compiledFile: String? = null,
        val createdAtUtc: String? = null
    )

    fun parseAndValidate(
        manifestJson: String,
        actualCompiledSha256: String,
        actualSourceSha256: String = SOURCE_SHA256
    ): Manifest = validate(
        json.decodeFromString<Manifest>(manifestJson), actualCompiledSha256, actualSourceSha256
    )

    fun validate(
        manifest: Manifest,
        actualCompiledSha256: String,
        actualSourceSha256: String = SOURCE_SHA256
    ): Manifest {
        require(manifest.schemaVersion == 1) { "Unsupported NPU manifest schema" }
        require(manifest.target == TARGET_SOC) { "The compiled model targets a different processor" }
        require(manifest.manufacturer == "Google") { "The compiled model targets a different NPU vendor" }
        require(manifest.signatureKey == SIGNATURE_KEY) { "The compiled model signature has changed" }
        require(manifest.litertVersion == LITERT_VERSION) { "The compiled model requires a different LiteRT version" }
        listOf(manifest.sourceSha256, manifest.compiledSha256, actualSourceSha256, actualCompiledSha256)
            .forEach { require(sha256Pattern.matches(it)) { "Invalid model SHA-256" } }
        require(manifest.sourceSha256.equals(SOURCE_SHA256, ignoreCase = true) &&
            actualSourceSha256.equals(SOURCE_SHA256, ignoreCase = true)) {
            "The compiled model was built from a different separation model"
        }
        require(manifest.compiledSha256.equals(actualCompiledSha256, ignoreCase = true)) {
            "The compiled model does not match its manifest"
        }
        require(!manifest.compiledSha256.equals(manifest.sourceSha256, ignoreCase = true)) {
            "The CPU model cannot be used as a compiled NPU artifact"
        }
        require(manifest.dispatchOpCount > 0 && manifest.partitionCount > 0) {
            "The compiled model contains no recorded NPU dispatch partitions"
        }
        require("DISPATCH_OP" in manifest.customOpCodes) { "The compiled model has no recorded dispatch operation" }
        require(manifest.precision in setOf("float32", "float16", "bfloat16", "unknown")) {
            "Unrecognized compiled model precision"
        }
        validateTensor(manifest.input, "input", INPUT_NAME)
        validateTensor(manifest.output, "output", OUTPUT_NAME)
        return manifest
    }

    private fun validateTensor(tensor: TensorSpec, name: String, signatureName: String) {
        require(tensor.dtype == "float32" && tensor.shape == SHAPE && tensor.byteSize == MODEL_BYTES &&
            tensor.name == signatureName) {
            "The compiled model $name does not match FLOAT32 $SHAPE ($MODEL_BYTES bytes)"
        }
    }

    /** Reads the entire tensor without changing position, limit, or byte order. */
    fun validateOutput(output: ByteBuffer) {
        requireTensorBuffer(output)
        val values = output.duplicate().order(output.order()).apply { clear() }
        for (index in 0 until MODEL_ELEMENTS) {
            require(values.getFloat(index * Float.SIZE_BYTES).isFinite()) {
                "Non-finite separation output at element $index"
            }
        }
    }

    data class ParityTolerance(
        val maxNormalizedRmse: Double = 0.02,
        val minCosineSimilarity: Double = 0.995,
        val absoluteRmseTolerance: Double = 1e-5,
        val nearZeroReferenceRms: Double = 1e-6,
        val maxNearZeroAbsoluteError: Double = 1e-4
    ) {
        init {
            require(maxNormalizedRmse.isFinite() && maxNormalizedRmse > 0.0)
            require(minCosineSimilarity.isFinite() && minCosineSimilarity in 0.0..1.0)
            require(absoluteRmseTolerance.isFinite() && absoluteRmseTolerance > 0.0)
            require(nearZeroReferenceRms.isFinite() && nearZeroReferenceRms > 0.0)
            require(maxNearZeroAbsoluteError.isFinite() && maxNearZeroAbsoluteError > 0.0)
        }
    }

    data class ParityResult(
        val finite: Boolean,
        val passed: Boolean,
        val maxAbsoluteError: Double,
        val normalizedRmse: Double,
        val cosineSimilarity: Double,
        val detail: String
    )

    /**
     * A numerical deployment guard, not a separation-quality or listening assessment.
     * Float32 compilation uses a tighter tolerance; reduced/unknown precision permits modest
     * rounding differences. Near-silent references use absolute error, since their normalized
     * error and cosine are undefined or dominated by noise. Callers may supply stricter limits.
     */
    fun compareOutputs(
        cpu: ByteBuffer,
        npu: ByteBuffer,
        precision: String = "unknown",
        tolerance: ParityTolerance = toleranceForPrecision(precision)
    ): ParityResult {
        requireTensorBuffer(cpu)
        requireTensorBuffer(npu)
        val reference = cpu.duplicate().order(cpu.order()).apply { clear() }
        val candidate = npu.duplicate().order(npu.order()).apply { clear() }
        var referenceEnergy = 0.0
        var candidateEnergy = 0.0
        var errorEnergy = 0.0
        var dotProduct = 0.0
        var maxAbsoluteError = 0.0
        for (index in 0 until MODEL_ELEMENTS) {
            val expected = reference.getFloat(index * Float.SIZE_BYTES).toDouble()
            val actual = candidate.getFloat(index * Float.SIZE_BYTES).toDouble()
            if (!expected.isFinite() || !actual.isFinite()) {
                return ParityResult(false, false, Double.POSITIVE_INFINITY,
                    Double.POSITIVE_INFINITY, 0.0, "Non-finite output at element $index")
            }
            val error = expected - actual
            referenceEnergy += expected * expected
            candidateEnergy += actual * actual
            errorEnergy += error * error
            dotProduct += expected * actual
            maxAbsoluteError = max(maxAbsoluteError, abs(error))
        }
        val referenceRms = sqrt(referenceEnergy / MODEL_ELEMENTS)
        val errorRms = sqrt(errorEnergy / MODEL_ELEMENTS)
        val nearZero = referenceRms <= tolerance.nearZeroReferenceRms
        val normalizedRmse = errorRms / max(referenceRms, tolerance.nearZeroReferenceRms)
        val cosine = when {
            referenceEnergy == 0.0 && candidateEnergy == 0.0 -> 1.0
            referenceEnergy == 0.0 || candidateEnergy == 0.0 -> 0.0
            else -> (dotProduct / sqrt(referenceEnergy * candidateEnergy)).coerceIn(-1.0, 1.0)
        }
        val passed = if (nearZero) {
            errorRms <= tolerance.absoluteRmseTolerance &&
                maxAbsoluteError <= tolerance.maxNearZeroAbsoluteError
        } else {
            normalizedRmse <= tolerance.maxNormalizedRmse && cosine >= tolerance.minCosineSimilarity
        }
        return ParityResult(
            finite = true,
            passed = passed,
            maxAbsoluteError = maxAbsoluteError,
            normalizedRmse = normalizedRmse,
            cosineSimilarity = cosine,
            detail = if (passed) "Numerical parity passed ($precision); audio quality remains unmeasured"
                else "Numerical parity failed ($precision); CPU output must be used"
        )
    }

    fun toleranceForPrecision(precision: String): ParityTolerance =
        if (precision == "float32") ParityTolerance(maxNormalizedRmse = 0.001, minCosineSimilarity = 0.9999)
        else ParityTolerance()

    private fun requireTensorBuffer(buffer: ByteBuffer) {
        require(buffer.capacity() == MODEL_BYTES) {
            "Expected $MODEL_BYTES bytes for the separation tensor, got ${buffer.capacity()}"
        }
    }
}
