package com.theveloper.pixelplay.data.ai.local

import androidx.annotation.StringRes
import com.google.mlkit.genai.common.GenAiException
import com.theveloper.pixelplay.R

/**
 * Why an on-device AI request failed, in the user's terms.
 *
 * On-device failures used to surface as "No Internet Connection": the provider was named
 * "On-Device (Offline)", every error message was prefixed with that name, and the playlist /
 * Daily Mix error tables match "offline" and "connect" before anything else. Each failure now has
 * its own message ([messageRes]), and every error table checks [findOnDeviceFailure] first.
 *
 * The messages must never contain the words network, connect, offline, wifi, timeout, key or
 * config: the older substring tables in AiPlaylistGenerator, AiStateHolder and LyricsStateHolder
 * would turn them back into network or API-key errors (OnDeviceErrorMessagesTest checks this).
 */
enum class OnDeviceFailure(@param:StringRes val messageRes: Int) {
    /** This phone has no Gemini Nano (or AICore is too old for the Prompt API). */
    NOT_SUPPORTED(R.string.ai_on_device_error_not_supported),
    /** AICore / Android System Intelligence needs an update first. */
    NEEDS_UPDATE(R.string.ai_on_device_error_needs_update),
    /** The system model is still being downloaded or prepared by Android. */
    PREPARING(R.string.ai_on_device_error_preparing),
    /** The request was too long for the model's context window. */
    TOO_LONG(R.string.ai_on_device_error_too_long),
    /** The model refused or could not process the request (safety filters, bad output). */
    BLOCKED(R.string.ai_on_device_error_blocked),
    /** Another app (or another PixlAudio request) is using the model right now. */
    BUSY(R.string.ai_on_device_error_busy),
    /** The per-app daily battery quota for on-device AI is used up. */
    DAILY_LIMIT(R.string.ai_on_device_error_daily_limit),
    /** Gemini Nano only answers the app on screen; PixlAudio went to the background. */
    NEEDS_FOREGROUND(R.string.ai_on_device_error_needs_foreground),
    /** "Use downloaded AI model" is on but the file isn't on the phone (and Nano can't stand in). */
    MODEL_MISSING(R.string.ai_on_device_error_model_missing),
    /** The downloaded model failed to load (corrupt file, out of memory, no engine for this CPU). */
    MODEL_LOAD_FAILED(R.string.ai_on_device_error_model_load_failed),
    /** The model answered, but too slowly for the feature that asked. */
    SLOW(R.string.ai_on_device_error_slow),
    OTHER(R.string.ai_on_device_error_other);
}

/** An on-device AI failure. Thrown as is: never wrapped in a cloud-provider exception. */
class OnDeviceAiException(
    val failure: OnDeviceFailure,
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(failure.name, cause)

/** The first [OnDeviceAiException] in this throwable's cause chain, if any. */
fun Throwable.findOnDeviceFailure(): OnDeviceAiException? =
    generateSequence(this) { it.cause }
        .take(MAX_CAUSE_DEPTH)
        .filterIsInstance<OnDeviceAiException>()
        .firstOrNull()

private const val MAX_CAUSE_DEPTH = 16

/** Maps engine errors (ML Kit GenAI, LiteRT-LM) onto [OnDeviceFailure]. Pure. */
object OnDeviceErrorMapper {

    /** ML Kit's [GenAiException.ErrorCode] values; [retryDelayMs] only matters for BUSY. */
    fun fromErrorCode(errorCode: Int, retryDelayMs: Long? = null, cause: Throwable? = null): OnDeviceAiException {
        val failure = when (errorCode) {
            GenAiException.ErrorCode.NOT_AVAILABLE,
            GenAiException.ErrorCode.NOT_SUPPORTED,
            GenAiException.ErrorCode.AICORE_INCOMPATIBLE -> OnDeviceFailure.NOT_SUPPORTED
            GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE -> OnDeviceFailure.NEEDS_UPDATE
            GenAiException.ErrorCode.REQUEST_TOO_LARGE -> OnDeviceFailure.TOO_LONG
            GenAiException.ErrorCode.REQUEST_PROCESSING_ERROR,
            GenAiException.ErrorCode.RESPONSE_PROCESSING_ERROR,
            GenAiException.ErrorCode.RESPONSE_GENERATION_ERROR -> OnDeviceFailure.BLOCKED
            GenAiException.ErrorCode.BUSY -> OnDeviceFailure.BUSY
            GenAiException.ErrorCode.PER_APP_BATTERY_USE_QUOTA_EXCEEDED -> OnDeviceFailure.DAILY_LIMIT
            GenAiException.ErrorCode.BACKGROUND_USE_BLOCKED -> OnDeviceFailure.NEEDS_FOREGROUND
            // AICore wants room to finish installing the model: the same "not ready yet" story.
            GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE -> OnDeviceFailure.PREPARING
            else -> OnDeviceFailure.OTHER
        }
        return OnDeviceAiException(
            failure = failure,
            retryAfterMs = retryDelayMs.takeIf { failure == OnDeviceFailure.BUSY },
            cause = cause,
        )
    }

    fun fromGenAi(e: GenAiException): OnDeviceAiException =
        fromErrorCode(e.errorCode, runCatching { e.retryDelay?.toMillis() }.getOrNull(), e)

    /**
     * LiteRT-LM (the downloaded model). Loading failures — a native error, a missing .so on a
     * 32-bit-only phone, out of memory — are MODEL_LOAD_FAILED so [LocalAi] can answer with Gemini
     * Nano instead; anything else from a loaded engine is a generation failure.
     */
    fun fromLiteRt(t: Throwable, whileLoading: Boolean): OnDeviceAiException {
        if (t is OnDeviceAiException) return t
        val failure = when {
            whileLoading -> OnDeviceFailure.MODEL_LOAD_FAILED
            t is OutOfMemoryError || t is UnsatisfiedLinkError -> OnDeviceFailure.MODEL_LOAD_FAILED
            looksLikeContextOverflow(t.message) -> OnDeviceFailure.TOO_LONG
            else -> OnDeviceFailure.BLOCKED
        }
        return OnDeviceAiException(failure, cause = t)
    }

    private fun looksLikeContextOverflow(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return ("token" in text && ("exceed" in text || "too many" in text || "limit" in text)) ||
            "context length" in text || "max_num_tokens" in text
    }
}
