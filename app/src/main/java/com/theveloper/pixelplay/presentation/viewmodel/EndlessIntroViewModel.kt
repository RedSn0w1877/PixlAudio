package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainMixes
import com.theveloper.pixelplay.data.brain.endlessSeeds
import com.theveloper.pixelplay.data.preferences.AlbumArtPaletteStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * The introduction of Endless Listening: three pages, one animated element. The only thing it loads is one colour, taken once from the art of
 * the song that stands for the listener's biggest taste island; until it is there (and when there is none) the screen uses the theme's own.
 */
@HiltViewModel
class EndlessIntroViewModel @Inject constructor(
    private val mixes: BrainMixes,
    private val colors: ColorSchemeProcessor,
    private val clock: BrainClock,
    val endless: EndlessStateHolder,
) : ViewModel() {
    private val mutableAccent = MutableStateFlow<Pair<Color, Color>?>(null)

    /** The (light, dark) primary colour of the top island's art, or null while it is not known. Static: it is set once. */
    val accent: StateFlow<Pair<Color, Color>?> = mutableAccent.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val art = mixes.endlessSeeds(clock.nowMs()).firstOrNull()?.song?.albumArtUriString?.takeIf { it.isNotBlank() } ?: return@launch
                val pair = colors.getOrGenerateColorScheme(art, AlbumArtPaletteStyle.default, throttled = true) ?: return@launch
                mutableAccent.value = pair.light.primary to pair.dark.primary
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "endless: the intro's colour could not be read")
            }
        }
    }

    /** The introduction was shown: it does not open by itself again (the card's menu replays it). */
    fun markSeen() = endless.markIntroSeen()
}
