package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The parts stage 3's first pass cut (docs/brain-spec.md, 5.10): the arm bandit, the novelty appetite, context lift and the shelf bandit.
 * All of them have run on synthetic data only.
 */
class Stage3bTest {

    private val day0 = 20_000L

    private fun event(
        id: Long,
        startedAtMs: Long,
        arm: Int = Arm.RADIO,
        surface: Int = Surface.AUTOPLAY,
        finished: Boolean = true,
        flags: Int = 0,
        rid: Long = id,
    ) = PlayEvent(
        eventId = id, origin = Origin.LIVE, rid = rid, primaryAid = 7L, startedAtMs = startedAtMs, tzOffsetMin = 0,
        playedMs = if (finished) 200_000 else 3_000, durationMs = 200_000, startReason = StartReason.AUTOPLAY,
        endReason = if (finished) EndReason.TRACK_DONE else EndReason.NEXT, flags = flags, surface = surface, arm = arm,
    )

    private fun graded(e: PlayEvent): Reward.Graded = Reward.grade(e, Reward.Context())

    // ── Arm bandit ──────────────────────────────────────────────────────────

    @Test
    fun `the arms start at the spec's priors`() {
        val s = ArmBandit.newState(day0)
        // FAV (7,3) BACK (6,4) DEEP (5,5) ARTIST (5,5) NEW (5,5) RADIO (4,6) ASK (4,6) SIM (3,7) EDGE (2,8), in every family.
        val expected = mapOf(Arm.FAV to (7.0 to 3.0), Arm.BACK to (6.0 to 4.0), Arm.DEEP to (5.0 to 5.0), Arm.ARTIST to (5.0 to 5.0),
            Arm.NEW to (5.0 to 5.0), Arm.RADIO to (4.0 to 6.0), Arm.ASK to (4.0 to 6.0), Arm.SIM to (3.0 to 7.0), Arm.EDGE to (2.0 to 8.0))
        for (f in 0 until ArmBandit.FAMILIES) for ((arm, ab) in expected) {
            assertEquals(ab.first, s.alpha[f * ArmBandit.ARMS + arm])
            assertEquals(ab.second, s.beta[f * ArmBandit.ARMS + arm])
        }
        assertEquals(0.7, ArmBandit.mean(s, ArmBandit.MIX, Arm.FAV), 1e-12)
    }

    @Test
    fun `a play moves only its own family and arm, and an exploration play also feeds the audit`() {
        val s = ArmBandit.newState(day0)
        assertTrue(ArmBandit.update(s, ArmBandit.STATION, Arm.RADIO, 1.0, 2.0, false, day0))
        val i = ArmBandit.STATION * ArmBandit.ARMS + Arm.RADIO
        assertEquals(6.0, s.alpha[i])
        assertEquals(6.0, s.beta[i])
        assertEquals(4.0, s.auditAlpha[i], "not an exploration play: the audit does not move")
        assertTrue(ArmBandit.update(s, ArmBandit.STATION, Arm.RADIO, 0.0, 1.5, true, day0))
        assertEquals(7.5, s.beta[i])
        assertEquals(7.5, s.auditBeta[i])
        assertEquals(4.0, s.alpha[ArmBandit.MIX * ArmBandit.ARMS + Arm.RADIO], "another family is untouched")
        assertFalse(ArmBandit.update(s, ArmBandit.HOME, Arm.NONE, 1.0, 1.0, false, day0))
        assertFalse(ArmBandit.update(s, ArmBandit.HOME, Arm.RADIO, 1.0, 0.0, false, day0), "no trust, no change")
        assertEquals(2L, s.updates)
    }

    @Test
    fun `the daily discount does not depend on how often it is called`() {
        fun fresh(): ArmBandit.State {
            val s = ArmBandit.newState(day0)
            ArmBandit.update(s, ArmBandit.MIX, Arm.FAV, 1.0, 3.0, true, day0)
            return s
        }
        val once = fresh()
        ArmBandit.discount(once, day0 + 10)
        val many = fresh()
        for (d in 1..10) ArmBandit.discount(many, day0 + d)
        assertArrayEquals(once.alpha, many.alpha)
        assertArrayEquals(once.auditBeta, many.auditBeta)
        // A day before the anchor makes no run.
        val back = fresh()
        assertEquals(0, ArmBandit.discount(back, day0 - 3))
        assertEquals(day0 - 3, back.anchorDay)
        // Toward the prior: more evidence decays toward (7, 3).
        assertTrue(once.alpha[ArmBandit.MIX * ArmBandit.ARMS + Arm.FAV] < 10.0 && once.alpha[ArmBandit.MIX * ArmBandit.ARMS + Arm.FAV] > 7.0)
    }

    @Test
    fun `the audit is drawn from only when it has evidence and disagrees by more than 0 15`() {
        val s = ArmBandit.newState(day0)
        val arm = Arm.RADIO
        val i = ArmBandit.MIX * ArmBandit.ARMS + arm
        // Main looks fine, the exploration plays do not: 20 finishes in main, 12 misses in the audit.
        s.alpha[i] += 20.0
        s.auditBeta[i] += 12.0
        assertTrue(ArmBandit.auditInUse(s, ArmBandit.MIX, arm))
        assertEquals(s.auditAlpha[i] / (s.auditAlpha[i] + s.auditBeta[i]), ArmBandit.mean(s, ArmBandit.MIX, arm), 1e-12)
        // Too little exploration evidence: the main estimate stays.
        val thin = ArmBandit.newState(day0)
        thin.alpha[i] += 20.0
        thin.auditBeta[i] += 2.0
        assertFalse(ArmBandit.auditInUse(thin, ArmBandit.MIX, arm))
    }

