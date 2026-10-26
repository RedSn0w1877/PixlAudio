package com.theveloper.pixelplay.presentation.viewmodel

import com.theveloper.pixelplay.data.ai.AiHandler
import com.theveloper.pixelplay.data.ai.AiSystemPromptType
import com.theveloper.pixelplay.data.ai.local.OnDevicePrompts
import com.theveloper.pixelplay.data.history.RecentPlay
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/** What the home screen greeting card shows: a headline plus one short, always-local line under it. */
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
 * locally from the listener's day (what just played, something new, a streak, the hour), so it
 * stays accurate even offline.
 *
 * Both lines are short on purpose (see [HomeGreetingText]) and never state play counts or the size of the
 * library; a generated line that is too long or reads like a stat is dropped for the local one.
 */
@Singleton
class HomeGreetingStateHolder @Inject constructor(
    private val statsRepository: PlaybackStatsRepository,
    private val aiHandler: AiHandler,
    private val aiAvailability: AiAvailabilityStateHolder,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val listeningInsights: com.theveloper.pixelplay.data.history.ListeningInsights,
) {
    private val _greeting = MutableStateFlow(initialGreeting())
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
                val ctx = loadContext(allSongs)

                // On-device (the default) counts as configured: it was locked out here before
                // because it has no API key. Not ready (no Nano, cloud key missing) -> local text.
                if (!aiAvailability.current().isUsable) {
                    _expandedInsight.value = HomeGreetingText.insightFallback(ctx)
                    return@launch
                }

                val prompt = buildString {
                    append(promptFacts(ctx))
                    append(". Write one or two short sentences (under ${HomeGreetingText.MAX_INSIGHT_CHARS} characters in all) ")
                    append("about their listening that read like a friend glancing at their day. ")
                    append("Never mention play counts, library size or song totals.")
                }

                val result = runCatching {
                    aiHandler.generateContent(
                        prompt = prompt,
                        type = AiSystemPromptType.GREETING,
                        maxOutputTokens = OnDevicePrompts.INSIGHT_MAX_OUTPUT,
                    )
                }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()

                _expandedInsight.value = HomeGreetingText.acceptGeneratedInsight(result)
                    ?: HomeGreetingText.insightFallback(ctx)
            } finally {
                _isLoadingExpandedInsight.value = false
            }
        }
    }

    fun refresh(scope: CoroutineScope, allSongs: List<Song>) {
        if (allSongs.isEmpty()) return
        scope.launch {
            val ctx = loadContext(allSongs)
            val subtitle = HomeGreetingText.subtitle(ctx)

            _greeting.value = HomeGreeting(HomeGreetingText.headline(ctx), subtitle)

            val today = LocalDate.now().toString()
            val cachedDate = userPreferencesRepository.homeGreetingDateFlow.first()
            // A line cached by an older build may be long or stat-like: only a fitting one is reused.
            val cachedText = HomeGreetingText.acceptGeneratedHeadline(userPreferencesRepository.homeGreetingTextFlow.first())
            if (cachedDate == today && cachedText != null) {
                _greeting.value = HomeGreeting(cachedText, subtitle)
                return@launch
            }
            if (hasRequestedAiGreetingThisProcess) return@launch

            // Runs from the UI (app start / Home), which satisfies Gemini Nano's foreground rule.
            if (!aiAvailability.current().isUsable) return@launch

            hasRequestedAiGreetingThisProcess = true
            val prompt = buildString {
                append(promptFacts(ctx))
                append(". Write one short greeting under ${HomeGreetingText.MAX_HEADLINE_CHARS - 6} characters ")
                append("that fits the moment. Never mention play counts, library size or song totals.")
            }

            runCatching {
                aiHandler.generateContent(
                    prompt = prompt,
                    type = AiSystemPromptType.GREETING,
                    maxOutputTokens = OnDevicePrompts.GREETING_MAX_OUTPUT,
                )
            }.onSuccess { text ->
                // Too long or stat-like -> keep the local headline, and don't cache it.
                val clean = HomeGreetingText.acceptGeneratedHeadline(text)
                if (clean != null) {
                    _greeting.value = HomeGreeting(clean, subtitle)
                    userPreferencesRepository.setHomeGreeting(today, clean)
                }
            }.onFailure { e ->
                Timber.tag("HomeGreeting").w(e, "AI greeting generation failed, keeping local fallback")
            }
        }
    }

    private fun initialGreeting(): HomeGreeting {
        val ctx = GreetingContext(phase = currentPhase(), dayOfYear = LocalDate.now().dayOfYear)
        return HomeGreeting(HomeGreetingText.headline(ctx), HomeGreetingText.subtitle(ctx))
    }

    /** The facts the greeting prompts get: the hour and, when known, what just played, something new, a streak. */
    private fun promptFacts(ctx: GreetingContext): String = buildString {
        append("time_of_day=${ctx.phase}")
        ctx.topArtist?.let { append(", top_artist=$it") }
        ctx.topGenre?.let { append(", top_genre=$it") }
        ctx.lastTitle?.let { title ->
            append(", just_played=${HomeGreetingText.cleanTitle(title)}")
            ctx.lastArtist?.let { append(" by ${it.trim()}") }
        }
        ctx.freshArtist?.let { append(", new_artist=$it") }
        if (ctx.streakDays >= 3) append(", listening_streak_days=${ctx.streakDays}")
    }

    /** Gathers [GreetingContext] from the listening history; every part is optional and failures only mean less context. */
    private suspend fun loadContext(allSongs: List<Song>): GreetingContext {
        val summary = runCatching {
            statsRepository.loadSummary(StatsTimeRange.ALL, allSongs)
        }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()
        val topArtist = recentTopArtist() ?: summary?.topArtists?.firstOrNull()?.artist
        // "Unknown Genre" is a legitimate bucket on the Stats screen but nonsensical to
        // name here ("in the mood for some Unknown Genre?") — skip straight to the next
        // real genre instead of ever surfacing it in the greeting.
        val topGenre = summary?.topGenres
            ?.firstOrNull { it.genre != PlaybackStatsRepository.UNKNOWN_GENRE_LABEL }
            ?.genre

        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val recent: List<RecentPlay> = runCatching { listeningInsights.recentPlays(RECENT_PLAYS_WINDOW) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrDefault(emptyList())
        val last = recent.firstOrNull()?.takeIf { now - it.playedAtMs in 0..JUST_PLAYED_WINDOW_MS }
        val playDays = recent.map { Instant.ofEpochMilli(it.playedAtMs).atZone(zone).toLocalDate() }
        val streak = HomeGreetingText.streakDays(
            playDays = playDays,
            today = LocalDate.now(zone),
            windowComplete = recent.size < RECENT_PLAYS_WINDOW,
        )
        return GreetingContext(
            phase = currentPhase(),
            topArtist = topArtist,
            topGenre = topGenre,
            lastTitle = last?.title,
            lastArtist = last?.artist,
            streakDays = streak,
            freshArtist = freshArtist(recent, now),
            dayOfYear = LocalDate.now().dayOfYear,
        )
    }

    /**
     * An artist heard in the last three days who has no play older than a week in the history (needs enough older
     * history to mean something). Null when there is none.
     */
    private fun freshArtist(recent: List<RecentPlay>, now: Long): String? {
        val older = recent.filter { now - it.playedAtMs > WEEK_MS }
        if (older.size < MIN_OLDER_PLAYS_FOR_FRESH) return null
        val knownArtists = older.map { it.artist.trim().lowercase() }.toHashSet()
        return recent
            .asSequence()
            .filter { now - it.playedAtMs in 0..THREE_DAYS_MS }
            .map { it.artist.trim() }
            .firstOrNull { it.isNotBlank() && it.lowercase() !in knownArtists }
    }

    /**
     * The artist played most lately across PixlAudio and the listening history imported from Spotify / YouTube
     * Music (Spotify weighted far above YouTube Music), so the greeting knows what you play outside the app too.
     */
    private suspend fun recentTopArtist(): String? = runCatching {
        listeningInsights.topArtists(com.theveloper.pixelplay.data.history.InsightPeriod.LAST_4_WEEKS, 1).firstOrNull()?.name
            ?: listeningInsights.topArtists(com.theveloper.pixelplay.data.history.InsightPeriod.LAST_6_MONTHS, 1).firstOrNull()?.name
    }.onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }.getOrNull()

    private fun currentPhase(): String = HomeGreetingText.dayPhase(LocalTime.now().hour)

    private companion object {
        const val RECENT_PLAYS_WINDOW = 300
        const val JUST_PLAYED_WINDOW_MS = 3L * 60 * 60 * 1000
        const val THREE_DAYS_MS = 3L * 24 * 60 * 60 * 1000
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1000
        const val MIN_OLDER_PLAYS_FOR_FRESH = 20
    }
}
