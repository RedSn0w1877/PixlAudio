package com.theveloper.pixelplay.data.spatial.playback

import android.net.Uri
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.spatial.SpatialLibrary
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.stream.CloudStreamSecurity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * §6.3 "At the start of a song, when Spatial is active and a local render exists for the profile: play the local
 * file". Asked by the player's resolver before it looks at the listening cache or the stream proxy. No network: the
 * matched video id is one database row, the render one [SpatialLibrary.localRender] (memory or a file check).
 */
@Singleton
class SpatialStartResolver @Inject constructor(
    private val stage: SpatialStage,
    private val library: SpatialLibrary,
    private val spotifyDao: SpotifyDao,
) {
    /**
     * The render [spotifyId]'s song should start on, with the URI to give the player, or null to play the stream.
     * Never throws. A song never played before has no video id yet (it is matched on its first stream): the stream.
     */
    suspend fun renderAtStart(spotifyId: String): Pair<Uri, SpatialRender>? {
        val state = stage.state
        val profile = state.profile ?: return null
        if (!state.wet) return null
        // Turned off for this song: its original, at full quality.
        if (spotifyId in stage.songsOff) return null
        return try {
            val videoId = spotifyDao.getMatchedVideoId(spotifyId)
                ?.takeIf { CloudStreamSecurity.validateYouTubeVideoId(it) } ?: return null
            val render = library.localRender(videoId, profile)
            val exists = render != null && withContext(Dispatchers.IO) { render.file.isFile }
            if (!SpatialRenditionRule.startsOnRender(stage.state.wet, profile, videoId, render, exists)) return null
            val found = render ?: return null
            stage.recordRender(found) to found
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Timber.tag("SpatialStartResolver").w(e, "No render lookup for %s", spotifyId)
            null
        }
    }
}
