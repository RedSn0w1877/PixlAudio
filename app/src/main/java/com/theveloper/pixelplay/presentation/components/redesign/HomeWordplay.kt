package com.theveloper.pixelplay.presentation.components.redesign

/*
 * Home's wordplay (redesign batch 3, mockup Main.dc.html + the canvas "wordplayNote"): the greeting and the section titles
 * change with the time of day and with what the listener plays. The lines themselves live in strings_home_redesign.xml as
 * string arrays; this object only decides WHICH line, so it is pure and unit-tested (HomeWordplayTest).
 *
 * Rules:
 *  - Deterministic: the same day, the same time bucket and the same listening give the same line (no flicker on recomposition,
 *    rotation or a new process).
 *  - No repeats back to back: the line of one day is never the line of the day before in the same set (the step between two days
 *    is never a multiple of the set's size). Two buckets of one day use different sets, so they never repeat either.
 *  - Real data only: a streak needs a real top song with at least [STREAK_MIN_PLAYS] plays this week.
 */
object HomeWordplay {

    /** A song played at least this many times this week puts Home in the "on a streak" set. */
    const val STREAK_MIN_PLAYS = 5

    enum class TimeBucket { MORNING, AFTERNOON, EVENING, LATE_NIGHT }

    /** The greeting's line sets: one per time bucket, plus the streak set that wins over them. */
    enum class GreetingSet { MORNING, AFTERNOON, EVENING, LATE_NIGHT, STREAK }

    /** The section titles that rotate. */
    enum class Section { MIXES, RECENT, FRESH }

    /** Morning 5-11, afternoon 12-16, evening 17-21, late night 22-4. */
    fun timeBucket(hour: Int): TimeBucket {
        val h = Math.floorMod(hour, 24)
        return when (h) {
            in 5..11 -> TimeBucket.MORNING
            in 12..16 -> TimeBucket.AFTERNOON
            in 17..21 -> TimeBucket.EVENING
            else -> TimeBucket.LATE_NIGHT
        }
    }

    fun greetingSet(hour: Int, topSongPlaysThisWeek: Int): GreetingSet {
        if (topSongPlaysThisWeek >= STREAK_MIN_PLAYS) return GreetingSet.STREAK
        return when (timeBucket(hour)) {
            TimeBucket.MORNING -> GreetingSet.MORNING
            TimeBucket.AFTERNOON -> GreetingSet.AFTERNOON
            TimeBucket.EVENING -> GreetingSet.EVENING
            TimeBucket.LATE_NIGHT -> GreetingSet.LATE_NIGHT
        }
    }

    /** One greeting: which set and which line of it. */
    data class Greeting(val set: GreetingSet, val index: Int)

    fun greeting(epochDay: Long, hour: Int, topSongPlaysThisWeek: Int, setSize: (GreetingSet) -> Int): Greeting {
        val set = greetingSet(hour, topSongPlaysThisWeek)
        return Greeting(set, rotate(epochDay, setSize(set), salt = SALT_GREETING + set.ordinal))
    }

    fun section(epochDay: Long, section: Section, size: Int): Int =
        rotate(epochDay, size, salt = SALT_SECTION + section.ordinal)

    /** The sub line under the greeting (only shown with a real play count). */
    fun subLine(epochDay: Long, size: Int): Int = rotate(epochDay, size, salt = SALT_SUB)

    /**
     * Index in 0 until [size] for [epochDay]: `(day * step + offset) mod size`, with a step in 1 until size that is coprime with the
     * size, so consecutive days always differ and every line comes round. [salt] separates the sets so they do not move in lockstep.
     */
    fun rotate(epochDay: Long, size: Int, salt: Int): Int {
        if (size <= 1) return 0
        val step = stepFor(size, salt)
        val offset = Math.floorMod(salt.toLong() * 7L + 3L, size.toLong())
        return Math.floorMod(epochDay * step + offset, size.toLong()).toInt()
    }

    private fun stepFor(size: Int, salt: Int): Long {
        // Candidates 1 until size coprime with size; there is always at least one (1).
        var count = 0
        for (s in 1 until size) if (gcd(s, size) == 1) count++
        var pick = Math.floorMod(salt, count)
        for (s in 1 until size) {
            if (gcd(s, size) == 1) {
                if (pick == 0) return s.toLong()
                pick--
            }
        }
        return 1L
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private const val SALT_GREETING = 11
    private const val SALT_SECTION = 31
    private const val SALT_SUB = 53
}
