package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.brain.GenrePage
import com.theveloper.pixelplay.data.brain.AtlasNames
import com.theveloper.pixelplay.data.brain.AtlasProvider
import com.theveloper.pixelplay.data.brain.BrainFeedback
import com.theveloper.pixelplay.data.brain.GenrePageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A genre page (docs/brain-spec.md, 8.7): the atlas's facts and links, nothing else. A link whose name the app does not say is dropped. */
@HiltViewModel
class GenrePageViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val atlasProvider: AtlasProvider,
    private val repository: GenrePageRepository,
    private val feedback: BrainFeedback,
) : ViewModel() {

    class Link(val id: Int, val name: String)

    class Facts(
        val id: Int,
        val name: String,
        val description: String?,
        val origin: String?,
        val parents: List<Link>,
        val children: List<Link>,
        val related: List<Link>,
        val influences: List<Link>,
    )

    /** [extras] arrives after the atlas facts (it reads the listener's own data): the page is drawn first, the sections join it. */
    class UiState(val loading: Boolean = true, val facts: Facts? = null, val extras: GenrePageRepository.Extras? = null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val id = savedState.get<String>("atlasId")?.toIntOrNull()
        viewModelScope.launch {
            val facts = if (id == null) null else try {
                withContext(Dispatchers.Default) { load(id) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            _state.value = UiState(loading = false, facts = facts)
            if (facts != null) {
                val extras = try {
                    repository.extras(facts.id)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                _state.value = UiState(loading = false, facts = facts, extras = extras)
            }
        }
    }

    /** "More of this" / "Less of this" (8.7): the existing boost or reduce on this genre; [done] gets true when it was stored. */
    fun adjust(more: Boolean, done: (Boolean) -> Unit) {
        val facts = _state.value.facts ?: return
        viewModelScope.launch {
            val id = try {
                feedback.adjustGenre(facts.name, more)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            done(id != null)
        }
    }

    private suspend fun load(id: Int): Facts? {
        val atlas = atlasProvider.atlas() ?: return null
        val page = GenrePage.of(atlas, id) ?: return null
        if (!AtlasNames.isShown(page.name)) return null
        fun links(ids: IntArray): List<Link> = ids.toList().mapNotNull { other -> AtlasNames.genreName(atlas, other)?.let { Link(other, it) } }
        return Facts(
            id = id,
            name = page.name,
            description = page.description?.takeIf(AtlasNames::isShown),
            origin = page.origin,
            parents = links(page.parents),
            children = links(page.children),
            related = links(page.related),
            influences = links(page.influences),
        )
    }
}