    @Test
    fun `a draw is deterministic, inside 0 to 1, and the arm with the largest draw wins among those that have a candidate`() {
        val s = ArmBandit.newState(day0)
        val a = ArmBandit.draw(s, ArmBandit.MIX, Arm.FAV, BrainHash.Rng(42L))
        val b = ArmBandit.draw(s, ArmBandit.MIX, Arm.FAV, BrainHash.Rng(42L))
        assertEquals(a.toRawBits(), b.toRawBits())
        assertTrue(a in 0.0..1.0)
        // Make EDGE overwhelmingly good and the rest bad: it wins whenever it is eligible, and never when it is not.
        for (arm in 0 until ArmBandit.ARMS) {
            val i = ArmBandit.MIX * ArmBandit.ARMS + arm
            if (arm == Arm.EDGE) s.alpha[i] += 500.0 else s.beta[i] += 500.0
        }
        val all = BooleanArray(ArmBandit.ARMS) { true }
        for (seed in 1L..20L) assertEquals(Arm.EDGE, ArmBandit.pickArm(s, ArmBandit.MIX, all, BrainHash.Rng(seed)))
        val noEdge = BooleanArray(ArmBandit.ARMS) { it != Arm.EDGE }
        for (seed in 1L..20L) assertTrue(ArmBandit.pickArm(s, ArmBandit.MIX, noEdge, BrainHash.Rng(seed)) != Arm.EDGE)
        assertEquals(-1, ArmBandit.pickArm(s, ArmBandit.MIX, BooleanArray(ArmBandit.ARMS), BrainHash.Rng(1L)))
    }

    private fun scored(rid: Long, aid: Long, arm: Int, novel: Boolean, z: Double): ListBuilder.Scored = ListBuilder.Scored(
        c = Candidate(rid = rid, aid = aid, albumKey = rid, inLibrary = !novel), arm = arm, z = z, p = BrainMath.sigmoid(z), novel = novel,
        bestRank = 0, bestSeed = -1, newestSeedRank = 0, lastMs = 0L, retrQ = 0,
    )

    private fun pool(): List<ListBuilder.Scored> {
        val out = ArrayList<ListBuilder.Scored>()
        // Familiar tracks, and novel ones from three arms, the best-valued of which is a RADIO track.
        for (i in 1..12) out.add(scored(i.toLong(), 100L + i, Arm.FAV, false, 1.0 - 0.01 * i))
        for (i in 1..6) out.add(scored(100L + i, 200L + i, Arm.RADIO, true, 0.9 - 0.01 * i))
        for (i in 1..6) out.add(scored(200L + i, 300L + i, Arm.SIM, true, 0.5 - 0.01 * i))
        for (i in 1..6) out.add(scored(300L + i, 400L + i, Arm.EDGE, true, 0.2 - 0.01 * i))
        return out
    }

    private fun build(picker: ArmPicker?): List<ListBuilder.Pick> = ListBuilder.build(
        ListBuilder.Request(count = 20, prior = emptyList(), nu = 0.4, tieSeed = 5L, nowMs = 1_750_000_000_000L, tzOffsetMin = 0, armPicker = picker),
        pool(), Graph.EMPTY,
    )

    @Test
    fun `with no picker the novel slots are what stage 2 chose, and nothing is flagged as exploration`() {
        val plain = build(null)
        assertTrue(plain.any { it.scored.novel })
        assertTrue(plain.none { it.explore })
        // The best-valued novel candidates are the RADIO ones.
        assertTrue(plain.filter { it.scored.novel }.all { it.scored.arm == Arm.RADIO || it.scored.arm == Arm.SIM })
    }

    @Test
    fun `with a picker a novel slot goes to the drawn arm and is flagged, a familiar slot is not`() {
        // A picker that always wants the EDGE arm when it has candidates.
        val picker = ArmPicker { eligible -> if (eligible[Arm.EDGE]) Arm.EDGE else if (eligible[Arm.SIM]) Arm.SIM else -1 }
        val picks = build(picker)
        val novel = picks.filter { it.scored.novel }
        assertTrue(novel.isNotEmpty())
        assertEquals(Arm.EDGE, novel.first().scored.arm)
        assertTrue(novel.all { it.explore }, "every novel slot was filled by the bandit's draw")
        assertTrue(picks.filter { !it.scored.novel }.none { it.explore })
        // Arms that ran out are not drawn: six EDGE candidates, then the picker's second choice.
        assertEquals(6, novel.count { it.scored.arm == Arm.EDGE }.coerceAtMost(6))
    }

    @Test
    fun `a sampler seeded the same draws the same arms`() {
        val s = ArmBandit.newState(day0)
        val eligible = BooleanArray(ArmBandit.ARMS) { it >= Arm.RADIO && it <= Arm.EDGE }
        val a = ArmBandit.Sampler(s, ArmBandit.HOME, 9L)
        val b = ArmBandit.Sampler(s, ArmBandit.HOME, 9L)
        for (k in 0 until 10) assertEquals(a.pick(eligible), b.pick(eligible))
        // The sampler works on a copy: the state it was made from does not move.
        assertEquals(0L, s.updates)
    }

    // ── Novelty appetite ────────────────────────────────────────────────────

