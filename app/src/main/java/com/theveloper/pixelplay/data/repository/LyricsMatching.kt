package com.theveloper.pixelplay.data.repository

import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.utils.MultiLangRomanizer
import java.io.File
import java.text.Normalizer
import java.util.Locale

internal enum class RemoteLyricsMatchMode {
    AUTOMATIC,
    CANDIDATE
}

/**
 * Title/artist/version matching shared by every online lyrics catalog, so LRCLIB and
 * BiniLyrics accept and reject the same recordings. Pure functions; no I/O.
 */
internal object LyricsMatching {
    internal val BRACKETED_QUALIFIER_REGEX = Regex("""[\(\[\{\uFF08\uFF3B\uFF5B\u3010\u300E\u300C\u3014\u3008\u300A]([^)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B]*)[\)\]\}\uFF09\uFF3D\uFF5D\u3011\u300F\u300D\u3015\u3009\u300B]""")
    internal val FEATURE_QUALIFIER_REGEX = Regex("""\b(feat(?:uring)?|ft)\.?\b""", RegexOption.IGNORE_CASE)
    internal val TITLE_SEPARATOR_REGEX = Regex("""\s*[-\u2013\u2014:\uFF0D\u00B7\u30FB]\s*""")
    internal val TIMING_VARIANT_KEYWORDS = setOf(
        "remix",
        "mix",
        "mashup",
        "bootleg",
        "edit",
        "extended",
        "radio",
        "club",
        "vip",
        "dub",
        "live",
        "acoustic",
        "unplugged",
        "sped",
        "slowed",
        "nightcore",
        "instrumental",
        "karaoke",
        "cover",
        "demo",
        "version",
        "rework",
        "flip",
        "refix",
        "opening",
        "ending",
        "op",
        "ed",
        "theme",
        "tv",
        "size",
        "ver",
        "full",
        "movie",
        "ost",
        "soundtrack",
        "background",
        "bgm",
        "short",
        "long",
        "reprise",
        "intro",
        "outro",
        "medley",
        "bonus"
    )
    internal val TITLE_DROP_QUALIFIERS = setOf(
        "explicit",
        "clean",
        "mono",
        "stereo",
        "official audio",
        "official video",
        "hi-res",
        "high-res",
        "mqa"
    )
    internal val UNKNOWN_ARTISTS = setOf(
        "",
        "<unknown>",
        "unknown",
        "unknown artist",
        "various artists",
        "various"
    )
    internal val ARTIST_CONNECTOR_TOKENS = setOf(
        "feat",
        "featuring",
        "ft",
        "and",
        "with",
        "x",
        "vs",
        "the"
    )

    internal fun titleMatchScore(songTitle: String, responseTitle: String, mode: RemoteLyricsMatchMode): Int? {
        val songBase = baseTitleForMatching(songTitle)
        val responseBase = baseTitleForMatching(responseTitle)
        if (songBase.isBlank() || responseBase.isBlank()) return null

        if (songBase == responseBase) return 70

        // Attempt Romanized match for non-Latin scripts
        if (MultiLangRomanizer.isScriptThatNeedsRomanization(songBase) || 
            MultiLangRomanizer.isScriptThatNeedsRomanization(responseBase)) {
            val songRoman = normalizeForMatch(romanizeForMatch(songBase))
            val responseRoman = normalizeForMatch(romanizeForMatch(responseBase))
            if (songRoman == responseRoman && songRoman.isNotBlank()) return 65
        }

        val songTokens = matchTokens(songBase)
        val responseTokens = matchTokens(responseBase)
        if (songTokens.isEmpty() || responseTokens.isEmpty()) return null

        if (songTokens.size == 1 || responseTokens.size == 1) {
            if (songTokens == responseTokens) return 60
            
            // Fuzzy match for single token CJK: if one contains the other
            val s1 = songBase.replace(" ", "")
            val s2 = responseBase.replace(" ", "")
            if (s1.isNotBlank() && s2.isNotBlank()) {
                if (s1.contains(s2) || s2.contains(s1)) return 55
            }
            
            return null
        }

        if (containsWholePhrase(responseBase, songBase) || containsWholePhrase(songBase, responseBase)) {
            return if (mode == RemoteLyricsMatchMode.AUTOMATIC) 58 else 54
        }

        val overlap = songTokens.intersect(responseTokens).size
        val songCoverage = overlap.toDouble() / songTokens.size
        val responseCoverage = overlap.toDouble() / responseTokens.size
        val requiredSongCoverage = if (mode == RemoteLyricsMatchMode.AUTOMATIC) 0.85 else 0.75
        val requiredResponseCoverage = if (mode == RemoteLyricsMatchMode.AUTOMATIC) 0.70 else 0.55

        return if (songCoverage >= requiredSongCoverage && responseCoverage >= requiredResponseCoverage) {
            45
        } else {
            null
        }
    }

