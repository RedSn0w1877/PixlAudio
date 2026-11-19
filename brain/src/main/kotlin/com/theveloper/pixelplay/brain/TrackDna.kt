package com.theveloper.pixelplay.brain

/**
 * The pure pieces of a track's DNA (docs/brain-spec.md, 3.2 to 3.4): which year to believe, how popular, how energetic
 * and how happy it sounds, which language its title is in. Integers only where the value is stored (so both apps store
 * and compare it identically); doubles only inside a function, built from `+ - * /`, `sqrt` and [BrainMath].
 *
 * Unknown is a value: year 0, popularity -1, energy and valence -1, language 0. Nothing here guesses.
 */
object TrackDna {
    // Where a year came from. The smaller number wins a tie.
    const val YEAR_NONE = 0
    const val YEAR_MUSICBRAINZ = 1
    const val YEAR_ITUNES = 2
    const val YEAR_SPOTIFY = 3
    const val YEAR_YOUTUBE = 4
    const val YEAR_DEEZER = 5

    const val GAIN_UNKNOWN = -32768
    const val UNKNOWN = -1

    class YearClaim(val source: Int, val year: Int)

    class Year(val year: Int, val source: Int) {
        val known: Boolean get() = year != 0
    }

    /**
     * The earliest believable year over every source (3.3, "Year"). A claim before 1900 or after [maxYear] is noise
     * and is ignored. Deezer alone is never believed: its date is often a re-release (wrong era on 6 of 16 in the
     * probe), so a year that only Deezer gave stays unknown. [maxYear] is the current year plus one, passed in
     * because the core has no clock.
     */
    fun mergeYear(claims: List<YearClaim>, maxYear: Int): Year {
        var best = 0
        var bestSource = YEAR_NONE
        var other = false
        for (c in claims) {
            if (c.year < 1900 || c.year > maxYear) continue
            if (c.source != YEAR_DEEZER) other = true
            if (best == 0 || c.year < best || (c.year == best && c.source < bestSource)) {
                best = c.year
                bestSource = c.source
            }
        }
        if (best == 0) return Year(0, YEAR_NONE)
        if (!other) return Year(0, YEAR_NONE)
        return Year(best, bestSource)
    }

    /** The year of `2019`, `2019-03` or `2019-03-29`; 0 for anything else. */
    fun parseYear(text: String?): Int {
        if (text == null || text.length < 4) return 0
        var n = 0
        for (i in 0 until 4) {
            val d = text[i].code - 48
            if (d < 0 || d > 9) return 0
            n = n * 10 + d
        }
        if (text.length > 4 && text[4] in '0'..'9') return 0
        return if (n in 1900..2200) n else 0
    }

    // ── Popularity, 0..1000 ──────────────────────────────────────────────────

    private const val LOG10_2_BITS = 0x3FD34413509F79FFL

    private fun log10(x: Double): Double = BrainMath.log2(x) * Double.fromBits(LOG10_2_BITS)

    private fun clampPop(v: Long): Int = if (v < 0L) 0 else if (v > 1000L) 1000 else v.toInt()

    /** Deezer's `rank` (about 0 to 1,000,000) divided by 1,000; -1 when there is none. */
    fun popFromDeezerRank(rank: Long): Int = if (rank <= 0L) UNKNOWN else clampPop(rank / 1000L)

    /** A play count of the YouTube row ("47M plays"): `min(1000, round(1000 * log10(1 + plays) / 9.7))`. */
    fun popFromPlays(plays: Long): Int =
        if (plays <= 0L) UNKNOWN else clampPop(BrainMath.floorToLong(1000.0 * log10(1.0 + plays.toDouble()) / 9.7 + 0.5))

    /** ListenBrainz distinct listeners: `min(1000, round(1000 * log10(1 + users) / 5.3))`. */
    fun popFromListeners(users: Long): Int =
        if (users <= 0L) UNKNOWN else clampPop(BrainMath.floorToLong(1000.0 * log10(1.0 + users.toDouble()) / 5.3 + 0.5))

    /** The first source that knows, in the order Deezer, YouTube, ListenBrainz (3.3). */
    fun mergePopularity(deezer: Int, youtube: Int, listenBrainz: Int): Int =
        if (deezer >= 0) deezer else if (youtube >= 0) youtube else if (listenBrainz >= 0) listenBrainz else UNKNOWN

