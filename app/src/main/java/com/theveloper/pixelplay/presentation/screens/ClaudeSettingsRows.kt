package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachMoney
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.claude.ClaudeConfig
import com.theveloper.pixelplay.presentation.components.taizo.AskTaizoSheet
import com.theveloper.pixelplay.presentation.viewmodel.ClaudeSettingsViewModel
import com.theveloper.pixelplay.presentation.viewmodel.ClaudeStatus
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Settings › AI features, for everyone: one line saying where AI runs ("Claude (cloud) —
 * switches to this phone when offline") and the way into Ask Taizo.
 */
@Composable
internal fun ClaudeStatusAndAssistantRows(
    playerViewModel: PlayerViewModel,
    viewModel: ClaudeSettingsViewModel = hiltViewModel(),
) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    var showTaizo by rememberSaveable { mutableStateOf(false) }

    SettingsSubsection(title = stringResource(R.string.settings_ai_status_title)) {
        val isCloud = status is ClaudeStatus.Claude || status is ClaudeStatus.OwnCloud
        SettingsItem(
            title = stringResource(R.string.settings_ai_status_title),
            subtitle = when (val s = status) {
                ClaudeStatus.Claude -> stringResource(R.string.settings_ai_status_claude)
                ClaudeStatus.Offline -> stringResource(R.string.settings_ai_status_offline)
                ClaudeStatus.OverBudget -> stringResource(R.string.settings_ai_status_over_budget)
                ClaudeStatus.NoKey -> stringResource(R.string.settings_ai_status_no_key)
                ClaudeStatus.Unavailable -> stringResource(R.string.settings_ai_status_unavailable)
                is ClaudeStatus.OwnCloud -> stringResource(R.string.settings_ai_status_own_cloud, s.providerName)
            },
            leadingIcon = {
                Icon(
                    imageVector = if (isCloud) Icons.Rounded.Cloud else Icons.Rounded.CloudOff,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
            },
            onClick = {},
        )
        SettingsItem(
            title = stringResource(R.string.settings_ask_taizo_title),
            subtitle = stringResource(R.string.settings_ask_taizo_subtitle),
            leadingIcon = { Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            onClick = { showTaizo = true },
        )
    }

    if (showTaizo) {
        AskTaizoSheet(
            playerViewModel = playerViewModel,
            onDismissRequest = { showTaizo = false },
        )
    }
}

/** Developer mode: the built-in Claude key's monthly budget on this phone ($0–$200, default $50). */
@Composable
internal fun ClaudeBudgetRows(viewModel: ClaudeSettingsViewModel = hiltViewModel()) {
    val budget by viewModel.budget.collectAsStateWithLifecycle()
    // Dragging moves this local value; the cap is stored when the finger lifts.
    var dragging by remember { mutableStateOf(false) }
    var draft by remember { mutableFloatStateOf(budget.capUsd.toFloat()) }
    val shown = if (dragging) draft else budget.capUsd.toFloat()

    SettingsSubsection(title = stringResource(R.string.settings_claude_budget_section)) {
        SettingsItem(
            title = stringResource(R.string.settings_claude_budget_title),
            subtitle = stringResource(
                R.string.settings_claude_budget_subtitle,
                usd(budget.spentUsd),
                usd(budget.capUsd),
            ),
            leadingIcon = { Icon(Icons.Rounded.AttachMoney, contentDescription = null, tint = MaterialTheme.colorScheme.secondary) },
            onClick = {},
        )
        Spacer(Modifier.height(2.dp))
        SliderSettingsItem(
            label = stringResource(R.string.settings_claude_budget_title),
            value = shown,
            valueRange = 0f..ClaudeConfig.MAX_MONTHLY_CAP_USD.toFloat(),
            steps = (ClaudeConfig.MAX_MONTHLY_CAP_USD / 5.0).roundToInt() - 1,
            onValueChange = {
                dragging = true
                draft = it
            },
            onValueChangeFinished = {
                viewModel.setMonthlyCapUsd(draft.toDouble())
                dragging = false
            },
            valueText = { usd(it.toDouble()) },
        )
    }
}

private fun usd(value: Double): String = String.format(Locale.US, "$%.2f", value)
