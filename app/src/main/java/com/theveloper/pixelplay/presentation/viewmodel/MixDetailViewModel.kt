package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.brain.BrainMixes
import com.theveloper.pixelplay.data.brain.BrainPlayTags
import com.theveloper.pixelplay.data.brain.MixKinds
import com.theveloper.pixelplay.data.brain.MixReason
import com.theveloper.pixelplay.data.brain.MixSpec
import com.theveloper.pixelplay.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class MixDetailUi(
    val loaded: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val songs: ImmutableList<Song> = persistentListOf(),
    val reasons: Map<String, MixReason> = emptyMap(),
    val novelIds: Set<String> = emptySet(),
)

/**
 * One of the made-for-you lists (docs/brain-spec.md, 8.2) opened from Home: Daily Mix 2..K, First Listen, Just Dropped. It reads the saved
 * list (the same all day, back after a restart) and builds it only when there is none yet.
 */
@HiltViewModel
class MixDetailViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val mixes: BrainMixes,
    private val playTags: BrainPlayTags,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val mixId: String = savedState.get<String>("mixId").orEmpty()
    private val mutable = MutableStateFlow(MixDetailUi())
    val state: StateFlow<MixDetailUi> = mutable.asStateFlow()
    private var tags: Map<String, com.theveloper.pixelplay.data.brain.BrainItemTag> = emptyMap()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val spec = MixSpec.of(mixId) ?: return@launch
                val view = mixes.savedOf(spec) ?: mixes.todayOf(spec)
                if (view == null) {
                    mutable.value = MixDetailUi(loaded = true, title = titleOf(spec.kind, 0))
                    return@launch
                }
                tags = view.tags
                mutable.value = MixDetailUi(
                    loaded = true, title = titleOf(view.spec.kind, view.number), subtitle = view.subtitle,
                    songs = view.songs.toImmutableList(), reasons = view.reasons, novelIds = view.novelIds,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "mix: opening a made-for-you list failed")
                mutable.value = MixDetailUi(loaded = true)
            }
        }
    }

    /** The listener is about to play the list: its rows carry the list, each pick's arm and its reason into the queue and the log. */
    fun expectPlay() {
        playTags.expect(queueName = state.value.title, bySongId = tags)
    }

    private fun titleOf(kind: Int, number: Int): String = context.getString(
        when (kind) {
            MixKinds.DAILY -> if (number > 0) R.string.brain_mix_daily_n else R.string.brain_mix_daily
            MixKinds.YOUR -> R.string.brain_mix_your
            MixKinds.FIRST_LISTEN -> R.string.brain_mix_first_listen
            MixKinds.JUST_DROPPED -> R.string.brain_mix_just_dropped
            MixKinds.ON_REPEAT -> R.string.brain_mix_on_repeat
            else -> R.string.brain_mix_rediscover
        },
        *(if (kind == MixKinds.DAILY && number > 0) arrayOf<Any>(number) else emptyArray()),
    )
}
