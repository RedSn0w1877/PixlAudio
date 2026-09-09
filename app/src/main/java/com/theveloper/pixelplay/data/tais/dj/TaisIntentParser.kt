package com.theveloper.pixelplay.data.tais.dj

import javax.inject.Inject
import javax.inject.Singleton

/**
 * TAIS Engine 3's prompt-to-[DjIntent] parser.
 *
 * This is the "lightweight regex/keyword rules" option from the spec, not local LLM inference —
 * there's no on-device language model in this repo, and a keyword parser is genuinely enough to
 * cover prompts like "play some chill acoustic songs" or "find energetic rock". If a local LLM
 * gets added later for this, it should sit behind the same [parse] signature so
 * [com.theveloper.pixelplay.data.tais.dj.TaisDjEngine] and the chat UI don't need to change.
 */
@Singleton
class TaisIntentParser @Inject constructor() {

    fun parse(prompt: String): DjIntent {
        val normalized = normalizeRequest(prompt)
        val action = detectAction(normalized)
        val phrase = ACTION_PHRASES.sortedByDescending { it.length }
            .firstOrNull { startsWithPhrase(normalized, it) }
        val request = (phrase?.let { normalized.removePrefix(it) } ?: normalized).trim()
            .removePrefix("some ").removePrefix("me some ").trim().trim('"')
        val parts = request.split(Regex("""\s+by\s+"""), limit = 2)
        val description = parts.first()
        val artist = parts.getOrNull(1).orEmpty()
        val genres = GENRE_KEYWORDS.filter { containsWord(description, it) }
        val moods = MOOD_KEYWORDS.filterKeys { containsWord(description, it) }.values.distinct()
        val withoutKeywords = (GENRE_KEYWORDS + MOOD_KEYWORDS.keys).fold(description) { text, word -> removeWord(text, word) }
        val remaining = FILLER_WORDS.fold(withoutKeywords) { text, word -> removeWord(text, word) }
            .replace(Regex("""\s+"""), " ").trim()
        val isDescription = remaining.isBlank() && (genres.isNotEmpty() || moods.isNotEmpty()) &&
            !prompt.contains('"') && description !in setOf("love", "night", "happy", "sad")
        // Keep exact titles such as "The Night We Met" or "Love Story" intact. Keyword
        // extraction must not silently turn them into a sleep/romance playlist.
        val query = if (isDescription) artist else listOf(description, artist)
            .filter { it.isNotBlank() }.joinToString(" ").trim()
        return DjIntent(prompt, action,
            if (isDescription) genres else emptyList(),
            if (isDescription) moods else emptyList(), query)
    }

    fun isMediaRequest(prompt: String): Boolean {
        val normalized = normalizeRequest(prompt)
        if (normalized.isBlank()) return false
        if (ACTION_PHRASES.any { startsWithPhrase(normalized, it) }) return true
        // Genre mentions in questions are conversation, not playback commands.
        if (normalized.endsWith('?') || QUESTION_PREFIXES.any { startsWithPhrase(normalized, it) }) return false
        val parsed = parse(normalized)
        return parsed.searchQuery.isBlank() && (parsed.genres.isNotEmpty() || parsed.moods.isNotEmpty())
    }

    private fun normalizeRequest(prompt: String): String = prompt.trim().lowercase(java.util.Locale.ROOT)
        .replace(Regex("""^(?:(?:can|could|would) you\s+)?(?:please\s+)?"""), "")
        .removeSuffix(" please").trim()

    private fun startsWithPhrase(text: String, phrase: String) = text == phrase || text.startsWith("$phrase ")

    private fun detectAction(normalized: String): DjAction = when {
        listOf("queue", "add").any { startsWithPhrase(normalized, it) } -> DjAction.QUEUE
        listOf("find", "search", "look for").any { startsWithPhrase(normalized, it) } -> DjAction.FIND
        else -> DjAction.PLAY
    }

    private fun containsWord(text: String, word: String): Boolean =
        Regex("(?<!\\w)${Regex.escape(word)}(?!\\w)").containsMatchIn(text)

    private fun removeWord(text: String, word: String): String =
        text.replace(Regex("(?<!\\w)${Regex.escape(word)}(?!\\w)"), " ")

    companion object {
        private val QUESTION_PREFIXES = listOf("what", "why", "who", "when", "where", "how", "tell me", "explain", "is", "does", "do")
        private val ACTION_PHRASES = listOf(
            "play some", "play me some", "play", "queue up", "queue", "add", "find me", "find", "search for", "search", "look for"
        )

        // Directly queryable via MusicRepository.getMusicByGenre / SpotifyRepository search.
        private val GENRE_KEYWORDS = listOf(
            "rock", "pop", "acoustic", "jazz", "classical", "electronic", "edm", "hip hop", "hip-hop",
            "rap", "metal", "country", "folk", "blues", "reggae", "r&b", "rnb", "soul", "indie",
            "punk", "lofi", "lo-fi", "ambient", "techno", "house", "disco", "funk", "k-pop", "kpop"
        )

        // Mood words map to a rough genre-shaped query term for the offline/online search below —
        // there's no mood metadata in Room, so "chill" effectively becomes a search keyword rather
        // than a structured filter. Kept as a separate list from GENRE_KEYWORDS because moods are
        // never valid getMusicByGenre ids on their own.
        private val MOOD_KEYWORDS = mapOf(
            "chill" to "chill", "relaxing" to "chill", "calm" to "chill", "mellow" to "chill",
            "energetic" to "energetic", "upbeat" to "energetic", "hype" to "energetic",
            "sad" to "sad", "melancholy" to "sad", "emotional" to "sad",
            "happy" to "happy", "feel good" to "happy",
            "romantic" to "romantic", "love" to "romantic",
            "workout" to "workout", "gym" to "workout",
            "party" to "party", "dance" to "party",
            "focus" to "focus", "study" to "focus", "concentration" to "focus",
            "sleep" to "sleep", "night" to "sleep"
        )

        private val FILLER_WORDS = listOf(
            "some", "songs", "song", "music", "tracks", "track", "please", "me", "a", "few", "the"
        )
    }
}
