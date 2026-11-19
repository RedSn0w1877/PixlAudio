package com.theveloper.pixelplay.presentation.viewmodel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.brain.PickedArtists
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableSet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@Immutable
data class PickerArtist(val name: String, val art: String?)

@Immutable
data class ArtistPickerUi(
    val query: String = "",
    val results: ImmutableList<PickerArtist> = persistentListOf(),
    val selected: ImmutableSet<String> = persistentSetOf(),
    val searching: Boolean = false,
    val starting: Boolean = false,
    val done: Boolean = false,
    /** True when a search or the start failed: the screen says so in plain words. */
    val failed: Boolean = false,
)

/**
 * "Pick three artists you love" (docs/brain-spec.md, 8.10), for a thin or empty library. The spec's grid is the device country's chart;
 * there is no chart call in the app's YouTube Music layer yet, so this is the search it names as the other way: type an artist,
 * the artists of the songs found are listed, pick up to three.
 */
@HiltViewModel
class ArtistPickerViewModel @Inject constructor(
    private val innerTube: InnerTubeClient,
    private val picked: PickedArtists,
) : ViewModel() {
    private val mutable = MutableStateFlow(ArtistPickerUi())
    val state: StateFlow<ArtistPickerUi> = mutable.asStateFlow()
    private var searchJob: Job? = null

    fun onQuery(text: String) {
        mutable.update { it.copy(query = text, failed = false) }
        searchJob?.cancel()
        if (text.trim().length < 2) {
            mutable.update { it.copy(results = persistentListOf(), searching = false) }
            return
        }
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            mutable.update { it.copy(searching = true) }
            try {
                val found = innerTube.searchSongs(text.trim(), 20)
                val byName = LinkedHashMap<String, PickerArtist>()
                for (r in found) {
                    val name = r.artist.substringBefore(',').substringBefore(" & ").trim()
                    if (name.isNotEmpty() && byName[name.lowercase()] == null) byName[name.lowercase()] = PickerArtist(name, r.thumbnailUrl)
                }
                mutable.update { it.copy(results = byName.values.toList().toImmutableList(), searching = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "picker: search failed")
                mutable.update { it.copy(searching = false, failed = true) }
            }
        }
    }

    fun toggle(name: String) {
        mutable.update {
            val next = it.selected.toMutableSet()
            if (!next.remove(name) && next.size < PickedArtists.PICKS) next.add(name)
            it.copy(selected = next.toImmutableSet())
        }
    }

    /** Starts the picked artists' radios; the screen closes when at least one worked. */
    fun confirm() {
        val names = state.value.selected.toList()
        if (names.isEmpty() || state.value.starting) return
        mutable.update { it.copy(starting = true, failed = false) }
        viewModelScope.launch {
            val worked = try {
                picked.pick(names)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "picker: starting the picks failed")
                emptyList()
            }
            mutable.update { it.copy(starting = false, done = worked.isNotEmpty(), failed = worked.isEmpty()) }
        }
    }

    private companion object {
        const val DEBOUNCE_MS = 450L
    }
}
