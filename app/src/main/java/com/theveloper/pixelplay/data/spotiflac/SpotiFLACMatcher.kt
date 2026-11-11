package com.theveloper.pixelplay.data.spotiflac

import java.text.Normalizer
import kotlin.math.abs

/**
 * Pure ISRC + metadata candidate scorer for SpotiFLAC track resolution.
 */
object SpotiFLACMatcher {

    /** Score awarded to an exact ISRC match (with compatible duration). */
    const val EXACT_ISRC_SCORE = 200

    /** Minimum score required to accept a candidate when no ISRC match is present. */
    const val MINIMUM_ACCEPT_SCORE = 85

    /** Maximum allowed duration difference (ms) for metadata-only matching. */
    const val MAX_METADATA_DURATION_DELTA_MS = 10_000L

    /** Maximum allowed duration difference (ms) when ISRCs match. */
    const val MAX_ISRC_DURATION_DELTA_MS = 20_000L

    private val VARIANT_MARKERS = listOf(
        "live", "remix", "karaoke", "instrumental", "acoustic",
        "sped up", "slowed", "nightcore", "demo", "commentary"
    )

    private val NON_ALPHANUMERIC_REGEX = Regex("[^a-z0-9\\s]+")
    private val WHITESPACE_REGEX = Regex("\\s+")
    private val DIACRITICS_REGEX = Regex("\\p{InCombiningDiacriticalMarks}+")

    /**
     * Normalizes an ISRC string (strips hyphens/spaces, uppercases, validates 12 alphanumeric chars).
     */
    fun normalizeIsrc(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = buildString(12) {
            for (ch in raw.uppercase()) {
                if (ch in 'A'..'Z' || ch in '0'..'9') append(ch)
            }
        }
        return cleaned.takeIf { it.length == 12 }
    }

    /**
     * Normalizes a title or artist string for comparison.
     */
    fun cleanString(text: String): String {
        val folded = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS_REGEX, "")
        val replaced = folded.replace(NON_ALPHANUMERIC_REGEX, " ")
        return replaced.replace(WHITESPACE_REGEX, " ").trim()
    }

    /**
     * Scores a candidate against the query. Returns `null` if the candidate is rejected.
     */
    fun scoreCandidate(candidate: SpotiFLACCandidate, query: SpotiFLACTrackQuery): Int? {
        if (!candidate.playable) return null

        val queryIsrc = normalizeIsrc(query.isrc)
        val candidateIsrc = normalizeIsrc(candidate.isrc)

        if (queryIsrc != null && candidateIsrc != null) {
            if (queryIsrc == candidateIsrc) {
                if (query.durationMs > 0 && candidate.durationMs > 0) {
                    val delta = abs(query.durationMs - candidate.durationMs)
                    if (delta > MAX_ISRC_DURATION_DELTA_MS) return null
                }
                return EXACT_ISRC_SCORE
            }
        }

        val qTitle = cleanString(query.title)
        val cTitle = cleanString(candidate.title)
        if (qTitle.isEmpty() || cTitle.isEmpty()) return null

        // Guard against unwanted variant mismatches (e.g. Live/Remix/Instrumental when original isn't).
        for (marker in VARIANT_MARKERS) {
            val queryHas = containsWordSequence(qTitle, marker)
            val candHas = containsWordSequence(cTitle, marker)
            if (queryHas != candHas) return null
        }

        // Duration guard for metadata-only matches.
        var durationScore = 10
        if (query.durationMs > 0 && candidate.durationMs > 0) {
            val delta = abs(query.durationMs - candidate.durationMs)
            if (delta > MAX_METADATA_DURATION_DELTA_MS) return null
            durationScore = when {
                delta <= 1_500L -> 30
                delta <= 3_000L -> 24
                delta <= 5_000L -> 16
                else -> 8
            }
        }

        val titleScore = similarityScore(qTitle, cTitle, maxPoints = 45)
        if (titleScore < 25) return null

        val qArtist = cleanString(query.artist)
        val cArtist = cleanString(candidate.artist)
        val artistScore = similarityScore(qArtist, cArtist, maxPoints = 35)
        if (artistScore < 15) return null

        var albumBonus = 0
        val rawQueryAlbum = query.album
        val rawCandAlbum = candidate.album
        if (!rawQueryAlbum.isNullOrBlank() && !rawCandAlbum.isNullOrBlank()) {
            val qAlbum = cleanString(rawQueryAlbum)
            val cAlbum = cleanString(rawCandAlbum)
            if (qAlbum.isNotEmpty() && cAlbum.isNotEmpty()) {
                if (qAlbum == cAlbum) {
                    albumBonus = 10
                } else if (qAlbum.contains(cAlbum) || cAlbum.contains(qAlbum)) {
                    albumBonus = 5
                }
            }
        }

        val total = titleScore + artistScore + durationScore + albumBonus
        return total.takeIf { it >= MINIMUM_ACCEPT_SCORE }
    }

    /**
     * Picks the highest-scoring acceptable candidate from [candidates].
     */
    fun selectBestCandidate(
        candidates: List<SpotiFLACCandidate>,
        query: SpotiFLACTrackQuery
    ): SpotiFLACCandidate? {
        var best: SpotiFLACCandidate? = null
        var bestScore = Int.MIN_VALUE
        for (candidate in candidates) {
            val score = scoreCandidate(candidate, query) ?: continue
            if (score > bestScore) {
                bestScore = score
                best = candidate
            }
        }
        return best
    }

    private fun containsWordSequence(haystack: String, needle: String): Boolean {
        val paddedHaystack = " $haystack "
        val paddedNeedle = " $needle "
        return paddedHaystack.contains(paddedNeedle)
    }

    private fun similarityScore(lhs: String, rhs: String, maxPoints: Int): Int {
        if (lhs.isEmpty() || rhs.isEmpty()) return 0
        if (lhs == rhs) return maxPoints

        val lhsStripped = stripFeatSuffix(lhs)
        val rhsStripped = stripFeatSuffix(rhs)
        if (lhsStripped.isNotEmpty() && lhsStripped == rhsStripped) {
            return maxPoints - 2
        }

        if (lhsStripped.contains(rhsStripped) || rhsStripped.contains(lhsStripped)) {
            return (maxPoints * 85) / 100
        }

        val lhsTokens = lhsStripped.split(' ').filter { it.isNotEmpty() }.toSet()
        val rhsTokens = rhsStripped.split(' ').filter { it.isNotEmpty() }.toSet()
        if (lhsTokens.isEmpty() || rhsTokens.isEmpty()) return 0

        val overlap = lhsTokens.intersect(rhsTokens).size
        val union = lhsTokens.union(rhsTokens).size
        if (overlap == 0 || union == 0) return 0

        val jaccard = overlap.toDouble() / union.toDouble()
        return (jaccard * maxPoints.toDouble()).toInt()
    }

    private fun stripFeatSuffix(text: String): String {
        val markers = listOf(" feat ", " ft ", " featuring ", " with ")
        var result = text
        for (marker in markers) {
            val idx = result.indexOf(marker)
            if (idx >= 0) {
                result = result.substring(0, idx)
            }
        }
        return result.trim()
    }
}
