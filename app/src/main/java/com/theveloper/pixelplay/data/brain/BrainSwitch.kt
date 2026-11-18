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
}
