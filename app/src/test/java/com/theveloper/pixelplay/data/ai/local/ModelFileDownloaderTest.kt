package com.theveloper.pixelplay.data.ai.local

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest

class ModelFileDownloaderTest {

    private val dir: File = Files.createTempDirectory("model-downloader-test").toFile()
    private val part = File(dir, "llm/model.litertlm.part")
    private val final = File(dir, "llm/model.litertlm")

    private val payload = ByteArray(300_000) { (it * 31 % 251).toByte() }
    private val spec = DownloadedModelSpec(
        id = "test",
        displayName = "Test model",
        fileName = "model.litertlm",
        url = "https://example.invalid/model.litertlm",
        sizeBytes = payload.size.toLong(),
        sha256 = sha256(payload),
        licenseName = "Apache License 2.0",
        licenseUrl = "https://example.invalid",
    )

    @AfterEach
    fun cleanUp() {
        dir.deleteRecursively()
    }

    /** Serves [payload], honouring Range when [supportsRange]; records every offset asked for. */
    private inner class FakeSource(
        private val supportsRange: Boolean = true,
        private val body: ByteArray = payload,
        private val failAfterBytes: Int? = null,
        private val code: Int? = null,
    ) : RangedSource {
        val offsets = mutableListOf<Long>()
        override fun open(offset: Long): RangedResponse {
            offsets += offset
            code?.let { return RangedResponse(it, 0, ByteArrayInputStream(ByteArray(0))) }
            return if (offset > 0 && supportsRange) {
                val slice = body.copyOfRange(offset.toInt(), body.size)
                RangedResponse(206, slice.size.toLong(), stream(slice))
            } else {
                RangedResponse(200, body.size.toLong(), stream(body))
            }
        }

        private fun stream(bytes: ByteArray): InputStream {
            val limit = failAfterBytes ?: return ByteArrayInputStream(bytes)
            return object : InputStream() {
                var position = 0
                override fun read(): Int {
                    if (position >= limit) throw IOException("connection dropped")
                    if (position >= bytes.size) return -1
                    return bytes[position++].toInt() and 0xFF
                }
            }
        }
    }

    private fun downloader(source: RangedSource, freeBytes: Long = Long.MAX_VALUE) =
        ModelFileDownloader(spec, source, part, final) { freeBytes }

    @Test
    fun `a full download is verified and moved into place`() {
        val progress = mutableListOf<Long>()
        val outcome = downloader(FakeSource()).run(onProgress = { bytes, _ -> progress += bytes })

        assertThat(outcome).isEqualTo(DownloadOutcome.Done)
        assertThat(final.readBytes()).isEqualTo(payload)
        assertThat(part.exists()).isFalse()
        // At most once per percent.
        assertThat(progress.size).isAtMost(101)
        assertThat(progress.last()).isEqualTo(payload.size.toLong())
    }

