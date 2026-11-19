package com.theveloper.pixelplay.brain

/**
 * "Your sound" (docs/brain-spec.md, 8.6): the figures the screen shows, from the listener's own plays. Pure: the app hands over plain rows
 * (plays in time order, who belongs to which island, tags and years by recording) and gets counts back. A section with nothing to say
 * comes back empty, and the screen shows only sections that have data. No name, no sentence and no guess is made here: names come from
 * the atlas and the islands in the app, and a genre or an island that is unknown is simply absent.
 *
 * Shares are in permille (integers), so two platforms print the same number.
 */
object YourSound {
    /** "Right now": the islands of the last 28 days. */
    const val RIGHT_NOW_DAYS = 28
    const val MAX_MONTHS = 24

    /** The eras section needs a year for at least this share of the plays (permille). */
    const val ERAS_MIN_COVERAGE_PERMILLE = 600

    /** One counted play. [first] is true for the listener's first play of that recording (computed by [withFirsts]). */
    class Play(
        val rid: Long,
        val aid: Long,
        val startedAtMs: Long,
        val tzOffsetMin: Int,
        val first: Boolean,
    )

    class IslandShare(val islandId: Int, val permille: Int, val plays: Int, val topAids: LongArray, val topRids: LongArray)

    class Daypart(val daypart: Int, val plays: Int, val leadingIslandId: Int)

    class Month(val month: Int, val plays: Int, val islandPermille: List<Pair<Int, Int>>)

    class Decade(val decade: Int, val permille: Int)

    class Novelty(val plays: Int, val novel: Int) {
        /** Permille of the plays that were new to the listener; 0 with no plays. */
        val permille: Int get() = if (plays == 0) 0 else BrainMath.floorToLong(novel.toDouble() * 1000.0 / plays.toDouble() + 0.5).toInt()
    }

    private fun permille(part: Int, whole: Int): Int =
        if (whole <= 0) 0 else BrainMath.floorToLong(part.toDouble() * 1000.0 / whole.toDouble() + 0.5).toInt()

    /** Marks each play's first of its recording. [plays] must be in time order; plays of [knownBefore] count as heard already. */
    fun withFirsts(rids: LongArray, aids: LongArray, startedAtMs: LongArray, tzOffsetMin: IntArray, knownBefore: Set<Long> = emptySet()): List<Play> {
        val seen = HashSet<Long>(knownBefore)
        val out = ArrayList<Play>(rids.size)
        for (i in rids.indices) out.add(Play(rids[i], aids[i], startedAtMs[i], tzOffsetMin[i], seen.add(rids[i])))
        return out
    }

    /** The islands of the plays that started within [RIGHT_NOW_DAYS] days before [nowMs], largest first (ties: smaller id). */
    fun islands(plays: List<Play>, islandOf: (Long) -> Int, nowMs: Long, top: Int = 3): List<IslandShare> {
        val from = nowMs - RIGHT_NOW_DAYS.toLong() * BrainMath.DAY_MS
        val counts = HashMap<Int, Int>()
        val byArtist = HashMap<Int, HashMap<Long, Int>>()
        val byTrack = HashMap<Int, HashMap<Long, Int>>()
        var total = 0
        for (p in plays) {
            if (p.startedAtMs < from || p.startedAtMs > nowMs) continue
            val id = islandOf(p.rid)
            if (id < 0) continue
            total++
            counts[id] = (counts[id] ?: 0) + 1
            if (p.aid != 0L) byArtist.getOrPut(id) { HashMap() }.merge(p.aid, 1, Int::plus)
            byTrack.getOrPut(id) { HashMap() }.merge(p.rid, 1, Int::plus)
        }
        val ids = counts.keys.sortedWith { a, b -> if (counts[a] != counts[b]) counts[b]!!.compareTo(counts[a]!!) else a.compareTo(b) }
        return ids.map { id ->
            IslandShare(id, permille(counts[id]!!, total), counts[id]!!, topKeys(byArtist[id], top), topKeys(byTrack[id], top))
        }
    }

    private fun topKeys(m: HashMap<Long, Int>?, n: Int): LongArray {
        if (m == null) return LongArray(0)
        val keys = m.keys.sortedWith { a, b -> if (m[a] != m[b]) m[b]!!.compareTo(m[a]!!) else a.compareTo(b) }
        return keys.take(n).toLongArray()
    }

    /** For each of the four dayparts: how many plays, and the island that led it (-1 when none of its plays belong to an island). */
    fun dayparts(plays: List<Play>, islandOf: (Long) -> Int): List<Daypart> {
        val counts = IntArray(4)
        val perIsland = Array(4) { HashMap<Int, Int>() }
        for (p in plays) {
            val d = HomePlanner.daypart(p.startedAtMs, if (p.tzOffsetMin == PlayEvent.TZ_UNKNOWN) 0 else p.tzOffsetMin)
            counts[d]++
            val id = islandOf(p.rid)
            if (id >= 0) perIsland[d].merge(id, 1, Int::plus)
        }
        return (0 until 4).map { d ->
            val m = perIsland[d]
            val lead = m.keys.sortedWith { a, b -> if (m[a] != m[b]) m[b]!!.compareTo(m[a]!!) else a.compareTo(b) }.firstOrNull() ?: -1
            Daypart(d, counts[d], lead)
        }
    }

