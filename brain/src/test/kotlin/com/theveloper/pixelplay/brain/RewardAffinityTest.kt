package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The event record, the reward table of 4.3, the counters of 5.2 and the replay of 4.4. */
class RewardAffinityTest {

    private val day = 86_400_000L
    private val now = 1_760_000_000_000L

    private fun event(
        playedMs: Int,
        durationMs: Int = 200_000,
        endReason: Int = EndReason.NEXT,
        origin: Int = Origin.LIVE,
        surface: Int = Surface.PLAYLIST,
        flags: Int = 0,
        arm: Int = Arm.NONE,
        rid: Long = 7L,
        aid: Long = 70L,
        startedAtMs: Long = now,
        passiveRun: Int = 0,
        sessionId: Long = 1L,
        otherAids: LongArray = PlayEvent.NO_AIDS,
    ) = PlayEvent(
        eventId = PlayEvent.mintId(startedAtMs, origin, "k$rid") { null },
        origin = origin, rid = rid, primaryAid = aid, otherAids = otherAids, keyAtWrite = "k$rid",
        startedAtMs = startedAtMs, playedMs = playedMs, durationMs = durationMs, endReason = endReason,
        flags = flags, surface = surface, arm = arm, passiveRun = passiveRun, sessionId = sessionId,
    )

    private fun grade(e: PlayEvent, past: Int = 0, streak: Int = 0) = Reward.grade(e, Reward.Context(past, streak))

    // ── PlayEvent ────────────────────────────────────────────────────────────

    @Test
    fun `an event survives its canonical encoding`() {
        val e = PlayEvent(
            eventId = (now shl 16) or 5L, origin = Origin.LIVE, rid = -99L, primaryAid = Long.MIN_VALUE,
            otherAids = longArrayOf(3L, -4L), keyAtWrite = "n:sơn tùng|\"x\"\\", startedAtMs = now, tzOffsetMin = -300,
            playedMs = 1234, durationMs = 200_000, startReason = StartReason.AUTOPLAY, endReason = EndReason.QUEUE_EDIT,
            flags = EventFlags.SHUFFLE or EventFlags.LEARN_EXCLUDED, surface = Surface.AUTOPLAY, surfaceId = "seed",
            recId = 77L, slot = 3, arm = Arm.RADIO, sessionId = 9L, posInSession = 4, passiveRun = 2,
            route = Route.CAR, retrQ = 512, radioRank = 9, pServedQ = 1, modelVersion = 2,
        )
        val line = e.encode()
        assertEquals(line, PlayEvent.decode(line).encode())
        assertTrue(line.startsWith("{\"eventId\":\""))
        assertFalse(line.any { it.code > 0x7E }, "non-ASCII must be escaped")
        val noTz = PlayEvent.decode(event(1).encode())
        assertEquals(PlayEvent.TZ_UNKNOWN, noTz.tzOffsetMin)
    }

    @Test
    fun `the event id is time-ordered, repeatable, and steps past another play's id`() {
        val a = PlayEvent.mintId(now, Origin.MIGRATED_HISTORY, "song-1") { null }
        assertEquals(a, PlayEvent.mintId(now, Origin.MIGRATED_HISTORY, "song-1") { null })
        assertEquals(now, a ushr 16)
        assertTrue(PlayEvent.mintId(now + 1, Origin.LIVE, "zzz") { null } > a)
        // The same play already stored: the same id (idempotent copy).
        assertEquals(a, PlayEvent.mintId(now, Origin.MIGRATED_HISTORY, "song-1") { PlayEvent.identityOf(Origin.MIGRATED_HISTORY, "song-1") })
        // Another play holds the id: the next sequence number.
        val moved = PlayEvent.mintId(now, Origin.MIGRATED_HISTORY, "song-1") { id -> if (id == a) "live|other" else null }
        assertEquals(now, moved ushr 16)
        assertEquals(((a and 0xFFFF) + 1) and 0xFFFF, moved and 0xFFFF)
    }