    /**
     * A YouTube Music play count as text ("47M plays", "1.2B views", "950K plays", "12,345 plays", "3.4K"): the number,
     * or 0 when the text holds none. Fractions are read to one decimal place.
     */
    fun parsePlayCount(text: String?): Long {
        if (text.isNullOrBlank()) return 0L
        var whole = 0L
        var frac = 0L
        var fracDigits = 0
        var seenDot = false
        var any = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c in '0'..'9') {
                any = true
                if (!seenDot) whole = whole * 10 + (c.code - 48)
                else if (fracDigits < 3) { frac = frac * 10 + (c.code - 48); fracDigits++ }
            } else if (c == '.' && any && !seenDot) seenDot = true
            else if (c == ',' && any && !seenDot) { /* thousands separator */ }
            else if (any) break
            i++
        }
        if (!any) return 0L
        var mult = 1L
        if (i < text.length) {
            when (text[i]) {
                'K', 'k' -> mult = 1_000L
                'M', 'm' -> mult = 1_000_000L
                'B', 'b' -> mult = 1_000_000_000L
                else -> {}
            }
        }
        var scale = 1L
        for (k in 0 until fracDigits) scale *= 10L
        return whole * mult + frac * mult / scale
    }

    // ── Energy, valence, moods (3.5) ─────────────────────────────────────────

    class Feel(val energy: Int, val valence: Int) {
        val known: Boolean get() = energy >= 0
    }

    private val NO_FEEL = Feel(UNKNOWN, UNKNOWN)

    /**
     * Energy and valence of a track: the tag-weighted mean of its tags' descriptors, then
     * `energy += clamp((gainDb + 9) * 4, -20, 20)` when the loudness is known (3.5). -1 when no tag has a descriptor.
     * [gainDbX10] is Deezer's `gain` times 10, or [GAIN_UNKNOWN].
     */
    fun feel(atlas: GenreAtlas, tags: Tags.Vector, gainDbX10: Int): Feel {
        var num = 0L
        var numV = 0L
        var den = 0L
        for (t in tags.tags) {
            val d = atlas.descriptor(t.atlasId) ?: continue
            num += t.q.toLong() * d.energy
            numV += t.q.toLong() * d.valence
            den += t.q.toLong()
        }
        if (den == 0L) return NO_FEEL
        var energy = ((2 * num + den) / (2 * den)).toInt()
        val valence = ((2 * numV + den) / (2 * den)).toInt()
        if (gainDbX10 != GAIN_UNKNOWN) {
            var nudge = BrainMath.floorDiv(((gainDbX10 + 90) * 4).toLong(), 10L).toInt()
            if (nudge < -20) nudge = -20
            if (nudge > 20) nudge = 20
            energy += nudge
        }
        if (energy < 0) energy = 0
        if (energy > 255) energy = 255
        return Feel(energy, if (valence > 255) 255 else valence)
    }

    /** Up to 4 mood ids: the ones the tags' descriptors name most, by summed tag weight (ties: smaller id). */
    fun moods(atlas: GenreAtlas, tags: Tags.Vector): IntArray {
        val sums = java.util.TreeMap<Int, Int>()
        for (t in tags.tags) {
            val d = atlas.descriptor(t.atlasId) ?: continue
            for (m in d.moods) sums[m] = (sums[m] ?: 0) + t.q
        }
        val order = sums.entries.sortedWith { a, b -> if (a.value != b.value) b.value.compareTo(a.value) else a.key.compareTo(b.key) }
        return IntArray(minOf(4, order.size)) { order[it].key }
    }

    // ── Language of the title (3.2 `scriptLang`) ─────────────────────────────

    const val SCRIPT_UNKNOWN = 0
    const val SCRIPT_VIETNAMESE = 1
    const val SCRIPT_KOREAN = 2
    const val SCRIPT_JAPANESE = 3
    const val SCRIPT_CHINESE = 4
    const val SCRIPT_LATIN = 5
    const val SCRIPT_CYRILLIC = 6
    const val SCRIPT_THAI = 7
    const val SCRIPT_ARABIC = 8
    const val SCRIPT_DEVANAGARI = 9

    /**
     * The language group of a title from the script ranges of its characters, with the artist's country (an ISO
     * 3166 code or empty) breaking a plain Latin title's tie. Vietnamese is told by the letters only Vietnamese uses
     * (ă đ ơ ư and the stacked-accent block); a title in plain ASCII is Latin unless the country says otherwise.
     */
    fun scriptLang(title: String, artistCountry: String): Int {
        var hangul = 0
        var kana = 0
        var han = 0
        var vi = 0
        var cyr = 0
        var thai = 0
        var arab = 0
        var deva = 0
        var latin = 0
        var i = 0
        while (i < title.length) {
            val c = Character.codePointAt(title, i)
            i += Character.charCount(c)
            when {
                c in 0xAC00..0xD7AF || c in 0x1100..0x11FF || c in 0x3130..0x318F -> hangul++
                c in 0x3040..0x30FF || c in 0x31F0..0x31FF || c in 0xFF66..0xFF9F -> kana++
                c in 0x4E00..0x9FFF || c in 0x3400..0x4DBF -> han++
                c in 0x1EA0..0x1EF9 || c == 0x102 || c == 0x103 || c == 0x110 || c == 0x111 || c == 0x1A0 || c == 0x1A1 || c == 0x1AF || c == 0x1B0 -> vi++
                c in 0x400..0x4FF -> cyr++
                c in 0xE00..0xE7F -> thai++
                c in 0x600..0x6FF -> arab++
                c in 0x900..0x97F -> deva++
                c in 0x41..0x5A || c in 0x61..0x7A || c in 0xC0..0x24F -> latin++
            }
        }
        if (hangul > 0) return SCRIPT_KOREAN
        if (kana > 0) return SCRIPT_JAPANESE
        if (han > 0) return SCRIPT_CHINESE
        if (vi > 0) return SCRIPT_VIETNAMESE
        if (thai > 0) return SCRIPT_THAI
        if (arab > 0) return SCRIPT_ARABIC
        if (deva > 0) return SCRIPT_DEVANAGARI
        if (cyr > 0) return SCRIPT_CYRILLIC
        if (latin == 0) return SCRIPT_UNKNOWN
        return when (artistCountry) {
            "VN" -> SCRIPT_VIETNAMESE
            "KR" -> SCRIPT_KOREAN
            "JP" -> SCRIPT_JAPANESE
            "CN", "TW", "HK" -> SCRIPT_CHINESE
            else -> SCRIPT_LATIN
        }
    }
}
