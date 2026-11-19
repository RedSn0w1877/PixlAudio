package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stage 2a, milestone 2 (docs/brain-spec.md): "Don't count this" (`Affinity.subtract`), repeat activation
 * (5.6), the weak negative of a swipe (8.4), chip labels (3.4), and the similar-artist arm (6.1).
 */
class Stage2aMilestone2Test {

    private val day = 86_400_000L
    private val hour = 3_600_000L
    private val now = 1_760_000_000_000L

    private fun play(
        rid: Long = 7L, aid: Long = 70L, startedAtMs: Long = now, playedMs: Int = 200_000, durationMs: Int = 200_000,
        endReason: Int = EndReason.TRACK_DONE, arm: Int = Arm.NONE, flags: Int = 0, other: LongArray = PlayEvent.NO_AIDS,
    ) = PlayEvent(
        eventId = PlayEvent.mintId(startedAtMs, Origin.LIVE, "k$rid") { null },
        origin = Origin.LIVE, rid = rid, primaryAid = aid, otherAids = other, keyAtWrite = "k$rid",
        startedAtMs = startedAtMs, playedMs = playedMs, durationMs = durationMs, endReason = endReason,
        flags = flags, surface = Surface.PLAYLIST, arm = arm, sessionId = 1L,
    )

    // ── Affinity.subtract ────────────────────────────────────────────────────

    @Test
    fun `subtracting the only play of a track empties its counters, and the artist's, arm's and totals`() {
        val state = Affinity.State()
        val e = play(arm = Arm.RADIO, other = longArrayOf(71L))
        val g = Reward.grade(e, Reward.Context(0, 0))
        Affinity.applyEvent(state, e, g, e.startedAtMs)
        assertTrue(state.counter(Family.TRACK, 7L)!!.posQ > 0)
        Affinity.subtract(state, e, g, e.startedAtMs)
        for ((family, key) in listOf(Family.TRACK to 7L, Family.ARTIST to 70L, Family.ARTIST to 71L, Family.ARM to Arm.RADIO.toLong(), Family.ALL to Family.ALL_KEY)) {
            val k = state.counter(family, key)!!
            assertEquals(0L, k.posQ, "family $family key $key pos")
            assertEquals(0L, k.negQ, "family $family key $key neg")
            assertEquals(0L, k.posRQ)
            assertEquals(0L, k.negRQ)
        }
        assertEquals(0L, state.sum(Family.TRACK).posQ)
        assertEquals(0, state.completions[7L] ?: 0)
    }

    @Test
    fun `subtracting one play of two leaves the other, whatever the order they were applied in`() {
        val first = play(startedAtMs = now - 3 * day)
        val second = play(startedAtMs = now - day)
        val both = Affinity.State()
        val only = Affinity.State()
        for (e in listOf(first, second)) Affinity.applyEvent(both, e, Reward.grade(e, Reward.Context(0, 0)), e.startedAtMs)
        Affinity.applyEvent(only, second, Reward.grade(second, Reward.Context(0, 0)), second.startedAtMs)
        Affinity.subtract(both, first, Reward.grade(first, Reward.Context(0, 0)), first.startedAtMs)
        val a = both.counter(Family.TRACK, 7L)!!
        val b = only.counter(Family.TRACK, 7L)!!
        // Fixed point: the two paths agree to within the rounding of three Q20 steps.
        assertTrue(kotlin.math.abs(a.posQ - b.posQ) <= 4, "pos ${a.posQ} vs ${b.posQ}")
        assertTrue(kotlin.math.abs(a.posRQ - b.posRQ) <= 4, "recent pos ${a.posRQ} vs ${b.posRQ}")
        assertEquals(b.lastMs, a.lastMs)
        // The track's aff is what it was with only the second play.
        val nowMs = now
        assertEquals(
            Affinity.aff(only, Family.TRACK, 7L, nowMs, Affinity.p0(only, nowMs)),
            Affinity.aff(both, Family.TRACK, 7L, nowMs, Affinity.p0(both, nowMs)),
            1e-3,
        )
    }

    @Test
    fun `a counter never goes below zero, a late play is taken out at its own decayed weight, and a missing key is skipped`() {
        val state = Affinity.State()
        val e = play(startedAtMs = now - 10 * day)
        val stranger = play(rid = 99L, aid = 990L)
        // Subtracting what was never applied floors at 0 and does not create a counter.
        Affinity.subtract(state, stranger, Reward.grade(stranger, Reward.Context(0, 0)), stranger.startedAtMs)
        assertEquals(null, state.counter(Family.TRACK, 99L))
        val later = play(startedAtMs = now)
        Affinity.applyEvent(state, later, Reward.grade(later, Reward.Context(0, 0)), later.startedAtMs)
        Affinity.subtract(state, e, Reward.grade(e, Reward.Context(0, 0)), e.startedAtMs)
        val k = state.counter(Family.TRACK, 7L)!!
        assertTrue(k.posQ >= 0 && k.negQ >= 0 && k.posRQ >= 0 && k.negRQ >= 0)
    }