    @Test
    fun `a dropped download keeps its part and the retry resumes with a Range request`() {
        val first = downloader(FakeSource(failAfterBytes = 100_000)).run(onProgress = { _, _ -> })
        assertThat(first).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((first as DownloadOutcome.Failed).retryable).isTrue()
        assertThat(part.length()).isGreaterThan(0L)

        val resumedFrom = part.length()
        val source = FakeSource()
        assertThat(downloader(source).run(onProgress = { _, _ -> })).isEqualTo(DownloadOutcome.Done)
        assertThat(source.offsets).containsExactly(resumedFrom)
        assertThat(final.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `a server that ignores Range restarts the file from zero`() {
        part.parentFile!!.mkdirs()
        part.writeBytes(payload.copyOfRange(0, 1_000))
        val source = FakeSource(supportsRange = false)

        assertThat(downloader(source).run(onProgress = { _, _ -> })).isEqualTo(DownloadOutcome.Done)
        assertThat(source.offsets).containsExactly(1_000L)
        assertThat(final.readBytes()).isEqualTo(payload)
    }

    @Test
    fun `a checksum mismatch deletes the part file`() {
        val wrong = payload.copyOf().also { it[42] = (it[42] + 1).toByte() }
        val outcome = downloader(FakeSource(body = wrong)).run(onProgress = { _, _ -> })

        assertThat(outcome).isInstanceOf(DownloadOutcome.Failed::class.java)
        assertThat((outcome as DownloadOutcome.Failed).reason).isEqualTo(DownloadFailureReason.CORRUPT)
        assertThat(part.exists()).isFalse()
        assertThat(final.exists()).isFalse()
    }

    @Test
    fun `not enough free space fails before anything downloads`() {
        val source = FakeSource()
        val outcome = downloader(source, freeBytes = 10_000).run(onProgress = { _, _ -> })

        assertThat(outcome).isEqualTo(DownloadOutcome.Failed(DownloadFailureReason.NOT_ENOUGH_SPACE, retryable = false))
        assertThat(source.offsets).isEmpty()
        assertThat(ModelFileDownloader.requiredFreeBytes(1_000, 400)).isEqualTo(600 + 100)
    }

    @Test
    fun `server errors are retryable only when the server may recover`() {
        val unavailable = downloader(FakeSource(code = 503)).run(onProgress = { _, _ -> }) as DownloadOutcome.Failed
        assertThat(unavailable.retryable).isTrue()
        val notFound = downloader(FakeSource(code = 404)).run(onProgress = { _, _ -> }) as DownloadOutcome.Failed
        assertThat(notFound.retryable).isFalse()
        assertThat(notFound.reason).isEqualTo(DownloadFailureReason.HTTP)
    }

    @Test
    fun `a stop keeps the part for the next attempt`() {
        var calls = 0
        val outcome = downloader(FakeSource()).run(onProgress = { _, _ -> }, isStopped = { ++calls > 1 })
        assertThat(outcome).isEqualTo(DownloadOutcome.Stopped)
        assertThat(part.exists()).isTrue()
        assertThat(final.exists()).isFalse()
    }

    @Test
    fun `the download lands in the final file only once complete`() {
        assertThat(final.exists()).isFalse()
        downloader(FakeSource()).run(onProgress = { _, _ -> assertThat(final.exists()).isFalse() })
        assertThat(final.exists()).isTrue()
    }

    @Test
    fun `the model's state follows the file first, then the worker`() {
        val total = 2_000L
        assertThat(DownloadedModelStateMapper.map(1_999L, total, DownloadWorkSnapshot(DownloadWorkSnapshot.Phase.RUNNING, 5)))
            .isEqualTo(DownloadedModelState.Ready(1_999L))
        assertThat(DownloadedModelStateMapper.map(null, total, DownloadWorkSnapshot(DownloadWorkSnapshot.Phase.RUNNING, 500)))
            .isEqualTo(DownloadedModelState.Downloading(500, total))
        assertThat(DownloadedModelStateMapper.map(null, total, DownloadWorkSnapshot(DownloadWorkSnapshot.Phase.QUEUED)))
            .isEqualTo(DownloadedModelState.Queued)
        assertThat(DownloadedModelStateMapper.map(null, total, DownloadWorkSnapshot(DownloadWorkSnapshot.Phase.VERIFYING)))
            .isEqualTo(DownloadedModelState.Verifying)
        assertThat(
            DownloadedModelStateMapper.map(
                null, total, DownloadWorkSnapshot(DownloadWorkSnapshot.Phase.FAILED, failure = DownloadFailureReason.NOT_ENOUGH_SPACE)
            )
        ).isEqualTo(DownloadedModelState.Failed(DownloadFailureReason.NOT_ENOUGH_SPACE))
        assertThat(DownloadedModelStateMapper.map(null, total, DownloadWorkSnapshot(DownloadWorkSnapshot.Phase.CANCELLED)))
            .isEqualTo(DownloadedModelState.NotDownloaded)
        assertThat(DownloadedModelStateMapper.map(null, total, null)).isEqualTo(DownloadedModelState.NotDownloaded)
    }

    @Test
    fun `after Delete a finished download reads as not downloaded, not as still checking`() {
        val total = 2_000L
        val succeeded = DownloadWorkSnapshot(DownloadWorkSnapshot.Phase.SUCCEEDED)
        // Between the worker's rename and the re-read of the file.
        assertThat(DownloadedModelStateMapper.map(null, total, succeeded)).isEqualTo(DownloadedModelState.Verifying)
        assertThat(DownloadedModelStateMapper.map(total, total, succeeded)).isEqualTo(DownloadedModelState.Ready(total))
        // WorkManager keeps the SUCCEEDED record for a day: once the file was re-read and is gone
        // (Delete), the row must offer Download again.
        assertThat(DownloadedModelStateMapper.map(null, total, succeeded.copy(fileChecked = true)))
            .isEqualTo(DownloadedModelState.NotDownloaded)
    }

    @Test
    fun `the pinned model matches the Hugging Face listing`() {
        val gemma = DownloadedModelCatalog.GEMMA_4_E2B
        assertThat(gemma.url).contains("/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/")
        assertThat(gemma.url).startsWith("https://huggingface.co/litert-community/")
        assertThat(gemma.sizeBytes).isEqualTo(2_588_147_712L)
        assertThat(gemma.sha256).hasLength(64)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
