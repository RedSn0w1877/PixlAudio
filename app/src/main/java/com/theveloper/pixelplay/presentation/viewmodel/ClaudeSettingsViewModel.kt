package com.theveloper.pixelplay.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.theveloper.pixelplay.data.ai.claude.ClaudeBudgetState
import com.theveloper.pixelplay.data.ai.claude.ClaudeFailure
import com.theveloper.pixelplay.data.ai.claude.ClaudeService
import com.theveloper.pixelplay.data.ai.provider.AiProvider
import com.theveloper.pixelplay.data.preferences.AiPreferencesRepository
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The one status line Settings › AI features shows. */
sealed interface ClaudeStatus {
    data object Claude : ClaudeStatus
    data object Offline : ClaudeStatus
    data object OverBudget : ClaudeStatus
    data object NoKey : ClaudeStatus
    data object Unavailable : ClaudeStatus
    data class OwnCloud(val providerName: String) : ClaudeStatus
}

/** The status-line rules, pure (ClaudeStatusResolverTest). */
object ClaudeStatusResolver {
    fun resolve(
        providerName: String,
        developerMode: Boolean,
        hasKey: Boolean,
        online: Boolean,
        budgetExhausted: Boolean,
        lastFailure: ClaudeFailure?,
    ): ClaudeStatus {
        val provider = AiProvider.fromString(providerName)
        return when {
            developerMode && provider != AiProvider.ON_DEVICE -> ClaudeStatus.OwnCloud(provider.displayName)
            !hasKey -> ClaudeStatus.NoKey
            budgetExhausted -> ClaudeStatus.OverBudget
            !online -> ClaudeStatus.Offline
            lastFailure == ClaudeFailure.UNAVAILABLE -> ClaudeStatus.Unavailable
            else -> ClaudeStatus.Claude
        }
    }
}

@HiltViewModel
class ClaudeSettingsViewModel @Inject constructor(
    private val claude: ClaudeService,
    aiPreferences: AiPreferencesRepository,
    private val userPreferences: UserPreferencesRepository,
    connectivity: ConnectivityStateHolder,
) : ViewModel() {

    /** "Let Taizo help the engine" (stage 4, docs/brain-spec.md, 9.4): off unless the listener turns it on. */
    val assistHelp: StateFlow<Boolean> = userPreferences.brainAssistHelpEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAssistHelp(enabled: Boolean) {
        viewModelScope.launch { userPreferences.setBrainAssistHelpEnabled(enabled) }
    }

    val budget: StateFlow<ClaudeBudgetState> = claude.budgetState

    val status: StateFlow<ClaudeStatus> = combine(
        aiPreferences.aiProvider,
        userPreferences.developerModeFlow,
        connectivity.isOnline,
        claude.budgetState,
        claude.lastFailure,
    ) { provider, developerMode, online, budget, failure ->
        ClaudeStatusResolver.resolve(
            providerName = provider,
            developerMode = developerMode,
            hasKey = claude.hasKey(),
            online = online || claude.isOnline(),
            budgetExhausted = budget.exhausted,
            lastFailure = failure,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ClaudeStatus.Claude)

    fun setMonthlyCapUsd(usd: Double) = claude.setMonthlyCapUsd(usd)
}
