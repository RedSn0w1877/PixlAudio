package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.brain.GenrePage
import com.theveloper.pixelplay.data.brain.AtlasNames
import com.theveloper.pixelplay.data.brain.AtlasProvider
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

    class UiState(val loading: Boolean = true, val facts: Facts? = null)

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
