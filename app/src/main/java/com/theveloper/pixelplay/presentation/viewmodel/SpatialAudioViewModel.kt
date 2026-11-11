package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * How a composable reaches [SpatialAudioStateHolder] (`hiltViewModel()` in the player's Spatial audio sheet and in
 * Settings' Sound group, cloud-audio-spec §6.5), the way `CloudStudioViewModel` fronts its holder. It keeps no state
 * of its own: everything lives in the process-scoped holder, so the sheet and Settings always show the same thing.
 */
@HiltViewModel
class SpatialAudioViewModel @Inject constructor(
    /** The state and actions of Spatial audio and Enhanced audio (§6.5). */
    val spatial: SpatialAudioStateHolder,
) : ViewModel()
