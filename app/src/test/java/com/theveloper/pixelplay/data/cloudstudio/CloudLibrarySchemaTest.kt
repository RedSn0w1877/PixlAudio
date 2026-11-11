package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.spatial.SpatialContract
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Spatial audio's job shape against the worker's golden examples (cloud-audio-spec §4, §8: "builder output equals the
 * examples"), copied byte for byte from the PixlAudio-iOS repo's `audio-worker` branch into
 * `src/test/resources/cloudstudio/worker/`.
 */
class CloudLibrarySchemaTest {
    private val jobKey = "3d9f0c52-7a41-4e8b-b6f3-91c2d5e7a804"
    private val videoId = "J7p4bzqLvCw"

    /** A library job prepared and uploaded exactly like the one the examples describe. */
    private fun uploaded(profiles: List<String> = listOf("studio"), itag: Int? = 251, codec: String? = "opus") = CloudJobRecord(
        jobKey = jobKey, songId = "spotify_x", videoId = videoId, tasks = listOf(CloudTask.SPATIAL),
        quality = CloudSeparationQuality.STANDARD, outputCodec = CloudOutputCodec.FLAC, origin = CloudJobOrigin.LIBRARY,
        library = CloudLibraryJob(videoId, profiles, sourceItag = itag, sourceCodec = codec),
        state = CloudJobState.UPLOADED, inputExt = "flac",
        sha256 = "F2218379956AB8D0D22BFD05D9C0B60A7F461AADA888AA86F614E0DB77724372", bytes = 27_418_112,
        durationMs = 202_000, createdAtMs = 0,
    )

    /** Every URL of a golden request, by the method and key it signs (so the builder can sign exactly those). */
    private fun signedUrls(element: JsonElement, field: String? = null, into: MutableMap<Pair<String, String>, String> = HashMap()):
        Map<Pair<String, String>, String> {
        when (element) {
            is JsonObject -> for ((name, value) in element) {
                // `output.put`'s entries are named by their slot: all of them are PUTs.
                signedUrls(value, if (field == "put") "put" else name, into)
            }
            is JsonArray -> element.forEach { signedUrls(it, field, into) }
            is JsonPrimitive -> if (element.isString && element.content.startsWith("https://")) {
                val url = element.content
                val method = when (field) {
                    "get", "manifestGet", "attemptGet" -> "GET"
                    "delete" -> "DELETE"
                    else -> "PUT"
                }
                val key = url.substringAfter("/pixl-cloud-studio/").substringBefore('?')
                into[method to key] = url
            }
        }
        return into
    }

    private fun presignFrom(golden: String, seconds: MutableList<Int>): CloudPresign {
        val table = signedUrls(CloudJson.parseToJsonElement(golden))
        return { method, key, expires ->
            seconds += expires
            table[method to key]
        }
    }

    /** The golden JSON as this app writes it: its own `client.app` (the examples come from the iPhone). */
    private fun asAndroid(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (name, value) ->
            if (name == "client") JsonObject(value.jsonObject + ("app" to JsonPrimitive(CloudSchema.CLIENT_APP))) else asAndroid(value)
        })
        else -> element
    }

    @Test fun `a Spatial audio job is the worker's own example, URL for URL`() {
        val golden = CloudFixtures.worker("job.input.spatial.json")
        val seconds = mutableListOf<Int>()
        val request = CloudJobBuilder.request(uploaded(), "1.1.0 (431)", lyrics = null, lastInBatch = true,
            presign = presignFrom(golden, seconds))!!
        val ours = CloudFixtures.canonical(CloudJson.encodeToString(CloudJobInput.serializer(), request.input))
        assertEquals(asAndroid(CloudFixtures.canonical(golden)), ours)
        // Every worker URL lives `ttl + 1 800 s + 1 h`, the library job's timeout (spec §6.2 step 3).
        assertEquals(CloudTiming.workerPresignSeconds(listOf(CloudTask.SPATIAL)), seconds.distinct().single())
        assertEquals(264_600, seconds.first())
    }

    @Test fun `the run body carries the 1 800 000 ms execution timeout`() {
        val golden = CloudFixtures.worker("run.request.spatial.json")
        val request = CloudJobBuilder.request(uploaded(), "1.1.0 (431)", lyrics = null, lastInBatch = true,
            presign = presignFrom(golden, mutableListOf()))!!
        assertEquals(CloudJobPolicy(259_200_000, 1_800_000), request.policy)
        val ours = CloudFixtures.canonical(CloudJson.encodeToString(CloudJobRequest.serializer(), request))
        assertEquals(asAndroid(CloudFixtures.canonical(golden)), ours)
        // And it reads back the same.
        val decoded = CloudJson.decodeFromString(CloudJobRequest.serializer(), golden)
        assertEquals(listOf(CloudTask.SPATIAL), decoded.input.typedTasks)
        assertEquals(CloudLibrarySource(251, "opus"), decoded.input.library?.source)
        assertEquals(listOf("studio"), decoded.input.library?.renders?.map { it.profile })
    }

    @Test fun `library jobs have no output slot and no codec, Sing's jobs are unchanged`() {
        assertEquals(listOf("manifest"), CloudJobBuilder.outputSlots(listOf(CloudTask.SPATIAL)))
        val presign: CloudPresign = { method, key, _ -> "https://acct.r2.cloudflarestorage.com/pixl-cloud-studio/$key?X-Amz-Signature=$method" }
        val input = CloudJobBuilder.request(uploaded(), "b", null, presign = presign)!!.input
        assertNull(input.output?.codec)
        assertNull(input.output?.kbps)
        assertEquals(listOf("manifest"), input.output?.put?.keys?.toList())
        assertEquals(SpatialContract.LIB_V, input.library?.v)
        val render = input.library!!.renders.single()
        assertTrue(render.audioPut.contains("lib/v1/$videoId/sp-studio.flac?X-Amz-Signature=PUT"))
        assertTrue(render.manifestPut.contains("lib/v1/$videoId/sp-studio.json?X-Amz-Signature=PUT"))
        assertTrue(render.manifestGet!!.contains("lib/v1/$videoId/sp-studio.json?X-Amz-Signature=GET"))
        // No itag known: the codec alone is sent; neither known: no source at all.
        assertEquals(CloudLibrarySource(null, "opus"), CloudJobBuilder.request(uploaded(itag = null), "b", null, presign = presign)!!.input.library?.source)
        assertNull(CloudJobBuilder.request(uploaded(itag = null, codec = null), "b", null, presign = presign)!!.input.library?.source)
        // A Sing job still asks for AAC at 256 kbps and the 900 s timeout.
        val sing = uploaded().copy(tasks = listOf(CloudTask.INSTRUMENTAL), library = null, outputCodec = CloudOutputCodec.AAC)
        val singRequest = CloudJobBuilder.request(sing, "b", null, presign = presign)!!
        assertEquals("aac", singRequest.input.output?.codec)
        assertEquals(256, singRequest.input.output?.kbps)
        assertNull(singRequest.input.library)
        assertEquals(CloudJobPolicy(CloudTiming.TTL_MS, CloudTiming.EXECUTION_TIMEOUT_MS), singRequest.policy)
    }

    @Test fun `a library job the worker would refuse is never built`() {
        val presign: CloudPresign = { method, key, _ -> "https://x/$key?$method" }
        assertNull(CloudJobBuilder.request(uploaded().copy(library = null), "b", null, presign = presign))
        assertNull(CloudJobBuilder.request(uploaded(profiles = emptyList()), "b", null, presign = presign))
        assertNull(CloudJobBuilder.request(uploaded(profiles = listOf("studio", "studio")), "b", null, presign = presign))
        assertNull(CloudJobBuilder.request(uploaded(profiles = listOf("atmos")), "b", null, presign = presign))
        val badVideo = uploaded().let { it.copy(library = it.library!!.copy(videoId = "spotify:track:1")) }
        assertNull(CloudJobBuilder.request(badVideo, "b", null, presign = presign))
        // All six profiles at once is the most a job takes.
        val six = CloudJobBuilder.request(uploaded(profiles = listOf("studio", "wide", "intimate", "hall", "surround", "night")),
            "b", null, presign = presign)!!
        assertEquals(6, six.input.library!!.renders.size)
        assertNull(CloudJobBuilder.request(uploaded().copy(library = null), "b", null) { _, _, _ -> null })
        assertNull(CloudJobBuilder.request(uploaded(), "b", null) { method, key, _ -> if (key.startsWith("lib/")) null else "https://x/$key?$method" })
    }

    @Test fun `the worker's results decode, round-trip and name the renders`() {
        for (name in listOf("job.result.spatial.json", "job.result.spatial.exists.json", "job.result.spatial.partial.json")) {
            val text = CloudFixtures.worker(name)
            val result = CloudJson.decodeFromString(CloudJobResult.serializer(), text)
            assertEquals(CloudFixtures.canonical(text), CloudFixtures.canonical(CloudJson.encodeToString(CloudJobResult.serializer(), result)), name)
        }
        val ok = CloudJson.decodeFromString(CloudJobResult.serializer(), CloudFixtures.worker("job.result.spatial.json"))
        assertEquals(CloudResultStatus.OK, ok.typedStatus)
        assertEquals(mapOf("studio" to "ok"), ok.library?.renders)
        assertEquals(listOf("studio"), ok.library?.delivered)
        assertEquals("stems6", ok.library?.stemMode)
        assertEquals(videoId, ok.library?.videoId)
        assertEquals(1, ok.library?.libV)
        assertEquals(CloudKeys.libraryAudio(videoId, "studio"), ok.outputs?.get(CloudKeys.librarySlot("studio"))?.key)
        assertEquals(41_800L, ok.timings?.stems6Ms)
        assertEquals(23_600L, ok.timings?.renderMs)
        assertEquals("open-cascade-6", ok.models?.stems6)

        val exists = CloudJson.decodeFromString(CloudJobResult.serializer(), CloudFixtures.worker("job.result.spatial.exists.json"))
        assertEquals(listOf("studio"), exists.library?.delivered)
        assertNull(exists.library?.stemMode)

        val partial = CloudJson.decodeFromString(CloudJobResult.serializer(), CloudFixtures.worker("job.result.spatial.partial.json"))
        assertEquals(CloudResultStatus.PARTIAL, partial.typedStatus)
        assertTrue(partial.hasResults)
        assertEquals(listOf("night"), partial.library?.delivered)
        assertEquals(listOf("hall"), partial.library?.failed)
        assertEquals("lib/v1/ZD6rXLXZOEI/sp-night.flac", partial.outputs?.get("sp-night")?.key)

        // A Sing result has no library block.
        assertNull(CloudJson.decodeFromString(CloudJobResult.serializer(), CloudFixtures.worker("job.result.ok.json")).library)
    }

    @Test fun `a record takes the result's renders and is done once the delivered ones are on the phone`() {
        val result = CloudJson.decodeFromString(CloudJobResult.serializer(), CloudFixtures.worker("job.result.spatial.partial.json"))
        val running = uploaded(profiles = listOf("night", "hall")).let {
            it.copy(library = it.library!!.copy(videoId = "ZD6rXLXZOEI"), state = CloudJobState.RUNNING)
        }
        assertFalse(running.isFullyImported, "no result yet")
        val taken = running.takingResult(result, CloudCost.DEFAULT_PRICE_PER_SECOND_MICRO_USD, 1_000)
        assertEquals(mapOf("night" to "ok", "hall" to "failed"), taken.library?.renders)
        assertEquals("stems6", taken.library?.stemMode)
        assertEquals(listOf("night"), taken.library?.awaitingDownload)
        assertFalse(taken.isFullyImported)
        val imported = taken.copy(library = taken.library!!.copy(imported = listOf("night")))
        assertTrue(imported.isFullyImported)
        assertTrue(imported.costMicroUsd!! > 0)
    }

    @Test fun `the combined example decodes with both kinds of work`() {
        val input = CloudJson.decodeFromString(CloudJobInput.serializer(), CloudFixtures.worker("job.input.spatial.combined.json"))
        assertEquals(listOf(CloudTask.INSTRUMENTAL, CloudTask.SPATIAL), input.typedTasks)
        assertEquals(listOf("night", "hall"), input.library?.renders?.map { it.profile })
        assertNull(input.library?.renders?.get(1)?.manifestGet, "manifestGet is optional")
        assertEquals("aac", input.output?.codec)
    }

    @Test fun `library keys, slots, video ids and the library job's timing`() {
        assertEquals("lib/v1/J7p4bzqLvCw/sp-hall.flac", CloudKeys.libraryAudio("J7p4bzqLvCw", "hall"))
        assertEquals("lib/v1/J7p4bzqLvCw/sp-hall.json", CloudKeys.libraryManifest("J7p4bzqLvCw", "hall"))
        assertEquals("sp-night", CloudKeys.librarySlot("night"))
        assertTrue(CloudKeys.isValidVideoId("J7p4bzqLvCw"))
        assertTrue(CloudKeys.isValidVideoId("a-b_c-d_e-f"))
        assertFalse(CloudKeys.isValidVideoId("J7p4bzqLvC"))
        assertFalse(CloudKeys.isValidVideoId("J7p4bzqLvCw1"))
        assertFalse(CloudKeys.isValidVideoId("J7p4bzq/vCw"))
        assertEquals(1_800_000L, CloudTiming.executionTimeoutMs(listOf(CloudTask.SPATIAL)))
        assertEquals(900_000L, CloudTiming.executionTimeoutMs(listOf(CloudTask.INSTRUMENTAL)))
        assertEquals(CloudTiming.WORKER_PRESIGN_SECONDS, CloudTiming.workerPresignSeconds(listOf(CloudTask.INSTRUMENTAL)))
        // A library job is lost only after its own longer run.
        val submitted = 0L
        assertFalse(CloudRetention.isPastTtl(submitted, CloudTiming.TTL_MS + 1_000_000, CloudTiming.LIBRARY_EXECUTION_TIMEOUT_MS))
        assertTrue(CloudRetention.isPastTtl(submitted, CloudTiming.TTL_MS + 1_000_000))
        assertEquals("Rendering Spatial audio", CloudProgress("render", 50).label)
        assertEquals("Splitting six stems", CloudProgress("stems6", null).label)
    }

    @Test fun `a library job's cost has its own figure and its own month`() {
        val spatial = CloudSongPlan("s", listOf(CloudTask.SPATIAL), true, CloudCost.REFERENCE_SONG_MS)
        val sing = CloudSongPlan("s", listOf(CloudTask.INSTRUMENTAL), true, CloudCost.REFERENCE_SONG_MS)
        assertEquals(CloudCost.WARM_SECONDS_SPATIAL, CloudCost.estimatedSeconds(spatial, CloudSeparationQuality.STANDARD))
        assertEquals(CloudCost.WARM_SECONDS_STANDARD, CloudCost.estimatedSeconds(sing, CloudSeparationQuality.STANDARD))
        val both = sing.copy(tasks = listOf(CloudTask.INSTRUMENTAL, CloudTask.SPATIAL))
        assertEquals(CloudCost.WARM_SECONDS_STANDARD + CloudCost.WARM_SECONDS_SPATIAL, CloudCost.estimatedSeconds(both, CloudSeparationQuality.STANDARD))

        val month = 0L
        val singJob = CloudJobRecord(jobKey = "a", songId = "1", tasks = listOf(CloudTask.INSTRUMENTAL), state = CloudJobState.IMPORTED,
            costMicroUsd = 1_000_000, completedAtMs = 10)
        val libraryJob = CloudJobRecord(jobKey = "b", songId = "2", tasks = listOf(CloudTask.SPATIAL), origin = CloudJobOrigin.LIBRARY,
            library = CloudLibraryJob(videoId, listOf("studio")), state = CloudJobState.IMPORTED, costMicroUsd = 2_000_000, completedAtMs = 10)
        val jobs = listOf(singJob, libraryJob)
        assertEquals(listOf(singJob), CloudBudget.studioRecords(jobs))
        assertEquals(1_000_000L, CloudBudget.committedMicroUsd(CloudBudget.studioRecords(jobs), month, 100))
        assertEquals(2_500_000L, CloudBudget.libraryCommittedMicroUsd(jobs, month, 100, removedLibrarySpendMicroUsd = 500_000))
        // No limit is no limit; a cap is the library's own, never Sing's $3.
        assertTrue(CloudBudget.allowsLibrary(Long.MAX_VALUE / 2, null, 0))
        assertTrue(CloudBudget.allowsLibrary(1_000_000, 30_000_000, 29_000_000))
        assertFalse(CloudBudget.allowsLibrary(1_000_001, 30_000_000, 29_000_000))
        // Library jobs are never in the priority lane; one being played goes with the person's batches.
        assertEquals(1, libraryJob.lane)
        assertEquals(2, libraryJob.copy(library = libraryJob.library!!.copy(ahead = true)).lane)
        assertFalse(libraryJob.isPriority)
    }

    @Test fun `the source codec name comes from the MIME type`() {
        assertEquals("opus", CloudSourceCodec.fromMime("audio/opus"))
        assertEquals("aac", CloudSourceCodec.fromMime("audio/mp4a-latm"))
        assertEquals("vorbis", CloudSourceCodec.fromMime("audio/vorbis"))
        assertEquals("mp3", CloudSourceCodec.fromMime("audio/mpeg"))
        assertEquals("flac", CloudSourceCodec.fromMime("AUDIO/FLAC"))
        assertEquals("x-unknown", CloudSourceCodec.fromMime("audio/x-unknown"))
        assertNull(CloudSourceCodec.fromMime(null))
        assertNull(CloudSourceCodec.fromMime(""))
    }

    @Test fun `the examples carry nothing of the cut Enhanced audio`() {
        for (name in listOf("job.input.spatial.json", "run.request.spatial.json", "job.result.spatial.json",
            "job.result.spatial.exists.json", "job.result.spatial.partial.json", "library.render.spatial.json")) {
            val text = CloudFixtures.worker(name)
            for (word in listOf("enhance", "restore", "restorer")) assertFalse("\"$word" in text, "$name names $word")
        }
        val input = CloudJson.parseToJsonElement(CloudFixtures.worker("job.input.spatial.json")).jsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("spatial"))), input.getValue("tasks"))
        assertEquals("spatial", (input.getValue("tasks") as JsonArray).single().jsonPrimitive.content)
    }
}