    // ── Reward ───────────────────────────────────────────────────────────────

    @Test
    fun `the nine rules for r, first match wins`() {
        assertEquals(0.0, grade(event(190_000, flags = EventFlags.NOT_FOR_ME or EventFlags.LIKED_DURING)).r)
        assertEquals(1.0, grade(event(2_000, flags = EventFlags.LIKED_DURING)).r)
        assertEquals(0.75, grade(event(-1, durationMs = 0, origin = Origin.IMPORT_SPOTIFY, endReason = EndReason.UNKNOWN)).r)
        assertEquals(1.0, grade(event(3_000, endReason = EndReason.TRACK_DONE)).r)
        assertEquals(1.0, grade(event(160_000)).r)
        assertEquals(0.0, grade(event(9_999)).r)
        assertEquals(0.1, grade(event(29_999)).r)
        assertEquals(0.3 + 0.7 * ((100_000.0 / 200_000.0) / 0.8), grade(event(100_000)).r)
        assertEquals(0.6, grade(event(45_000, durationMs = 0)).r)
        assertEquals(8, grade(event(100_000)).rule)
    }

    @Test
    fun `a two second skip is a skip of full strength and counts against the track`() {
        val g = grade(event(2_000))
        assertTrue(g.isSkip)
        assertEquals(1.0, g.h)
        assertEquals(0.0, g.r)
        assertEquals(1.5, g.c) // the listener's own early skip: the strongest evidence (tools/eval, 2026-11-25)
        assertEquals(1.0, grade(event(2_000, surface = Surface.AUTOPLAY, arm = Arm.RADIO)).c) // an active rejection
    }

    @Test
    fun `what is a skip and what is not`() {
        assertTrue(Reward.isSkip(event(99_000)))
        assertFalse(Reward.isSkip(event(100_000)))
        assertFalse(Reward.isSkip(event(2_000, endReason = EndReason.STOPPED)))
        assertFalse(Reward.isSkip(event(2_000, endReason = EndReason.PREV)))
        assertTrue(Reward.isSkip(event(20_000, durationMs = 0, endReason = EndReason.PICKED_OTHER)))
        assertFalse(Reward.isSkip(event(31_000, durationMs = 0, endReason = EndReason.QUEUE_EDIT)))
        assertEquals(0.6, Reward.skipStrength(event(15_000), 0))
        assertEquals(0.4, Reward.skipStrength(event(60_000), 0))
        assertEquals(0.3, Reward.skipStrength(event(1_000), 3))
    }

    @Test
    fun `trust - engine picks count less when finished, and never more than 4,5`() {
        assertEquals(1.0, grade(event(200_000, endReason = EndReason.TRACK_DONE)).c)
        assertEquals(0.6, grade(event(200_000, endReason = EndReason.TRACK_DONE, surface = Surface.AUTOPLAY)).c)
        assertEquals(0.8, grade(event(200_000, endReason = EndReason.TRACK_DONE, origin = Origin.MIGRATED_HISTORY)).c)
        assertEquals(0.5 * 0.35, grade(event(-1, origin = Origin.IMPORT_YTM, endReason = EndReason.UNKNOWN)).c)
        // Put the phone down after 20 s: not evidence about the song.
        assertEquals(0.0, grade(event(20_000, endReason = EndReason.STOPPED)).c)
        assertEquals(0.0, grade(event(20_000, endReason = EndReason.KILLED)).c)
        // A like is an explicit action: three times the weight.
        assertEquals(3.0, grade(event(100_000, flags = EventFlags.LIKED_DURING)).c)
        assertEquals(4.5, grade(event(100_000, surface = Surface.AUTOPLAY, flags = EventFlags.NOT_FOR_ME or EventFlags.EXPLORE_SLOT or EventFlags.SEEK_BACK)).c)
        assertEquals(0.0, grade(event(200_000, endReason = EndReason.TRACK_DONE, flags = EventFlags.NEVER_AUDIBLE)).c)
        assertEquals(0.5, grade(event(200_000, endReason = EndReason.TRACK_DONE, passiveRun = 6)).c)
        assertEquals(0.5, grade(event(200_000, endReason = EndReason.TRACK_DONE, passiveRun = 12)).c)
    }

