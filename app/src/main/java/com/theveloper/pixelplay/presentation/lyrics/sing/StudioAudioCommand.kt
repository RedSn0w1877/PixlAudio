package com.theveloper.pixelplay.presentation.lyrics.sing

import android.os.Bundle
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.theveloper.pixelplay.data.service.MusicNotificationProvider
import java.util.concurrent.ExecutionException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Sends the studio-audio command ([MusicNotificationProvider.CUSTOM_COMMAND_SET_STUDIO_AUDIO_OVERRIDE]) and
 * waits for the service's answer: true only when the switch really happened (the instrumental is audible,
 * or the vocals are back). A command the service refused, a lost connection and a service that never
 * answers all read as false, so the Sing button never claims "Vocals off" for a switch that did not happen.
 */
internal suspend fun MediaController.sendStudioAudioCommand(args: Bundle): Boolean {
    val command = SessionCommand(MusicNotificationProvider.CUSTOM_COMMAND_SET_STUDIO_AUDIO_OVERRIDE, Bundle())
    return try {
        val result: SessionResult = sendCustomCommand(command, args).await()
        result.resultCode == SessionResult.RESULT_SUCCESS &&
            result.extras.getBoolean(MusicNotificationProvider.EXTRA_STUDIO_CROSSFADED, false)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "The studio audio command failed")
        false
    }
}

/** Waits for a Media3 future without blocking a thread. */
private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addListener(
        {
            try {
                continuation.resume(get())
            } catch (e: ExecutionException) {
                continuation.resumeWithException(e.cause ?: e)
            } catch (e: CancellationException) {
                continuation.cancel(e)
            }
        },
        MoreExecutors.directExecutor()
    )
    continuation.invokeOnCancellation { cancel(false) }
}
