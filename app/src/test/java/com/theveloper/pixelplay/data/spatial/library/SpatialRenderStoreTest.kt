package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudDigest
import com.theveloper.pixelplay.data.cloudstudio.CloudFixtures
import com.theveloper.pixelplay.data.cloudstudio.CloudJson
import com.theveloper.pixelplay.data.cloudstudio.CloudKeys
import com.theveloper.pixelplay.data.spatial.SpatialLibraryEvent
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Render manifests in, cloud-audio-spec §3.1: what is accepted and what is refused. */
class SpatialRenderManifestTest {
    private val text = CloudFixtures.worker("library.render.spatial.json")
    private val manifest = SpatialManifests.decode(text.toByteArray())!!

    @Test fun `the worker's manifest is accepted for exactly what it describes`() {
        assertNull(SpatialManifests.refusal(manifest, "J7p4bzqLvCw", SpatialProfile.STUDIO))
        val render = SpatialManifests.render(manifest, SpatialProfile.STUDIO, File("sp-studio.flac"))
        assertEquals("J7p4bzqLvCw", render.videoId)
        assertEquals(SpatialProfile.STUDIO, render.profile)
        assertEquals(48_000, render.sampleRate)
        assertEquals(9_696_000L, render.samples)
        assertEquals(202_000L, render.durationMs)
        assertEquals(4.0f, render.headroomDb)
        assertEquals("opus", render.sourceCodec)
        assertEquals("stems6", render.stemMode)
        assertEquals(-13.82f, render.integratedLufs)
        assertEquals(-9.81f, render.sourceLufs)
        // Asked for another video or another profile, the same manifest is refused.
        assertNotNull(SpatialManifests.refusal(manifest, "ZD6rXLXZOEI", SpatialProfile.STUDIO))
        assertNotNull(SpatialManifests.refusal(manifest, "J7p4bzqLvCw", SpatialProfile.HALL))
    }

    @Test fun `every field the spec names must be the one asked for`() {
        val wrong = listOf(
            manifest.copy(schema = "pixl.cloudstudio.result"),
            manifest.copy(v = 2),
            manifest.copy(libV = 2),
            manifest.copy(videoId = "ZD6rXLXZOEI"),
            manifest.copy(kind = "other"),
            manifest.copy(profile = "hall"),
            manifest.copy(audio = manifest.audio.copy(key = "lib/v1/J7p4bzqLvCw/sp-hall.flac")),
            manifest.copy(audio = manifest.audio.copy(key = "out/3d9f0c52-7a41-4e8b-b6f3-91c2d5e7a804/sp-studio.flac")),
            manifest.copy(audio = manifest.audio.copy(bytes = 0)),
            manifest.copy(audio = manifest.audio.copy(sha256 = "abc")),
            manifest.copy(audio = manifest.audio.copy(sampleRate = 0)),
            manifest.copy(audio = manifest.audio.copy(samples = 0)),
        )
        for (candidate in wrong) {
            assertNotNull(SpatialManifests.refusal(candidate, "J7p4bzqLvCw", SpatialProfile.STUDIO), candidate.toString())
        }
    }

    @Test fun `the downloaded file must match the manifest's size and checksum`() {
        val sha = manifest.audio.sha256
        assertTrue(SpatialManifests.fileMatches(manifest, 27_100_200, sha))
        assertTrue(SpatialManifests.fileMatches(manifest, 27_100_200, sha.uppercase()))
        assertFalse(SpatialManifests.fileMatches(manifest, 27_100_199, sha))
        assertFalse(SpatialManifests.fileMatches(manifest, 27_100_200, "0".repeat(64)))
    }