    @Test
    fun `skip streaks and skips of loved tracks are discounted`() {
        val plain = grade(event(5_000)).c
        assertEquals(plain * 0.3, grade(event(5_000), streak = 2).c, 1e-12)
        assertEquals(plain * 0.3 * 0.3, grade(event(1_500), streak = 2).c, 1e-12)
        val loved = grade(event(5_000), past = 3)
        assertEquals(plain, loved.c)
        assertEquals(plain * 0.3, loved.cOther, 1e-12)
    }

    @Test
    fun `the tracker counts a streak only inside one session and within a minute`() {
        val state = Affinity.State()
        val tracker = GradeTracker(state.completions)
        val c1 = tracker.grade(event(3_000, startedAtMs = now)).c
        val c2 = tracker.grade(event(3_000, startedAtMs = now + 10_000)).c
        val c3 = tracker.grade(event(3_000, startedAtMs = now + 20_000)).c
        assertEquals(c1, c2)
        assertEquals(c1 * 0.3, c3, 1e-12)
        // More than a minute later the run is over.
        assertEquals(c1, tracker.grade(event(3_000, startedAtMs = now + 200_000)).c)
        // A new session starts clean.
        tracker.grade(event(3_000, startedAtMs = now + 210_000))
        assertEquals(c1, tracker.grade(event(3_000, startedAtMs = now + 220_000, sessionId = 2L)).c)
    }

    // ── Affinity ─────────────────────────────────────────────────────────────

    @Test
    fun `a new listener has affinity exactly zero and p0 one half`() {
        val s = Affinity.State()
        assertEquals(0.5, Affinity.p0(s, now))
        assertEquals(0.0, Affinity.aff(s, Family.TRACK, 1L, now, 0.5))
        assertEquals(0.0, Affinity.affRecent(s, Family.ARTIST, 1L, now, 0.5))
        assertEquals(0.0, Affinity.share(s, Family.ARTIST, 1L, now))
        assertFalse(Affinity.isHeard(s, Family.TRACK, 1L))
    }

    @Test
    fun `counters halve in 120 days and the recent copy in 21`() {
        val s = Affinity.State()
        Affinity.apply(s, Family.TRACK, 1L, 1.0, 1.0, 1.0, now)
        val k = s.counter(Family.TRACK, 1L)!!
        assertEquals(1_048_576L, k.posQ)
        assertEquals(0.5, Affinity.pos(k, now + 120 * day), 1e-12)
        assertEquals(0.5, Affinity.posRecent(k, now + 21 * day), 1e-12)
        // Reads never write.
        assertEquals(1_048_576L, k.posQ)
        assertEquals(now, k.lastMs)
    }

    @Test
    fun `a late event shrinks its increment and never moves the clock back`() {
        val onTime = Affinity.State()
        Affinity.apply(onTime, Family.ARTIST, 5L, 1.0, 1.0, 1.0, now - 30 * day)
        Affinity.apply(onTime, Family.ARTIST, 5L, 0.0, 1.0, 1.0, now)
        val late = Affinity.State()
        Affinity.apply(late, Family.ARTIST, 5L, 0.0, 1.0, 1.0, now)
        Affinity.apply(late, Family.ARTIST, 5L, 1.0, 1.0, 1.0, now - 30 * day)
        val a = onTime.counter(Family.ARTIST, 5L)!!
        val b = late.counter(Family.ARTIST, 5L)!!
        assertEquals(now, b.lastMs)
        // Exponential decay makes the two orders agree to the last bits.
        assertTrue(Math.abs(a.posQ - b.posQ) <= 2, "${a.posQ} vs ${b.posQ}")
        assertEquals(a.negQ, b.negQ)
        assertEquals(now, late.newestMs)
    }

