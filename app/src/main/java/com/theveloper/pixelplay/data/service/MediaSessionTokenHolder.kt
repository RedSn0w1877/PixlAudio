package com.theveloper.pixelplay.data.service

import android.media.session.MediaSession
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The platform token of MusicService's MediaSession, for `Activity.setMediaController` (the
 * Spotify Connect volume keys). Media3's `SessionToken` keeps its platform token package-private;
 * `MediaSession.getPlatformToken()` is public, and the service runs in the app's process, so the
 * service publishes it here while its session exists.
 */
@Singleton
class MediaSessionTokenHolder @Inject constructor() {
    private val _platformToken = MutableStateFlow<MediaSession.Token?>(null)
    val platformToken: StateFlow<MediaSession.Token?> = _platformToken.asStateFlow()

    fun publish(token: MediaSession.Token?) {
        _platformToken.value = token
    }
}
