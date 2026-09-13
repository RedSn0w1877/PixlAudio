package com.theveloper.pixelplay.data.tais.dj

import com.theveloper.pixelplay.data.ai.AiHandler
import com.theveloper.pixelplay.data.ai.AiSystemPromptType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** One reply from Taizo — either a media result to act on, a conversational answer, or a failure to surface. */
sealed interface TaizoTurn {
    /**
     * [aiIntro] is a short AI-written line introducing the results ("Here's some road-trip
     * energy from your library") in place of the flat "Found N songs" text — filled in only
     * when an AI provider is configured; null falls back to the plain count line. The song
     * matching itself is never AI-dependent — this only decorates an already-resolved result,
     * so a slow/failed AI call can't make song-finding flaky.
     */
    data class Media(val intent: DjIntent, val result: DjRouteResult, val aiIntro: String? = null) : TaizoTurn
    data class Conversation(val text: String) : TaizoTurn
    data class Error(val message: String) : TaizoTurn
}

/**
 * TAIS Engine 3 — Local AI DJ & Natural Language Agent.
 *
 * Two routes out of one prompt, chosen by [TaisIntentParser.isMediaRequest]:
 * - A genuine "play/queue/find" request (action verb up front, or a genre/mood keyword anywhere)
 *   goes through the deterministic, offline-capable [TaisIntentParser] -> [TaisMediaRouter] path —
 *   instant, no network/API-key dependency, exactly like before this became conversational.
 * - Anything else — "who wrote this song", "what's a good genre for a road trip", "tell me about
 *   this artist" — is treated as a real question and answered by [AiHandler] using whichever AI
 *   provider the user has configured in Settings → AI Integration (same infra
 *   [com.theveloper.pixelplay.data.ai.AiPlaylistGenerator] and the Daily Mix writer already use).
 *   No API key configured means no conversational answer — [TaizoTurn.Error] surfaces that
 *   directly rather than pretending Taizo can chat without one.
 */
@Singleton
class TaisDjEngine @Inject constructor(
    private val intentParser: TaisIntentParser,
    private val mediaRouter: TaisMediaRouter,
    private val aiHandler: AiHandler
) {
    suspend fun respond(prompt: String): TaizoTurn {
        if (intentParser.isMediaRequest(prompt)) {
            val intent = intentParser.parse(prompt)
            val result = mediaRouter.route(intent)
            return TaizoTurn.Media(intent, result, aiIntro = buildAiIntro(prompt, result))
        }

        return try {
            val reply = withTimeoutOrNull(25_000L) {
                aiHandler.generateContent(prompt = prompt, type = AiSystemPromptType.TAIZO_CHAT)
            } ?: return TaizoTurn.Error("The AI provider took too long. Try again, or ask me to find music.")
            if (reply.isBlank()) TaizoTurn.Error("The AI provider returned an empty response. Please try again.")
            else TaizoTurn.Conversation(reply.trim())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Timber.tag(TAG).w(e, "Taizo conversational reply failed")
            TaizoTurn.Error(e.message ?: "Couldn't reach an AI provider.")
        }
    }

    /**
     * A short AI-written line introducing an already-resolved media result. Best-effort only —
     * the song matching above is already done and correct by the time this runs, so a missing
     * API key, a slow provider, or a network blip here just means the UI falls back to its
     * plain "Found N songs" text instead of breaking anything.
     */
    private suspend fun buildAiIntro(prompt: String, result: DjRouteResult): String? {
        val resultCount = when (result) {
            is DjRouteResult.Offline -> result.songs.size
            is DjRouteResult.Online -> result.tracks.size
            DjRouteResult.NoResults -> 0
        }
        if (resultCount == 0) return null

        return runCatching {
            val introPrompt = "user_request=\"$prompt\", results_found=$resultCount. Write ONE short, " +
                "upbeat sentence (max 12 words) introducing these results to the user. No quotes, no emoji."
            withTimeoutOrNull(1_200L) {
                aiHandler.generateContent(prompt = introPrompt, type = AiSystemPromptType.TAIZO_CHAT)
            }
        }.onFailure { if (it is CancellationException) throw it }.getOrNull()?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
    }

    private companion object {
        const val TAG = "TaisDjEngine"
    }
}
