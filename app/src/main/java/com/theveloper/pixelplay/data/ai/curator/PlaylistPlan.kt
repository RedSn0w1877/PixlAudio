package com.theveloper.pixelplay.data.ai.curator

import com.theveloper.pixelplay.data.ai.AiResponseCleaner
import com.theveloper.pixelplay.data.tais.dj.DjIntent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What the on-device model decides for a playlist request: attributes chosen from the library's
 * own vocabulary, never song ids. The app turns it into songs ([PlaylistPlanFiller]).
 */
data class PlaylistPlan(
    val genres: List<String> = emptyList(),
    val artists: List<String> = emptyList(),
    val moods: List<String> = emptyList(),
    /** 1 calm .. 5 intense, or null for "any". */
    val energy: Int? = null,
    /** 1 familiar favourites .. 5 rarely played, or null for "any". */
    val discovery: Int? = null,
    val eraFrom: Int? = null,
    val eraTo: Int? = null,
    val keywords: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = genres.isEmpty() && artists.isEmpty() && moods.isEmpty() && energy == null &&
            discovery == null && eraFrom == null && eraTo == null && keywords.isEmpty()
}

/** The genre and artist names the model may choose from (the library's own spelling). */
data class LibraryVocabulary(val genres: List<String>, val artists: List<String>)

/** Lenient parsing of the model's plan; anything it can't place in the vocabulary is dropped. Pure. */
object PlaylistPlanParser {
    val MOODS = listOf("chill", "energetic", "sad", "happy", "romantic", "workout", "party", "focus", "sleep")

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** The plan in [raw], or null when there's no usable JSON object in it. */
    fun parse(raw: String, vocabulary: LibraryVocabulary): PlaylistPlan? {
        val cleaned = AiResponseCleaner.cleanJsonResponse(raw)
        val objectText = AiResponseCleaner.extractJsonObject(cleaned) ?: return null
        val root = runCatching { json.parseToJsonElement(objectText).jsonObject }.getOrNull() ?: return null
        val plan = PlaylistPlan(
            genres = mapToVocabulary(strings(root["genres"]), vocabulary.genres).take(4),
            artists = mapToVocabulary(strings(root["artists"]), vocabulary.artists).take(6),
            moods = strings(root["moods"]).mapNotNull(::normalizeMood).distinct().take(3),
            energy = int(root["energy"])?.coerceIn(1, 5),
            discovery = int(root["discovery"])?.coerceIn(1, 5),
            eraFrom = int(root["eraFrom"])?.takeIf { it in YEARS },
            eraTo = int(root["eraTo"])?.takeIf { it in YEARS },
            keywords = strings(root["keywords"]).map { it.trim() }.filter { it.length in 2..40 }.distinct().take(4),
        )
        val (from, to) = plan.eraFrom to plan.eraTo
        return if (from != null && to != null && from > to) plan.copy(eraFrom = to, eraTo = from) else plan
    }

    /** The deterministic plan when the model's answer is unusable: the keywords Taizo's parser finds. */
    fun fromIntent(intent: DjIntent, vocabulary: LibraryVocabulary): PlaylistPlan = PlaylistPlan(
        genres = mapToVocabulary(intent.genres, vocabulary.genres).take(4),
        artists = vocabulary.artists.filter { artist -> containsWord(intent.rawPrompt, artist) }.take(6),
        moods = intent.moods.mapNotNull(::normalizeMood).distinct().take(3),
        keywords = intent.searchQuery.split(' ').map { it.trim() }.filter { it.length >= 3 }.take(4),
    )

    /** Case-insensitive match onto [vocabulary]: exact first, then "contains" either way. */
    fun mapToVocabulary(values: List<String>, vocabulary: List<String>): List<String> {
        val result = LinkedHashSet<String>()
        for (value in values) {
            val wanted = normalize(value)
            if (wanted.isEmpty()) continue
            val exact = vocabulary.firstOrNull { normalize(it) == wanted }
            val match = exact ?: vocabulary.firstOrNull { candidate ->
                val have = normalize(candidate)
                have.length >= 3 && wanted.length >= 3 && (have.contains(wanted) || wanted.contains(have))
            }
            if (match != null) result += match
        }
        return result.toList()
    }

    fun normalize(value: String): String =
        value.lowercase().replace(NON_ALNUM, " ").replace(SPACES, " ").trim()

    private fun normalizeMood(value: String): String? {
        val v = normalize(value)
        return MOODS.firstOrNull { it == v } ?: MOOD_SYNONYMS[v]
    }

    private fun containsWord(text: String, word: String): Boolean {
        val w = normalize(word)
        if (w.length < 3) return false
        return " ${normalize(text)} ".contains(" $w ")
    }

    private fun strings(element: JsonElement?): List<String> = when (element) {
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.takeIf(String::isNotBlank))
            .flatMap { it.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    private fun int(element: JsonElement?): Int? = when (element) {
        null, is JsonNull -> null
        is JsonPrimitive -> element.intOrNull ?: element.contentOrNull?.trim()?.take(4)?.toIntOrNull()
        is JsonObject, is JsonArray -> null
    }

    private val YEARS = 1900..2100
    private val NON_ALNUM = Regex("[^\\p{L}\\p{N}&]+")
    private val SPACES = Regex("\\s+")
    private val MOOD_SYNONYMS = mapOf(
        "relaxing" to "chill", "calm" to "chill", "mellow" to "chill", "relaxed" to "chill",
        "upbeat" to "energetic", "hype" to "energetic", "intense" to "energetic",
        "melancholy" to "sad", "melancholic" to "sad", "emotional" to "sad",
        "feel good" to "happy", "joyful" to "happy", "love" to "romantic",
        "gym" to "workout", "dance" to "party", "study" to "focus", "concentration" to "focus",
        "night" to "sleep", "sleepy" to "sleep",
    )
}

/** The song order the model answered with. Pure. */
object OrderResponseParser {
    /**
     * A permutation of 0 until [count]: the 1-based numbers in [raw] in the order given, with
     * out-of-range numbers and repeats dropped, and every number the model left out appended in
     * its original order — so a short, rambling or empty answer still yields every song once.
     */
    fun parse(raw: String, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val seen = BooleanArray(count)
        val order = ArrayList<Int>(count)
        for (match in NUMBER.findAll(raw)) {
            val index = match.value.toIntOrNull()?.minus(1) ?: continue
            if (index in 0 until count && !seen[index]) {
                seen[index] = true
                order += index
            }
        }
        for (i in 0 until count) if (!seen[i]) order += i
        return order
    }

    private val NUMBER = Regex("\\d{1,4}")
}
