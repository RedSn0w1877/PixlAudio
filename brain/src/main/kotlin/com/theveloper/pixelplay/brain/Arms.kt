package com.theveloper.pixelplay.brain

/**
 * The candidate arms of docs/brain-spec.md, 6.1, with the integer codes `play_event.arm` stores and the
 * names the `brain.arm` media-item extra carries. Codes are part of the event log: never renumber.
 */
object Arm {
    const val FAV = 0
    const val DEEP = 1
    const val BACK = 2
    const val RADIO = 3
    const val SIM = 4
    const val ARTIST = 5
    const val NEW = 6
    const val EDGE = 7
    const val ASK = 8
    const val NONE = 9

    private val NAMES = arrayOf("FAV", "DEEP", "BACK", "RADIO", "SIM", "ARTIST", "NEW", "EDGE", "ASK", "NONE")

    fun nameOf(code: Int): String = if (code >= 0 && code < NAMES.size) NAMES[code] else NAMES[NONE]

    /** The code for [name]; [NONE] for null or anything unknown. */
    fun codeOf(name: String?): Int {
        if (name == null) return NONE
        for (i in NAMES.indices) if (NAMES[i] == name) return i
        return NONE
    }

    /** True for the arms that serve music the listener does not have. */
    fun isNovel(code: Int): Boolean =
        code == RADIO || code == SIM || code == ARTIST || code == NEW || code == EDGE || code == ASK || code == BACK
}

/** Where a play was started from (4.1 `surface`). Codes are part of the event log: never renumber. */
object Surface {
    const val HOME_SHELF = 0
    const val DAILY_MIX = 1
    const val YOUR_MIX = 2
    const val NAMED_MIX = 3
    const val STATION = 4
    const val AUTOPLAY = 5
    const val ALBUM = 6
    const val PLAYLIST = 7
    const val LIKED = 8
    const val ARTIST = 9
    const val SEARCH = 10
    const val ASSISTANT = 11
    const val MANUAL_QUEUE = 12
    const val EXTERNAL = 13
    const val UNKNOWN = 14

    private val NAMES = arrayOf(
        "homeShelf", "dailyMix", "yourMix", "namedMix", "station", "autoplay", "album", "playlist",
        "liked", "artist", "search", "assistant", "manualQueue", "external", "unknown",
    )

    fun nameOf(code: Int): String = if (code >= 0 && code < NAMES.size) NAMES[code] else NAMES[UNKNOWN]

    fun codeOf(name: String?): Int {
        if (name == null) return UNKNOWN
        for (i in NAMES.indices) if (NAMES[i] == name) return i
        return UNKNOWN
    }
}
