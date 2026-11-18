package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/** Daily Mix and Your Mix (6.2, 6.4, 8.2), the served-log budget (6.5), the snapshot codec (10.1). */
class MixPlannerTest {

    private val listener = SyntheticListeners.explorer()
    private val now = listener.nowMs + 3_600_000L
    private val affinity get() = Replay.rebuild(listener.events.iterator(), LibraryFacts.EMPTY, now)
    private val pool = listener.library + listener.novel

    private fun request(
        kind: Int = MixPlanner.KIND_DAILY,
        multiplier: Double = 1.0,
        excluded: Set<Long> = emptySet(),
        served: Map<Long, Int> = emptyMap(),
        regenerate: Int = 0,
        at: Long = now,
    ) = MixPlanner.Request(kind, pool, listener.seeds, at, 0, nuMultiplier = multiplier, salt = 99L, regenerate = regenerate,
        excludedRids = excluded, servedTimes = served)

    @Test
    fun `a daily mix has 30 tracks and your mix 60, each track once`() {
        val daily = MixPlanner.plan(request(), affinity)
        val your = MixPlanner.plan(request(MixPlanner.KIND_YOUR), affinity)
        assertEquals(30, daily.picks.size)
        assertEquals(60, your.picks.size)
        assertEquals(daily.picks.size, daily.picks.map { it.rid }.toSet().size)
        assertEquals(your.picks.size, your.picks.map { it.rid }.toSet().size)
    }

    @Test
    fun `the same day gives the same mix and regenerate gives another`() {
        val a = MixPlanner.plan(request(), affinity).picks.map { it.rid }
        val b = MixPlanner.plan(request(), affinity).picks.map { it.rid }
        assertEquals(a, b)
        val c = MixPlanner.plan(request(regenerate = 1), affinity).picks.map { it.rid }
        assertNotEquals(a, c)
        // The seed is the day's: the same instant gives the same one, two days later another.
        assertEquals(MixPlanner.tieSeedOf(request(at = now)), MixPlanner.tieSeedOf(request(at = now)))
        assertNotEquals(MixPlanner.tieSeedOf(request(at = now)), MixPlanner.tieSeedOf(request(at = now + 2 * Repetition.DAY_MS)))
    }

    @Test
    fun `nothing excluded is picked, and nothing played in the last four hours`() {
        val first = MixPlanner.plan(request(), affinity).picks.map { it.rid }.toSet()
        val second = MixPlanner.plan(request(excluded = first), affinity).picks.map { it.rid }.toSet()
        assertTrue(first.intersect(second).isEmpty())
        // The newest event of the history is minutes before [now]: its track cannot be picked.
        val last = listener.events.last()
        assertEquals(now - last.endedAtMs < StationPlanner.FOUR_HOURS_MS, true)
        assertFalse(MixPlanner.plan(request(), affinity).picks.any { it.rid == last.rid })
    }

    @Test
    fun `the switch sets the share of new music`() {
        assertEquals(0.10, MixPlanner.nuOf(MixPlanner.KIND_DAILY, 0.4), 1e-12)
        assertEquals(0.25, MixPlanner.nuOf(MixPlanner.KIND_DAILY, 1.0), 1e-12)
        assertEquals(0.50, MixPlanner.nuOf(MixPlanner.KIND_DAILY, 2.0), 1e-12)
        assertEquals(0.20, MixPlanner.nuOf(MixPlanner.KIND_YOUR, 1.0), 1e-12)
        val novel = listener.novel.map { it.rid }.toSet()
        fun share(m: Double) = MixPlanner.plan(request(multiplier = m), affinity).picks.let { p -> p.count { it.rid in novel }.toDouble() / p.size }
        val shares = listOf(0.4, 1.0, 2.0).map(::share)
        assertTrue(shares[0] < shares[1] && shares[1] < shares[2], shares.toString())
        for ((i, m) in listOf(0.4, 1.0, 2.0).withIndex()) assertEquals(MixPlanner.nuOf(MixPlanner.KIND_DAILY, m), shares[i], 0.05)
    }

    @Test
    fun `a track served often lately is picked less`() {
        val base = MixPlanner.plan(request(), affinity).picks.map { it.rid }
        val served = base.take(10).associateWith { 3 }
        val after = MixPlanner.plan(request(served = served), affinity).picks.map { it.rid }.toSet()
        // Not a ban, a penalty: but a third of ten full penalties must have moved some of them out.
        assertTrue(base.take(10).count { it in after } < 10)
    }

    @Test
    fun `the library is not asked to be close to a seed, and a mix carries no line under a plain library track`() {
        val plan = MixPlanner.plan(request(), affinity)
        val library = listener.library.map { it.rid }.toSet()
        val libraryPicks = plan.picks.filter { it.rid in library }
        assertTrue(libraryPicks.isNotEmpty())
        for (p in libraryPicks) assertTrue(p.whyKey == WhyLine.NONE || p.whyKey == WhyLine.ON_REPEAT, "library why ${p.whyKey}")
        for (p in plan.picks.filter { it.rid !in library }) assertTrue(p.novel)
    }

