package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Session reactions (5.5, 7.3), the graph (3.6, 5.3) and list building (6.1, 6.4, 6.5). */
class SessionPlannerTest {

    private val now = 1_760_000_000_000L
    private var clock = now
    private val affinity = Affinity.State()
    private val tracker = GradeTracker(affinity.completions)
    private val session = SessionState()

    /** Plays one track through the live step. [played] of a 200 s track; ended by "next" unless it finished. */
    private fun play(rid: Long, aid: Long, played: Int, engine: Boolean = false, flags: Int = 0, end: Int = -1): Reward.Graded {
        val stamp = Session.begin(session, clock, if (engine) StartReason.AUTOPLAY else StartReason.TAP)
        val e = PlayEvent(
            eventId = PlayEvent.mintId(clock, Origin.LIVE, "k$rid") { null },
            origin = Origin.LIVE, rid = rid, primaryAid = aid, keyAtWrite = "k$rid", startedAtMs = clock,
            playedMs = played, durationMs = 200_000,
            startReason = if (engine) StartReason.AUTOPLAY else StartReason.TAP,
            endReason = if (end >= 0) end else if (played >= 200_000) EndReason.TRACK_DONE else EndReason.NEXT,
            flags = flags, surface = if (engine) Surface.AUTOPLAY else Surface.PLAYLIST,
            arm = if (engine) Arm.RADIO else Arm.NONE, sessionId = stamp.sessionId,
            posInSession = stamp.posInSession, passiveRun = stamp.passiveRun,
        )
        clock = e.endedAtMs + 1_000
        return Brain.onEvent(affinity, tracker, session, e)
    }

    // ── Session ──────────────────────────────────────────────────────────────

    @Test
    fun `a hard skip blocks the artist for ten tracks, a second one for the session`() {
        play(1L, 10L, 200_000)
        play(2L, 20L, 3_000, engine = true)
        assertTrue(session.isBlocked(20L))
        assertEquals(1, session.skipRun)
        assertEquals(2L, session.recentNeg[0])
        assertEquals(1.0, session.recentNegH[0])
        // Ten tracks later the block is gone.
        repeat(10) { play(100L + it, 30L + it, 200_000) }
        assertFalse(session.isBlocked(20L))
        // A second hard skip of the same artist: the rest of the session.
        play(3L, 20L, 15_000, engine = true)
        assertTrue(session.isBlocked(20L))
        repeat(30) { play(200L + it, 60L + it, 200_000) }
        assertTrue(session.isBlocked(20L))
        assertEquals(2, session.skipsOf(20L))
    }

    @Test
    fun `a soft skip after thirty seconds blocks nobody and does not start a run`() {
        play(1L, 10L, 60_000)
        assertFalse(session.isBlocked(10L))
        assertEquals(0, session.skipRun)
        assertEquals(0.4, session.recentNegH[0])
    }

    @Test
    fun `two skips in a row ask for something familiar, three change direction`() {
        play(1L, 10L, 200_000)
        play(5L, 50L, 200_000)
        play(2L, 20L, 2_000, engine = true)
        assertFalse(session.familiarNext)
        play(3L, 30L, 2_000, engine = true)
        assertTrue(session.familiarNext)
        assertEquals(0, session.reseedCount)
        play(4L, 40L, 2_000, engine = true)
        assertEquals(1, session.reseedCount)
        assertEquals(0, session.skipRun)
        assertFalse(session.familiarNext)
        // The anchors are the two tracks the listener chose; a change of direction turns to the older one.
        assertEquals(listOf(1L, 5L), Session.seedOrder(session).toList())
        // A completion ends the run.
        play(2L, 20L, 2_000, engine = true)
        play(6L, 60L, 200_000)
        assertEquals(0, session.skipRun)
    }

    @Test
    fun `only the listener's own choices are anchors, plus engine picks they liked`() {
        play(1L, 10L, 200_000) // chosen and heard
        play(2L, 20L, 200_000, engine = true) // merely played through
        play(3L, 30L, 5_000) // chosen, skipped
        play(4L, 40L, 120_000) // chosen, r >= 0.5
        assertEquals(listOf(4L, 1L), session.anchors.take(session.anchorCount))
        play(5L, 50L, 200_000, engine = true, flags = EventFlags.LIKED_DURING)
        assertEquals(listOf(5L, 4L, 1L), session.anchors.take(session.anchorCount))
        // An hour of untouched autoplay adds nothing.
        repeat(20) { play(300L + it, 70L + it, 200_000, engine = true) }
        assertEquals(listOf(5L, 4L, 1L), session.anchors.take(session.anchorCount))
    }

