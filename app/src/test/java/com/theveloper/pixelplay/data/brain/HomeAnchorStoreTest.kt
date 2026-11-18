package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.brain.StartReason
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which plays a "Because you listened to" row may be built around (docs/brain-spec.md, 8.1): the
 * listener's own choices heard out, and engine picks they acted on. Never a track that merely played
 * through on autoplay.
 */
class HomeAnchorStoreTest {

    private val store = MemoryBrainStore()
    private val resolver = MemoryTrackResolver()
    private val catalog = FakeTrackCatalog()
    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }
    private val noRebuild = object : StateRebuilder {
        override suspend fun isNeeded(): Boolean = false
        override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt = error("not needed")
    }
    private val engine = BrainEngine(store, { noRebuild }, clock)
    private val anchors = HomeAnchorStore(store)
    private val recorder = BrainEventRecorder(engine, resolver, catalog, store, anchors)

    init {
        catalog.add("song-a", "Sabrina Carpenter", "Espresso")
        catalog.add("song-b", "NewJeans", "Ditto")
        catalog.add("song-c", "Sabrina Carpenter", "Taste")
        catalog.add("engine-1", "d4vd", "Here With Me", inLibrary = false)
    }

    private val autoplayTag = BrainItemTag(surface = "autoplay", surfaceId = "seed", recId = 99L, slot = 2, arm = "RADIO")

    private fun play(songId: String, playedMs: Long, endReason: Int, tag: BrainItemTag? = null, flags: Int = 0): FinishedPlay {
        val started = now
        now += playedMs + 1_000L
        return FinishedPlay(
            songId = songId,
            context = PlayContext(title = "", artistDisplay = "", startReason = if (tag == null) StartReason.TAP else StartReason.AUTOPLAY, tag = tag),
            startedAtMs = started,
            playedMs = playedMs,
            durationMs = 200_000L,
            endReason = endReason,
            flags = flags,
        )
    }

    @Test
    fun `a track the listener chose and heard out is an anchor, with what a radio request needs`() = runTest {
        recorder.record(play("song-a", 200_000L, EndReason.TRACK_DONE))
        val anchor = anchors.read().single()
        assertEquals("song-a", anchor.mediaId)
        assertEquals("Espresso", anchor.title)
        assertEquals("Sabrina Carpenter", anchor.primaryArtist)
        assertEquals(resolver.rid("Sabrina Carpenter", "Espresso"), anchor.rid)
        assertEquals(resolver.aid("Sabrina Carpenter"), anchor.aid)
    }

    @Test
    fun `a skipped track and a track that merely played through on autoplay are not anchors`() = runTest {
        recorder.record(play("song-a", 4_000L, EndReason.NEXT))
        recorder.record(play("engine-1", 200_000L, EndReason.TRACK_DONE, tag = autoplayTag))
        assertTrue(anchors.read().isEmpty())
    }

    @Test
    fun `an engine pick the listener liked is an anchor`() = runTest {
        recorder.record(play("engine-1", 200_000L, EndReason.TRACK_DONE, tag = autoplayTag, flags = EventFlags.LIKED_DURING))
        assertEquals("engine-1", anchors.read().single().mediaId)
    }

    @Test
    fun `a not-for-me is never an anchor`() = runTest {
        recorder.record(play("song-a", 200_000L, EndReason.NEXT, flags = EventFlags.NOT_FOR_ME))
        assertTrue(anchors.read().isEmpty())
    }

    @Test
    fun `newest first, one entry per recording, eight at most`() {
        var list = emptyList<HomeAnchor>()
        for (i in 1..12) list = HomeAnchorStore.push(list, anchor(rid = i.toLong(), artist = "A$i", heardAt = i.toLong()))
        assertEquals(HomeAnchorStore.MAX, list.size)
        assertEquals(12L, list.first().rid)
        list = HomeAnchorStore.push(list, anchor(rid = 9L, artist = "A9", heardAt = 99L))
        assertEquals(9L, list.first().rid)
        assertEquals(1, list.count { it.rid == 9L })
        assertEquals(list, HomeAnchorStore.decode(HomeAnchorStore.encode(list)))
    }

    @Test
    fun `a page gets the two newest anchors by different artists, of the last seven days`() {
        val day = 24L * 60 * 60 * 1000
        val list = listOf(
            anchor(1, "Sabrina Carpenter", heardAt = now - 1_000L, aid = 10),
            anchor(2, "Sabrina Carpenter", heardAt = now - 2_000L, aid = 10),
            anchor(3, "sabrina carpenter", heardAt = now - 3_000L, aid = 0),
            anchor(4, "NewJeans", heardAt = now - 8 * day, aid = 11),
            anchor(5, "d4vd", heardAt = now - 2 * day, aid = 12),
            anchor(6, "Nirvana", heardAt = now - 3 * day, aid = 13),
        )
        assertEquals(listOf(1L, 5L), HomeAnchorStore.pick(list, now, 2).map { it.rid })
        assertTrue(HomeAnchorStore.pick(emptyList(), now, 2).isEmpty())
    }

    @Test
    fun `nonsense in the store reads as no anchors`() {
        assertTrue(HomeAnchorStore.decode(null).isEmpty())
        assertTrue(HomeAnchorStore.decode("{").isEmpty())
        store.putState(HomeAnchorStore.KEY, "not json")
        assertTrue(anchors.read().isEmpty())
    }

    @Test
    fun `an engine arm of NONE with a completion qualifies, a never-audible play does not`() = runTest {
        recorder.record(play("song-b", 200_000L, EndReason.TRACK_DONE, tag = BrainItemTag(surface = "playlist", arm = Arm.nameOf(Arm.NONE))))
        assertEquals(1, anchors.read().size)
        recorder.record(play("song-c", 0L, EndReason.ERROR, flags = EventFlags.NEVER_AUDIBLE))
        assertEquals(1, anchors.read().size)
    }

    private fun anchor(rid: Long, artist: String, heardAt: Long, aid: Long = rid) =
        HomeAnchor(rid = rid, aid = aid, mediaId = "m$rid", title = "T$rid", primaryArtist = artist, spotifyId = "s$rid", heardAtMs = heardAt)
}