    internal fun artistMatchScore(songArtist: String, responseArtist: String): Int? {
        if (isUnknownArtist(songArtist)) return 0

        val songBase = normalizeForMatch(songArtist)
        val responseBase = normalizeForMatch(responseArtist)
        if (songBase.isBlank() || responseBase.isBlank()) return null

        if (songBase == responseBase) return 30
        
        // Attempt Romanized match
        if (MultiLangRomanizer.isScriptThatNeedsRomanization(songBase) || 
            MultiLangRomanizer.isScriptThatNeedsRomanization(responseBase)) {
            val songRoman = normalizeForMatch(romanizeForMatch(songBase))
            val responseRoman = normalizeForMatch(romanizeForMatch(responseBase))
            if (songRoman == responseRoman && songRoman.isNotBlank()) return 28
        }

        if (containsWholePhrase(responseBase, songBase) || containsWholePhrase(songBase, responseBase)) {
            return 22
        }

        val songTokens = artistTokens(songBase)
        val responseTokens = artistTokens(responseBase)
        if (songTokens.isEmpty() || responseTokens.isEmpty()) return null

        val overlap = songTokens.intersect(responseTokens).size
        val smallerArtistCoverage = overlap.toDouble() / minOf(songTokens.size, responseTokens.size)
        return if (smallerArtistCoverage >= 0.5) 12 else null
    }

    internal fun baseTitleForMatching(title: String): String {
        var base = title.replace(Regex("""^\s*\d{1,3}\s*[\._-]\s+"""), "")

        base = BRACKETED_QUALIFIER_REGEX.replace(base) { match ->
            val qualifier = match.groupValues.getOrNull(1).orEmpty()
            if (shouldDropTitleQualifier(qualifier)) " " else " $qualifier "
        }

        var parts = TITLE_SEPARATOR_REGEX.split(base)
        while (parts.size > 1 && shouldDropTitleQualifier(parts.last())) {
            parts = parts.dropLast(1)
        }

        return normalizeForMatch(parts.joinToString(" "))
    }

    internal fun shouldDropTitleQualifier(value: String): Boolean {
        val normalized = normalizeForMatch(value)
        if (normalized.isBlank()) return true
        return FEATURE_QUALIFIER_REGEX.containsMatchIn(value) ||
            timingVariantTokens(value).isNotEmpty() ||
            normalized in TITLE_DROP_QUALIFIERS
    }

    internal fun timingVariantTokens(value: String): Set<String> {
        val normalized = normalizeForMatch(value)
        if (normalized.isBlank()) return emptySet()

        val tokens = matchTokens(normalized)
        val variants = tokens
            .filter { it in TIMING_VARIANT_KEYWORDS }
            .toMutableSet()

        if (Regex("""\bmash\s+up\b""").containsMatchIn(normalized)) {
            variants += "mashup"
        }
        if ("versus" in tokens || "vs" in tokens) {
            variants += "mashup"
        }

        return variants
    }

    internal fun timingVariantTokensFromFileName(song: Song): Set<String> {
        val fileName = songFileName(song)
        if (fileName.isBlank()) return emptySet()

        val variants = BRACKETED_QUALIFIER_REGEX
            .findAll(fileName)
            .flatMap { match -> timingVariantTokens(match.groupValues.getOrNull(1).orEmpty()) }
            .toMutableSet()

        val titleBase = baseTitleForMatching(song.title)
        if (titleBase.isBlank()) return variants

        TITLE_SEPARATOR_REGEX.split(fileName).forEach { part ->
            val normalizedPart = normalizeForMatch(part)
            if (normalizedPart.startsWith("$titleBase ")) {
                variants += timingVariantTokens(normalizedPart.removePrefix(titleBase).trim())
            }
        }

        return variants
    }

    internal fun songFileName(song: Song): String {
        if (song.path.isBlank()) return ""
        return runCatching { File(song.path).nameWithoutExtension }.getOrDefault("")
    }

    internal fun artistTokens(normalizedArtist: String): Set<String> =
        matchTokens(normalizedArtist)
            .filterNot { it in ARTIST_CONNECTOR_TOKENS }
            .toSet()

    internal fun matchTokens(normalizedValue: String): Set<String> =
        normalizedValue
            .split(' ')
            .filter { it.isNotBlank() }
            .toSet()

    internal fun containsWholePhrase(haystack: String, needle: String): Boolean {
        if (needle.isBlank()) return false
        return Regex("""(?:^|\s)${Regex.escape(needle)}(?:\s|$)""").containsMatchIn(haystack)
    }

    internal fun normalizeForMatch(value: String): String {
        val withoutDiacritics = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")

        return withoutDiacritics
            .replace("&", " and ")
            .replace(Regex("""[\u2019'`]"""), "")
            .replace(Regex("""[^\p{L}\p{N}]+"""), " ")
            .trim()
            .replace(Regex("""\s+"""), " ")
    }

    internal fun isUnknownArtist(value: String): Boolean =
        normalizeForMatch(value) in UNKNOWN_ARTISTS

    internal fun romanizeForMatch(text: String): String {
        return when {
            MultiLangRomanizer.isJapanese(text) -> MultiLangRomanizer.romanizeJapanese(text) ?: text
            MultiLangRomanizer.isChinese(text) -> MultiLangRomanizer.romanizeChinese(text) ?: text
            MultiLangRomanizer.isKorean(text) -> MultiLangRomanizer.romanizeKorean(text)
            else -> text
        }
    }

    /**
     * Version descriptors (remix, live, acoustic, sped up...) of the song and the catalog
     * title must agree exactly: "Blinding Lights (Remix)" never matches the album version.
     */
    internal fun variantsCompatible(song: Song, candidateTitle: String): Boolean {
        val songVariants = timingVariantTokens(song.title) + timingVariantTokensFromFileName(song)
        val candidateVariants = timingVariantTokens(candidateTitle)
        if (songVariants.isEmpty()) return candidateVariants.isEmpty()
        return candidateVariants == songVariants
    }
}
