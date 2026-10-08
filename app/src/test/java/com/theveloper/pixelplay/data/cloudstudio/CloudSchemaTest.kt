package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.CloudFixtures.JOB_KEY
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/** The phone decodes, and re-encodes, every golden example the worker's schema ships. */
class CloudSchemaTest {
    private inline fun <reified T> decode(text: String): T = CloudJson.decodeFromString(text)
    private inline fun <reified T> encode(value: T): String = CloudJson.encodeToString(value)

    @ParameterizedTest
    @ValueSource(strings = ["job.input.process.json", "job.input.transcribe.json", "job.input.volume.json",
        "job.input.bench.json", "job.input.selftest.json"])
    fun `worker job inputs round-trip`(name: String) {
        val text = CloudFixtures.worker(name)
        val input = decode<CloudJobInput>(text)
        assertEquals(CloudSchema.VERSION, input.v)
        assertEquals(CloudFixtures.canonical(text), CloudFixtures.canonical(encode(input)))
    }

    @Test fun `the run request round-trips and carries the process input`() {
        val text = CloudFixtures.worker("run.request.json")
        val request = decode<CloudJobRequest>(text)
        assertEquals(CloudJobPolicy(259_200_000, 900_000), request.policy)
        assertEquals(CloudFixtures.canonical(text), CloudFixtures.canonical(encode(request)))
        assertEquals(decode<CloudJobInput>(CloudFixtures.worker("job.input.process.json")), request.input)
    }

    @ParameterizedTest
    @ValueSource(strings = ["job.result.ok.json", "job.result.partial.json", "job.result.error.json", "job.result.poisoned.json"])
    fun `worker results round-trip`(name: String) {
        val text = CloudFixtures.worker(name)
        val result = decode<CloudJobResult>(text)
        assertEquals(CloudSchema.RESULT, result.schema)
        assertTrue(CloudKeys.isValidJobKey(result.jobKey))
        assertEquals(CloudFixtures.canonical(text), CloudFixtures.canonical(encode(result)))
    }

    @ParameterizedTest
    @ValueSource(strings = ["lyrics.aligned.json", "lyrics.transcribed.json"])
    fun `worker lyrics round-trip`(name: String) {
        val text = CloudFixtures.worker(name)
        val lyrics = decode<CloudLyricsDocument>(text)
        assertEquals(CloudSchema.LYRICS, lyrics.schema)
        assertEquals(CloudFixtures.canonical(text), CloudFixtures.canonical(encode(lyrics)))
    }

    @Test fun `selftest and attempt round-trip, and the caps match the app's own limits`() {
        val text = CloudFixtures.worker("selftest.result.json")
        val selftest = decode<CloudSelftestResult>(text)
        assertTrue(selftest.isOk)
        assertEquals(1, selftest.agreedVersion)
        val models = selftest.modelAvailability
        assertEquals(CloudModelAvailability.Loaded, models["anvuew-bs-roformer-ft1"])
        assertEquals(CloudModelAvailability.Lazy, models["qwen3-asr-1.7b"])
        assertTrue(models.values.all { it.isAvailable })
        assertTrue(selftest.wordTimingLanguages!!.contains("ko"))
        assertEquals(JsonPrimitive("1.12.0"), selftest.versions!!["runpod"])
        val caps = selftest.caps!!
        assertEquals(CloudLimits.MAX_INPUT_BYTES, caps.maxInputMB!!.toLong() * 1_048_576)
        assertEquals(CloudLimits.MAX_DURATION_MS, caps.maxAudioS!!.toLong() * 1000)
        assertEquals(CloudLimits.BEST_QUALITY_MAX_DURATION_MS, caps.bestMaxAudioS!!.toLong() * 1000)
        assertEquals(CloudLimits.MAX_LYRICS_LINES, caps.maxLyricsLines)
        assertEquals(CloudLimits.MAX_LYRICS_CHARS, caps.maxLyricsChars)
        assertEquals(1, caps.hostsConfigured)
        assertEquals(CloudFixtures.canonical(text), CloudFixtures.canonical(encode(selftest)))

        val attemptText = CloudFixtures.worker("attempt.json")
        val attempt = decode<CloudAttempt>(attemptText)
        assertEquals(1, attempt.attempts)
        assertEquals(CloudFixtures.canonical(attemptText), CloudFixtures.canonical(encode(attempt)))
    }