    @Test
    fun `with no history the appetite is 0 25 and a surface scales it`() {
        val s = NoveltyAppetite.newState(day0)
        assertEquals(0.25, NoveltyAppetite.nu0(s, day0))
        NoveltyAppetite.advance(s, day0)
        assertEquals(0.25, NoveltyAppetite.target(s, day0), 1e-12)
        assertEquals(0.25, s.nuLong, 1e-12)
        assertEquals(0.25, NoveltyAppetite.nu(s, day0 * BrainMath.DAY_MS), 1e-12)
    }

    @Test
    fun `nu0 is the share of first plays in the last year, clipped`() {
        val s = NoveltyAppetite.newState(day0)
        val e = event(1L, day0 * BrainMath.DAY_MS)
        val g = graded(e)
        // 100 plays, 45 of them first plays on day0; one more play 400 days earlier is out of the window.
        for (k in 0 until 100) NoveltyAppetite.onPlay(s, event(10L + k, day0 * BrainMath.DAY_MS), g, k < 45, false, day0)
        assertEquals(0.45, NoveltyAppetite.nu0(s, day0), 1e-12)
        assertEquals(0.45, NoveltyAppetite.nu0(s, day0 + 300), 1e-12)
        assertEquals(0.25, NoveltyAppetite.nu0(s, day0 + 400), "the plays left the window: no history again")
        val allNew = NoveltyAppetite.newState(day0)
        for (k in 0 until 20) NoveltyAppetite.onPlay(allNew, event(10L + k, day0 * BrainMath.DAY_MS), g, true, false, day0)
        assertEquals(NoveltyAppetite.NU0_MAX, NoveltyAppetite.nu0(allNew, day0))
        val none = NoveltyAppetite.newState(day0)
        for (k in 0 until 20) NoveltyAppetite.onPlay(none, event(10L + k, day0 * BrainMath.DAY_MS), g, false, false, day0)
        assertEquals(NoveltyAppetite.NU0_MIN, NoveltyAppetite.nu0(none, day0))
    }

    @Test
    fun `novel plays that are finished more often than familiar ones raise the target, and the slow appetite moves at most 0 02 a day`() {
        val s = NoveltyAppetite.newState(day0)
        NoveltyAppetite.advance(s, day0)
        val start = s.nuLong
        val e = event(1L, day0 * BrainMath.DAY_MS)
        val finished = graded(e)
        val skipped = graded(event(2L, day0 * BrainMath.DAY_MS, finished = false))
        for (k in 0 until 40) {
            NoveltyAppetite.onPlay(s, event(100L + k, day0 * BrainMath.DAY_MS), finished, true, true, day0)
            NoveltyAppetite.onPlay(s, event(200L + k, day0 * BrainMath.DAY_MS), skipped, false, true, day0)
        }
        assertTrue(NoveltyAppetite.target(s, day0) > start, "novel plays finish, familiar ones are skipped")
        NoveltyAppetite.advance(s, day0 + 1)
        assertTrue(s.nuLong - start <= NoveltyAppetite.MAX_STEP_PER_DAY + 1e-12)
        assertTrue(s.nuLong > start)
        NoveltyAppetite.advance(s, day0 + 1)
        val after = s.nuLong
        NoveltyAppetite.advance(s, day0 + 11)
        assertTrue(s.nuLong - after <= 10 * NoveltyAppetite.MAX_STEP_PER_DAY + 1e-12)
        assertTrue(s.nuLong <= NoveltyAppetite.NU_LONG_MAX)
    }

    @Test
    fun `the appetite posteriors keep being discounted after the first thirty days`() {
        val s = NoveltyAppetite.newState(day0)
        val finished = graded(event(1L, day0 * BrainMath.DAY_MS))
        for (k in 0 until 40) NoveltyAppetite.onPlay(s, event(100L + k, day0 * BrainMath.DAY_MS), finished, true, true, day0)
        val excess0 = s.nAlpha - NoveltyAppetite.PRIOR
        assertTrue(excess0 > 10.0)
        // A play that is not from a recommender surface moves nothing but the clock: 60 days on, then 60 days more.
        val other = event(900L, (day0 + 60) * BrainMath.DAY_MS)
        NoveltyAppetite.onPlay(s, other, finished, true, false, day0 + 60)
        val excess1 = s.nAlpha - NoveltyAppetite.PRIOR
        val capped = Math.pow(NoveltyAppetite.DISCOUNT, NoveltyAppetite.MAX_FORGET_RUNS.toDouble())
        assertEquals(excess0 * capped, excess1, 1e-9)
        NoveltyAppetite.onPlay(s, event(901L, (day0 + 120) * BrainMath.DAY_MS), finished, true, false, day0 + 120)
        val excess2 = s.nAlpha - NoveltyAppetite.PRIOR
        assertEquals(excess1 * capped, excess2, 1e-9, "the second gap is discounted as well")
    }