    @Test fun `what isn't a manifest reads as none, and a newer one still reads`() {
        assertNull(SpatialManifests.decode(ByteArray(0)))
        assertNull(SpatialManifests.decode("{}".toByteArray()))
        assertNull(SpatialManifests.decode("not json".toByteArray()))
        assertNull(SpatialManifests.decode(ByteArray(SpatialManifests.MAX_MANIFEST_BYTES + 1) { ' '.code.toByte() }))
        val newer = text.replace("\"createdAt\"", "\"somethingNew\": {\"x\": 1}, \"createdAt\"")
        assertNotNull(SpatialManifests.decode(newer.toByteArray()))
        // No loudness block: the spec's 4 dB.
        val bare = SpatialManifests.render(manifest.copy(loudness = null, source = null, render = null), SpatialProfile.STUDIO, File("x"))
        assertEquals(SpatialManifests.DEFAULT_HEADROOM_DB, bare.headroomDb)
        assertNull(bare.sourceCodec)
    }
}

/** The local store, cloud-audio-spec §6.2 "Local store": the index, the checks on the way in, and eviction. */
class SpatialRenderStoreTest {
    @TempDir lateinit var dir: Path

    private var now = 1_800_000_000_000L
    private var budget = Long.MAX_VALUE
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val root: File get() = dir.resolve("cloud_audio").toFile()

    @AfterEach fun tearDown() = scope.cancel()

    private fun store() = SpatialRenderStore(root, budgetBytes = { budget }, scope = scope, nowMs = { now })

    private fun audio(seed: Int, size: Int = 10_000) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

    private fun staged(name: String, bytes: ByteArray): File = dir.resolve("staged-$name.flac").toFile().apply { writeBytes(bytes) }

    /** The worker's example manifest, for [videoId] and [profile] and the bytes of [audio]. */
    private fun manifestFor(videoId: String, profile: SpatialProfile, audio: ByteArray): ByteArray {
        val base = SpatialManifests.decode(CloudFixtures.worker("library.render.spatial.json").toByteArray())!!
        val manifest = base.copy(
            videoId = videoId, profile = profile.id,
            audio = base.audio.copy(key = CloudKeys.libraryAudio(videoId, profile.id), bytes = audio.size.toLong(),
                sha256 = CloudDigest.sha256Hex(audio)),
        )
        return CloudJson.encodeToString(SpatialRenderManifest.serializer(), manifest).toByteArray()
    }

    private suspend fun SpatialRenderStore.put(videoId: String, profile: SpatialProfile = SpatialProfile.STUDIO, seed: Int = 1): String? {
        val bytes = audio(seed)
        return installRender(videoId, profile.id, staged("$videoId-${profile.id}", bytes), manifestFor(videoId, profile, bytes))
    }

    private fun events(store: SpatialRenderStore): MutableList<SpatialLibraryEvent> {
        val seen = mutableListOf<SpatialLibraryEvent>()
        scope.launch(start = CoroutineStart.UNDISPATCHED) { store.events.collect { seen += it } }
        return seen
    }

    @Test fun `a verified render comes in, with its manifest written last`() = runBlocking {
        val store = store()
        val seen = events(store)
        val bytes = audio(7)
        val file = staged("a", bytes)
        assertNull(store.installRender("J7p4bzqLvCw", "studio", file, manifestFor("J7p4bzqLvCw", SpatialProfile.STUDIO, bytes)))
        assertFalse(file.exists(), "the staged file was moved in")
        val render = store.localRender("J7p4bzqLvCw", SpatialProfile.STUDIO)!!
        assertEquals(File(root, "J7p4bzqLvCw/sp-studio.flac"), render.file)
        assertTrue(File(root, "J7p4bzqLvCw/sp-studio.json").isFile)
        assertEquals(bytes.toList(), render.file.readBytes().toList())
        assertEquals(48_000, render.sampleRate)
        assertTrue(store.hasRender("J7p4bzqLvCw", "studio"))
        assertFalse(store.hasRender("J7p4bzqLvCw", "hall"))
        assertNull(store.localRender("J7p4bzqLvCw", SpatialProfile.WIDE))
        assertEquals(1, store.usage.value.songs)
        assertEquals(bytes.size + File(root, "J7p4bzqLvCw/sp-studio.json").length(), store.usage.value.bytes)
        assertEquals(listOf<SpatialLibraryEvent>(SpatialLibraryEvent.RenderReady(render)), seen)
        assertEquals(setOf(SpatialRenderKey("J7p4bzqLvCw", SpatialProfile.STUDIO)), store.renders.value.keys)
    }