    @Test
    fun `persisted edges reach the graph`() {
        val a = pool[3]
        val b = pool[700]
        val edge = MixPlanner.Edge(a.rid, b.rid, 0.9)
        val plain = MixPlanner.plan(request(), affinity)
        val with = MixPlanner.plan(
            MixPlanner.Request(MixPlanner.KIND_DAILY, pool, listener.seeds, now, 0, salt = 99L, edges = listOf(edge)), affinity)
        assertEquals(plain.picks.size, with.picks.size)
    }

    // ── repetition ──

    @Test
    fun `the served penalty grows by a third per serve and stops at three`() {
        assertEquals(0.0, Repetition.servedPenalty(0), 0.0)
        assertEquals(Repetition.MAX_PENALTY / 3.0, Repetition.servedPenalty(1), 1e-12)
        assertEquals(Repetition.MAX_PENALTY, Repetition.servedPenalty(3), 1e-12)
        assertEquals(Repetition.MAX_PENALTY, Repetition.servedPenalty(9), 1e-12)
    }

    @Test
    fun `the day of a mix starts at four in the morning local time`() {
        val t = 1_760_000_000_000L // some instant
        val tz = 420
        val local = t + tz * 60_000L
        val fourAm = BrainMath.floorDiv(local, Repetition.DAY_MS) * Repetition.DAY_MS + 4L * 3_600_000L - tz * 60_000L
        assertEquals(Repetition.mixDay(fourAm, tz) , Repetition.mixDay(fourAm + 3_600_000L, tz))
        assertEquals(Repetition.mixDay(fourAm, tz) - 1, Repetition.mixDay(fourAm - 1, tz))
    }

    @Test
    fun `times served counts a list once per recording and only the last 30 days`() {
        val now = 10L * Repetition.DAY_MS * 10
        val lists = listOf(
            Repetition.Served(Surface.DAILY_MIX, now - Repetition.DAY_MS, longArrayOf(1, 2, 2, 3)),
            Repetition.Served(Surface.YOUR_MIX, now - 5 * Repetition.DAY_MS, longArrayOf(2, 4)),
            Repetition.Served(Surface.DAILY_MIX, now - 31 * Repetition.DAY_MS, longArrayOf(1, 1)),
            Repetition.Served(Surface.DAILY_MIX, now + Repetition.DAY_MS, longArrayOf(9)),
        )
        val times = Repetition.timesServed(lists, now)
        assertEquals(mapOf(1L to 1, 2L to 2, 3L to 1, 4L to 1), times)
    }

    @Test
    fun `a mix of the same day holds its recordings, a rebuilt list does not hold itself`() {
        val now = 5_000L * Repetition.DAY_MS + 9 * 3_600_000L
        val lists = listOf(
            Repetition.Served(Surface.DAILY_MIX, now - 2 * 3_600_000L, longArrayOf(1, 2, 3)),
            Repetition.Served(Surface.YOUR_MIX, now - 1 * 3_600_000L, longArrayOf(7, 8)),
            Repetition.Served(Surface.AUTOPLAY, now - 1 * 3_600_000L, longArrayOf(50)),
            Repetition.Served(Surface.DAILY_MIX, now - 30 * 3_600_000L, longArrayOf(99)),
        )
        assertEquals(setOf(1L, 2L, 3L, 7L, 8L), Repetition.takenOnDay(lists, now, 0))
        assertEquals(setOf(7L, 8L), Repetition.takenOnDay(lists, now, 0, Surface.DAILY_MIX))
    }

    @Test
    fun `distinct ratio and artist share`() {
        assertEquals(0.5, Repetition.distinctRatio(listOf(longArrayOf(1, 2), longArrayOf(1, 2))), 0.0)
        assertEquals(1.0, Repetition.distinctRatio(emptyList()), 0.0)
        assertEquals(0.5, Repetition.maxArtistShare(longArrayOf(1, 1, 2, 3)), 0.0)
    }

    // ── on repeat ──

    @Test
    fun `on repeat needs a recent rate and about three finished plays lately`() {
        fun why(aff: Double, mass: Double) = WhyLine.pick(Arm.FAV, ListBuilder.SLOT_FAMILIAR, false, 5L, 6L, 3, 0L, 0L, aff, mass).key
        assertEquals(WhyLine.ON_REPEAT, why(1.5, 3.0))
        assertNotEquals(WhyLine.ON_REPEAT, why(1.5, 1.0), "one finished play is not on repeat")
        assertNotEquals(WhyLine.ON_REPEAT, why(0.5, 3.0))
    }

    // ── snapshot ──

    @Test
    fun `a snapshot reads back to the state it wrote`() {
        val state = affinity
        val text = Snapshot.encode(state)
        val back = Snapshot.decode(text)
        assertEquals(text, Snapshot.encode(back))
        assertEquals(state.completions, back.completions)
        assertThrows(IllegalArgumentException::class.java) { Snapshot.decode("{\"format\":\"other\",\"rows\":[]}") }
    }
}