    /** `year * 12 + month0` of a UTC day, by integer arithmetic (the proleptic Gregorian calendar). */
    fun monthOfDay(day: Long): Int {
        val z = day + 719468L
        val era = BrainMath.floorDiv(z, 146097L)
        val doe = z - era * 146097L
        val yoe = (doe - doe / 1460L + doe / 36524L - doe / 146096L) / 365L
        val y = yoe + era * 400L
        val doy = doe - (365L * yoe + yoe / 4L - yoe / 100L)
        val mp = (5L * doy + 2L) / 153L
        val m = if (mp < 10L) mp + 3L else mp - 9L
        val year = if (m <= 2L) y + 1L else y
        return (year * 12L + (m - 1L)).toInt()
    }

    /** The last [MAX_MONTHS] months with plays, oldest first: each month's island shares (permille), largest first. */
    fun months(plays: List<Play>, islandOf: (Long) -> Int): List<Month> {
        val perMonth = java.util.TreeMap<Int, HashMap<Int, Int>>()
        val totals = HashMap<Int, Int>()
        for (p in plays) {
            val month = monthOfDay(BrainMath.localDay(p.startedAtMs, if (p.tzOffsetMin == PlayEvent.TZ_UNKNOWN) 0 else p.tzOffsetMin))
            totals[month] = (totals[month] ?: 0) + 1
            val id = islandOf(p.rid)
            if (id >= 0) perMonth.getOrPut(month) { HashMap() }.merge(id, 1, Int::plus)
        }
        val months = totals.keys.sorted().let { if (it.size > MAX_MONTHS) it.subList(it.size - MAX_MONTHS, it.size) else it }
        return months.map { month ->
            val m = perMonth[month] ?: HashMap()
            val ids = m.keys.sortedWith { a, b -> if (m[a] != m[b]) m[b]!!.compareTo(m[a]!!) else a.compareTo(b) }
            Month(month, totals[month]!!, ids.map { it to permille(m[it]!!, totals[month]!!) })
        }
    }

    /** New to the listener: the plays of the last 7 days that were their first of the recording. */
    fun novelty(plays: List<Play>, nowMs: Long): Novelty {
        val from = nowMs - 7L * BrainMath.DAY_MS
        var n = 0
        var novel = 0
        for (p in plays) {
            if (p.startedAtMs < from || p.startedAtMs > nowMs) continue
            n++
            if (p.first) novel++
        }
        return Novelty(n, novel)
    }

    /**
     * The decades of the plays whose year is known ([yearOf] gives 0 for unknown), largest share first by decade. Empty until a year is
     * known for [ERAS_MIN_COVERAGE_PERMILLE] of the plays.
     */
    fun decades(plays: List<Play>, yearOf: (Long) -> Int): List<Decade> {
        val counts = java.util.TreeMap<Int, Int>()
        var known = 0
        for (p in plays) {
            val y = yearOf(p.rid)
            if (y < 1900 || y > 2100) continue
            known++
            val decade = (y / 10) * 10
            counts[decade] = (counts[decade] ?: 0) + 1
        }
        if (plays.isEmpty() || permille(known, plays.size) < ERAS_MIN_COVERAGE_PERMILLE) return emptyList()
        return counts.map { Decade(it.key, permille(it.value, known)) }
    }

    /** The genres of the plays, heaviest first: each play gives its track's top tag one count (unknown genres are absent). */
    fun genres(plays: List<Play>, top: Int = 8, topTagOf: (Long) -> Int): List<Pair<Int, Int>> {
        val counts = HashMap<Int, Int>()
        var known = 0
        for (p in plays) {
            val t = topTagOf(p.rid)
            if (t == GenreAtlas.NONE) continue
            known++
            counts[t] = (counts[t] ?: 0) + 1
        }
        val ids = counts.keys.sortedWith { a, b -> if (counts[a] != counts[b]) counts[b]!!.compareTo(counts[a]!!) else a.compareTo(b) }
        return ids.take(top).map { it to permille(counts[it]!!, known) }
    }
}

/**
 * What "Your sound" may say about the model, a function of the model's real state and nothing else (honesty rule of stage 3).
 *
 * - [GETTING_TO_KNOW]: fewer than [Gate.MIN_PLAYS] graded plays. Shows how many there are. No figure.
 * - [STANDARD_RECIPE]: enough plays, but the gate has the prior on (the learned weights have not beaten it two weeks running, or lost).
 *   Shows the prior's own accuracy, computed from the same graded plays.
 * - [LEARNING]: the learned weights are in use. Shows how many plays moved them, the accuracy now, a month ago when there is one, and the
 *   standard recipe's for comparison.
 *
 * Every percentage is `floor(share * 100 + 0.5)` of the graded plays (a finish or a skip) in the gate's window: no other source.
 */
object ModelVoice {
    const val GETTING_TO_KNOW = 0
    const val STANDARD_RECIPE = 1
    const val LEARNING = 2

    class State(
        val kind: Int,
        /** Graded plays so far (shown by [GETTING_TO_KNOW]). */
        val gradedPlays: Int,
        /** Plays that moved the weights since the model was last at its prior (shown by [LEARNING]). */
        val learnedFrom: Long,
        val accNowPercent: Int?,
        val accBeforePercent: Int?,
        val accPriorPercent: Int?,
    )

    private fun percent(share: Double): Int = BrainMath.floorToLong(share * 100.0 + 0.5).toInt()

    fun of(summary: Gate.Summary?, useLearned: Boolean, modelUpdates: Long): State {
        val graded = summary?.playsTotal ?: 0
        if (summary == null || graded < Gate.MIN_PLAYS) return State(GETTING_TO_KNOW, graded, 0L, null, null, null)
        if (!useLearned) return State(STANDARD_RECIPE, graded, 0L, null, null, percent(summary.accPrior))
        return State(
            LEARNING, graded, modelUpdates, percent(summary.accLearned), summary.accLearnedBefore?.let(::percent), percent(summary.accPrior),
        )
    }
}