    @Test fun `a damaged download or another render's manifest is refused and leaves nothing`() = runBlocking {
        val store = store()
        val bytes = audio(3)
        val damaged = staged("d", bytes.copyOf().also { it[0] = (it[0] + 1).toByte() })
        assertNotNull(store.installRender("J7p4bzqLvCw", "studio", damaged, manifestFor("J7p4bzqLvCw", SpatialProfile.STUDIO, bytes)))
        assertFalse(damaged.exists())
        val other = staged("o", bytes)
        assertNotNull(store.installRender("J7p4bzqLvCw", "studio", other, manifestFor("J7p4bzqLvCw", SpatialProfile.HALL, bytes)))
        val otherVideo = staged("v", bytes)
        assertNotNull(store.installRender("J7p4bzqLvCw", "studio", otherVideo, manifestFor("ZD6rXLXZOEI", SpatialProfile.STUDIO, bytes)))
        val unknown = staged("u", bytes)
        assertNotNull(store.installRender("J7p4bzqLvCw", "atmos", unknown, manifestFor("J7p4bzqLvCw", SpatialProfile.STUDIO, bytes)))
        assertNull(store.localRender("J7p4bzqLvCw", SpatialProfile.STUDIO))
        assertEquals(0, store.usage.value.songs)
        assertTrue(root.listFiles().isNullOrEmpty() || File(root, "J7p4bzqLvCw").listFiles().isNullOrEmpty())
    }

    @Test fun `the files are the index, read back after a restart with leftovers cleared`() = runBlocking {
        val first = store()
        assertNull(first.put("J7p4bzqLvCw"))
        assertNull(first.put("ZD6rXLXZOEI", SpatialProfile.NIGHT, seed = 2))
        // An interrupted install, a render whose file doesn't match its manifest, and a stray folder.
        File(root, "AAAAAAAAAAA").mkdirs()
        File(root, "AAAAAAAAAAA/sp-wide.flac").writeBytes(audio(4))
        File(root, "J7p4bzqLvCw/sp-hall.flac.tmp").writeBytes(audio(5))
        File(root, "BBBBBBBBBBB").mkdirs()
        File(root, "BBBBBBBBBBB/sp-hall.flac").writeBytes(audio(6, 9_000))
        File(root, "BBBBBBBBBBB/sp-hall.json").writeBytes(manifestFor("BBBBBBBBBBB", SpatialProfile.HALL, audio(6)))
        File(root, "not a video").mkdirs()
        // A fresh process answers from the files before its index is read…
        val fresh = store()
        assertNotNull(fresh.localRender("ZD6rXLXZOEI", SpatialProfile.NIGHT))
        // …and once it is, only the two valid renders are left.
        fresh.ensureIndexed()
        assertEquals(
            setOf(SpatialRenderKey("J7p4bzqLvCw", SpatialProfile.STUDIO), SpatialRenderKey("ZD6rXLXZOEI", SpatialProfile.NIGHT)),
            fresh.renders.value.keys,
        )
        assertFalse(File(root, "AAAAAAAAAAA").exists())
        assertFalse(File(root, "BBBBBBBBBBB").exists())
        assertFalse(File(root, "J7p4bzqLvCw/sp-hall.flac.tmp").exists())
        assertFalse(File(root, "not a video").exists())
        assertEquals(2, fresh.usage.value.songs)
        assertNull(fresh.localRender("BBBBBBBBBBB", SpatialProfile.HALL))
    }