    @Test
    fun `rates not sums - being played more does not raise an affinity`() {
        val s = Affinity.State()
        // Artist 1: finished 3 of 3. Artist 2: finished 30 of 60.
        repeat(3) { Affinity.apply(s, Family.ARTIST, 1L, 1.0, 1.0, 1.0, now) }
        repeat(60) { Affinity.apply(s, Family.ARTIST, 2L, if (it % 2 == 0) 1.0 else 0.0, 1.0, 1.0, now) }
        repeat(63) { Affinity.apply(s, Family.ALL, Family.ALL_KEY, if (it < 33) 1.0 else 0.0, 1.0, 1.0, now) }
        val p0 = Affinity.p0(s, now)
        assertTrue(Affinity.aff(s, Family.ARTIST, 1L, now, p0) > Affinity.aff(s, Family.ARTIST, 2L, now, p0))
        assertTrue(Affinity.share(s, Family.ARTIST, 2L, now) > Affinity.share(s, Family.ARTIST, 1L, now))
        assertTrue(Affinity.aff(s, Family.ARTIST, 1L, now, p0) in -3.0..3.0)
    }

    @Test
    fun `an event touches its track, artists, arm and the key all`() {
        val s = Affinity.State()
        val e = event(200_000, endReason = EndReason.TRACK_DONE, surface = Surface.AUTOPLAY, arm = Arm.RADIO, otherAids = longArrayOf(71L))
        Affinity.applyEvent(s, e, grade(e), e.startedAtMs)
        assertTrue(Affinity.isHeard(s, Family.TRACK, 7L))
        assertEquals(BrainMath.toQ(0.6), s.counter(Family.ARTIST, 70L)!!.posQ)
        assertEquals(BrainMath.toQ(0.3), s.counter(Family.ARTIST, 71L)!!.posQ) // half credit
        assertEquals(BrainMath.toQ(0.6), s.counter(Family.ARM, Arm.RADIO.toLong())!!.posQ)
        assertEquals(BrainMath.toQ(0.6), s.counter(Family.ALL, Family.ALL_KEY)!!.posQ)
        assertTrue(Math.abs(BrainMath.toQ(0.9) - s.sum(Family.ARTIST).posQ) <= 1L)
        assertEquals(1, s.completions[7L])
        val touched = HashSet<Pair<Int, Long>>()
        s.drainTouched { f, k -> touched.add(f to k) }
        assertTrue((Family.TRACK to 7L) in touched && (Family.COMPLETIONS to 7L) in touched && (Family.SUM_BASE + Family.ARTIST to 0L) in touched)
        // A never-audible play changes nothing.
        val silent = event(200_000, endReason = EndReason.TRACK_DONE, flags = EventFlags.NEVER_AUDIBLE, rid = 8L)
        Affinity.applyEvent(s, silent, grade(silent), silent.startedAtMs)
        assertFalse(Affinity.isHeard(s, Family.TRACK, 8L))
    }

    // ── Replay ───────────────────────────────────────────────────────────────

    private fun history(): List<PlayEvent> {
        val rng = BrainHash.Rng(5)
        val out = ArrayList<PlayEvent>()
        for (i in 0 until 200) {
            val played = if (rng.uniform() < 0.5) 200_000 else (rng.uniform() * 60_000).toInt()
            out.add(
                event(
                    played, endReason = if (played == 200_000) EndReason.TRACK_DONE else EndReason.NEXT,
                    rid = 1L + (rng.uniform() * 12).toLong(), aid = 50L + (rng.uniform() * 4).toLong(),
                    startedAtMs = now - 100 * day + i * 3_600_000L, sessionId = 1L + i / 10,
                    origin = if (i % 7 == 0) Origin.MIGRATED_HISTORY else Origin.LIVE,
                )
            )
        }
        return out.sortedBy { it.eventId }
    }

    private fun dump(s: Affinity.State) = GoldenGenerator.dump(s)