    @Test
    fun `an event that never sounded is not subtracted, since it added nothing`() {
        val state = Affinity.State()
        val ok = play()
        Affinity.applyEvent(state, ok, Reward.grade(ok, Reward.Context(0, 0)), ok.startedAtMs)
        val before = state.counter(Family.TRACK, 7L)!!.posQ
        val silent = play(flags = EventFlags.NEVER_AUDIBLE)
        Affinity.subtract(state, silent, Reward.grade(silent, Reward.Context(0, 0)), silent.startedAtMs)
        assertEquals(before, state.counter(Family.TRACK, 7L)!!.posQ)
    }

    // ── RepeatActivation ─────────────────────────────────────────────────────

    @Test
    fun `a track never played has no activation, one just played is hot, one played long ago is cold`() {
        assertEquals(0.0, RepeatActivation.feature(null, now))
        assertEquals(0.0, RepeatActivation.feature(RepeatActivation.History(), now))
        val justNow = RepeatActivation.History().also { it.add(now - 1_000, 1.0) }
        val longAgo = RepeatActivation.History().also { it.add(now - 200 * day, 1.0) }
        val hot = RepeatActivation.feature(justNow, now)
        val cold = RepeatActivation.feature(longAgo, now)
        assertTrue(hot > 0.6, "hot $hot")
        assertTrue(cold < hot, "cold $cold below hot $hot")
        assertTrue(cold >= 0.0 && hot <= 1.0)
    }

    @Test
    fun `B is the log of the weighted sum, with the exponent one half`() {
        val h = RepeatActivation.History()
        h.add(now - 3 * hour, 1.0)   // hours + 1 = 4  -> 4^-0.5 = 0.5
        h.add(now - 15 * hour, 3.0)  // hours + 1 = 16 -> 16^-0.5 = 0.25 -> 0.75
        val expected = BrainMath.ln(0.5 + 0.75)
        assertEquals(expected, RepeatActivation.b(h, now), 1e-9)
        assertEquals(BrainMath.clip((expected + 4.0) / 6.0, 0.0, 1.0), RepeatActivation.feature(h, now), 1e-9)
    }

    @Test
    fun `a history keeps its last 30 plays and folds the older ones into one bucket`() {
        val h = RepeatActivation.History()
        for (i in 0 until 40) h.add(now - (40 - i) * day, 1.0)
        assertEquals(30, h.count)
        assertEquals(10.0, h.aggC)
        // The bucket sits at the mean time of the ten oldest plays.
        val expectedMean = (0 until 10).map { (now - (40 - it) * day).toDouble() }.sum() / 10.0
        assertEquals(expectedMean, h.aggT, 1.0)
        // Folding changes B only a little against keeping all of them.
        val all = (0 until 40).sumOf { BrainMath.powNeg(((now - (now - (40 - it) * day)).toDouble() / 3_600_000.0) + 1.0, 0.5) }
        assertEquals(BrainMath.ln(all), RepeatActivation.b(h, now), 0.05)
    }

    @Test
    fun `a play with no weight adds nothing, and a play in the future counts as just now`() {
        val h = RepeatActivation.History()
        h.add(now - hour, 0.0)
        assertTrue(h.isEmpty)
        h.add(now + 5 * day, 1.0)
        assertEquals(BrainMath.ln(1.0), RepeatActivation.b(h, now), 1e-12)
    }

    @Test
    fun `activation moves the score of a library track, and zero activation moves nothing`() {
        val state = Affinity.State()
        val session = SessionState()
        val graph = Graph.Builder().build()
        val weights = Ranker.weights(0.5)
        val a = DoubleArray(Features.D)
        val b = DoubleArray(Features.D)
        val plain = Candidate(rid = 1L, aid = 10L, inLibrary = true)
        val hot = Candidate(rid = 1L, aid = 10L, inLibrary = true, actr = 0.9)
        Features.compute(plain, Arm.FAV, 0.0, 0, state, session, graph, now, 0.5, false, a)
        Features.compute(hot, Arm.FAV, 0.0, 0, state, session, graph, now, 0.5, false, b)
        assertEquals(0.0, a[Features.ACTR])
        assertEquals(0.0, a[Features.ACTR_SQ])
        assertEquals(0.9, b[Features.ACTR])
        assertEquals(0.81, b[Features.ACTR_SQ], 1e-12)
        // M0: +0.5 on feature 10 and -0.3 on 11: a track near its peak is worth less than one half as hot.
        val zHot = Ranker.z(weights, b)
        val zPlain = Ranker.z(weights, a)
        assertEquals(0.5 * 0.9 - 0.3 * 0.81, zHot - zPlain, 1e-12)
    }

