package com.theveloper.pixelplay.data.brain.source

import com.theveloper.pixelplay.brain.BrainText

/**
 * Whether a track a source returned is the track the brain asked about (docs/brain-spec.md, 3.3 B: "accept the first hit whose
 * folded title and primary artist match and whose duration is within 3 s"). Pure, so every source client and its tests share it.
 *
 * Titles are compared after [BrainText.cleanTitle] (so "Blinding Lights - Radio Edit" is "Blinding Lights") and only as folded
 * text; a version the listener's track does not have ("live", "remix", "instrumental") is a different recording and is refused.
 */
object TrackMatch {
    const val DURATION_TOLERANCE_MS = 3_000

    /** What the listener's track is. */
    class Want(val title: String, val artist: String, val durationMs: Int) {
        val clean: BrainText.CleanTitle = BrainText.cleanTitle(title)
        val artistFolded: String = BrainText.fold(artist).trim()
    }

    fun titleMatches(want: Want, gotTitle: String): Boolean {
        val got = BrainText.cleanTitle(gotTitle)
        if (got.title != want.clean.title) return false
        // A remix, a live take or a karaoke version is another recording unless the listener's own track is one too.
        val extra = got.identityFlags and want.clean.identityFlags.inv()
        return extra == 0
    }

    /** True when [gotNames] holds the artist: the same folded name, or one whole-word run inside the other ("A" in "A & B"). */
    fun artistMatches(want: Want, gotNames: List<String>): Boolean {
        val w = want.artistFolded
        if (w.isEmpty()) return false
        for (g in gotNames) {
            val f = BrainText.fold(g).trim()
            if (f.isEmpty()) continue
            if (f == w) return true
            val (short, long) = if (f.length <= w.length) f to w else w to f
            if (short.length >= 3 && " $long ".contains(" $short ")) return true
        }
        return false
    }

    /** Unknown on either side is close enough; otherwise within [DURATION_TOLERANCE_MS]. */
    fun durationClose(wantMs: Int, gotMs: Int): Boolean {
        if (wantMs <= 0 || gotMs <= 0) return true
        val d = if (wantMs > gotMs) wantMs - gotMs else gotMs - wantMs
        return d <= DURATION_TOLERANCE_MS
    }

    /** A MusicBrainz recording's disambiguation ("live, 2024-09-07: Sao Paulo") that names a version the listener's track lacks. */
    fun disambiguationRefuses(want: Want, disambiguation: String): Boolean {
        if (disambiguation.isBlank()) return false
        val flags = BrainText.cleanTitle("x ($disambiguation)").versionFlags and BrainText.FLAG_REMASTER.inv()
        // "live, 2024-09-07: ..." starts with the word; cleanTitle also reads "live" inside the group.
        val firstWord = BrainText.fold(disambiguation).trim().substringBefore(' ')
        val named = flags or when (firstWord) {
            "live" -> BrainText.FLAG_LIVE
            "remix" -> BrainText.FLAG_REMIX
            "instrumental" -> BrainText.FLAG_INSTRUMENTAL
            "karaoke" -> BrainText.FLAG_KARAOKE
            "acoustic" -> BrainText.FLAG_ACOUSTIC
            else -> 0
        }
        return named and want.clean.identityFlags.inv() != 0
    }
}
