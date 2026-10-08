package com.theveloper.pixelplay.presentation.viewmodel

import androidx.annotation.StringRes
import com.theveloper.pixelplay.data.ai.local.findOnDeviceFailure

/**
 * The first rule of every AI error table: an on-device failure gets its own message, before any
 * of the cloud rows (API key, "No Internet Connection", quota…) can match its text. Pure.
 *
 * It walks the whole cause chain, because AiPlaylistGenerator wraps failures as
 * `Exception(details, cause)` and the cloud handler wraps them again.
 */
object AiErrorMessages {
    @StringRes
    fun onDeviceMessageRes(error: Throwable): Int? = error.findOnDeviceFailure()?.failure?.messageRes
}
