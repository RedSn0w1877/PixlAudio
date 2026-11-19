package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.brain.BrainFeedback
import com.theveloper.pixelplay.data.brain.YourSoundRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/** "Your sound" (docs/brain-spec.md, 8.6). Reads when the screen opens, off the main thread; nothing runs at app start. */
@HiltViewModel
class YourSoundViewModel @Inject constructor(
    private val repository: YourSoundRepository,
    private val feedback: BrainFeedback,
) : ViewModel() {

    class UiState(val loading: Boolean = true, val data: YourSoundRepository.State? = null)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun refresh(force: Boolean = false) {
        viewModelScope.launch {
            try {
                _state.value = UiState(loading = false, data = repository.load(force))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: Your sound failed to load")
                _state.value = UiState(loading = false, data = null)
            }
        }
    }

    /** "More of this" / "Less of this" on an island's genre (8.6 point 1); [done] gets true when the change was stored. */
    fun adjustGenre(name: String, more: Boolean, done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val id = try {
                feedback.adjustGenre(name, more)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            done(id != null)
        }
    }

    /** "Reset what I've learned": the picks go back to the standard recipe now; [done] runs when it has. */
    fun resetLearned(done: () -> Unit) {
        viewModelScope.launch {
            try {
                repository.resetLearned()
                refresh(force = true)
                done()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: resetting what was learned failed")
            }
        }
    }
}