    @Test
    fun `a like counts twice, schedules a follow-up and raises the appetite for new music`() {
        val nu = session.nuSession
        play(7L, 70L, 200_000, engine = true, flags = EventFlags.LIKED_DURING)
        assertEquals(2, session.posCount)
        assertEquals(7L, session.likedRid)
        assertEquals(Session.LIKED_WITHIN, session.likedWithin)
        assertEquals(nu * 1.25, session.nuSession)
        play(8L, 80L, 1_000, engine = true)
        assertEquals(BrainMath.max(Session.NU_FLOOR, nu * 1.25 * 0.67), session.nuSession)
    }

    @Test
    fun `a station's own seed artist is thinned out, never banned`() {
        session.seed = 1L
        session.seedAid = 10L
        session.seedKind = Session.SEED_STATION_ARTIST
        play(2L, 10L, 2_000, engine = true)
        assertFalse(session.isBlocked(10L))
        assertEquals(1, session.seedLimit)
        play(3L, 10L, 2_000, engine = true)
        assertEquals(2, session.seedLimit)
        repeat(10) { play(400L + it, 90L + it, 200_000) }
        assertEquals(0, session.seedLimit)
        assertFalse(session.isBlocked(10L))
    }

    @Test
    fun `a play that proves nothing changes nothing but the countdown`() {
        play(2L, 20L, 3_000, engine = true)
        val before = session.encode()
        // Stopped after 4 seconds: c = 0.
        val g = play(9L, 90L, 4_000, end = EndReason.STOPPED)
        assertEquals(0.0, g.c)
        assertEquals(1, session.skipRun)
        assertEquals(0, session.posCount)
        assertTrue(before != session.encode()) // the block counted down, the clock moved
        assertEquals(9, session.blockLeft[0])
    }

    @Test
    fun `sessions expire after thirty minutes and plays are stamped in order`() {
        val a = Session.begin(session, now, StartReason.TAP)
        val b = Session.begin(session, now + 1_000, StartReason.AUTO_ADVANCE)
        val c = Session.begin(session, now + 2_000, StartReason.AUTOPLAY)
        val d = Session.begin(session, now + 3_000, StartReason.NEXT)
        assertEquals(listOf(0, 1, 2, 3), listOf(a, b, c, d).map { it.posInSession })
        assertEquals(listOf(0, 1, 2, 0), listOf(a, b, c, d).map { it.passiveRun })
        assertEquals(now, d.sessionId)
        assertFalse(Session.isExpired(session, now + 3_000 + Session.GAP_MS))
        assertTrue(Session.isExpired(session, now + 3_001 + Session.GAP_MS))
        assertTrue(Session.isExpired(SessionState(), now))
    }

    // ── Graph and similarity ─────────────────────────────────────────────────

    @Test
    fun `the stage 1 similarity scale`() {
        val rows = LongArray(50) { 100L + it }
        val g = Graph.Builder().addList(1L, rows, Graph.RADIO).addTrack(1L, 10L).addTrack(100L, 11L).addTrack(900L, 10L).build()
        assertEquals(1.0, Similarity.sim(g, 5L, 5L))
        assertEquals(0.8, Similarity.sim(g, 1L, 900L)) // same primary artist
        assertEquals(1.0 / BrainMath.log2(3.0), Similarity.sim(g, 1L, 100L)) // 0.63 at rank 1
        assertEquals(1.0 / BrainMath.log2(10.0), Similarity.sim(g, 107L, 1L)) // 0.30 at rank 8
        assertEquals(0.25, Similarity.sim(g, 100L, 101L)) // two rows of one radio: at most 0.25
        assertEquals(0.0, Similarity.sim(g, 100L, 777L))
        assertTrue(Similarity.sim(g, 1L, 149L) < 0.18)
    }

