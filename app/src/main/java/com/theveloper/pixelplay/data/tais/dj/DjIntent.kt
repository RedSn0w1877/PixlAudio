package com.theveloper.pixelplay.data.tais.dj

/** What the user wants done with whatever [TaisMediaRouter] finds. */
enum class DjAction {
    PLAY,
    QUEUE,
    FIND
}

/**
 * Structured result of parsing a free-text DJ prompt ("play some chill acoustic songs").
 * [genres]/[moods] are the recognized keywords [TaisIntentParser] pulled out of the prompt;
 * [searchQuery] is whatever text was left over, used as a free-text fallback by
 * [TaisMediaRouter] when genre/mood matching comes up empty.
 */
data class DjIntent(
    val rawPrompt: String,
    val action: DjAction,
    val genres: List<String>,
    val moods: List<String>,
    val searchQuery: String
) {
    /** Every keyword worth querying with, genres first (more specific) then moods. */
    val queryTerms: List<String> get() = genres + moods
}