    @Test
    fun `replaying the log gives what the live path built, event by event`() {
        val events = history()
        val live = Affinity.State()
        val tracker = GradeTracker(live.completions)
        val session = SessionState()
        for (e in events) Brain.onEvent(live, tracker, session, e)
        val rebuilt = Replay.rebuild(events.iterator(), LibraryFacts.EMPTY, now)
        assertEquals(dump(live), dump(rebuilt))
        // And again: a rebuild is a pure function of the log.
        assertEquals(dump(rebuilt), dump(Replay.rebuild(events.iterator(), LibraryFacts.EMPTY, now)))
    }

    @Test
    fun `a clock that ran ahead cannot pin the counters in the future`() {
        val events = history() + event(200_000, endReason = EndReason.TRACK_DONE, rid = 3L, startedAtMs = now + 900 * day)
        val state = Replay.rebuild(events.iterator(), LibraryFacts.EMPTY, now)
        assertEquals(now, state.newestMs)
        assertEquals(now, state.counter(Family.TRACK, 3L)!!.lastMs)
        assertFalse(Affinity.clockJumpedBack(state, now))
        // The live path meets a state from the future: that is what triggers a rebuild.
        val future = Affinity.State()
        Affinity.apply(future, Family.TRACK, 3L, 1.0, 1.0, 1.0, now + 900 * day)
        assertTrue(Affinity.clockJumpedBack(future, now))
        // Until it is rebuilt, a late update stays finite and does not grow the state.
        val before = future.counter(Family.TRACK, 3L)!!.posQ
        Affinity.apply(future, Family.TRACK, 3L, 1.0, 1.0, 1.0, now)
        val after = future.counter(Family.TRACK, 3L)!!.posQ
        assertTrue(after >= before && after - before < 1_048_576L / 100)
    }

    @Test
    fun `facts become pseudo-counts, the largest range only, and removing a like removes its effect`() {
        val facts = LibraryFacts(
            liked = listOf(LibraryFacts.TrackFact(1L, 50L)),
            topArtists = listOf(
                LibraryFacts.TopArtist(50L, LibraryFacts.RANGE_SHORT, 5, 10),
                LibraryFacts.TopArtist(50L, LibraryFacts.RANGE_LONG, 0, 10),
            ),
            topTracks = listOf(LibraryFacts.TopTrack(2L, 51L, LibraryFacts.RANGE_MEDIUM, 0, 20)),
            followed = longArrayOf(52L, 52L),
            adjustments = listOf(LibraryFacts.Adjustment(Family.ARTIST, 53L, false, 3)),
        )
        val state = Replay.rebuild(emptyList<PlayEvent>().iterator(), facts, now)
        assertEquals(BrainMath.toQ(2.0), state.prior(Family.TRACK, 1L)!!.posQ)
        // short range at position 5 of 10: 6 * 1.0 * (1 - 0.35) = 3.9; long at 0: 6 * 0.6 = 3.6. The larger, plus the like's 0.5.
        assertEquals(BrainMath.toQ(6.0 * (1.0 - 0.7 * 5.0 / 10.0)) + BrainMath.toQ(0.5), state.prior(Family.ARTIST, 50L)!!.posQ)
        assertEquals(BrainMath.toQ(3.0 * 0.8), state.prior(Family.TRACK, 2L)!!.posQ)
        assertEquals(BrainMath.toQ(0.5), state.prior(Family.ARTIST, 51L)!!.posQ)
        assertEquals(BrainMath.toQ(2.0), state.prior(Family.ARTIST, 52L)!!.posQ)
        assertEquals(BrainMath.toQ(12.0), state.prior(Family.ARTIST, 53L)!!.negQ)
        assertTrue(Affinity.aff(state, Family.TRACK, 1L, now, 0.5) > 0.0)
        assertTrue(Affinity.aff(state, Family.ARTIST, 53L, now, 0.5) < -1.0)
        Replay.applyFacts(state, LibraryFacts.EMPTY)
        assertEquals(0.0, Affinity.aff(state, Family.TRACK, 1L, now, 0.5))
        assertNotEquals(null, state)
    }
}
