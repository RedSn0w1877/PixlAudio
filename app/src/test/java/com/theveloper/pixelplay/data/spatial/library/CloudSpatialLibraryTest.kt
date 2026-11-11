package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudDigest
import com.theveloper.pixelplay.data.cloudstudio.CloudFixtures
import com.theveloper.pixelplay.data.cloudstudio.CloudJobOrigin
import com.theveloper.pixelplay.data.cloudstudio.CloudJobRecord
import com.theveloper.pixelplay.data.cloudstudio.CloudJobState
import com.theveloper.pixelplay.data.cloudstudio.CloudJson
import com.theveloper.pixelplay.data.cloudstudio.CloudKeys
import com.theveloper.pixelplay.data.cloudstudio.CloudLibraryJob
import com.theveloper.pixelplay.data.cloudstudio.CloudLibrarySend
import com.theveloper.pixelplay.data.cloudstudio.CloudSettingsSnapshot
import com.theveloper.pixelplay.data.cloudstudio.CloudTask
import com.theveloper.pixelplay.data.cloudstudio.CloudTransferResult
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The library's way to a render (cloud-audio-spec §6.2) and the state it reports on the way (§6.1): the switches, the
 * shared library before any job, the waits for the network and the cap, the failure backoff, and the job's states.
 */
class CloudSpatialLibraryTest {
    @TempDir lateinit var dir: Path

    private val video = "J7p4bzqLvCw"
    private var now = 1_800_000_000_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val spatial = MutableStateFlow(SpatialSettings(profile = SpatialProfile.STUDIO, cloud = true))
    private val cloud = MutableStateFlow(CloudSettingsSnapshot(enabled = true))
    private val wifi = SpatialNetwork(connected = true, unmetered = true)
    private val network = MutableStateFlow(wifi)
    private val engine = FakeCloud()
    private val ledger = SpatialFailureLedger()
    private val schedules = mutableListOf<Boolean>()
    private lateinit var store: SpatialRenderStore
    private lateinit var library: CloudSpatialLibrary

    @BeforeEach fun setUp() {
        store = SpatialRenderStore(dir.resolve("cloud_audio").toFile(), { Long.MAX_VALUE }, scope, { now })
        library = CloudSpatialLibrary(
            engine = engine, store = store, settings = spatial, cloudSettings = cloud,
            networkSource = object : SpatialNetworkSource {
                override val network: StateFlow<SpatialNetwork> = this@CloudSpatialLibraryTest.network
            },
            ledger = ledger, aheadSchedule = { schedules += it }, stagingDir = dir.resolve("staging").toFile(),
            scope = scope, nowMs = { now },
        )
    }

    @AfterEach fun tearDown() = scope.cancel()

    private suspend fun status(profile: SpatialProfile = SpatialProfile.STUDIO): SpatialRenditionStatus =
        library.status(video, profile).first()

    private suspend fun eventually(what: String, check: suspend () -> Boolean) {
        repeat(300) {
            if (check()) return
            delay(10)
        }
        throw AssertionError("never happened: $what")
    }

    /** The status settles on [expected] (flows carry settings and network changes a moment later). */
    private suspend fun expect(expected: SpatialRenditionStatus, profile: SpatialProfile = SpatialProfile.STUDIO) {
        eventually("status $expected, was ${status(profile)}") { status(profile) == expected }
    }

    /** Puts a finished render of [profile] into the fake shared library. */
    private fun publish(profile: SpatialProfile = SpatialProfile.STUDIO) {
        val audio = ByteArray(5_000) { (it % 97).toByte() }
        val base = SpatialManifests.decode(CloudFixtures.worker("library.render.spatial.json").toByteArray())!!
        val manifest = base.copy(profile = profile.id, audio = base.audio.copy(key = CloudKeys.libraryAudio(video, profile.id),
            bytes = audio.size.toLong(), sha256 = CloudDigest.sha256Hex(audio)))
        engine.objects[CloudKeys.libraryAudio(video, profile.id)] = audio
        engine.objects[CloudKeys.libraryManifest(video, profile.id)] =
            CloudJson.encodeToString(SpatialRenderManifest.serializer(), manifest).toByteArray()
    }

    @Test fun `with either switch off nothing is looked up, sent or waited for`() = runBlocking {
        eventually("on with both switches on") { library.cloudRendersOn.value }
        cloud.value = cloud.value.copy(enabled = false)
        eventually("off with the master off") { !library.cloudRendersOn.value }
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        cloud.value = cloud.value.copy(enabled = true)
        eventually("on again") { library.cloudRendersOn.value }
        spatial.update { it.copy(cloud = false) }
        eventually("off with Spatial's own switch off") { !library.cloudRendersOn.value }
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertTrue(engine.fetches.isEmpty())
        assertTrue(engine.sent.isEmpty())
        assertEquals(SpatialRenditionStatus.NONE, status())
        // Spatial's own switch going off stops its jobs still on the phone.
        eventually("its jobs on the phone stopped") { engine.cancelledAll > 0 }
        // And the background queue's schedule follows: on with everything on, off now.
        assertEquals(false, schedules.last())
        assertTrue(true in schedules)
    }