    @Test fun `over budget, the least recently played songs go first, never the one played last or just stored`() = runBlocking {
        val store = store()
        val seen = events(store)
        val one = 10_000L + manifestFor("AAAAAAAAAAA", SpatialProfile.STUDIO, audio(1)).size
        budget = one * 2 + 100 // two renders fit, three don't
        assertNull(store.put("AAAAAAAAAAA"))
        now += 1_000
        assertNull(store.put("BBBBBBBBBBB"))
        now += 1_000
        assertNull(store.put("CCCCCCCCCCC"))
        assertNull(store.localRender("AAAAAAAAAAA", SpatialProfile.STUDIO), "the least recently played went")
        assertFalse(File(root, "AAAAAAAAAAA").exists())
        assertTrue(SpatialLibraryEvent.Removed("AAAAAAAAAAA") in seen)
        // B is played: D's arrival removes C, never B.
        now += 1_000
        store.markPlayed("BBBBBBBBBBB", SpatialProfile.STUDIO)
        now += 1_000
        assertNull(store.put("DDDDDDDDDDD"))
        assertNotNull(store.localRender("BBBBBBBBBBB", SpatialProfile.STUDIO))
        assertNull(store.localRender("CCCCCCCCCCC", SpatialProfile.STUDIO))
        assertNotNull(store.localRender("DDDDDDDDDDD", SpatialProfile.STUDIO))
        // A budget smaller than one render still keeps the one played last and the one just stored.
        budget = 1
        now += 1_000
        assertNull(store.put("EEEEEEEEEEE"))
        assertNotNull(store.localRender("BBBBBBBBBBB", SpatialProfile.STUDIO))
        assertNotNull(store.localRender("EEEEEEEEEEE", SpatialProfile.STUDIO))
        assertNull(store.localRender("DDDDDDDDDDD", SpatialProfile.STUDIO))
        // A song's renders leave together.
        budget = Long.MAX_VALUE
        assertNull(store.put("FFFFFFFFFFF", SpatialProfile.WIDE))
        assertNull(store.put("FFFFFFFFFFF", SpatialProfile.NIGHT, seed = 9))
        budget = 1
        store.markPlayed("EEEEEEEEEEE", SpatialProfile.STUDIO)
        store.trimToBudget()
        assertNull(store.localRender("FFFFFFFFFFF", SpatialProfile.WIDE))
        assertNull(store.localRender("FFFFFFFFFFF", SpatialProfile.NIGHT))
        assertNotNull(store.localRender("EEEEEEEEEEE", SpatialProfile.STUDIO))
    }

    @Test fun `removing every render empties the store and says so`() = runBlocking {
        val store = store()
        val seen = events(store)
        assertNull(store.put("J7p4bzqLvCw"))
        store.removeAll()
        assertFalse(root.exists())
        assertNull(store.localRender("J7p4bzqLvCw", SpatialProfile.STUDIO))
        assertEquals(SpatialStoreUsage(), store.usage.value)
        assertEquals(SpatialLibraryEvent.Removed(null), seen.last())
        // And it takes renders again afterwards.
        assertNull(store.put("J7p4bzqLvCw"))
        assertNotNull(store.localRender("J7p4bzqLvCw", SpatialProfile.STUDIO))
    }

    @Test fun `the eviction order is least recently played first, by song`() {
        val items = listOf(
            SpatialEviction.Item("a", 10, 300), SpatialEviction.Item("a", 10, 100),
            SpatialEviction.Item("b", 10, 200), SpatialEviction.Item("c", 10, 50), SpatialEviction.Item("d", 10, 50),
        )
        assertEquals(listOf("c", "d", "b", "a"), SpatialEviction.order(items, kept = emptySet()))
        assertEquals(listOf("d", "a"), SpatialEviction.order(items, kept = setOf("b", "c")))
    }
}
