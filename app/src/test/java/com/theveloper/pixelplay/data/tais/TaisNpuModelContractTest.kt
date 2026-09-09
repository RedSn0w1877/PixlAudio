package com.theveloper.pixelplay.data.tais

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaisNpuModelContractTest {
    private val compiledHash = "a".repeat(64)
    private val tensor = TaisNpuModelContract.TensorSpec("float32", listOf(1, 4, 3072, 256), 12_582_912, "args_0")
    private val manifest = TaisNpuModelContract.Manifest(
        schemaVersion = 1, target = "Tensor_G5",
        manufacturer = "Google", signatureKey = "serving_default",
        sourceSha256 = TaisNpuModelContract.SOURCE_SHA256, compiledSha256 = compiledHash,
        litertVersion = "2.2.0", dispatchOpCount = 1, partitionCount = 1,
        input = tensor, output = tensor.copy(name = "output_0"), precision = "float16",
        customOpCodes = listOf("DISPATCH_OP")
    )

    @Test fun `compiler manifest round trips and accepts verified model hash`() {
        val encoded = Json.encodeToString(manifest)
        assertEquals(manifest, TaisNpuModelContract.parseAndValidate(encoded, compiledHash))
    }

    @Test fun `source model cannot masquerade as a compiled dispatch model`() {
        val disguised = manifest.copy(compiledSha256 = manifest.sourceSha256)
        assertThrows(IllegalArgumentException::class.java) {
            TaisNpuModelContract.validate(disguised, disguised.compiledSha256)
        }
    }

    @Test fun `wrong target runtime and source are rejected before inference`() {
        val invalid = listOf(
            manifest.copy(target = "Tensor_G4"), manifest.copy(litertVersion = "2.1.0"),
            manifest.copy(sourceSha256 = "b".repeat(64)), manifest.copy(schemaVersion = 2),
            manifest.copy(manufacturer = "Qualcomm"), manifest.copy(signatureKey = "renamed")
        )
        invalid.forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) {
                TaisNpuModelContract.validate(candidate, compiledHash)
            }
        }
    }

    @Test fun `changed or malformed artifact hash is rejected`() {
        for (actual in listOf("b".repeat(64), "invalid")) {
            assertThrows(IllegalArgumentException::class.java) {
                TaisNpuModelContract.validate(manifest, actual)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaisNpuModelContract.validate(manifest, compiledHash, "c".repeat(64))
        }
    }

    @Test fun `zero dispatch or partitions is not an NPU model`() {
        listOf(manifest.copy(dispatchOpCount = 0), manifest.copy(partitionCount = 0),
            manifest.copy(customOpCodes = emptyList())).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                TaisNpuModelContract.validate(it, compiledHash)
            }
        }
    }

    @Test fun `transposed quantized truncated and dynamic tensor contracts are rejected`() {
        val invalid = listOf(
            tensor.copy(shape = listOf(1, 3072, 256, 4)), tensor.copy(dtype = "float16"),
            tensor.copy(byteSize = tensor.byteSize / 2), tensor.copy(shape = listOf(1, 4, 3072, -1))
        )
        invalid.forEach { wrongTensor ->
            assertThrows(IllegalArgumentException::class.java) {
                TaisNpuModelContract.validate(manifest.copy(input = wrongTensor), compiledHash)
            }
            assertThrows(IllegalArgumentException::class.java) {
                TaisNpuModelContract.validate(manifest.copy(output = wrongTensor.copy(name = "output_0")), compiledHash)
            }
        }
    }

    @Test fun `finite output validation preserves buffer state and examines its entire tensor`() {
        val buffer = buffer(0f)
        buffer.position(12)
        buffer.limit(16)
        TaisNpuModelContract.validateOutput(buffer)
        assertEquals(12, buffer.position())
        assertEquals(16, buffer.limit())
        assertEquals(ByteOrder.LITTLE_ENDIAN, buffer.order())
        buffer.duplicate().order(buffer.order()).apply { clear() }
            .putFloat(TaisNpuModelContract.MODEL_BYTES - 4, Float.NaN)
        assertThrows(IllegalArgumentException::class.java) { TaisNpuModelContract.validateOutput(buffer) }
    }

    @Test fun `signature tensor names cannot silently change`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaisNpuModelContract.validate(manifest.copy(input = tensor.copy(name = "serving_default_args_0:0")), compiledHash)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TaisNpuModelContract.validate(manifest.copy(output = manifest.output.copy(name = "StatefulPartitionedCall:0")), compiledHash)
        }
    }

    @Test fun `truncated tensor buffer cannot pass validation`() {
        assertThrows(IllegalArgumentException::class.java) {
            TaisNpuModelContract.validateOutput(ByteBuffer.allocate(4))
        }
    }

    @Test fun `small reduced precision changes pass numerical parity without claiming audio quality`() {
        val cpu = buffer(1f)
        val npu = buffer(1.005f)
        cpu.position(8)
        npu.position(16)
        val result = TaisNpuModelContract.compareOutputs(cpu, npu, "float16")
        assertTrue(result.finite)
        assertTrue(result.passed)
        assertEquals(0.005, result.normalizedRmse, 0.00001)
        assertTrue(result.detail.contains("audio quality remains unmeasured"))
        assertEquals(8, cpu.position())
        assertEquals(16, npu.position())
        assertFalse(TaisNpuModelContract.compareOutputs(cpu, npu, "float32").passed)
    }

    @Test fun `gain drift fails even when cosine is perfect`() {
        val result = TaisNpuModelContract.compareOutputs(buffer(1f), buffer(1.1f), "float16")
        assertEquals(1.0, result.cosineSimilarity, 0.000001)
        assertFalse(result.passed)
    }

    @Test fun `phase inversion cannot pass parity`() {
        val result = TaisNpuModelContract.compareOutputs(buffer(1f), buffer(-1f), "float16")
        assertEquals(-1.0, result.cosineSimilarity, 0.000001)
        assertFalse(result.passed)
    }

    @Test fun `silent outputs pass but silent reference cannot hide an output spike`() {
        val cpu = buffer(0f)
        val npu = buffer(0f)
        assertTrue(TaisNpuModelContract.compareOutputs(cpu, npu).passed)
        npu.putFloat(0, 0.01f)
        assertFalse(TaisNpuModelContract.compareOutputs(cpu, npu).passed)
    }

    @Test fun `non finite candidate or reference fails numerical parity`() {
        val cpu = buffer(1f)
        val npu = buffer(1f)
        npu.putFloat(0, Float.POSITIVE_INFINITY)
        assertFalse(TaisNpuModelContract.compareOutputs(cpu, npu).finite)
        npu.putFloat(0, 1f)
        cpu.putFloat(0, Float.NaN)
        assertFalse(TaisNpuModelContract.compareOutputs(cpu, npu).passed)
    }

    @Test fun `caller may tighten reduced precision tolerance`() {
        val strict = TaisNpuModelContract.ParityTolerance(maxNormalizedRmse = 0.001)
        assertFalse(TaisNpuModelContract.compareOutputs(buffer(1f), buffer(1.005f), "float16", strict).passed)
        assertThrows(IllegalArgumentException::class.java) {
            TaisNpuModelContract.ParityTolerance(maxNormalizedRmse = Double.NaN)
        }
    }

    private fun buffer(value: Float): ByteBuffer =
        ByteBuffer.allocate(TaisNpuModelContract.MODEL_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
            if (value != 0f) for (index in 0 until TaisNpuModelContract.MODEL_ELEMENTS) putFloat(index * 4, value)
        }
}