    @Test fun `the shared library comes first, and costs no job`() = runBlocking {
        publish()
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertNotNull(store.localRender(video, SpatialProfile.STUDIO))
        expect(SpatialRenditionStatus.READY)
        assertTrue(engine.sent.isEmpty())
        assertEquals(listOf(CloudKeys.libraryManifest(video, "studio")), engine.fetches)
        assertNotNull(library.localRender(video, SpatialProfile.STUDIO))
        // Asking again changes nothing: the render is here.
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertEquals(1, engine.fetches.size)
    }

    @Test fun `a render the library doesn't have becomes one job, and the job's states are the song's`() = runBlocking {
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertEquals(listOf(Triple(video, "studio", false)), engine.sent)
        expect(SpatialRenditionStatus.QUEUED)
        // Asked again while it is on its way: still one job.
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertEquals(1, engine.sent.size)
        engine.move(CloudJobState.RUNNING)
        expect(SpatialRenditionStatus.PROCESSING)
        engine.move(CloudJobState.RESULTS_READY)
        expect(SpatialRenditionStatus.DOWNLOADING)
        // Results wait for a network the person allows.
        network.value = SpatialNetwork(connected = true, unmetered = false)
        expect(SpatialRenditionStatus.WAITING_FOR_NETWORK)
        cloud.value = cloud.value.copy(useCellular = true)
        expect(SpatialRenditionStatus.DOWNLOADING)
        // Another profile of the same song is its own business.
        assertEquals(SpatialRenditionStatus.NONE, status(SpatialProfile.HALL))
    }

    @Test fun `no network the person allows: it waits, and starts when one comes back`() = runBlocking {
        network.value = SpatialNetwork(connected = true, unmetered = false)
        eventually("the library saw the network") { status() == SpatialRenditionStatus.NONE }
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        expect(SpatialRenditionStatus.WAITING_FOR_NETWORK)
        assertTrue(engine.fetches.isEmpty())
        network.value = wifi
        eventually("the job after Wi-Fi came back") { engine.sent.size == 1 }
        expect(SpatialRenditionStatus.QUEUED)
    }

    @Test fun `the month's limit: it waits, and starts when the cap is raised`() = runBlocking {
        engine.capReached = true
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        expect(SpatialRenditionStatus.CAP_REACHED)
        assertTrue(status().isQueued)
        engine.capReached = false
        spatial.update { it.copy(monthlyCapUsd = 50) }
        eventually("the job after the cap went up") { engine.sent.size == 1 }
    }

    @Test fun `a failure shows, holds new attempts back 10 minutes, and clears once it works`() = runBlocking {
        engine.fetchError = IOException("offline")
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertEquals(SpatialRenditionStatus.FAILED, status())
        assertEquals(1, ledger.failure(SpatialRenderKey(video, SpatialProfile.STUDIO))?.count)
        engine.fetchError = null
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertEquals(1, engine.fetches.size, "no new attempt inside the backoff")
        assertEquals(SpatialRenditionStatus.FAILED, status())
        now += 10L * 60_000
        publish()
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertEquals(SpatialRenditionStatus.READY, status())
        assertEquals(null, ledger.failure(SpatialRenderKey(video, SpatialProfile.STUDIO)))
    }

    @Test fun `a cloud job that failed counts once, and a later delivered one clears it`() = runBlocking {
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        engine.move(CloudJobState.FAILED, at = now)
        eventually("the failure recorded") { ledger.failure(SpatialRenderKey(video, SpatialProfile.STUDIO)) != null }
        engine.list.update { it.toList() } // the same list read again
        assertEquals(1, ledger.failure(SpatialRenderKey(video, SpatialProfile.STUDIO))?.count)
        assertEquals(SpatialRenditionStatus.FAILED, status())
        // A partial job whose render failed counts as a failure too; a delivered render clears it.
        engine.list.value = engine.list.value + engine.record(CloudJobState.IMPORTED, at = now + 1_000)
            .let { it.copy(library = it.library!!.copy(renders = mapOf("studio" to "ok"), imported = listOf("studio"))) }
        eventually("the failure cleared") { ledger.failure(SpatialRenderKey(video, SpatialProfile.STUDIO)) == null }
    }

    @Test fun `the person playing a song takes over the background queue's job for it`() = runBlocking {
        engine.list.value = listOf(engine.record(CloudJobState.UPLOADED, ahead = true))
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        assertEquals(listOf(Triple(video, "studio", false)), engine.sent)
        assertTrue(engine.fetches.isEmpty())
    }

