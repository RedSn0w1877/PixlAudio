package com.theveloper.pixelplay.data.spatial

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The player side of Spatial audio as the rest of the app sees it (§6.3, §7): the on-device stage, which file an
 * item plays from, the fade to a render that arrives mid-song (about 250 ms, only with at least 20 s left; Sing's own
 * swap keeps its 300-700 ms and must not change), the route rule and the level rule.
 *
 * It is also the one that asks for renders: when a song plays with Spatial audio active and has no local render it
 * calls [SpatialLibrary.request], and it tells the library what it played ([SpatialLibrary.markPlayed]).
 *
 * Two things give way while a profile is selected and Spatial audio is active (§6.3), because neither lets the
 * on-device stage run: audio offload is off (turning a profile on mid-song costs one short gap, once, which is
 * accepted), and Hi-Fi float output is off ("Spatial wins").
 *
 * The playback service drives it: it collects [SpatialSettingsRepository.settings] and hands every value to [apply],
 * so the settings reach the player with or without the app's UI. The UI only reads [audible], through
 * [com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioStateHolder].
 *
 * Bound in [SpatialPlaybackModule]. Until the player side lands it is [NoOpSpatialPlayback]: plain stereo.
 */
interface SpatialPlayback {
    /**
     * What the listener hears for the current item (§6.3): stereo, the on-device stage or a cloud render, with which
     * profile, and what holds a selected profile back (the route rule, Sing, another device). Starts as
     * [SpatialAudible.NOTHING]; updated on the main thread as the item, the route or the rendition changes.
     */
    val audible: StateFlow<SpatialAudible>

    /**
     * Applies the settings of §6.1 to playback (§6.3). Main thread. Called once when the playback service starts
     * and again on every change; applying the same settings twice changes nothing.
     *
     * A new profile switches the on-device preset at once (a ramp of about 80 ms). `off`, or a route the rule
     * rejects, bypasses the stage at once, and a cloud render that is playing goes back to the stream (§6.3 allows
     * letting it finish the song where that is hard).
     */
    fun apply(settings: SpatialSettings)
}

/** The player before the player side exists: nothing is ever spatial, settings are ignored. */
object NoOpSpatialPlayback : SpatialPlayback {
    private val nothing = MutableStateFlow(SpatialAudible.NOTHING)

    override val audible: StateFlow<SpatialAudible> = nothing.asStateFlow()

    override fun apply(settings: SpatialSettings) = Unit
}