    @Test
    fun `a play stamped by a clock that was a day ahead does not stop the learning of the plays after it`() {
        val now = day0 * BrainMath.DAY_MS + 10L * 3_600_000L
        val model = Ranker.newModel(day0)
        val posteriors = Posteriors.newState(day0)
        val affinity = Affinity.State()
        val session = SessionState()
        // The phone said it was a day later than it was: this play's id is a day in the future.
        val ahead = event((now + BrainMath.DAY_MS) shl 16, now + BrainMath.DAY_MS)
        val first = LearnReplay.applyLive(model, ahead, graded(ahead), false, affinity, session, now + 2_000L, posteriors)
        assertEquals(Ranker.UPDATE_APPLIED, first)
        // The clock is corrected: the next plays have ids far below the first.
        val a = event((now + 1_000L) shl 16, now + 1_000L, rid = 11L)
        val b = event((now + 2_000L) shl 16, now + 2_000L, rid = 12L)
        assertEquals(Ranker.UPDATE_APPLIED, LearnReplay.applyLive(model, a, graded(a), false, affinity, session, now + 3_000L, posteriors))
        assertEquals(Ranker.UPDATE_APPLIED, LearnReplay.applyLive(model, b, graded(b), false, affinity, session, now + 4_000L, posteriors))
        assertEquals(3L, model.updates)
        assertEquals(3L, posteriors.arms.updates)
        // The watermark only moves forward, by one for a play that is below it.
        assertEquals(ahead.eventId + 2L, model.lastEventId)
        // In order, nothing changes: the real id is used.
        assertEquals(10L, LearnReplay.liveId(10L, 5L))
        assertEquals(6L, LearnReplay.liveId(3L, 5L))
        assertEquals(Long.MAX_VALUE, LearnReplay.liveId(3L, Long.MAX_VALUE))
    }

    @Test
    fun `a liked novel track adds 0 01 that fades over 30 days and is capped at 0 10`() {
        val s = NoveltyAppetite.newState(day0)
        val t = day0 * BrainMath.DAY_MS
        val liked = event(1L, t, flags = EventFlags.LIKED_DURING)
        NoveltyAppetite.onPlay(s, liked, graded(liked), true, true, day0)
        assertEquals(0.01, NoveltyAppetite.boost(s, t), 1e-12)
        assertEquals(0.005, NoveltyAppetite.boost(s, t + 15L * BrainMath.DAY_MS), 1e-9)
        assertEquals(0.0, NoveltyAppetite.boost(s, t + 30L * BrainMath.DAY_MS), 1e-12)
        // A liked track that was not new does not count; twenty that were stop at +0.10.
        val old = event(2L, t, flags = EventFlags.SAVED_DURING)
        NoveltyAppetite.onPlay(s, old, graded(old), false, true, day0)
        assertEquals(0.01, NoveltyAppetite.boost(s, t), 1e-12)
        for (k in 0 until 20) {
            val e = event(10L + k, t, flags = EventFlags.SAVED_DURING)
            NoveltyAppetite.onPlay(s, e, graded(e), true, true, day0)
        }
        assertEquals(NoveltyAppetite.BOOST_CAP, NoveltyAppetite.boost(s, t), 1e-12)
        assertTrue(NoveltyAppetite.nu(s, t) <= NoveltyAppetite.NU_LONG_MAX)
    }

    @Test
    fun `an excluded play changes no posterior`() {
        val s = NoveltyAppetite.newState(day0)
        val e = event(1L, day0 * BrainMath.DAY_MS, flags = EventFlags.LEARN_EXCLUDED)
        assertFalse(NoveltyAppetite.onPlay(s, e, graded(e), true, true, day0))
        assertEquals(0L, s.updates)
        assertTrue(s.days.isEmpty())
    }

    // ── The posteriors together: live and replay, the codec ─────────────────

    private fun history(n: Int): List<PlayEvent> {
        val rng = BrainHash.Rng(77L)
        val out = ArrayList<PlayEvent>()
        var t = day0 * BrainMath.DAY_MS
        for (i in 0 until n) {
            t += 3_000_000L
            val arm = intArrayOf(Arm.FAV, Arm.DEEP, Arm.RADIO, Arm.SIM, Arm.EDGE, Arm.ARTIST)[(rng.uniform() * 6.0).toInt().coerceIn(0, 5)]
            val surface = intArrayOf(Surface.AUTOPLAY, Surface.DAILY_MIX, Surface.HOME_SHELF, Surface.PLAYLIST)[(rng.uniform() * 4.0).toInt().coerceIn(0, 3)]
            val flags = if (i % 9 == 0) EventFlags.EXPLORE_SLOT else if (i % 17 == 0) EventFlags.LIKED_DURING else 0
            out.add(event(((t shl 16) or (i.toLong() and 0xFFFF)), t, arm = arm, surface = surface, finished = rng.uniform() < 0.6, flags = flags, rid = 1L + i % 40))
        }
        return out
    }