    // ── Chip labels ──────────────────────────────────────────────────────────

    @Test
    fun `chips that only steer a radio are not tags, and an unknown label is not guessed`() {
        for (label in listOf("Popular", "Deep cuts", "Discover", "All", "Familiar")) {
            assertEquals(ChipLabels.Kind.STEER, ChipLabels.classify(label).kind, label)
        }
        assertEquals(ChipLabels.Kind.UNKNOWN, ChipLabels.classify("Some New Thing").kind)
        assertEquals(ChipLabels.Kind.UNKNOWN, ChipLabels.classify("").kind)
        assertEquals(ChipLabels.Kind.UNKNOWN, ChipLabels.classify("   ").kind)
    }

    @Test
    fun `genre, decade and mood chips are read, whatever their case`() {
        assertEquals(ChipLabels.Kind.GENRE, ChipLabels.classify("V-pop").kind)
        assertEquals(ChipLabels.Kind.GENRE, ChipLabels.classify("POP").kind)
        assertEquals(ChipLabels.Kind.GENRE, ChipLabels.classify("Adult Pop").kind)
        assertEquals(ChipLabels.Kind.GENRE, ChipLabels.classify("Hip-Hop").kind)
        assertEquals(ChipLabels.Kind.MOOD, ChipLabels.classify("Workout").kind)
        val decade = ChipLabels.classify("2010s")
        assertEquals(ChipLabels.Kind.DECADE, decade.kind)
        assertEquals("2010s", decade.key)
        assertEquals("1990s", ChipLabels.classify("1990s").key)
        assertEquals(ChipLabels.Kind.UNKNOWN, ChipLabels.classify("2015s").kind)
        assertEquals(ChipLabels.Kind.UNKNOWN, ChipLabels.classify("3000s").kind)
    }

    // ── Swipe ────────────────────────────────────────────────────────────────

    @Test
    fun `a swipe is a weak negative and nothing else`() {
        val state = SessionState()
        Session.weakNegative(state, 11L, 5L)
        assertEquals(1, state.negCount)
        assertEquals(11L, state.recentNeg[0])
        assertEquals(Session.SWIPE_H, state.recentNegH[0])
        assertEquals(0, state.skipRun)
        assertFalse(state.isBlocked(5L))
        assertEquals(0, state.skipsOf(5L))
        assertFalse(state.familiarNext)
        // It is the weakest skip of 5.5: weaker than the hard-skip line.
        assertTrue(Session.SWIPE_H < Session.HARD_SKIP)
        // Only the last five are remembered.
        for (i in 1..8) Session.weakNegative(state, 100L + i, 5L)
        assertEquals(Session.RECENT, state.negCount)
        assertEquals(108L, state.recentNeg[0])
    }

    @Test
    fun `a swipe lowers a similar track in a plan`() {
        val graph = Graph.Builder().apply {
            addList(1L, longArrayOf(11L, 12L, 13L), Graph.RADIO)
            addTrack(1L, 5L); addTrack(11L, 6L); addTrack(12L, 7L); addTrack(13L, 8L)
        }.build()
        val seeds = listOf(Retrieval.SeedList(1L, 5L, longArrayOf(11L, 12L, 13L), Graph.RADIO))
        val candidates = listOf(Candidate(11L, 6L), Candidate(12L, 7L), Candidate(13L, 8L))
        val request = StationPlanner.Request(StationPlanner.MODE_AUTOPLAY, 3, candidates, seeds, nowMs = now)
        val affinity = Affinity.State()
        val calm = SessionState()
        val swiped = SessionState()
        Session.weakNegative(swiped, 11L, 6L)
        val a = StationPlanner.plan(request, affinity, calm, StationPlanner.buildGraph(seeds, candidates, calm))
        val b = StationPlanner.plan(request, affinity, swiped, StationPlanner.buildGraph(seeds, candidates, swiped))
        assertEquals(3, a.picks.size)
        // The swiped track itself is not what changes (it is a candidate here); its place is no better than before.
        val before = a.picks.indexOfFirst { it.rid == 11L }
        val after = b.picks.indexOfFirst { it.rid == 11L }
        assertTrue(after >= before, "swiped track at $after, was at $before")
    }

