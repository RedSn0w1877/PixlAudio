package com.theveloper.pixelplay.data.tais.dj

import android.content.Context
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.AiHandler
import com.theveloper.pixelplay.data.ai.AiSystemPromptType
import com.theveloper.pixelplay.data.ai.local.AiRoute
import com.theveloper.pixelplay.data.ai.local.LocalAi
import com.theveloper.pixelplay.data.ai.local.LocalGenerationRequest
import com.theveloper.pixelplay.data.ai.local.OnDevicePrompts
import com.theveloper.pixelplay.data.ai.local.TokenBudget
import com.theveloper.pixelplay.data.ai.local.findOnDeviceFailure
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/** One reply from Taizo — either a media result to act on, a conversational answer, or a failure to surface. */
sealed interface TaizoTurn {
    /**
     * [aiIntro] is a short AI-written line introducing the results ("Here's some road-trip
     * energy from your library") in place of the flat "Found N songs" text. The song matching
     * itself is never AI-dependent, and the intro arrives afterwards ([TaisDjEngine.introFor]),
     * so a slow model never holds the result card back.
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
 *   instant, no model involved.
 * - Anything else is a real question. On the on-device route (the default) Taizo answers with the
 *   phone's model, grounded in [LibraryLookup] facts and the chat so far ([TaizoMemory]); on a
 *   cloud route [AiHandler] answers as before.
 */
@Singleton
class TaisDjEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val intentParser: TaisIntentParser,
    private val mediaRouter: TaisMediaRouter,
    private val aiHandler: AiHandler,
    private val localAi: LocalAi,
    private val libraryLookup: LibraryLookup,
    private val aiPreferences: AiPreferencesRepository,
) {
    suspend fun respond(prompt: String, memory: TaizoMemory? = null): TaizoTurn {
        if (intentParser.isMediaRequest(prompt)) {
            val intent = intentParser.parse(prompt)
            val result = mediaRouter.route(intent)
            return TaizoTurn.Media(intent, result)
        }

        return try {
            val onDevice = aiHandler.currentRoute() is AiRoute.OnDevice
            val reply = withTimeoutOrNull(CHAT_TIMEOUT_MS) {
                if (onDevice) {
                    answerOnDevice(prompt, memory)
                } else {
                    aiHandler.generateContent(prompt = prompt, type = AiSystemPromptType.TAIZO_CHAT)
                }
            } ?: return TaizoTurn.Error(
                context.getString(if (onDevice) R.string.ai_on_device_error_slow else R.string.taizo_cloud_too_slow)
            )
            if (reply.isBlank()) TaizoTurn.Error(context.getString(R.string.taizo_empty_reply))
            else TaizoTurn.Conversation(reply.trim())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Taizo conversational reply failed")
            val onDeviceFailure = e.findOnDeviceFailure()
            TaizoTurn.Error(
                if (onDeviceFailure != null) context.getString(onDeviceFailure.failure.messageRes)
                else e.message ?: context.getString(R.string.taizo_cloud_unreachable)
            )
        }
    }

    /**
     * A short AI-written line introducing an already-resolved media result. Best-effort only:
     * null on any failure, and the UI keeps its plain "Found N songs" text.
     */
    suspend fun introFor(prompt: String, result: DjRouteResult): String? {
        val resultCount = when (result) {
            is DjRouteResult.Offline -> result.songs.size
            is DjRouteResult.Online -> result.tracks.size
            DjRouteResult.NoResults -> 0
        }
        if (resultCount == 0) return null

        val introPrompt = "user_request=\"${TokenBudget.clamp(prompt, 60)}\", results_found=$resultCount. " +
            "Write ONE short, upbeat sentence (max 12 words) introducing these results to the user. No quotes, no emoji."
        return runCatching {
            withTimeoutOrNull(INTRO_TIMEOUT_MS) {
                if (aiHandler.currentRoute() is AiRoute.OnDevice) {
                    localAi.generate(
                        LocalGenerationRequest(
                            instruction = OnDevicePrompts.TAIZO_INTRO,
                            prompt = introPrompt,
                            temperature = 0.8f,
                            maxOutputTokens = OnDevicePrompts.TAIZO_INTRO_MAX_OUTPUT,
                        )
                    ).text
                } else {
                    aiHandler.generateContent(prompt = introPrompt, type = AiSystemPromptType.TAIZO_CHAT)
                }
            }
        }.onFailure { if (it is CancellationException) throw it }
            .getOrNull()?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
    }

    /** Loads the on-device model when the chat sheet opens. No-op on a cloud route. */
    suspend fun prewarm() {
        runCatching {
            if (aiHandler.currentRoute() is AiRoute.OnDevice) localAi.prewarm()
        }.onFailure { if (it is CancellationException) throw it }
    }

    private suspend fun answerOnDevice(question: String, memory: TaizoMemory?): String {
        val facts = TokenBudget.clamp(libraryLookup.factsFor(question), OnDevicePrompts.TAIZO_FACTS_TOKENS)
        val recent = memory?.render().orEmpty()
        val prompt = buildString {
            if (facts.isNotBlank()) append("<library_facts>\n").append(facts).append("\n</library_facts>\n")
            if (recent.isNotBlank()) append("<recent_chat>\n").append(recent).append("\n</recent_chat>\n")
            append(TokenBudget.clamp(question.trim(), OnDevicePrompts.TAIZO_QUESTION_TOKENS))
        }
        val persona = aiPreferences.getSystemPrompt(AiProvider.ON_DEVICE).first()
        return localAi.generate(
            LocalGenerationRequest(
                instruction = OnDevicePrompts.taizoChat(persona),
                prompt = prompt,
                temperature = aiHandler.temperatureFor(0.8f),
                maxOutputTokens = OnDevicePrompts.TAIZO_CHAT_MAX_OUTPUT,
            )
        ).text
    }

    private companion object {
        const val TAG = "TaisDjEngine"
        const val CHAT_TIMEOUT_MS = 25_000L
        // The intro is fetched after the card is shown, so it can take longer than the old
        // in-line 1.2 s budget (which an on-device model would never meet).
        const val INTRO_TIMEOUT_MS = 6_000L
    }
}
