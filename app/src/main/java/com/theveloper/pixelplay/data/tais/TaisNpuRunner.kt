package com.theveloper.pixelplay.data.tais

import android.content.Context
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Optional LiteRT AOT runtime for a separately validated Google Tensor model.
 *
 * The caller must validate the compiled artifact and its manifest before [open]. LiteRT 2.2
 * automatically permits CPU kernels alongside Accelerator.NPU for partially compiled models;
 * requesting this accelerator alone is not proof that every operation executed on the TPU.
 */
class TaisNpuRunner private constructor(
    private val environment: Environment,
    private val model: CompiledModel,
    private val inputs: List<TensorBuffer>,
    private val outputs: List<TensorBuffer>
) : AutoCloseable {
    // Reuse the managed input and both native buffers across every spectrogram chunk.
    private val inputValues = FloatArray(TaisNpuModelContract.MODEL_ELEMENTS)
    private var closed = false

    /**
     * Reads and writes the complete tensor without changing either caller buffer's position.
     * Invalid output is rejected before publishing any samples, so the caller can retry on CPU.
     */
    @Synchronized
    fun run(input: ByteBuffer, output: ByteBuffer) {
        check(!closed) { "The Google Tensor model is closed" }
        requireBuffer(input, "input")
        requireBuffer(output, "output")
        require(!output.isReadOnly) { "The separation output buffer is read-only" }

        input.duplicate().order(input.order()).apply { clear() }.asFloatBuffer().get(inputValues)
        inputs.single().writeFloat(inputValues)
        model.run(inputs, outputs, TaisNpuModelContract.SIGNATURE_KEY)

        // LiteRT 2.2's public Kotlin API has no read-into-existing-array overload. Only this
        // output array is allocated per invocation; native TensorBuffers stay allocated.
        val result = outputs.single().readFloat()
        require(result.size == TaisNpuModelContract.MODEL_ELEMENTS) {
            "The Google Tensor output has ${result.size} values; expected ${TaisNpuModelContract.MODEL_ELEMENTS}"
        }
        for (index in result.indices) {
            require(result[index].isFinite()) { "Non-finite Google Tensor output at element $index" }
        }
        output.duplicate().order(output.order()).apply { clear() }.asFloatBuffer().put(result)
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        var firstFailure: Throwable? = null
        for (resource in outputs + inputs + listOf(model, environment)) {
            try {
                resource.close()
            } catch (error: Throwable) {
                val previous = firstFailure
                if (previous == null) firstFailure = error else previous.addSuppressed(error)
            }
        }
        firstFailure?.let { throw it }
    }

    companion object {
        const val DISPATCH_LIBRARY_NAME = "libLiteRtDispatch_GoogleTensor.so"

        /** Loads only the caller's validated local AOT artifact; no model or SDK is downloaded. */
        fun open(context: Context, compiledModelFile: File): TaisNpuRunner {
            require(compiledModelFile.isFile && compiledModelFile.length() > 0L) {
                "The compiled Google Tensor model is unavailable"
            }
            val nativeDirectory = File(requireNotNull(context.applicationInfo.nativeLibraryDir) {
                "The installed app has no native library directory"
            })
            val dispatchLibrary = File(nativeDirectory, DISPATCH_LIBRARY_NAME)
            require(dispatchLibrary.isFile && dispatchLibrary.length() > 0L) {
                "The Google Tensor dispatch library is unavailable for this app ABI"
            }

            val resources = mutableListOf<AutoCloseable>()
            try {
                val environment = Environment.create(
                    context,
                    mapOf(Environment.Option.DispatchLibraryDir to nativeDirectory.absolutePath),
                    enableCompilerCache = false
                ).also(resources::add)
                val model = CompiledModel.create(
                    compiledModelFile.absolutePath,
                    CompiledModel.Options(Accelerator.NPU),
                    environment
                ).also(resources::add)
                validateTensor(
                    model.getInputTensorType(TaisNpuModelContract.INPUT_NAME, TaisNpuModelContract.SIGNATURE_KEY),
                    "input"
                )
                validateTensor(
                    model.getOutputTensorType(TaisNpuModelContract.OUTPUT_NAME, TaisNpuModelContract.SIGNATURE_KEY),
                    "output"
                )
                val inputs = model.createInputBuffers(TaisNpuModelContract.SIGNATURE_KEY)
                    .also { resources.addAll(it) }
                val outputs = model.createOutputBuffers(TaisNpuModelContract.SIGNATURE_KEY)
                    .also { resources.addAll(it) }
                require(inputs.size == 1 && outputs.size == 1) {
                    "The compiled separation model must have exactly one input and one output"
                }
                return TaisNpuRunner(environment, model, inputs, outputs)
            } catch (error: Throwable) {
                // Preserve the original initialization failure and release partial native state.
                for (resource in resources.asReversed()) {
                    try {
                        resource.close()
                    } catch (closeError: Throwable) {
                        error.addSuppressed(closeError)
                    }
                }
                throw error
            }
        }

        private fun validateTensor(type: TensorType, name: String) {
            require(type.elementType == TensorType.ElementType.FLOAT &&
                type.layout?.dimensions == TaisNpuModelContract.SHAPE) {
                "The compiled model $name must be FLOAT32 ${TaisNpuModelContract.SHAPE}, got $type"
            }
            // Transfers below are contiguous NCHW float arrays. Reject a strided tensor instead
            // of silently interpreting padding or a different layout as audio coefficients.
            val strides = type.layout?.strides.orEmpty()
            require(strides.isEmpty() || strides == listOf(3_145_728, 786_432, 256, 1)) {
                "The compiled model $name has an unsupported non-contiguous layout"
            }
        }

        private fun requireBuffer(buffer: ByteBuffer, name: String) {
            require(buffer.capacity() == TaisNpuModelContract.MODEL_BYTES) {
                "The separation $name must contain exactly ${TaisNpuModelContract.MODEL_BYTES} bytes"
            }
            require(buffer.order() == ByteOrder.nativeOrder()) {
                "The separation $name must use native byte order"
            }
        }
    }
}