    @Test
    fun `a node keeps its 64 heaviest edges and the largest weight of a pair`() {
        val g = Graph.Builder()
            .addList(1L, LongArray(100) { 100L + it }, Graph.RADIO)
            .addList(1L, longArrayOf(199L), Graph.RELATED) // the last radio row is also Related rank 1
            .build()
        assertEquals(64, g.neighboursOf(1L).size)
        assertEquals(0.8 / BrainMath.log2(3.0), g.edgeWeight(1L, 199L))
        // Row 80 is not among the seed's 64 heaviest, but the row itself still keeps its only edge.
        assertTrue(g.edgeWeight(1L, 180L) > 0.0)
        assertEquals(g.edgeWeight(1L, 180L), g.edgeWeight(180L, 1L))
    }

    // ── Lists ────────────────────────────────────────────────────────────────

    private fun candidate(rid: Long, aid: Long, library: Boolean = false, album: Long = 0L) =
        Candidate(rid = rid, aid = aid, albumKey = album, inLibrary = library)

    private fun radio(seedRid: Long, seedAid: Long, vararg rids: Long) = Retrieval.SeedList(seedRid, seedAid, rids)

    private fun plan(
        candidates: List<Candidate>,
        seeds: List<Retrieval.SeedList>,
        count: Int = 5,
        prior: List<ListBuilder.Prior> = emptyList(),
        bridge: Boolean = false,
        mode: Int = StationPlanner.MODE_AUTOPLAY,
        nu: Double = 1.0,
    ): StationPlanner.Plan {
        val graph = StationPlanner.buildGraph(seeds, candidates, session)
        return StationPlanner.plan(
            StationPlanner.Request(mode, count, candidates, seeds, prior, clock, 0, nu, tieSeed = 1L, bridge = bridge),
            affinity, session, graph,
        )
    }

    @Test
    fun `with an empty state the picks are the radio's own order`() {
        // 30 rows by 30 different artists: nothing to space out, nothing known about any of them.
        val rids = LongArray(30) { 1000L + it }
        val candidates = rids.mapIndexed { i, rid -> candidate(rid, 500L + i) }
        val picks = plan(candidates, listOf(radio(1L, 10L, *rids)), count = 12).picks
        assertEquals(rids.take(12), picks.map { it.rid })
        assertTrue(picks.all { it.arm == Arm.RADIO && it.whyKey == WhyLine.RADIO_FANS && it.whyArg == 10L })
        assertEquals((1..12).toList(), picks.map { it.radioRank })
        assertEquals(1000, picks[0].retrQ)
    }

    @Test
    fun `hard constraints - no recording twice, artist and album spacing`() {
        // Every row by one of two artists; the radio order would put them back to back.
        val rids = LongArray(24) { 2000L + it }
        val candidates = rids.mapIndexed { i, rid -> candidate(rid, if (i % 2 == 0) 600L else 601L) }
        val picks = plan(candidates, listOf(radio(1L, 10L, *rids)), count = 12).picks
        // Two artists, at most 2 per 20 each, never within 4 slots: 4 picks at most, then the list stops.
        assertEquals(2, picks.size)
        val wide = rids.mapIndexed { i, rid -> candidate(rid, 700L + (i % 6), album = if (i < 6) 9L else 0L) }
        val out = plan(wide, listOf(radio(1L, 10L, *rids)), count = 12).picks
        assertEquals(out.map { it.rid }.toSet().size, out.size)
        val byRid = wide.associateBy { it.rid }
        for (i in out.indices) for (j in i + 1 until out.size) {
            val a = byRid[out[i].rid]!!
            val b = byRid[out[j].rid]!!
            if (a.aid == b.aid) assertTrue(j - i > ListBuilder.ARTIST_GAP, "artist ${a.aid} at $i and $j")
            if (a.albumKey != 0L && a.albumKey == b.albumKey) assertTrue(j - i > ListBuilder.ALBUM_GAP, "album at $i and $j")
        }
        for (aid in 700L..705L) assertTrue(out.count { byRid[it.rid]!!.aid == aid } <= 2)
    }

