package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.ai.AiHandler
import com.theveloper.pixelplay.data.ai.AiSystemPromptType
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the home screen greeting card shows — a headline plus a smaller, always-deterministic stats line. */
data class HomeGreeting(
    val headline: String,
    val subtitle: String
)

/**
 * Drives the contextual greeting shown at the top of the home screen. [HomeGreeting.headline]
 * always resolves instantly to a local, time-of-day + top-artist/genre template so the greeting
 * is never blank — an AI-generated replacement (if a provider is configured) streams in
 * afterward and is cached for the rest of the day via [UserPreferencesRepository] so opening
 * Home repeatedly doesn't re-hit the AI provider. [HomeGreeting.subtitle] is always computed
 * locally from listening stats — it's never AI-dependent, so it stays accurate even offline.
 */
@Singleton
class HomeGreetingStateHolder @Inject constructor(
    private val statsRepository: PlaybackStatsRepository,
    private val aiHandler: AiHandler,
    private val aiPreferencesRepository: AiPreferencesRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) {
    private val _greeting = MutableStateFlow(HomeGreeting(localHeadline(null, null), defaultSubtitle()))
    val greeting: StateFlow<HomeGreeting> = _greeting.asStateFlow()

    private var hasRequestedAiGreetingThisProcess = false

    // The expanded ("longer insight") text and its loading state, for the greeting card's
    // expand affordance. Unlike the headline this is fetched on demand, not cached across days —
    // it's meant to read fresh each time the user actually asks for more, not be a second
    // AI call that always fires alongside the headline.
    private val _expandedInsight = MutableStateFlow<String?>(null)
    val expandedInsight: StateFlow<String?> = _expandedInsight.asStateFlow()

    private val _isLoadingExpandedInsight = MutableStateFlow(false)
    val isLoadingExpandedInsight: StateFlow<Boolean> = _isLoadingExpandedInsight.asStateFlow()

    private var expandedInsightJob: kotlinx.coroutines.Job? = null

    /** Collapses the expanded insight back down, e.g. when the user taps the card shut again. */
    fun collapseInsight() {
        expandedInsightJob?.cancel()
        _isLoadingExpandedInsight.value = false
        _expandedInsight.value = null
    }

    fun expandInsight(scope: CoroutineScope, allSongs: List<Song>) {
        if (_isLoadingExpandedInsight.value || _expandedInsight.value != null) return
        expandedInsightJob = scope.launch {
            _isLoadingExpandedInsight.value = true
            try {
                val summary = runCatching {
                    statsRepository.loadSummary(StatsTimeRange.ALL, allSongs)
                }.getOrNull()
                val topArtist = summary?.topArtists?.firstOrNull()?.artist
                val topGenre = summary?.topGenres
                    ?.firstOrNull { it.genre != PlaybackStatsRepository.UNKNOWN_GENRE_LABEL }
                    ?.genre
                val totalPlayCount = summary?.totalPlayCount ?: 0

                val provider = AiProvider.fromString(aiPreferencesRepository.aiProvider.first())
                val apiKey = aiPreferencesRepository.getApiKey(provider).first()
                if (provider.requiresApiKey && apiKey.isBlank()) {
                    _expandedInsight.value = expandedFallback(allSongs.size, totalPlayCount, topArtist, topGenre)
                    return@launch
                }

                val prompt = buildString {
                    append("time_of_day=${dayPhase()}")
                    topArtist?.let { append(", top_artist=$it") }
                    topGenre?.let { append(", top_genre=$it") }
                    append(", total_plays=$totalPlayCount, library_size=${allSongs.size}")
                    append(". Write 2-3 sentences of listening insight, more detailed than a one-line greeting.")
                }

                val result = runCatching {
                    aiHandler.generateContent(prompt = prompt, type = AiSystemPromptType.GREETING)
                }.getOrNull()?.trim()?.trim('"')

                _expandedInsight.value = result?.takeIf { it.isNotBlank() }
                    ?: expandedFallback(allSongs.size, totalPlayCount, topArtist, topGenre)
            } finally {
                _isLoadingExpandedInsight.value = false
            }
        }
    }

    private fun expandedFallback(librarySize: Int, totalPlayCount: Int, topArtist: String?, topGenre: String?): String {
        val artistPart = topArtist?.let { " $it has been getting the most plays." } ?: ""
        val genrePart = topGenre?.let { " $it is the genre you're leaning on most." } ?: ""
        return "You've logged $totalPlayCount plays across $librarySize songs in your library.$artistPart$genrePart"
    }

    fun refresh(scope: CoroutineScope, allSongs: List<Song>) {
        if (allSongs.isEmpty()) return
        scope.launch {
            val summary = runCatching {
                statsRepository.loadSummary(StatsTimeRange.ALL, allSongs)
            }.getOrNull()
            val topArtist = summary?.topArtists?.firstOrNull()?.artist
            // "Unknown Genre" is a legitimate bucket on the Stats screen but nonsensical to
            // name here ("in the mood for some Unknown Genre?") — skip straight to the next
            // real genre instead of ever surfacing it in the greeting.
            val topGenre = summary?.topGenres
                ?.firstOrNull { it.genre != PlaybackStatsRepository.UNKNOWN_GENRE_LABEL }
                ?.genre
            val totalPlayCount = summary?.totalPlayCount ?: 0
            val subtitle = statsSubtitle(allSongs.size, totalPlayCount, topGenre)

            _greeting.value = HomeGreeting(localHeadline(topArtist, topGenre), subtitle)

            val today = LocalDate.now().toString()
            val cachedDate = userPreferencesRepository.homeGreetingDateFlow.first()
            val cachedText = userPreferencesRepository.homeGreetingTextFlow.first()
            if (cachedDate == today && cachedText.isNotBlank()) {
                _greeting.value = HomeGreeting(cachedText, subtitle)
                return@launch
            }
            if (hasRequestedAiGreetingThisProcess) return@launch

            val provider = AiProvider.fromString(aiPreferencesRepository.aiProvider.first())
            val apiKey = aiPreferencesRepository.getApiKey(provider).first()
            if (apiKey.isBlank()) return@launch

            hasRequestedAiGreetingThisProcess = true
            val prompt = buildString {
                append("time_of_day=${dayPhase()}")
                topArtist?.let { append(", top_artist=$it") }
                topGenre?.let { append(", top_genre=$it") }
                append(", total_plays=$totalPlayCount")
            }

            runCatching {
                aiHandler.generateContent(prompt = prompt, type = AiSystemPromptType.GREETING)
            }.onSuccess { text ->
                val clean = text.trim().trim('"').take(140)
                if (clean.isNotBlank()) {
                    _greeting.value = HomeGreeting(clean, subtitle)
                    userPreferencesRepository.setHomeGreeting(today, clean)
                }
            }.onFailure { e ->
                Timber.tag("HomeGreeting").w(e, "AI greeting generation failed, keeping local fallback")
            }
        }
    }

    private fun dayPhase(): String = when (LocalTime.now().hour) {
        in 5..10 -> "morning"
        in 11..16 -> "afternoon"
        in 17..21 -> "evening"
        else -> "night"
    }

    private fun localHeadline(topArtist: String?, topGenre: String?): String {
        val salutation = when (dayPhase()) {
            "morning" -> "Good morning"
            "afternoon" -> "Good afternoon"
            "evening" -> "Good evening"
            else -> "Still up?"
        }
        return when {
            topArtist != null -> "$salutation — ready for more $topArtist?"
            topGenre != null -> "$salutation — in the mood for some $topGenre?"
            else -> "$salutation — let's find something to play."
        }
    }

    private fun defaultSubtitle(): String = "Let's find your next favorite song."

    private fun statsSubtitle(librarySize: Int, totalPlayCount: Int, topGenre: String?): String = when {
        totalPlayCount > 0 && topGenre != null ->
            "$totalPlayCount plays logged · mostly $topGenre lately"
        totalPlayCount > 0 ->
            "$totalPlayCount plays across $librarySize songs in your library"
        librarySize > 0 ->
            "$librarySize songs in your library, ready to explore"
        else -> defaultSubtitle()
    }
}
