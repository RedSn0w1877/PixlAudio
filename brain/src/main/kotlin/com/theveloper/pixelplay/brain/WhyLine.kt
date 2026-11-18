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
    /** "On repeat this month"; no argument. */
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

    /** A track's 21-day affinity above this reads as "on repeat". */
    const val ON_REPEAT_AFF = 1.0

    /**
     * [seedAid] is the primary artist of the seed whose radio ranked the pick best (0 when it came from
     * no radio); [trackAffRecent] is the pick's own 21-day affinity.
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
    ): Why {
        if (slotKind == ListBuilder.SLOT_LIKED && likedRid != 0L) return Why(BECAUSE_LIKED, likedRid)
        if (homeRow && homeSeedRid != 0L) return Why(BECAUSE_LISTENED, homeSeedRid)
        return when (arm) {
            Arm.RADIO -> if (seedAid != 0L) Why(RADIO_FANS, seedAid) else Why(NONE, 0L)
            Arm.ARTIST -> if (candidateAid != 0L) Why(ARTIST_MORE, candidateAid) else Why(NONE, 0L)
            Arm.FAV, Arm.DEEP -> when {
                arm == Arm.FAV && trackAffRecent > ON_REPEAT_AFF -> Why(ON_REPEAT, 0L)
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