    @Test fun `the background queue's own lookups never send a job`() = runBlocking {
        assertEquals(SharedLookup.MISSING, library.lookUp("s1", video, SpatialProfile.STUDIO))
        assertTrue(engine.sent.isEmpty())
        publish()
        assertEquals(SharedLookup.FOUND, library.lookUp("s1", video, SpatialProfile.STUDIO))
        assertTrue(library.hasLocal(video, SpatialProfile.STUDIO))
        assertEquals(SharedLookup.BUSY, library.lookUp("s1", video, SpatialProfile.STUDIO))
        // Off Wi-Fi it doesn't even look.
        network.value = SpatialNetwork(connected = true, unmetered = false)
        assertEquals(SharedLookup.BUSY, library.lookUp("s2", "ZD6rXLXZOEI", SpatialProfile.STUDIO))
        assertTrue(library.sendAhead("s2", "ZD6rXLXZOEI", SpatialProfile.STUDIO) is CloudLibrarySend.Sent)
        assertEquals(Triple("ZD6rXLXZOEI", "studio", true), engine.sent.last())
    }

    @Test fun `stats are the store's songs and bytes with the library's own month`() = runBlocking {
        engine.committed = 1_234_567
        engine.today = 4
        publish()
        library.requestNow("s1", video, SpatialProfile.STUDIO)
        eventually("the stats") { library.stats.value.songsReady == 1 }
        val stats = library.stats.value
        assertTrue(stats.bytesOnPhone > 5_000)
        assertEquals(1_234_567L, stats.spentThisMonthMicroUsd)
        assertEquals(4, stats.sentToday)
    }

    /** Cloud Studio's engine as the library sees it: a job list, the shared library's objects, and the send rules. */
    private inner class FakeCloud : SpatialCloudEngine {
        val list = MutableStateFlow<List<CloudJobRecord>>(emptyList())
        override val jobs: Flow<List<CloudJobRecord>> = list
        val objects = HashMap<String, ByteArray>()
        val fetches = mutableListOf<String>()
        var fetchError: Exception? = null
        val sent = mutableListOf<Triple<String, String, Boolean>>()
        var capReached = false
        var cancelledAll = 0
        var committed = 0L
        var today = 0

        fun record(state: CloudJobState, ahead: Boolean = false, at: Long = now) = CloudJobRecord(
            jobKey = "3d9f0c52-7a41-4e8b-b6f3-91c2d5e7a8%02d".format(list.value.size), songId = "s1",
            videoId = video, tasks = listOf(CloudTask.SPATIAL), origin = CloudJobOrigin.LIBRARY,
            library = CloudLibraryJob(video, listOf("studio"), ahead = ahead), state = state, createdAtMs = at, updatedAtMs = at,
        )

        fun move(state: CloudJobState, at: Long = now) {
            list.update { jobs -> jobs.map { it.copy(state = state, updatedAtMs = at) } }
        }

        override fun currentJobs(): List<CloudJobRecord> = list.value
        override suspend fun load() = Unit
        override suspend fun fetchLibraryObject(key: String): ByteArray? {
            fetches += key
            fetchError?.let { throw it }
            return objects[key]
        }
        override suspend fun downloadLibraryObject(key: String, destination: File): CloudTransferResult {
            val data = objects[key] ?: return CloudTransferResult.Failed("not found", 404)
            destination.parentFile?.mkdirs()
            destination.writeBytes(data)
            return CloudTransferResult.Ok
        }
        override suspend fun sendLibraryJob(songId: String, videoId: String, profileId: String, ahead: Boolean): CloudLibrarySend {
            if (capReached) return CloudLibrarySend.CapReached
            sent += Triple(videoId, profileId, ahead)
            val existing = list.value.lastOrNull { it.state.isPending && it.library?.videoId == videoId }
            if (existing != null) {
                val promoted = existing.copy(library = existing.library!!.copy(ahead = existing.isLibraryAhead && ahead))
                list.update { jobs -> jobs.map { if (it.jobKey == existing.jobKey) promoted else it } }
                return CloudLibrarySend.Sent(promoted)
            }
            val created = record(CloudJobState.QUEUED, ahead).let { it.copy(library = it.library!!.copy(videoId = videoId)) }
            list.update { it + created }
            return CloudLibrarySend.Sent(created)
        }
        override suspend fun cancelPendingLibrary(matches: (CloudJobRecord) -> Boolean) {
            if (matches(record(CloudJobState.QUEUED))) cancelledAll++
            list.update { jobs -> jobs.filterNot { it.state.isBeforeSubmit && matches(it) } }
        }
        override suspend fun libraryCapAllows(durationMs: Long): Boolean = !capReached
        override fun libraryCommittedMicroUsd(): Long = committed
        override fun libraryJobEstimateMicroUsd(durationMs: Long): Long = 1_000
        override fun aheadSentToday(): Int = today
    }
}
