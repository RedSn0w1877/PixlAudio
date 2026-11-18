package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * The switches of the music brain (docs/brain-spec.md, 2.1, 7.1, 12.3). Stage 1 has two, both local:
 * whether Autoplay continues the queue, and whether YouTube Music may be asked for related music.
 * With the second one off, autoplay continues from the library alone.
 */
@Singleton
class BrainSwitch @Inject constructor(
    private val preferences: UserPreferencesRepository,
) {
    val autoplayEnabledFlow: Flow<Boolean> get() = preferences.brainAutoplayEnabledFlow

    val youTubeSourceEnabledFlow: Flow<Boolean> get() = preferences.brainYouTubeSourceEnabledFlow

    suspend fun setAutoplayEnabled(enabled: Boolean) = preferences.setBrainAutoplayEnabled(enabled)

    suspend fun setYouTubeSourceEnabled(enabled: Boolean) = preferences.setBrainYouTubeSourceEnabled(enabled)

    companion object {
        /**
         * The legacy discovery paths the brain replaces in stage 1 (section 13, "Retire"): the eager
         * import of `MusicDiscoveryRepository.augment` into the mixes, and Home's "Beyond your library"
         * text search. Off. The code stays until stage 2 deletes it, so this is the one place to turn
         * them back on if the brain's row has to be pulled.
         */
        const val LEGACY_EAGER_DISCOVERY = false

        /**
         * Autoplay and stations on a Spotify Connect device (14.6, owner question 7). On: the phone's
         * queue is what the device plays, and a pick is queued only when Spotify has it. The one place
         * to turn it off if a speaker misbehaves; Cast is always off (see `AutoplayPolicy.remoteRule`).
         */
        const val AUTOPLAY_ON_CONNECT = true
    }
}
