package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.recommendation.TasteAdjustment
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Settings › Library › Ask Taizo's taste changes: the boosts, reductions, mutes and blocks in force, each removable. */
@HiltViewModel
class TasteChangesViewModel @Inject constructor(
    private val repository: TasteAdjustmentRepository,
) : ViewModel() {

    val adjustments: StateFlow<List<TasteAdjustment>> = repository.activeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun remove(id: String) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clear() }
    }
}
