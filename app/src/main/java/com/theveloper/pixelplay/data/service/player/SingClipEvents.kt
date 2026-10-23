package com.theveloper.pixelplay.data.service.player

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Tells the Sing button that the player left a preview clip on its own: the clip was about to run out before the full
 * instrumental arrived, or a seek took the song outside it. The vocals are back and the Sing keeps waiting for the full
 * instrumental. [InstrumentalCrossfadeController] reports it (from MusicService); LyricsSingStateHolder listens. The
 * service and the app share one process, so this is a plain process-wide signal.
 */
@Singleton
class SingClipEvents @Inject constructor() {
    private val _left = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** The song id (the session's media id) whose clip was left. */
    val left: SharedFlow<String> = _left.asSharedFlow()

    fun clipLeft(songId: String) {
        _left.tryEmit(songId)
    }
}
