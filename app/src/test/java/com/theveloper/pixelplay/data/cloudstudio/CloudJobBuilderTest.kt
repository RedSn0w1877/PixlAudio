package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.cloudstudio.CloudFixtures.JOB_KEY
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudJobBuilderTest {
    private val signed = mutableListOf<Triple<String, String, Int>>()
    private val presign: CloudPresign = { method, key, seconds ->
        signed += Triple(method, key, seconds)
        "https://acct.r2.cloudflarestorage.com/pixl-cloud-studio/$key?X-Amz-Signature=$method"
    }

    private fun prepared(
        tasks: List<CloudTask> = listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS),
        codec: CloudOutputCodec = CloudOutputCodec.AAC,
        quality: CloudSeparationQuality = CloudSeparationQuality.STANDARD,
        durationMs: Long = 241_000,
    ) = CloudJobRecord(
        jobKey = JOB_KEY, songId = "s1", tasks = tasks, quality = quality, outputCodec = codec,
        state = CloudJobState.UPLOADED, inputExt = "flac", sha256 = "AB".repeat(32), bytes = 31_457_280,
        durationMs = durationMs, createdAtMs = 0,
    )

    private val lyrics = CloudLyricsRequest(mode = "align", language = "ko", synced = true,
        lines = listOf(CloudLyricsInputLine(0, 1000, "line")))

    @Test fun `a full request signs every worker URL at submission`() {
        val request = CloudJobBuilder.request(prepared(), "0.7.6 (11)", lyrics, presign)!!
        val input = request.input
        assertEquals(CloudJobPolicy(259_200_000, 900_000), request.policy)
        assertEquals("process", input.op)
        assertEquals("presigned", input.storage)
        assertEquals(CloudSchema.CLIENT_APP, input.client?.app)
        assertEquals("ab".repeat(32), input.audio?.sha256) // lower-case on the wire
        assertTrue(input.audio!!.get!!.contains("/in/$JOB_KEY.flac?X-Amz-Signature=GET"))
        assertTrue(input.audio!!.delete!!.endsWith("DELETE"))
        assertEquals(listOf("instrumental", "lyrics", "manifest"), input.output!!.put!!.keys.toList())
        assertTrue(input.output!!.put!!.getValue("instrumental").contains("out/$JOB_KEY/instrumental.m4a"))
        assertTrue(input.output!!.put!!.getValue("lyrics").contains("out/$JOB_KEY/lyrics.json"))
        assertEquals(256, input.output?.kbps)
        assertTrue(input.guard!!.attemptPut.contains("out/$JOB_KEY/attempt.json?X-Amz-Signature=PUT"))
        assertTrue(input.guard!!.manifestGet.contains("out/$JOB_KEY/manifest.json?X-Amz-Signature=GET"))
        assertEquals(lyrics, input.lyrics)
        assertTrue(signed.all { it.third == CloudTiming.WORKER_PRESIGN_SECONDS })
        // The request validates against the golden run.request.json's shape: same top-level keys.
        val golden = CloudFixtures.canonical(CloudFixtures.worker("run.request.json"))
        val ours = CloudFixtures.canonical(CloudJson.encodeToString(CloudJobRequest.serializer(), request))
        assertEquals(
            (golden as kotlinx.serialization.json.JsonObject).getValue("input").let { (it as kotlinx.serialization.json.JsonObject).keys },
            (ours as kotlinx.serialization.json.JsonObject).getValue("input").let { (it as kotlinx.serialization.json.JsonObject).keys },
        )
    }

    @Test fun `FLAC output has no bitrate, best falls back on long songs, lyrics are required for the lyrics task`() {
        val flac = CloudJobBuilder.request(prepared(listOf(CloudTask.INSTRUMENTAL), CloudOutputCodec.FLAC,
            CloudSeparationQuality.BEST, durationMs = 500_000), "b", null, presign)!!
        assertNull(flac.input.output!!.kbps)
        assertEquals("flac", flac.input.output!!.codec)
        assertTrue(flac.input.output!!.put!!.getValue("instrumental").contains("instrumental.flac"))
        assertEquals("standard", flac.input.separation!!.quality)
        assertNull(flac.input.lyrics)
        assertNull(CloudJobBuilder.request(prepared(), "b", null, presign))
        assertNull(CloudJobBuilder.request(prepared().copy(sha256 = null), "b", lyrics, presign))
        assertNull(CloudJobBuilder.request(prepared().copy(jobKey = "nope"), "b", lyrics, presign))
        assertNull(CloudJobBuilder.request(prepared(), "b", lyrics) { _, _, _ -> null })
    }

    @Test fun `lyrics requests`() {
        val lines = listOf(CloudLyricsInputLine(1000, 2000, "a"))
        val aligned = CloudJobBuilder.lyricsRequest(CloudLyricsMode.ALIGN, lines, true, "KO", 200_000, 200_500)
        assertEquals("align", aligned.mode)
        assertEquals("ko", aligned.language)
        assertTrue(aligned.synced)
        assertEquals(lines, aligned.lines)
        val drifted = CloudJobBuilder.lyricsRequest(CloudLyricsMode.ALIGN, lines, true, null, 200_000, 230_000)
        assertFalse(drifted.synced)
        val lost = CloudJobBuilder.lyricsRequest(CloudLyricsMode.ALIGN, emptyList(), false, null, null, 1)
        assertEquals("auto", lost.mode)
        assertNull(lost.lines)
        val transcribe = CloudJobBuilder.lyricsRequest(CloudLyricsMode.TRANSCRIBE, lines, true, "vi", null, 1)
        assertNull(transcribe.lines)
        assertTrue(transcribe.synced) // there were known lines, but they aren't sent
    }

    @Test fun `language hints follow the worker's pattern`() {
        assertEquals("ko", CloudJobBuilder.normalizedLanguage(" KO "))
        assertEquals("zh-Hant", CloudJobBuilder.normalizedLanguage("zh_Hant"))
        assertEquals("pt-BR", CloudJobBuilder.normalizedLanguage("PT-BR"))
        assertEquals("en", CloudJobBuilder.normalizedLanguage("en-x"))
        assertNull(CloudJobBuilder.normalizedLanguage("und"))
        assertNull(CloudJobBuilder.normalizedLanguage("english"))
        assertNull(CloudJobBuilder.normalizedLanguage(""))
        assertNull(CloudJobBuilder.normalizedLanguage(null))
    }

    @Test fun `object keys stay inside the job's own folder`() {
        val record = prepared().copy(
            outputs = mapOf(
                "instrumental" to CloudOutputFile("out/$JOB_KEY/instrumental.m4a", 1, "a"),
                "evil" to CloudOutputFile("out/other/../../in/x.m4a", 1, "a"),
                "extra" to CloudOutputFile("out/$JOB_KEY/vocals.m4a", 1, "a"),
            ),
            lyricsKey = "out/$JOB_KEY/lyrics.json",
        )
        assertEquals(
            listOf("in/$JOB_KEY.flac", "out/$JOB_KEY/instrumental.m4a", "out/$JOB_KEY/lyrics.json",
                "out/$JOB_KEY/manifest.json", "out/$JOB_KEY/vocals.m4a", "out/$JOB_KEY/attempt.json"),
            CloudJobBuilder.objectKeys(record)
        )
        assertEquals(listOf("drums", "bass", "other", "manifest"), CloudJobBuilder.outputSlots(listOf(CloudTask.STEMS4)))
    }
}

