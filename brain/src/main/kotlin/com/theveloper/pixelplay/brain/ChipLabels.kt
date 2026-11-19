package com.theveloper.pixelplay.brain

/**
 * What a radio's tuning chip says about the music inside it (docs/brain-spec.md, 3.4 tag source 4,
 * stage 2a: "chips mapped to tags and stored for stage 2b").
 *
 * A YouTube Music radio offers chips such as "Popular", "Deep cuts", "V-pop", "2010s", "Workout". Only
 * some of them describe the music: a genre, a decade, a mood. The rest ("Popular", "Discover", "All")
 * only steer the radio. Stage 2b's genre atlas turns a [Kind.GENRE] label into an atlas id; until then
 * the folded label is what is stored, and an unknown label is [Kind.UNKNOWN], never a guessed genre
 * (principle 7: unknown stays unknown).
 */
object ChipLabels {
    enum class Kind { GENRE, DECADE, MOOD, STEER, UNKNOWN }

    /** A classified chip: [key] is the folded label; for a decade, `"2010s"`. */
    class Chip(val kind: Kind, val key: String)

    private val STEER = folded(
        "all", "popular", "deep cuts", "discover", "familiar", "new releases", "recent", "trending", "hits", "unplayed",
    )
    private val MOODS = folded(
        "party", "romance", "workout", "chill", "sad", "happy", "focus", "sleep", "energize", "feel good", "relax",
        "commute", "gaming", "love", "dance", "study",
    )
    // Labels that name a genre in the words YouTube Music uses. The atlas of stage 2b maps them to ids.
    private val GENRES = folded(
        "pop", "adult pop", "alternative", "electronic", "rock", "hip hop", "hip-hop", "rap", "r&b", "r&b & soul", "soul",
        "country", "jazz", "blues", "classical", "metal", "folk", "indie", "latin", "reggae", "k-pop", "j-pop", "v-pop",
        "c-pop", "dance & electronic", "indie & alternative", "punk", "disco", "funk", "house", "techno", "ambient",
        "soundtrack", "gospel", "afrobeats", "bollywood", "opm", "mandopop", "cantopop",
    )

    // The tables are written in the labels' own words and folded the way a label is before the lookup.
    private fun folded(vararg labels: String): Set<String> = labels.mapTo(HashSet()) { BrainText.fold(it).trim() }

    /** Classifies one chip label. Case, accents and spacing do not matter. */
    fun classify(label: String): Chip {
        val key = BrainText.fold(label).trim()
        if (key.isEmpty()) return Chip(Kind.UNKNOWN, "")
        if (key in STEER) return Chip(Kind.STEER, key)
        val decade = decadeOf(key)
        if (decade != null) return Chip(Kind.DECADE, decade)
        if (key in MOODS) return Chip(Kind.MOOD, key)
        if (key in GENRES) return Chip(Kind.GENRE, key)
        return Chip(Kind.UNKNOWN, key)
    }

    /** "2010s", "1990s", "80s", "'90s": the decade as `"2010s"`; null for anything else. */
    fun decadeOf(key: String): String? {
        val k = key.trim().removePrefix("'")
        if (!k.endsWith("s") || k.length < 3) return null
        val digits = k.substring(0, k.length - 1)
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val n = digits.toInt()
        val year = when {
            digits.length == 4 -> n
            digits.length == 2 -> if (n >= 30) 1900 + n else 2000 + n
            else -> return null
        }
        if (year < 1900 || year > 2090 || year % 10 != 0) return null
        return "${year}s"
    }
}