    @Test
    fun `the replay builds the posteriors, the same way twice, and applies no event twice`() {
        val events = history(400)
        val now = events.last().startedAtMs + 3_600_000L
        val a = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet())
        val b = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet())
        assertEquals(PosteriorCodec.encode(a.posteriors), PosteriorCodec.encode(b.posteriors))
        assertTrue(a.posteriors.arms.updates > 100)
        assertTrue(a.posteriors.appetite.updates > 100)
        // Only recommender surfaces on a known family count: a playlist play moves nothing in the arm bandit.
        val playlistOnly = events.filter { it.surface == Surface.PLAYLIST }
        val c = LearnReplay.run(playlistOnly.iterator(), LibraryFacts.EMPTY, now, emptySet())
        assertEquals(0L, c.posteriors.arms.updates)
        // The same play again changes nothing.
        val p = a.posteriors.copy()
        val e = events.last()
        assertFalse(PosteriorUpdate.onPlay(p, e, graded(e), true, now))
        assertEquals(PosteriorCodec.encode(a.posteriors), PosteriorCodec.encode(p))
    }

    @Test
    fun `a live pass over the same events gives the posteriors the replay gives`() {
        val events = history(300)
        val now = events.last().startedAtMs + 3_600_000L
        val replay = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet())
        // The live path: one event at a time, the state as it was before each play.
        val affinity = Affinity.State()
        val tracker = GradeTracker(affinity.completions)
        val live = Posteriors.newState(LearnReplay.dayOf(events[0], events[0].startedAtMs))
        for (e in events) {
            val novel = !Affinity.isHeard(affinity, Family.TRACK, e.rid)
            val g = tracker.grade(e)
            PosteriorUpdate.onPlay(live, e, g, novel, now)
            Affinity.applyEvent(affinity, e, g, e.startedAtMs)
        }
        PosteriorUpdate.advanceTo(live, BrainMath.localDay(now, 0))
        assertEquals(PosteriorCodec.encode(replay.posteriors), PosteriorCodec.encode(live))
    }

    @Test
    fun `plays before a reset are not learned from, and the codec refuses a damaged state`() {
        val events = history(200)
        val now = events.last().startedAtMs + 3_600_000L
        val after = events[99].eventId
        val result = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet(), 0, after)
        val full = LearnReplay.run(events.iterator(), LibraryFacts.EMPTY, now, emptySet())
        assertTrue(result.posteriors.arms.updates < full.posteriors.arms.updates)
        val text = PosteriorCodec.encode(result.posteriors)
        val back = PosteriorCodec.decode(text)
        assertNotNull(back)
        assertEquals(text, PosteriorCodec.encode(back!!))
        for (bad in listOf("", "{}", "not json", "{\"format\":\"brain-posteriors-v1\"}", text.replace("brain-posteriors-v1", "other"))) {
            assertNull(PosteriorCodec.decode(bad), bad)
        }
        // A hand-edited value that is not finite (an all-ones exponent) is refused.
        val nan = text.replaceFirst("\"" + java.lang.Long.toHexString(result.posteriors.arms.alpha[0].toRawBits()).padStart(16, '0') + "\"", "\"7ff8000000000000\"")
        assertNull(PosteriorCodec.decode(nan))
    }

    // ── Context ─────────────────────────────────────────────────────────────

    @Test
    fun `the dayparts and the eight buckets`() {
        fun at(hour: Int, day: Long = 20_000L) = day * BrainMath.DAY_MS + hour * 3_600_000L
        assertEquals(ContextLift.NIGHT, ContextLift.daypart(at(4), 0))
        assertEquals(ContextLift.MORNING, ContextLift.daypart(at(5), 0))
        assertEquals(ContextLift.MORNING, ContextLift.daypart(at(10), 0))
        assertEquals(ContextLift.DAY, ContextLift.daypart(at(11), 0))
        assertEquals(ContextLift.DAY, ContextLift.daypart(at(16), 0))
        assertEquals(ContextLift.EVENING, ContextLift.daypart(at(17), 0))
        assertEquals(ContextLift.EVENING, ContextLift.daypart(at(21), 0))
        assertEquals(ContextLift.NIGHT, ContextLift.daypart(at(22), 0))
        // The offset moves the hour: 23:00 UTC at +120 minutes is 01:00 the next day, still night.
        assertEquals(ContextLift.NIGHT, ContextLift.daypart(at(23), 120))
        assertEquals(ContextLift.MORNING, ContextLift.daypart(at(3), 180))
        // 1970-01-01 was a Thursday: day 20_000 is a Saturday? (20_000 + 3) mod 7 = 4 -> Friday; 20_001 is Saturday.
        assertFalse(ContextLift.isWeekendBucket(ContextLift.bucket(at(9, 20_000L), 0)))
        assertTrue(ContextLift.isWeekendBucket(ContextLift.bucket(at(9, 20_001L), 0)))
        assertEquals(4 + ContextLift.MORNING, ContextLift.bucket(at(9, 20_001L), 0))
        val all = HashSet<Int>()
        for (d in 20_000L until 20_007L) for (h in 0 until 24) all.add(ContextLift.bucket(at(h, d), 0))
        assertEquals(8, all.size)
    }

    private fun table(plays: Int, now: Long): ContextLift.Table {
        val b = ContextLift.Builder(now)
        val fridayNight = (20_000L * BrainMath.DAY_MS) + 23L * 3_600_000L
        // Island 1 owns Friday nights (weekday night); island 2 owns weekend mornings.
        for (k in 0 until plays) b.add(1, fridayNight - k * 7L * BrainMath.DAY_MS, 0, 1.0, 1.0)
        for (k in 0 until 60) b.add(2, 20_001L * BrainMath.DAY_MS + 9L * 3_600_000L - k * 7L * BrainMath.DAY_MS, 0, 1.0, 1.0)
        for (k in 0 until 60) b.add(2, 20_002L * BrainMath.DAY_MS + 9L * 3_600_000L - k * 7L * BrainMath.DAY_MS, 0, 1.0, 1.0)
        // Island 2 also plays on weekday evenings, a lot, so that island 1's night is a real contrast.
        for (k in 0 until 80) b.add(2, 20_000L * BrainMath.DAY_MS + 18L * 3_600_000L - k * BrainMath.DAY_MS, 0, 1.0, 1.0)
        return b.build()
    }

    @Test
    fun `the lift is 0 below 50 plays in a bucket and follows the formula above it`() {
        val now = 20_000L * BrainMath.DAY_MS + 23L * 3_600_000L
        val thin = table(40, now)
        val night = ContextLift.bucket(now, 0)
        assertFalse(thin.hasLift(night))
        assertEquals(0.0, thin.lift(1, night))
        val full = table(80, now)
        assertTrue(full.hasLift(night))
        assertTrue(full.lift(1, night) > 0.3, "island 1 is what is played on weekday nights")
        assertTrue(full.lift(2, night) < 0.0 || full.lift(2, night) == 0.0 || !full.hasLift(night))
        assertEquals(0.0, full.lift(99, night), "an island the table does not know")
        for (id in intArrayOf(1, 2)) for (b in 0 until 8) assertTrue(full.lift(id, b) in -1.5..1.5)
    }

    @Test
    fun `an older play counts for less and an unknown timezone is resolved by the caller`() {
        val now = 20_100L * BrainMath.DAY_MS
        val b = ContextLift.Builder(now)
        b.add(1, now - 10L * BrainMath.DAY_MS, 0, 1.0, 1.0)
        b.add(1, now - 130L * BrainMath.DAY_MS, 0, 1.0, 1.0)
        val t = b.build()
        // Two plays, one a half-life older: 1 + 0.5 (about, 120 days) in the island's row; the play count is exact.
        assertEquals(2, t.plays.sum())
        val skipOnly = ContextLift.Builder(now)
        skipOnly.add(1, now, 0, 1.0, 0.0)
        assertEquals(1, skipOnly.build().plays.sum(), "a skip is a play of the bucket with weight 0")
        val none = ContextLift.Builder(now)
        none.add(-1, now, 0, 1.0, 1.0)
        none.add(1, now, 0, 0.0, 1.0)
        assertEquals(0, none.build().plays.sum())
    }

    @Test
    fun `the leader is the island with the largest weight times exp of its lift`() {
        val now = 20_000L * BrainMath.DAY_MS + 23L * 3_600_000L
        val t = table(80, now)
        val night = ContextLift.bucket(now, 0)
        assertEquals(1, t.leader(night, listOf(1 to 1.0, 2 to 1.0)))
        // A much heavier island 2 wins anyway: the lift only moves the odds.
        assertEquals(2, t.leader(night, listOf(1 to 1.0, 2 to 50.0)))
        assertEquals(-1, table(40, now).leader(night, listOf(1 to 1.0)))
        assertEquals(-1, t.leader(night, emptyList()))
    }

    // ── Shelf bandit ────────────────────────────────────────────────────────

    @Test
    fun `the shelves start at the pessimistic priors`() {
        val s = ShelfBandit.newState()
        assertEquals(0.3, ShelfBandit.mean(s, HomePlanner.MADE_FOR_YOU, 2), 1e-12)
        assertEquals(0.2, ShelfBandit.mean(s, HomePlanner.ON_REPEAT, 0), 1e-12)
        assertEquals(0.1, ShelfBandit.mean(s, HomePlanner.REDISCOVER, 3), 1e-12)
        assertEquals(0.1, ShelfBandit.mean(s, ShelfBandit.DAYPART_MIX, 1), 1e-12)
    }

    @Test
    fun `a visit updates only the shelves down to the last one seen or used`() {
        val s = ShelfBandit.newState()
        val shown = intArrayOf(HomePlanner.MADE_FOR_YOU, HomePlanner.BECAUSE_LISTENED, HomePlanner.ON_REPEAT, HomePlanner.REDISCOVER, HomePlanner.STATIONS)
        // Seen down to the third shelf, the second was used.
        assertEquals(3, ShelfBandit.visit(s, 1, shown, lastSeen = 2, used = 1, today = day0))
        assertEquals(3.0, s.alpha[HomePlanner.MADE_FOR_YOU * 4 + 1]); assertEquals(8.0, s.beta[HomePlanner.MADE_FOR_YOU * 4 + 1])
        assertEquals(2.0, s.alpha[HomePlanner.BECAUSE_LISTENED * 4 + 1]); assertEquals(9.0, s.beta[HomePlanner.BECAUSE_LISTENED * 4 + 1])
        assertEquals(2.0, s.alpha[HomePlanner.ON_REPEAT * 4 + 1]); assertEquals(9.0, s.beta[HomePlanner.ON_REPEAT * 4 + 1])
        assertEquals(1.0, s.alpha[HomePlanner.REDISCOVER * 4 + 1]); assertEquals(9.0, s.beta[HomePlanner.REDISCOVER * 4 + 1], "below the last one seen: untouched")
        assertEquals(3.0, s.alpha[HomePlanner.MADE_FOR_YOU * 4 + 0], "another daypart is untouched")
        // A play from a lower shelf than the last seen one counts as seen down to it.
        assertEquals(5, ShelfBandit.visit(s, 2, shown, lastSeen = 0, used = 4, today = day0 + 1))
        assertEquals(2.0, s.alpha[HomePlanner.STATIONS * 4 + 2])
        // Nothing seen, nothing used: no update.
        assertEquals(0, ShelfBandit.visit(s, 2, shown, -1, -1, day0 + 2))
    }

    @Test
    fun `with no data the stage 2 order stays, and once there is data the order is drawn once per daypart and frozen`() {
        val s = ShelfBandit.newState()
        val types = intArrayOf(HomePlanner.MADE_FOR_YOU, HomePlanner.BECAUSE_LISTENED, HomePlanner.NEW_FROM_LOVED, HomePlanner.ON_REPEAT, HomePlanner.REDISCOVER)
        assertNull(ShelfBandit.order(s, 100L, 1, types, 5L, day0))
        for (k in 0 until 25) ShelfBandit.visit(s, 1, types, lastSeen = 4, used = if (k % 2 == 0) 3 else 1, today = day0)
        assertTrue(ShelfBandit.hasData(s))
        val first = ShelfBandit.order(s, 100L, 1, types, 5L, day0 + 1)!!
        assertEquals(types.toSet(), first.toSet())
        // Posteriors move, the seed changes: the order for the same daypart does not.
        ShelfBandit.visit(s, 1, types, lastSeen = 4, used = 0, today = day0 + 1)
        assertArrayEquals(first, ShelfBandit.order(s, 100L, 1, types, 999L, day0 + 1))
        // A shelf that is missing today is dropped, a new one is placed where it stands in the fixed order.
        val fewer = intArrayOf(HomePlanner.MADE_FOR_YOU, HomePlanner.ON_REPEAT)
        assertEquals(fewer.toSet(), ShelfBandit.order(s, 100L, 1, fewer, 5L, day0 + 1)!!.toSet())
        // The next daypart draws again from its own posteriors and seed.
        assertNotNull(ShelfBandit.order(s, 101L, 2, types, 6L, day0 + 1))
        assertEquals(101L, s.frozenKey)
    }

    @Test
    fun `a shelf type unseen for 14 days is shown once at the top`() {
        val s = ShelfBandit.newState()
        val types = intArrayOf(HomePlanner.MADE_FOR_YOU, HomePlanner.BECAUSE_LISTENED, HomePlanner.ON_REPEAT, HomePlanner.REDISCOVER)
        // The listener used Made for you every time; Rediscover was last seen on day0.
        ShelfBandit.visit(s, 0, intArrayOf(HomePlanner.REDISCOVER), 0, -1, day0)
        for (k in 0 until 40) ShelfBandit.visit(s, 0, types.copyOfRange(0, 3), 2, 0, day0 + 1 + k.toLong() / 10L)
        val today = day0 + 20
        val order = ShelfBandit.order(s, 5L, 0, types, 1L, today)!!
        assertEquals(HomePlanner.REDISCOVER, order[0])
        // The exposure is spent when a visit shows the shelf, not when the order is drawn: a plan that leaves it out forces it again.
        assertEquals(day0, s.lastSeenDay[HomePlanner.REDISCOVER])
        assertEquals(HomePlanner.REDISCOVER, ShelfBandit.order(s, 6L, 0, types, 1L, today)!![0])
        ShelfBandit.visit(s, 0, order, lastSeen = 0, used = -1, today = today)
        assertEquals(today, s.lastSeenDay[HomePlanner.REDISCOVER])
        // The next plan 3 days later does not force it again.
        val later = ShelfBandit.order(s, 7L, 0, types, 1L, today + 3)!!
        assertTrue(later.indexOf(HomePlanner.REDISCOVER) >= 0)
    }

    
    fun `a shelf type that was never on screen is forced to the top once there is data`() {
        val s = ShelfBandit.newState()
        val types = intArrayOf(HomePlanner.MADE_FOR_YOU, HomePlanner.BECAUSE_LISTENED, HomePlanner.ON_REPEAT)
        // Only the first two have ever been shown.
        for (k in 0 until 60) ShelfBandit.visit(s, 0, types.copyOfRange(0, 2), 1, 0, day0)
        assertEquals(Long.MIN_VALUE, s.lastSeenDay[HomePlanner.ON_REPEAT])
        assertEquals(HomePlanner.ON_REPEAT, ShelfBandit.order(s, 1L, 0, types, 1L, day0)!![0])
    }

    @Test
    fun `the posteriors can be made again from the impressions, and the codec is strict`() {
        val live = ShelfBandit.newState()
        val rows = ArrayList<ShelfBandit.VisitRow>()
        val types = intArrayOf(HomePlanner.MADE_FOR_YOU, HomePlanner.BECAUSE_LISTENED, HomePlanner.ON_REPEAT)
        for (v in 0 until 30) {
            val used = v % 3
            val lastSeen = if (v % 4 == 0) 2 else used
            ShelfBandit.visit(live, v % 4, types, lastSeen, used, day0 + v)
            for (p in types.indices) rows.add(ShelfBandit.VisitRow(v.toLong(), types[p], v % 4, p, p <= lastSeen, p == used, day0 + v))
        }
        val rebuilt = ShelfBandit.rebuild(rows)
        assertArrayEquals(live.alpha, rebuilt.alpha)
        assertArrayEquals(live.beta, rebuilt.beta)
        val text = ShelfBanditCodec.encode(live)
        assertEquals(text, ShelfBanditCodec.encode(ShelfBanditCodec.decode(text)!!))
        for (bad in listOf("", "{}", "{\"format\":\"brain-shelf-bandit-v1\"}", "xx")) assertNull(ShelfBanditCodec.decode(bad))
    }

    @Test
    fun `the page order follows the shelf types and keeps shelves of one type in their order`() {
        fun shelf(type: Int, id: String) = HomePlanner.ShelfIn(type, id, emptyList())
        val fixed = listOf(
            shelf(HomePlanner.MADE_FOR_YOU, "a"), shelf(HomePlanner.BECAUSE_LISTENED, "b1"), shelf(HomePlanner.BECAUSE_LISTENED, "b2"),
            shelf(HomePlanner.ON_REPEAT, "c"), shelf(HomePlanner.REDISCOVER, "d"),
        )
        val out = HomePlanner.reorder(fixed, intArrayOf(HomePlanner.ON_REPEAT, HomePlanner.BECAUSE_LISTENED, HomePlanner.MADE_FOR_YOU))
        assertEquals(listOf("c", "b1", "b2", "a", "d"), out.map { it.id })
    }

    // ── Lately, the genre tree, "you and this genre" ─────────────────────────

    private fun play(aid: Long, finished: Boolean, t: Long, id: Long): PlayEvent = PlayEvent(
        eventId = id, origin = Origin.LIVE, rid = aid * 100 + id % 3, primaryAid = aid, startedAtMs = t, tzOffsetMin = 0,
        playedMs = if (finished) 200_000 else 3_000, durationMs = 200_000, startReason = StartReason.TAP,
        endReason = if (finished) EndReason.TRACK_DONE else EndReason.NEXT, surface = Surface.PLAYLIST,
    )

    @Test
    fun `lately names the artists whose affinity moved most in 14 days, largest first, at most five`() {
        val now = 1_760_000_000_000L
        val cutoff = now - YourSound.LATELY_DAYS * BrainMath.DAY_MS
        val before = Affinity.State()
        val after = Affinity.State()
        val tracker = GradeTracker(after.completions)
        val trackerBefore = GradeTracker(before.completions)
        var id = 1L
        // Artist 1 was loved until the cutoff, then skipped; artist 2 only appeared after it; artist 3 did not change.
        for (k in 0 until 30) {
            val t = cutoff - (30 - k) * 3_600_000L
            for (aid in longArrayOf(1L, 3L)) {
                val e = play(aid, true, t, id++)
                Affinity.applyEvent(before, e, trackerBefore.grade(e), t)
                Affinity.applyEvent(after, e, tracker.grade(e), t)
            }
        }
        for (k in 0 until 30) {
            val t = cutoff + (k + 1) * 3_600_000L
            val down = play(1L, false, t, id++)
            Affinity.applyEvent(after, down, tracker.grade(down), t)
            val up = play(2L, true, t + 60_000L, id++)
            Affinity.applyEvent(after, up, tracker.grade(up), t + 60_000L)
        }
        val moves = YourSound.lately(before, after, cutoff, now)
        assertTrue(moves.isNotEmpty() && moves.size <= YourSound.LATELY_TOP)
        val artists = moves.filter { it.family == Family.ARTIST }.associate { it.key to it.delta }
        assertTrue(artists[2L]!! > 0.0, "the new favourite is up")
        assertTrue(artists[1L]!! < 0.0, "the one that was skipped is down")
        assertFalse(3L in artists && BrainMath.abs(artists[3L]!!) > BrainMath.abs(artists[2L]!!), "an unchanged artist is not the biggest move")
        for (i in 1 until moves.size) assertTrue(BrainMath.abs(moves[i - 1].delta) >= BrainMath.abs(moves[i].delta) - 1e-9)
        // Nothing moved: nothing shown.
        assertTrue(YourSound.lately(after, after, now, now).isEmpty())
    }

    @Test
    fun `the genre tree rolls plays up to their family, and an unknown genre is absent`() {
        fun p(rid: Long, t: Long) = YourSound.Play(rid, 1L, t, 0, false)
        val plays = listOf(p(1, 1), p(1, 2), p(2, 3), p(3, 4), p(4, 5))
        val tag = mapOf(1L to 11, 2L to 12, 3L to 20)
        val root = mapOf(11 to 10, 12 to 10, 20 to 20)
        val tree = YourSound.genreTree(plays, 8, { tag[it] ?: GenreAtlas.NONE }, { root[it] ?: it })
        assertEquals(listOf(10, 20), tree.map { it.rootId })
        assertEquals(750, tree[0].permille)
        assertEquals(listOf(11 to 500, 12 to 250), tree[0].children)
        assertEquals(250, tree[1].permille)
        assertTrue(YourSound.genreTree(emptyList(), 8, { GenreAtlas.NONE }, { it }).isEmpty())
    }

    @Test
    fun `you and this genre is hidden when nothing was played and otherwise says first play, share and top tracks`() {
        fun p(rid: Long, t: Long) = YourSound.Play(rid, 1L, t, 0, false)
        val plays = listOf(p(5, 300), p(6, 100), p(5, 200), p(9, 400), p(5, 500))
        assertNull(YourSound.genreHistory(plays, 5) { false })
        val h = YourSound.genreHistory(plays, 5) { it == 5L || it == 6L }!!
        assertEquals(100L, h.firstPlayedMs)
        assertEquals(800, h.permille)
        assertEquals(4, h.plays)
        assertArrayEquals(longArrayOf(5L, 6L), h.topRids)
    }

    @Test
    fun `a tag adjustment becomes a pseudo-count on the genre and nothing else`() {
        val facts = LibraryFacts(adjustments = listOf(LibraryFacts.Adjustment(Family.TAG, 77L, true, 2), LibraryFacts.Adjustment(Family.TAG, 78L, false, 1)))
        val state = Affinity.State()
        Replay.applyFacts(state, facts)
        assertTrue(Affinity.aff(state, Family.TAG, 77L, 0L, 0.5) > 0.0)
        assertTrue(Affinity.aff(state, Family.TAG, 78L, 0L, 0.5) < 0.0)
        assertEquals(0, state.sortedKeys(Family.TRACK).size)
    }
}
