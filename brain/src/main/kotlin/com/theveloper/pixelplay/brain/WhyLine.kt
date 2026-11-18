package com.theveloper.pixelplay.brain

/**
 * The reason shown under an engine pick (docs/brain-spec.md, 8.8, decision D16): a key and one
 * argument from pure logic; the sentence itself lives in each app's resources. Stage 1 has the arm
 * reasons. A key is only returned when its sentence is a true statement about the decision.
 */
object WhyLine {
    const val NONE = 0
    /** "Fans of X also play this"; the argument is the seed's artist (aid). */
    const val RADIO_FANS = 1
    /** "Because you liked X"; the argument is the liked track (rid). */
    const val BECAUSE_LIKED = 2
    /** "Because you listened to X"; the argument is the row's anchor track (rid). */
    const val BECAUSE_LISTENED = 3
    /** "More from X"; the argument is the pick's own artist (aid). */
    const val ARTIST_MORE = 4
    /** "On repeat lately"; no argument. Decided by [ON_REPEAT_AFF] and [ON_REPEAT_MASS]. */
    const val ON_REPEAT = 5
    const val SIMILAR_ARTIST = 6
    const val NEW_RELEASE = 7
    const val FORGOTTEN = 8
    const val ADJACENT_GENRE = 9
    const val ASKED = 10

    private val NAMES = arrayOf(
        "", "radio_fans", "because_liked", "because_listened", "artist_more", "on_repeat",
        "similar_artist", "new_release", "forgotten", "adjacent_genre", "asked",
    )

    fun nameOf(key: Int): String = if (key >= 0 && key < NAMES.size) NAMES[key] else ""

    fun keyOf(name: String?): Int {
        if (name == null) return NONE
        for (i in 1 until NAMES.size) if (NAMES[i] == name) return i
        return NONE
    }

    class Why(val key: Int, val arg: Long)

    /** A track's 21-day affinity must be above this to read as "on repeat". */
    const val ON_REPEAT_AFF = 1.0

    /**
     * And its 21-day positive mass at least this much: about three finished plays of the listener's
     * own choosing in the last three weeks (one finished play is worth 1.0, and the mass halves every
     * 21 days). The rate alone is not enough: for a listener who rarely finishes a song, one finished
     * play already lifts the rate above [ON_REPEAT_AFF], and "on repeat" would be untrue.
     */
    const val ON_REPEAT_MASS = 2.5

    /**
     * [seedAid] is the primary artist of the seed whose radio ranked the pick best (0 when it came from
     * no radio); [trackAffRecent] is the pick's own 21-day affinity and [trackPosRecent] its 21-day
     * positive mass (0 for a track the listener has not played). In a [mix] the library tracks carry
     * a line only when they are on repeat.
     */
    fun pick(
        arm: Int,
        slotKind: Int,
        homeRow: Boolean,
        candidateAid: Long,
        seedAid: Long,
        bestRank: Int,
        likedRid: Long,
        homeSeedRid: Long,
        trackAffRecent: Double,
        trackPosRecent: Double = 0.0,
        mix: Boolean = false,
    ): Why {
        if (slotKind == ListBuilder.SLOT_LIKED && likedRid != 0L) return Why(BECAUSE_LIKED, likedRid)
        if (homeRow && homeSeedRid != 0L) return Why(BECAUSE_LISTENED, homeSeedRid)
        return when (arm) {
            Arm.RADIO -> if (seedAid != 0L) Why(RADIO_FANS, seedAid) else Why(NONE, 0L)
            Arm.ARTIST -> if (candidateAid != 0L) Why(ARTIST_MORE, candidateAid) else Why(NONE, 0L)
            Arm.FAV, Arm.DEEP -> when {
                arm == Arm.FAV && trackAffRecent > ON_REPEAT_AFF && trackPosRecent >= ON_REPEAT_MASS -> Why(ON_REPEAT, 0L)
                // A mix holds the listener's own music by the dozen: a line under each of them would be noise.
                mix -> Why(NONE, 0L)
                bestRank > 0 && seedAid != 0L -> Why(RADIO_FANS, seedAid)
                candidateAid != 0L -> Why(ARTIST_MORE, candidateAid)
                else -> Why(NONE, 0L)
            }
            Arm.SIM -> Why(SIMILAR_ARTIST, candidateAid)
            Arm.NEW -> Why(NEW_RELEASE, candidateAid)
            Arm.BACK -> Why(FORGOTTEN, 0L)
            Arm.EDGE -> Why(ADJACENT_GENRE, 0L)
            Arm.ASK -> Why(ASKED, 0L)
            else -> Why(NONE, 0L)
        }
    }
}
