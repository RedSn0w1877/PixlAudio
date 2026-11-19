package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.GenreAtlas

/**
 * Which atlas names the listener may read. The atlas is data (CC0, byte-for-byte as published), and a few real genres carry a word the
 * app never says ("atmospheric ..." holds "atmos"). Such a node is treated as absent in everything the listener sees (cards, Search
 * tiles, island names, "A step sideways"): never renamed, never guessed. The check is by substring, like the copy test.
 */
object AtlasNames {
    private val forbidden = listOf(
        "lossless", "hi-res", "hires", "dolby", "atmos", "360 reality", "claude", "anthropic", "binilyrics", "enhanced", "learn",
    )

    /** True when [name] may be shown. */
    fun isShown(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val lower = name.lowercase()
        for (w in forbidden) if (w in lower) return false
        return true
    }

    /** The genre's name when it may be shown, else null. */
    fun genreName(atlas: GenreAtlas, id: Int): String? = atlas.genre(id)?.name?.takeIf(::isShown)
}
