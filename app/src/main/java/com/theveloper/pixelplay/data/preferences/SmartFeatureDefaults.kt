package com.theveloper.pixelplay.data.preferences

import com.theveloper.pixelplay.data.history.ListeningHistoryRepository
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The smart features that cost nothing per use are on from the first launch and stay on after setup:
 * learning from listening, the Home mixes, the automatic Spotify / YouTube Music history import and
 * automatic lyrics. Every one of them already defaults to on; this makes sure a stored "off" (an old
 * build, a restored backup, the Music Intelligence card that no longer exists) cannot keep them off.
 *
 * Deliberately NOT here, because they cost money or battery per song: "Prepare songs ahead in the cloud"
 * and Cloud processing (both off until chosen), and the cloud AI budget.
 */
@Singleton
class SmartFeatureDefaults @Inject constructor(
    private val userPreferences: UserPreferencesRepository,
    private val taste: MusicTasteRepository,
    private val history: dagger.Lazy<ListeningHistoryRepository>,
) {
    /** Once per install, on the first start after this change; later changes the person makes in Settings stick. */
    suspend fun applyOnce() {
        if (userPreferences.smartDefaultsAppliedFlow.first()) return
        applyNow()
    }

    /** At the end of setup: the person finishing setup should land with the smart features on. */
    suspend fun applyNow() {
        userPreferences.setAutomaticLyricsEnabled(true)
        taste.ensureLearningOn()
        history.get().ensureAutoSyncOn()
        userPreferences.setSmartDefaultsApplied(true)
    }
}
