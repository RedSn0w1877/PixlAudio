package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.data.service.player.AutoplayPolicy.Candidate
import com.theveloper.pixelplay.data.service.player.AutoplayPolicy.Decision
import com.theveloper.pixelplay.data.service.player.AutoplayPolicy.Slot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AutoplayPolicyTest {

    private val repeatOff = 0
    private val repeatOne = 1
    private val repeatAll = 2

    private fun decide(
        enabled: Boolean = true,
        repeatMode: Int = repeatOff,
        remote: Boolean = false,
        restoring: Boolean = false,
        size: Int,
        index: Int,
    ) = AutoplayPolicy.decide(enabled, repeatMode, remote, restoring, size, index)

    private var nextId = 0
    private var nextMediaId = 0

    private fun candidate(
        artist: String,
        album: String? = null,
        mediaId: String = "m${++nextMediaId}",
        rid: Long = (++nextId + 1_000).toLong(),
        durationMs: Long = 200_000L,
        flags: Int = 0,
        officialAudio: Boolean = true,
        inLibrary: Boolean = false,
        rank: Int = 1,
    ) = Candidate(mediaId, rid, artist, album, durationMs, flags, officialAudio, inLibrary, if (inLibrary) Arm.FAV else Arm.RADIO, rank)

    // ── Trigger ──────────────────────────────────────────────────────────────

    @Test
    fun `tops up when two or fewer tracks are left after the current one`() {
        assertEquals(Decision.TOP_UP, decide(size = 1, index = 0))
        assertEquals(Decision.TOP_UP, decide(size = 10, index = 9))
        assertEquals(Decision.TOP_UP, decide(size = 10, index = 8))
        assertEquals(Decision.TOP_UP, decide(size = 10, index = 7))
        assertEquals(Decision.ENOUGH_QUEUED, decide(size = 10, index = 6))
        assertEquals(Decision.ENOUGH_QUEUED, decide(size = 10, index = 0))
        assertEquals(Decision.NO_QUEUE, decide(size = 0, index = 0))
        assertEquals(Decision.NO_QUEUE, decide(size = 5, index = -1))
    }

    @Test
    fun `the count is the full queue, not the window the player holds after a crossfade`() {
        // A 260-item queue, playing item 150. After a crossfade the active player holds a 200-item
        // window (items 50..249), in which the same track is index 100.
        val fullSize = 260
        val absoluteIndex = 150
        assertEquals(109, AutoplayPolicy.remainingAfterCurrent(fullSize, absoluteIndex))
        assertEquals(Decision.ENOUGH_QUEUED, decide(size = fullSize, index = absoluteIndex))

        // Playing item 248 of 260: the window is items 60..259 and the player says "index 188 of 200".
        // Both views agree here only because the window reaches the end of the queue.
        assertEquals(11, AutoplayPolicy.remainingAfterCurrent(260, 248))
        assertEquals(Decision.ENOUGH_QUEUED, decide(size = 260, index = 248))
        assertEquals(Decision.TOP_UP, decide(size = 260, index = 258))

        // The trap: a window that ends before the queue does. The player's own numbers (index 199 of
        // 200) say "nothing left" while 60 tracks are still queued. The full-queue numbers do not.
        val windowSize = 200
        val indexInWindow = 199
        assertEquals(0, AutoplayPolicy.remainingAfterCurrent(windowSize, indexInWindow))
        assertEquals(60, AutoplayPolicy.remainingAfterCurrent(fullSize, 199))
        assertEquals(Decision.ENOUGH_QUEUED, decide(size = fullSize, index = 199))
    }

    @Test
    fun `no top-up under repeat`() {
        assertEquals(Decision.REPEATING, decide(repeatMode = repeatOne, size = 1, index = 0))
        assertEquals(Decision.REPEATING, decide(repeatMode = repeatAll, size = 10, index = 9))
        assertEquals(Decision.TOP_UP, decide(repeatMode = repeatOff, size = 10, index = 9))
    }

    @Test
    fun `off when switched off, during cast and spotify connect, and while a queue is restored`() {
        assertEquals(Decision.OFF, decide(enabled = false, size = 1, index = 0))
        assertEquals(Decision.REMOTE, decide(remote = true, size = 1, index = 0))
        assertEquals(Decision.RESTORING, decide(restoring = true, size = 1, index = 0))
        // The switch wins over everything else, so a disabled autoplay never reports another reason.
        assertEquals(Decision.OFF, decide(enabled = false, remote = true, repeatMode = repeatAll, size = 1, index = 0))
    }

    // ── Remote outputs (14.6) ────────────────────────────────────────────────

    @Test
    fun `on this phone everything runs`() {
        val rule = AutoplayPolicy.remoteRule(AutoplayPolicy.RemoteOutput.NONE)
        assertTrue(rule.picks)
        assertFalse(rule.onlyWhatTheOutputPlays)
        assertTrue(rule.rescore)
        assertTrue(rule.settleOnLocalPlayer)
    }

    @Test
    fun `picks run while casting, only ones whose stream resolves, and the tail is never replaced there`() {
        val rule = AutoplayPolicy.remoteRule(AutoplayPolicy.RemoteOutput.CAST)
        assertTrue(rule.picks)
        assertTrue(rule.onlyWhatTheOutputPlays)
        // Replacing what the receiver was told to play means sending it the list again, which is audible.
        assertFalse(rule.rescore)
        // The phone's own player sits paused during a session: no position to wait for.
        assertFalse(rule.settleOnLocalPlayer)
        assertEquals(Decision.TOP_UP, decide(remote = !rule.picks, size = 3, index = 1))
        // The switch for Spotify Connect does not close Cast.
        assertTrue(AutoplayPolicy.remoteRule(AutoplayPolicy.RemoteOutput.CAST, connectAllowed = false).picks)
    }

    @Test
    fun `picks run on spotify connect, limited to what spotify has, and the tail is never replaced there`() {
        val rule = AutoplayPolicy.remoteRule(AutoplayPolicy.RemoteOutput.CONNECT)
        assertTrue(rule.picks)
        assertTrue(rule.onlyWhatTheOutputPlays)
        // Replacing what the device was told to play means sending the list again, which is audible.
        assertFalse(rule.rescore)
        // The phone's own player sits paused at 0 during a session: there is no position to wait for.
        assertFalse(rule.settleOnLocalPlayer)
        assertEquals(Decision.TOP_UP, decide(remote = !rule.picks, size = 3, index = 1))
    }

    @Test
    fun `one switch turns picks on spotify connect off again`() {
        val rule = AutoplayPolicy.remoteRule(AutoplayPolicy.RemoteOutput.CONNECT, connectAllowed = false)
        assertFalse(rule.picks)
        assertEquals(Decision.REMOTE, decide(remote = !rule.picks, size = 1, index = 0))
    }

    @Test
    fun `only the picks the output answered yes for go into the queue, in the planned order`() {
        val ids = listOf("a", "b", "c", "d")
        assertEquals(listOf("a", "c"), AutoplayPolicy.keepPlayable(ids, booleanArrayOf(true, false, true, false)).toList())
        assertTrue(AutoplayPolicy.keepPlayable(ids, booleanArrayOf(false, false, false, false)).isEmpty())
        // A short answer (the lookup was cut off) keeps nothing it did not answer for.
        assertEquals(listOf("a"), AutoplayPolicy.keepPlayable(ids, booleanArrayOf(true)).toList())
    }

    @Test
    fun `an id spotify gave out plays on connect with no lookup - the app's own ids do not`() {
        assertTrue(AutoplayPolicy.isSpotifyTrackId("4iV5W9uYEdYUVa79Axb7Rh"))
        // 22 lower-case hex characters: the id the app gives a track saved from YouTube Music.
        assertFalse(AutoplayPolicy.isSpotifyTrackId("0123456789abcdef012345"))
        assertFalse(AutoplayPolicy.isSpotifyTrackId(null))
        assertFalse(AutoplayPolicy.isSpotifyTrackId("too-short"))
        assertFalse(AutoplayPolicy.isSpotifyTrackId("4iV5W9uYEdYUVa79Axb7R!"))
        // The rule is the one the Connect code itself uses.
        for (id in listOf("4iV5W9uYEdYUVa79Axb7Rh", "0123456789abcdef012345", "", "ABCDEFGHIJKLMNOPQRSTUV")) {
            assertEquals(
                com.theveloper.pixelplay.data.spotify.connect.SpotifyConnect.isTrackId(id),
                AutoplayPolicy.isSpotifyTrackId(id),
                id,
            )
        }
    }

    @Test
    fun `nothing is decided while the rest of a tapped song's queue is being attached`() {
        // A song tapped in a playlist is a one-item queue for a moment. That is not a single-song play.
        assertEquals(
            Decision.ATTACHING,
            AutoplayPolicy.decide(true, repeatOff, false, false, fullQueueSize = 1, currentAbsoluteIndex = 0, isAttachingQueue = true),
        )
        // Once the rest is there the queue is judged as it is.
        assertEquals(Decision.ENOUGH_QUEUED, decide(size = 40, index = 0))
        // A real single-song play (no attach follows) is topped up at once.
        assertEquals(Decision.TOP_UP, decide(size = 1, index = 0))
        // Off, remote, restoring and repeat still say what they are.
        assertEquals(
            Decision.OFF,
            AutoplayPolicy.decide(false, repeatOff, false, false, fullQueueSize = 1, currentAbsoluteIndex = 0, isAttachingQueue = true),
        )
        assertEquals(
            Decision.REPEATING,
            AutoplayPolicy.decide(true, repeatAll, false, false, fullQueueSize = 1, currentAbsoluteIndex = 0, isAttachingQueue = true),
        )
    }

    @Test
    fun `a batch held during a hand-over goes in only if the queue is still the one it was planned for`() {
        fun fits(
            decision: Decision = Decision.TOP_UP,
            poolKey: String = "a|b",
            queueSize: Int = 12,
            lastMediaId: String? = "last",
        ) = AutoplayPolicy.heldBatchStillFits(decision, "a|b", poolKey, 12, queueSize, "last", lastMediaId)
        assertTrue(fits())
        // The listener started another queue during the fade (which cancels the hand-over).
        assertFalse(fits(queueSize = 1, lastMediaId = "tapped"))
        assertFalse(fits(lastMediaId = "other"))
        // Other anchors, another pool: these picks belong to the old one.
        assertFalse(fits(poolKey = "c"))
        // The new queue is long enough, or its rest is still being attached.
        assertFalse(fits(decision = Decision.ENOUGH_QUEUED))
        assertFalse(fits(decision = Decision.ATTACHING))
        assertFalse(fits(decision = Decision.OFF))
    }

    @Test
    fun `a change of direction steers only while the engine's own picks play`() {
        // Three picks were skipped and a pick of the new direction is playing.
        assertTrue(AutoplayPolicy.turnApplies(turn = 1, reactionIsFresh = true, currentIsListenersChoice = false))
        // The listener tapped a song: autoplay follows that song, whatever was skipped before.
        assertFalse(AutoplayPolicy.turnApplies(turn = 1, reactionIsFresh = true, currentIsListenersChoice = true))
        // Not turned, or the session that turned went quiet.
        assertFalse(AutoplayPolicy.turnApplies(turn = 0, reactionIsFresh = true, currentIsListenersChoice = false))
        assertFalse(AutoplayPolicy.turnApplies(turn = 2, reactionIsFresh = false, currentIsListenersChoice = false))
    }

    @Test
    fun `offline only tracks with a copy on the phone can be queued`() {
        val cached = setOf("4uLU6hMCjMI75M1A2tKUQC")
        // A streamed library track with a complete copy in the audio cache.
        assertTrue(AutoplayPolicy.playsWithoutNetwork("spotify://4uLU6hMCjMI75M1A2tKUQC", "4uLU6hMCjMI75M1A2tKUQC", cached))
        // The same kind of track without one needs the network.
        assertFalse(AutoplayPolicy.playsWithoutNetwork("spotify://0VjIjW4GlUZAMYd2vXMi3b", "0VjIjW4GlUZAMYd2vXMi3b", cached))
        assertFalse(AutoplayPolicy.playsWithoutNetwork("spotify://0VjIjW4GlUZAMYd2vXMi3b", null, cached))
        // Files of the device.
        assertTrue(AutoplayPolicy.playsWithoutNetwork("content://media/external/audio/media/12", null, emptySet()))
        assertTrue(AutoplayPolicy.playsWithoutNetwork("file:///storage/emulated/0/Music/a.mp3", null, emptySet()))
        assertTrue(AutoplayPolicy.playsWithoutNetwork("/storage/emulated/0/Music/a.mp3", null, emptySet()))
        assertFalse(AutoplayPolicy.playsWithoutNetwork("https://example.com/a.mp3", null, emptySet()))
    }

    @Test
    fun `a skipped artist leaves a tail that cannot be planned again`() {
        // Queue indices 3..7 are the engine's replaceable tail.
        val artists = listOf("own", "own", "own", "x", "y", "x", "z", "y")
        assertEquals(4, AutoplayPolicy.firstIndexOfArtist(start = 3, size = artists.size, artistKey = "y") { artists[it] })
        assertEquals(3, AutoplayPolicy.firstIndexOfArtist(start = 3, size = artists.size, artistKey = "x") { artists[it] })
        // Before the start nothing is touched: the track that is starting and the warmed ones stay.
        assertEquals(-1, AutoplayPolicy.firstIndexOfArtist(start = 3, size = artists.size, artistKey = "own") { artists[it] })
        assertEquals(-1, AutoplayPolicy.firstIndexOfArtist(start = 3, size = artists.size, artistKey = "") { artists[it] })
        assertEquals(-1, AutoplayPolicy.firstIndexOfArtist(start = -1, size = artists.size, artistKey = "x") { artists[it] })
    }

    @Test
    fun `next pressed with nothing queued moves on to the picks only in time and on the same track`() {
        assertTrue(AutoplayPolicy.nextMayStillSkip(askedAtMs = 1_000, nowMs = 3_500, askedMediaId = "a", currentMediaId = "a"))
        assertTrue(AutoplayPolicy.nextMayStillSkip(1_000, 1_000 + AutoplayPolicy.BACKSTOP_WINDOW_MS, "a", "a"))
        // Too late: the listener has settled into the track.
        assertFalse(AutoplayPolicy.nextMayStillSkip(1_000, 1_001 + AutoplayPolicy.BACKSTOP_WINDOW_MS, "a", "a"))
        // They started something else meanwhile.
        assertFalse(AutoplayPolicy.nextMayStillSkip(1_000, 2_000, "a", "b"))
        assertFalse(AutoplayPolicy.nextMayStillSkip(1_000, 2_000, null, null))
    }

    @Test
    fun `a queue edit is deferred during a transition`() {
        assertTrue(AutoplayPolicy.mustDeferQueueEdit(isTransitionRunning = true))
        assertFalse(AutoplayPolicy.mustDeferQueueEdit(isTransitionRunning = false))
    }

    @Test
    fun `a radio request waits for the settle time and needs the source`() {
        assertFalse(AutoplayPolicy.isSettled(positionMs = 0))
        assertFalse(AutoplayPolicy.isSettled(positionMs = 1_499))
        assertTrue(AutoplayPolicy.isSettled(positionMs = 1_500))
        assertTrue(AutoplayPolicy.mayUseNetwork(online = true, youTubeSourceEnabled = true))
        // Offline, or with the YouTube source switched off, the pool is library-only.
        assertFalse(AutoplayPolicy.mayUseNetwork(online = false, youTubeSourceEnabled = true))
        assertFalse(AutoplayPolicy.mayUseNetwork(online = true, youTubeSourceEnabled = false))
    }

    @Test
    fun `the backstop resumes only inside ten seconds and only if the listener did not pause`() {
        assertTrue(AutoplayPolicy.backstopMayResume(endedAtMs = 1_000, nowMs = 1_000, playWhenReady = true))
        assertTrue(AutoplayPolicy.backstopMayResume(endedAtMs = 1_000, nowMs = 11_000, playWhenReady = true))
        assertFalse(AutoplayPolicy.backstopMayResume(endedAtMs = 1_000, nowMs = 11_001, playWhenReady = true))
        assertFalse(AutoplayPolicy.backstopMayResume(endedAtMs = 1_000, nowMs = 3_000, playWhenReady = false))
        // A clock that went backwards never resumes.
        assertFalse(AutoplayPolicy.backstopMayResume(endedAtMs = 5_000, nowMs = 1_000, playWhenReady = true))
    }

    // ── Anchors ──────────────────────────────────────────────────────────────

    @Test
    fun `anchors are the last three tracks the listener chose - engine picks never are`() {
        // u0 u1 u2 u3 | e4 e5 e6 (engine), playing e6.
        val engineFrom = 4
        val ids = listOf("u0", "u1", "u2", "u3", "e4", "e5", "e6")
        val anchors = AutoplayPolicy.anchorIndices(6, ids.size, { it >= engineFrom }, { ids[it] })
        assertEquals(listOf(3, 2, 1), anchors)

        // Tracks after the current one were not heard yet: they are not anchors.
        assertEquals(listOf(1, 0), AutoplayPolicy.anchorIndices(1, ids.size, { it >= engineFrom }, { ids[it] }))

        // One song tapped: it is the only anchor.
        assertEquals(listOf(0), AutoplayPolicy.anchorIndices(0, 1, { false }, { "only" }))

        // A queue of nothing but engine picks has no anchor.
        assertEquals(emptyList<Int>(), AutoplayPolicy.anchorIndices(2, 3, { true }, { "e$it" }))

        // The same track queued twice is one anchor.
        val twice = listOf("a", "b", "a", "a")
        assertEquals(listOf(3, 1), AutoplayPolicy.anchorIndices(3, twice.size, { false }, { twice[it] }))
    }

    @Test
    fun `before the session has anchors only the track that is playing seeds the pool`() {
        val ids = listOf("u0", "u1", "u2", "e3")
        assertEquals(listOf(2), AutoplayPolicy.anchorIndices(2, ids.size, { it >= 3 }, { ids[it] }, max = 1))
        // Playing an engine pick: the last track the listener chose before it.
        assertEquals(listOf(2), AutoplayPolicy.anchorIndices(3, ids.size, { it >= 3 }, { ids[it] }, max = 1))
        assertEquals(emptyList<Int>(), AutoplayPolicy.anchorIndices(2, ids.size, { it >= 3 }, { ids[it] }, max = 0))
    }

    // ── Re-scoring the tail ──────────────────────────────────────────────────

    @Test
    fun `a re-score never touches the track that is starting, nor a warmed one`() {
        // u0 u1 | e2 e3 e4 e5 e6, playing u1 (index 1).
        val engineFrom = 2
        fun start(current: Int, warmed: Int, size: Int = 7, engine: (Int) -> Boolean = { it >= engineFrom }) =
            AutoplayPolicy.rescoreStart(size, current, warmed, engine)
        assertEquals(1, AutoplayPolicy.warmedAhead(unmeteredWifi = false))
        assertEquals(2, AutoplayPolicy.warmedAhead(unmeteredWifi = true))
        // On cellular the next track (e2) is warmed: the re-score starts at e3.
        assertEquals(3, start(current = 1, warmed = 1))
        // On unmetered Wi-Fi the next two are.
        assertEquals(4, start(current = 1, warmed = 2))
        // Whatever the caller says, the next track is never replaced.
        assertEquals(3, start(current = 1, warmed = 0))
        // Nothing left behind the warmed tracks: nothing to re-score.
        assertEquals(-1, start(current = 5, warmed = 1))
        assertEquals(-1, start(current = 4, warmed = 2))
        assertEquals(-1, start(current = 6, warmed = 1))
        assertEquals(-1, AutoplayPolicy.rescoreStart(0, 0, 1) { true })
        assertEquals(-1, start(current = -1, warmed = 1))
    }

    @Test
    fun `a re-score only owns the unbroken run of engine items at the very end`() {
        // u0 e1 e2 e3 U4 e5 e6: the listener queued U4 between the engine's picks.
        val owned = setOf(1, 2, 3, 5, 6)
        assertEquals(5, AutoplayPolicy.rescoreStart(7, 0, 1) { it in owned })
        // The last item is the listener's: the engine owns nothing at the end.
        assertEquals(-1, AutoplayPolicy.rescoreStart(7, 0, 1) { it in setOf(1, 2, 3) })
        // On a windowed queue the indices are absolute: 400 items, playing 230, all engine after 300.
        assertEquals(301, AutoplayPolicy.rescoreStart(400, 230, 1) { it > 300 })
        assertEquals(232, AutoplayPolicy.rescoreStart(400, 230, 1) { it > 200 })
    }

    // ── Filters ──────────────────────────────────────────────────────────────

    @Test
    fun `filters - only official audio between one and fifteen minutes`() {
        assertTrue(AutoplayPolicy.passesFilters(candidate("a"), seedFlags = 0))
        assertFalse(AutoplayPolicy.passesFilters(candidate("a", officialAudio = false), 0))
        assertFalse(AutoplayPolicy.passesFilters(candidate("a", durationMs = 59_999), 0))
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", durationMs = 60_000), 0))
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", durationMs = 900_000), 0))
        assertFalse(AutoplayPolicy.passesFilters(candidate("a", durationMs = 900_001), 0))
        // An unknown length (a Related-tab row) is not a reason to drop the track.
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", durationMs = 0), 0))
    }

    @Test
    fun `filters - karaoke, cover, sped-up and slowed never, live and remix only with a seed that is one`() {
        for (flag in listOf(BrainText.FLAG_KARAOKE, BrainText.FLAG_COVER, BrainText.FLAG_SPED_UP, BrainText.FLAG_SLOWED)) {
            assertFalse(AutoplayPolicy.passesFilters(candidate("a", flags = flag), seedFlags = flag), "flag $flag")
        }
        for (flag in listOf(BrainText.FLAG_LIVE, BrainText.FLAG_INSTRUMENTAL, BrainText.FLAG_REMIX)) {
            assertFalse(AutoplayPolicy.passesFilters(candidate("a", flags = flag), seedFlags = 0), "flag $flag")
            assertTrue(AutoplayPolicy.passesFilters(candidate("a", flags = flag), seedFlags = flag), "flag $flag")
        }
        // A live remix needs a seed that is both.
        val liveRemix = BrainText.FLAG_LIVE or BrainText.FLAG_REMIX
        assertFalse(AutoplayPolicy.passesFilters(candidate("a", flags = liveRemix), seedFlags = BrainText.FLAG_LIVE))
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", flags = liveRemix), seedFlags = liveRemix))
        // Remaster, acoustic and re-recorded versions are fine.
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", flags = BrainText.FLAG_REMASTER), 0))
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", flags = BrainText.FLAG_ACOUSTIC), 0))
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", flags = BrainText.FLAG_RE_RECORDED), 0))
    }

    @Test
    fun `filters - a library track is the listener's own version and only the length rule applies`() {
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", flags = BrainText.FLAG_LIVE, inLibrary = true), 0))
        assertTrue(AutoplayPolicy.passesFilters(candidate("a", flags = BrainText.FLAG_COVER, inLibrary = true), 0))
        assertFalse(AutoplayPolicy.passesFilters(candidate("a", durationMs = 30_000, inLibrary = true), 0))
    }

    // ── Picking ──────────────────────────────────────────────────────────────

    @Test
    fun `picks in pool order - radio order is the only ranking in stage 1a`() {
        val pool = List(12) { candidate("artist$it", "album$it") }
        val picked = AutoplayPolicy.pickBatch(pool, emptyList(), emptySet(), emptySet())
        assertEquals(pool.take(5), picked)
    }

    @Test
    fun `never a recording twice, never what is queued or was played`() {
        val a = candidate("a", mediaId = "ma", rid = 100)
        val sameRecording = candidate("b", mediaId = "mb", rid = 100)
        val queued = candidate("c", mediaId = "queued", rid = 101)
        val playedRecently = candidate("d", mediaId = "md", rid = 102)
        val fine = candidate("e", mediaId = "me", rid = 103)
        val picked = AutoplayPolicy.pickBatch(
            pool = listOf(a, sameRecording, queued, playedRecently, fine),
            engineTail = emptyList(),
            excludedMediaIds = setOf("queued"),
            excludedRids = setOf(102L),
        )
        assertEquals(listOf(a, fine), picked)
    }

    @Test
    fun `the same primary artist is not within four slots`() {
        val a1 = candidate("a")
        val a2 = candidate("a")
        val others = List(6) { candidate("o$it") }
        val picked = AutoplayPolicy.pickBatch(listOf(a1, a2) + others, emptyList(), emptySet(), emptySet(), count = 6)
        // a1, then four others, and only then a2.
        assertEquals(listOf(a1, others[0], others[1], others[2], others[3], a2), picked)
    }

    @Test
    fun `at most two tracks by one artist in twenty and three in fifty`() {
        val byA = List(6) { candidate("a") }
        val others = List(60) { candidate("o$it") }
        val picked = AutoplayPolicy.pickBatch(byA + others, emptyList(), emptySet(), emptySet(), count = 50)
        assertEquals(50, picked.size)
        val positions = picked.withIndex().filter { it.value.artistKey == "a" }.map { it.index }
        assertEquals(3, positions.size, "three in fifty")
        for (start in 0..30) {
            assertTrue(positions.count { it in start until start + 20 } <= 2, "two in twenty from $start: $positions")
        }
    }

    @Test
    fun `the engine's own tail counts for spacing, and so does the batch being built`() {
        // The last engine item in the queue is by "a": the first pick cannot be.
        val a = candidate("a")
        val b = candidate("b")
        val picked = AutoplayPolicy.pickBatch(listOf(a, b), listOf(Slot("a", null)), emptySet(), emptySet(), count = 2)
        assertEquals(listOf(b), picked)

        // Four slots later it can.
        val tail = listOf(Slot("a", null), Slot("x", null), Slot("y", null), Slot("z", null), Slot("w", null))
        assertEquals(listOf(a, b), AutoplayPolicy.pickBatch(listOf(a, b), tail, emptySet(), emptySet(), count = 2))
    }

    @Test
    fun `the same album is not within eight slots`() {
        val first = candidate("a", album = "album")
        val second = candidate("b", album = "album")
        val others = List(10) { candidate("o$it", album = "other$it") }
        val picked = AutoplayPolicy.pickBatch(listOf(first, second) + others, emptyList(), emptySet(), emptySet(), count = 10)
        assertEquals(0, picked.indexOf(first))
        assertEquals(9, picked.indexOf(second))
        // Unknown albums never collide with each other.
        val unknown = List(5) { candidate("u$it", album = null) }
        assertEquals(unknown, AutoplayPolicy.pickBatch(unknown, emptyList(), emptySet(), emptySet()))
    }

    @Test
    fun `a pool that cannot fill the batch without breaking a rule returns what fits`() {
        val pool = List(5) { candidate("a") }
        assertEquals(1, AutoplayPolicy.pickBatch(pool, emptyList(), emptySet(), emptySet()).size)
        assertEquals(emptyList<Candidate>(), AutoplayPolicy.pickBatch(emptyList(), emptyList(), emptySet(), emptySet()))
        assertEquals(emptyList<Candidate>(), AutoplayPolicy.pickBatch(pool, emptyList(), emptySet(), emptySet(), count = 0))
    }

    @Test
    fun `interleave merges the anchors' radios rank by rank, newest anchor first, one row per recording`() {
        val a1 = candidate("a1", rid = 1)
        val a2 = candidate("a2", rid = 2)
        val a3 = candidate("a3", rid = 3)
        val b1 = candidate("b1", rid = 10)
        val b2same = candidate("b2", rid = 2) // the same recording as a2, under another audio id
        val merged = AutoplayPolicy.interleave(listOf(listOf(a1, a2, a3), listOf(b1, b2same)))
        assertEquals(listOf(a1, b1, a2, a3), merged)
        assertEquals(listOf(a1, a2, a3), AutoplayPolicy.interleave(listOf(listOf(a1, a2, a3))))
        assertEquals(emptyList<Candidate>(), AutoplayPolicy.interleave(emptyList()))
    }

    @Test
    fun `library tracks are FAV when ever played and DEEP when never`() {
        assertEquals(Arm.FAV, AutoplayPolicy.libraryArm(everPlayed = true))
        assertEquals(Arm.DEEP, AutoplayPolicy.libraryArm(everPlayed = false))
    }
}