    @Test
    fun `the slot plan - two familiar openers, then one novel in four at a quarter`() {
        fun scored(rid: Long, aid: Long, novel: Boolean, z: Double) = ListBuilder.Scored(
            c = candidate(rid, aid, library = !novel), arm = if (novel) Arm.RADIO else Arm.FAV, z = z, p = BrainMath.sigmoid(z),
            novel = novel, bestRank = 0, bestSeed = -1, newestSeedRank = 0, lastMs = 0L, retrQ = 0,
        )
        val all = (0 until 40).map { scored(3000L + it, 800L + it, false, 1.0 - it * 0.01) } +
            (0 until 40).map { scored(4000L + it, 900L + it, true, 2.0 - it * 0.01) }
        fun pattern(nu: Double, count: Int, finite: Boolean = false): String =
            ListBuilder.build(ListBuilder.Request(count, emptyList(), nu, 1L, now, 0, finite = finite), all, Graph.EMPTY)
                .joinToString("") { if (it.scored.novel) "N" else "F" }
        assertEquals("FFNFFFNFFFNFFFN", pattern(0.25, 15))
        assertEquals("FFNFNFNFNFNFNF", pattern(0.5, 14))
        assertEquals("FFFFFFFF", pattern(0.0, 8))
        // A finite list ends on a familiar track; an all-novel one cannot, so the novel takes the slot.
        assertEquals("FFNFNF", pattern(0.5, 6, finite = true))
        assertEquals("FFNFF", pattern(0.5, 5, finite = true))
        // Within a kind the best value wins.
        val first = ListBuilder.build(ListBuilder.Request(3, emptyList(), 0.25, 1L, now, 0), all, Graph.EMPTY)
        assertEquals(listOf(3000L, 3001L, 4000L), first.map { it.scored.c.rid })
        assertEquals(ListBuilder.SLOT_OPENER, first[0].kind)
    }

    @Test
    fun `on autoplay a library track far from every anchor is not a candidate`() {
        // Rows 1-8 of a radio are within 0.3 of the seed; row 20 is not.
        val rids = LongArray(20) { 3500L + it }
        val candidates = rids.mapIndexed { i, rid -> candidate(rid, 850L + i, library = true) }
        val p = plan(candidates, listOf(radio(1L, 10L, *rids)), count = 20)
        assertEquals(8, p.poolSize)
        assertTrue(p.picks.all { it.rid < 3508L && (it.arm == Arm.DEEP) })
    }

    @Test
    fun `a skipped artist leaves the next plan, and candidates like the skip sink`() {
        val rids = LongArray(20) { 5000L + it }
        val candidates = rids.mapIndexed { i, rid -> candidate(rid, 1000L + i % 10) }
        val seeds = listOf(radio(1L, 10L, *rids))
        play(1L, 10L, 200_000)
        val before = plan(candidates, seeds).picks.map { it.rid }
        assertEquals(5000L, before[0])
        play(5000L, 1000L, 2_000, engine = true)
        val after = plan(candidates, seeds, count = 8).picks.map { it.rid }
        assertFalse(5010L in after, "the same artist's other track must be gone")
        assertFalse(5000L in after, "played in the last four hours")
    }

    @Test
    fun `after two skips in a row the next pick is a familiar track by the artist last finished`() {
        val library = listOf(candidate(6000L, 10L, library = true), candidate(6001L, 77L, library = true))
        val rows = (0 until 20).map { candidate(6100L + it, 1100L + it) }
        val seeds = listOf(radio(1L, 10L, *(rows.map { it.rid } + 6001L).toLongArray()))
        play(1L, 10L, 200_000)
        play(6100L, 1100L, 2_000, engine = true)
        play(6101L, 1101L, 2_000, engine = true)
        assertTrue(session.familiarNext)
        val p = plan(library + rows, seeds)
        assertEquals(6000L, p.picks[0].rid)
        assertEquals(ListBuilder.SLOT_BRIDGE, p.picks[0].slotKind)
        assertTrue(p.usedFamiliarNext)
        StationPlanner.consume(session, p)
        assertFalse(session.familiarNext)
    }

    @Test
    fun `the bridge - a familiar track by an artist just heard, else the newest anchor's best row`() {
        val rows = (0 until 20).map { candidate(7100L + it, 1200L + it) }
        val seeds = listOf(radio(1L, 10L, *rows.map { it.rid }.toLongArray()))
        play(1L, 10L, 200_000)
        assertEquals(7100L, plan(rows, seeds, bridge = true).picks[0].rid)
        val withLibrary = rows + candidate(7000L, 10L, library = true)
        val first = plan(withLibrary, seeds, bridge = true).picks[0]
        assertEquals(7000L, first.rid)
        assertEquals(WhyLine.ARTIST_MORE, first.whyKey)
    }