class CloudConfigTest {
    private fun input(
        endpointId: String = "kjoa5h86wrpf2q",
        key: String = "rpa_x",
        endpoint: String = "49083275082e89f3a024292385941801",
        bucket: String = "pixl-cloud-studio",
        accessKey: String = "AKID",
        secret: String = "SECRET",
    ) = CloudConfigInput(endpointId, key, endpoint, bucket, accessKey, secret)

    @Test fun `endpoints normalise from an account ID or a URL`() {
        assertEquals("https://49083275082e89f3a024292385941801.r2.cloudflarestorage.com",
            CloudConfig.normalizedEndpoint("49083275082E89F3A024292385941801"))
        assertEquals("https://acct.r2.cloudflarestorage.com",
            CloudConfig.normalizedEndpoint(" https://ACCT.r2.cloudflarestorage.com/pixl-cloud-studio "))
        assertEquals("https://minio.example.com:9000", CloudConfig.normalizedEndpoint("minio.example.com:9000"))
        assertNull(CloudConfig.normalizedEndpoint("http://acct.r2.cloudflarestorage.com"))
        assertNull(CloudConfig.normalizedEndpoint("localhost"))
        assertNull(CloudConfig.normalizedEndpoint("bad host.com"))
        assertNull(CloudConfig.normalizedEndpoint(""))
        assertEquals("49083275082e89f3a024292385941801",
            CloudConfig.r2AccountId("https://49083275082e89f3a024292385941801.r2.cloudflarestorage.com"))
        assertNull(CloudConfig.r2AccountId("https://example.com"))
    }

    @Test fun `a complete input yields a location and credentials, and never prints keys`() {
        val config = input()
        assertTrue(config.isComplete)
        assertEquals("49083275082e89f3a024292385941801.r2.cloudflarestorage.com", config.location?.endpointHost)
        assertEquals("pixl-cloud-studio", config.location?.bucket)
        assertTrue(CloudConfig.problems(config).isEmpty())
        assertFalse("SECRET" in config.toString() || "rpa_x" in config.toString() || "AKID" in config.toString())
        assertEquals(input(), input())
    }

    @Test fun `problems name each missing or wrong field in order`() {
        val problems = CloudConfig.problems(input(endpointId = "https://api.runpod.ai", key = "", endpoint = "http://x.y",
            bucket = "Bad_Bucket", accessKey = " ", secret = ""))
        assertEquals(
            listOf(
                "The Endpoint ID should be letters and digits only (no https://, no slashes).",
                "Paste the Restricted RunPod key.",
                "The R2 endpoint should look like https://<account-id>.r2.cloudflarestorage.com.",
                "Bucket names are 3–63 lower-case letters, digits, dots or dashes.",
                "Paste the R2 access key ID.",
                "Paste the R2 secret access key.",
            ),
            problems
        )
        assertFalse(input(key = "").isComplete)
        assertFalse(input(bucket = "").hasStorage)
    }
}