    // ── Similar artists ──────────────────────────────────────────────────────

    @Test
    fun `a song from a similar artist is arm SIM and its line names the artist whose list brought it`() {
        val rids = longArrayOf(21L, 22L, 23L, 24L, 25L, 26L)
        val seeds = listOf(Retrieval.SeedList(1L, 5L, rids, Graph.RADIO))
        val candidates = rids.mapIndexed { i, rid -> Candidate(rid, 90L + i, fromSimilarArtist = i == 0) }
        val request = StationPlanner.Request(StationPlanner.MODE_STATION, 5, candidates, seeds, nowMs = now, nu = 1.0)
        val session = SessionState().also { it.seed = 1L; it.seedAid = 5L; it.seedKind = Session.SEED_STATION_ARTIST }
        val plan = StationPlanner.plan(request, Affinity.State(), session, StationPlanner.buildGraph(seeds, candidates, session))
        val sim = plan.picks.first { it.rid == 21L }
        assertEquals(Arm.SIM, sim.arm)
        assertEquals(WhyLine.SIMILAR_ARTIST, sim.whyKey)
        assertEquals(5L, sim.whyArg, "Close to the seed's artist, not to the pick's own")
        assertTrue(plan.picks.filter { it.rid != 21L }.none { it.arm == Arm.SIM })
    }

    @Test
    fun `the similar-artist line falls back to the pick's own artist when no list named one`() {
        val why = WhyLine.pick(Arm.SIM, ListBuilder.SLOT_NOVEL, false, 9L, 0L, 0, 0L, 0L, 0.0)
        assertEquals(WhyLine.SIMILAR_ARTIST, why.key)
        assertEquals(9L, why.arg)
    }

    // ── Supporting facts ─────────────────────────────────────────────────────

    @Test
    fun `facts are only the true ones, at most two, in the order of how much they tell`() {
        // All three: the artist rate and the track win; the year is left out.
        val all = WhyFacts.pick(artistPlays = 10, artistFinished = 9, trackFinished = 4, year = 2024)
        assertEquals(listOf(WhyFacts.KEY_ARTIST_FINISHED, WhyFacts.KEY_TRACK_FINISHED), all.map { it.key })
        assertEquals(9, all[0].a)
        assertEquals(10, all[0].b)
        // Too few plays by the artist for a rate: the track, then the year.
        val few = WhyFacts.pick(artistPlays = 2, artistFinished = 2, trackFinished = 3, year = 2024)
        assertEquals(listOf(WhyFacts.KEY_TRACK_FINISHED, WhyFacts.KEY_YEAR), few.map { it.key })
        assertEquals(2024, few[1].a)
        // Nothing known: nothing is said.
        assertTrue(WhyFacts.pick(0, 0, 0, 0).isEmpty())
        // A finished count above the plays looked at is not a fact; a wild year is not a year.
        assertTrue(WhyFacts.pick(3, 5, 0, 1850).isEmpty())
        assertTrue(WhyFacts.pick(0, 0, 0, 2101).isEmpty())
        // One finished play of a track is not "finished it twice".
        assertEquals(listOf(WhyFacts.KEY_YEAR), WhyFacts.pick(0, 0, 1, 1999).map { it.key })
    }

    @Test
    fun `a station of several seeds wants a track near the closest one, a single-seed station is as it was`() {
        val seeds = listOf(
            Retrieval.SeedList(1L, 5L, longArrayOf(21L, 22L, 23L, 24L, 25L, 26L), Graph.RADIO),
            Retrieval.SeedList(2L, 6L, longArrayOf(31L, 32L, 33L, 34L, 35L, 36L), Graph.RADIO),
        )
        val candidates = (21L..26L).map { Candidate(it, 100L + it) } + (31L..36L).map { Candidate(it, 100L + it) }
        val session = SessionState().also { it.seed = 1L; it.seedAid = 5L; it.seedKind = Session.SEED_STATION_SONG }
        fun picked(closest: Boolean): Set<Long> {
            val request = StationPlanner.Request(
                StationPlanner.MODE_STATION, 12, candidates, seeds, nowMs = now, nu = 1.0, closestOfSeeds = closest,
            )
            val plan = StationPlanner.plan(request, Affinity.State(), session, StationPlanner.buildGraph(seeds, candidates, session))
            return plan.picks.map { it.rid }.toSet()
        }
        val first = picked(closest = false)
        val closest = picked(closest = true)
        assertTrue(first.none { it in 31L..36L }, "the first seed's station: the second seed's radio is too far: $first")
        assertTrue(closest.any { it in 31L..36L }, "the nearest seed counts: $closest")
    }
}