    @Test fun `the process input decodes field for field`() {
        val input = decode<CloudJobInput>(CloudFixtures.worker("job.input.process.json"))
        assertEquals("pixl.cloudstudio.job", input.schema)
        assertEquals("process", input.op)
        assertEquals(JOB_KEY, input.jobKey)
        assertEquals("1.0 (412)", input.client?.build)
        assertEquals("presigned", input.storage)
        assertEquals("m4a", input.audio?.ext)
        assertEquals(7_712_345L, input.audio?.bytes)
        assertEquals(241_000L, input.audio?.durationMs)
        assertTrue(input.audio!!.get!!.contains("/in/$JOB_KEY.m4a?"))
        assertEquals(listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS), input.typedTasks)
        assertEquals("standard", input.separation?.quality)
        assertEquals("auto", input.lyrics?.mode)
        assertEquals("ko", input.lyrics?.language)
        assertEquals(true, input.lyrics?.synced)
        assertEquals(CloudLyricsInputLine(12_340, 15_800, "별빛 아래 우리 둘이"), input.lyrics?.lines?.first())
        assertEquals(CloudLyricsInputLine(23_050, null, ""), input.lyrics?.lines?.last())
        assertEquals("aac", input.output?.codec)
        assertEquals(256, input.output?.kbps)
        assertEquals(setOf("instrumental", "lyrics", "manifest"), input.output?.put?.keys)
        assertTrue(input.guard!!.attemptPut.contains("attempt.json"))
    }

    @Test fun `volume, bench and transcribe inputs`() {
        val volume = decode<CloudJobInput>(CloudFixtures.worker("job.input.volume.json"))
        assertEquals("volume", volume.storage)
        assertEquals("in/$JOB_KEY.m4a", volume.audio?.key)
        assertNull(volume.audio?.get)
        assertNull(volume.output?.put)
        val bench = decode<CloudJobInput>(CloudFixtures.worker("job.input.bench.json"))
        assertEquals("bench", bench.op)
        assertEquals(240, bench.bench?.seconds)
        assertTrue(bench.bench!!.stages!!.contains("separate"))
        val transcribe = decode<CloudJobInput>(CloudFixtures.worker("job.input.transcribe.json"))
        assertEquals("flac", transcribe.output?.codec)
        assertNull(transcribe.output?.kbps)
        assertEquals(false, transcribe.lyrics?.synced)
        assertEquals(emptyList<CloudLyricsInputLine>(), transcribe.lyrics?.lines)
        assertEquals("flac", transcribe.audio?.ext)
    }

    @Test fun `the ok manifest decodes field for field`() {
        val result = decode<CloudJobResult>(CloudFixtures.worker("job.result.ok.json"))
        val process = decode<CloudJobInput>(CloudFixtures.worker("job.input.process.json"))
        assertEquals(CloudResultStatus.OK, result.typedStatus)
        assertTrue(result.hasResults)
        assertNull(result.error)
        assertNull(result.errorCode)
        assertEquals("NVIDIA L4", result.worker?.gpu)
        assertEquals(22.5, result.worker?.vramGB)
        assertNull(result.models?.stems4)
        assertEquals("qwen3-forced-aligner-0.6b", result.models?.aligner)
        assertEquals(10_628_100L, result.input?.decodedSamples)
        assertTrue(CloudImportCheck.manifestDescribesUpload(result, process.audio?.sha256))
        val instrumental = result.outputs!!.getValue("instrumental")
        assertEquals("out/$JOB_KEY/instrumental.m4a", instrumental.key)
        assertEquals(44_100, instrumental.sampleRate)
        assertEquals(10_628_100L, instrumental.samples)
        val lyrics = result.lyrics!!
        assertEquals("out/$JOB_KEY/lyrics.json", lyrics.key)
        assertEquals(2_210L, lyrics.bytes)
        assertEquals(64, lyrics.sha256?.length)
        assertEquals(0L, lyrics.offsetMs)
        assertEquals(18_400L, result.timings?.coldStartMs)
    }

    @Test fun `partial, error and poisoned manifests`() {
        val partial = decode<CloudJobResult>(CloudFixtures.worker("job.result.partial.json"))
        assertEquals(CloudResultStatus.PARTIAL, partial.typedStatus)
        assertTrue(partial.hasResults)
        assertNull(partial.lyrics)
        val error = decode<CloudJobResult>(CloudFixtures.worker("job.result.error.json"))
        assertFalse(error.hasResults)
        assertEquals(CloudErrorCode.INPUT_MISMATCH, error.errorCode)
        assertTrue(error.errorCode!!.needsReupload)
        assertNull(error.input)
        val poisoned = decode<CloudJobResult>(CloudFixtures.worker("job.result.poisoned.json"))
        assertEquals(CloudErrorCode.POISONED, poisoned.errorCode)
        assertFalse(poisoned.errorCode!!.isRetryable)
    }

    @Test fun `an unknown error code reads as INTERNAL and an unknown status as error`() {
        val text = """{"schema":"pixl.cloudstudio.result","v":1,"jobKey":"$JOB_KEY","status":"weird",
            "error":{"code":"SOMETHING_NEW"},"newField":{"x":1}}"""
        val result = decode<CloudJobResult>(text)
        assertEquals(CloudResultStatus.ERROR, result.typedStatus)
        assertEquals(CloudErrorCode.INTERNAL, result.errorCode)
        assertTrue(result.errorCode!!.isRetryable)
    }

    @Test fun `the phone's own encoding leaves nulls out and always names schema, v and op`() {
        val text = encode(CloudOpRequest.of(CloudOp.SELFTEST, "0.7.6 (11)"))
        assertEquals(
            """{"input":{"schema":"pixl.cloudstudio.job","v":1,"op":"selftest","client":{"app":"pixlaudio-android","build":"0.7.6 (11)"}}}""",
            text
        )
    }

    @Test fun `RunPod answers parse`() {
        val inProgress = RunPodJob.parse(CloudFixtures.phone("runpod.status.inprogress.json").toByteArray())!!
        assertEquals(RunPodJobStatus.IN_PROGRESS, inProgress.typedStatus)
        assertEquals(CloudProgress("separate", 40), inProgress.progress)
        assertEquals("Separating vocals", inProgress.progress?.label)
        val failed = RunPodJob.parse(CloudFixtures.phone("runpod.status.failed.json").toByteArray())!!
        assertEquals(CloudErrorCode.POISONED, failed.errorCode)
        val selftest = RunPodJob.parse(CloudFixtures.phone("runpod.selftest.json").toByteArray())!!
        assertEquals(RunPodJobStatus.COMPLETED, selftest.typedStatus)
        assertNotNull(selftest.selftest)
        assertEquals(1, selftest.selftest!!.agreedVersion)
        val health = RunPodHealth.parse(CloudFixtures.phone("runpod.health.json").toByteArray())!!
        assertEquals(3, health.inQueue)
        assertEquals(1, health.runningWorkers)
        assertEquals("3 songs waiting · 1 worker running", CloudConnectionTest.healthSummary(health))
        // A completed job whose output is the manifest.
        val okManifest = CloudFixtures.worker("job.result.ok.json")
        val completed = RunPodJob.parse("""{"id":"j1","status":"COMPLETED","output":$okManifest}""".toByteArray())!!
        assertEquals(JOB_KEY, completed.result?.jobKey)
        assertEquals(CloudResultStatus.OK, completed.result?.typedStatus)
    }

    @Test fun `progress text parsing`() {
        assertEquals(CloudProgress("separate", 40), CloudProgress.parse("separate:40"))
        assertEquals(CloudProgress("align", null), CloudProgress.parse("align"))
        assertEquals(CloudProgress("encode", 100), CloudProgress.parse("encode:250"))
        assertNull(CloudProgress.parse("{\"schema\":1}"))
        assertNull(CloudProgress.parse("bad stage:1"))
        assertNull(CloudProgress.parse(""))
    }
}