    @Test
    fun `a like puts one track by that artist into the next batch with its reason`() {
        val rows = (0 until 20).map { candidate(8100L + it, if (it == 14) 55L else 1300L + it) }
        val seeds = listOf(radio(1L, 10L, *rows.map { it.rid }.toLongArray()))
        play(1L, 10L, 200_000)
        play(8000L, 55L, 200_000, engine = true, flags = EventFlags.LIKED_DURING)
        val p = plan(rows, seeds, prior = listOf(ListBuilder.Prior(8000L, 55L, 0L, true), ListBuilder.Prior(1L, 10L, 0L, false),
            ListBuilder.Prior(2L, 11L, 0L, false), ListBuilder.Prior(3L, 12L, 0L, false), ListBuilder.Prior(4L, 13L, 0L, false)))
        assertEquals(8114L, p.picks[0].rid)
        assertEquals(WhyLine.BECAUSE_LIKED, p.picks[0].whyKey)
        assertEquals(8000L, p.picks[0].whyArg)
        assertTrue(p.usedLiked)
    }

    @Test
    fun `patching the tail returns null when nothing changes and a new tail when it does`() {
        val rids = LongArray(20) { 9000L + it }
        val candidates = rids.mapIndexed { i, rid -> candidate(rid, 1400L + i % 10) }
        val seeds = listOf(radio(1L, 10L, *rids))
        play(1L, 10L, 200_000)
        val first = plan(candidates, seeds, count = 5)
        val tail = first.picks.map { it.rid }.toLongArray()
        fun patch(): StationPlanner.Plan? {
            val graph = StationPlanner.buildGraph(seeds, candidates, session)
            val request = StationPlanner.Request(StationPlanner.MODE_AUTOPLAY, tail.size, candidates, seeds, nowMs = clock, tieSeed = 1L)
            return Session.patchQueue(session, tail, request, affinity, graph)
        }
        assertNull(patch())
        // Skip a track by the artist of the tail's second pick: that pick has to go.
        val victim = candidates.first { it.rid == tail[1] }
        play(9999L, victim.aid, 2_000, engine = true)
        val patched = patch()
        assertNotNull(patched)
        assertFalse(patched!!.picks.any { it.rid == tail[1] })
        assertEquals(tail.size, patched.picks.size)
    }

    @Test
    fun `a home row holds only music the listener does not have, with its reason`() {
        val rows = (0 until 20).map { candidate(9500L + it, 1500L + it, library = it % 4 == 0) }
        val seeds = listOf(radio(42L, 10L, *rows.map { it.rid }.toLongArray()))
        val p = plan(rows, seeds, count = 12, mode = StationPlanner.MODE_HOME_ROW)
        assertEquals(12, p.picks.size)
        assertTrue(p.picks.all { it.novel && it.whyKey == WhyLine.BECAUSE_LISTENED && it.whyArg == 42L })
    }

    @Test
    fun `the listener's boost outranks the radio order`() {
        val rids = LongArray(10) { 9700L + it }
        val candidates = rids.mapIndexed { i, rid ->
            Candidate(rid = rid, aid = 1600L + i, boostLn = if (i == 7) BrainMath.ln(2.2) else 0.0)
        }
        val picks = plan(candidates, listOf(radio(1L, 10L, *rids)), count = 3).picks
        assertEquals(9707L, picks[0].rid)
    }

    @Test
    fun `cooc adds across seeds and rewards agreement between artists`() {
        val a = radio(1L, 10L, 100L, 101L, 102L)
        val b = radio(2L, 20L, 102L, 103L)
        val c = radio(3L, 10L, 101L)
        val table = Retrieval.cooc(listOf(a, b, c), affinity, now, 0.5)
        assertEquals(1.0 / 11.0, table[100L]!!.cooc)
        assertEquals((1.0 / 13.0 + 1.0 / 11.0) * 1.25, table[102L]!!.cooc) // two seeds by different artists
        assertEquals(1.0 / 12.0 + 1.0 / 11.0, table[101L]!!.cooc) // two seeds by the same artist
        assertEquals(1, table[102L]!!.bestRank)
        assertEquals(1, table[102L]!!.bestSeed)
        val pct = Retrieval.percentiles(intArrayOf(Arm.RADIO, Arm.RADIO, Arm.RADIO, Arm.FAV), doubleArrayOf(0.3, 0.1, 0.2, 0.0))
        assertEquals(listOf(1.0, 0.0, 0.5, 0.0), pct.toList())
    }
}
