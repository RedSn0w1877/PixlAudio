package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.cloudstudio.auto.CloudAutoPrepare
import com.theveloper.pixelplay.presentation.model.LocalDeveloperMode
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.viewmodel.CloudStudioViewModel
import kotlin.math.roundToInt

/**
 * Settings › Sing › Cloud: the two doors into cloud processing, the Cloud processing screen (the
 * on/off consent and Wi-Fi switches) and the Cloud queue (what was sent and what came back),
 * then "Prepare ahead": the opt-in for getting likely songs ready in the cloud on Wi-Fi, with its
 * monthly budget and a live status line. Developer mode adds the daily limit under Advanced.
 * The cloud doors used to hide in Developer › Experimental.
 */
@Composable
fun SettingsSingCloudSection(
    navController: NavController,
    viewModel: CloudStudioViewModel = hiltViewModel(),
) {
    val cloudSettings by viewModel.settings.collectAsStateWithLifecycle()
    val cloudState by viewModel.engineState.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoPrepareStatus.collectAsStateWithLifecycle()
    val developerMode = LocalDeveloperMode.current
    val queueSummary = remember(cloudState.jobs) { viewModel.summaryLine() }
    val tint = MaterialTheme.colorScheme.secondary
    val chevron = @Composable {
        Icon(
            Icons.Rounded.ChevronRight,
            stringResource(R.string.settings_cd_open),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    Column {
        SettingsSubsection(title = stringResource(R.string.settings_sing_cloud_section)) {
            SettingsItem(
                title = stringResource(R.string.settings_sing_cloud_processing_title),
                subtitle = stringResource(
                    if (cloudSettings.enabled) R.string.settings_sing_cloud_processing_on
                    else R.string.settings_sing_cloud_processing_off
                ),
                leadingIcon = { Icon(Icons.Rounded.CloudUpload, null, tint = tint) },
                trailingIcon = chevron,
                onClick = { navController.navigateSafely(Screen.CloudProcessing.route) }
            )
            SettingsItem(
                title = stringResource(R.string.settings_sing_cloud_queue_title),
                subtitle = queueSummary ?: stringResource(R.string.settings_sing_cloud_queue_default),
                leadingIcon = { Icon(painterResource(R.drawable.rounded_queue_music_24), null, tint = tint) },
                trailingIcon = chevron,
                onClick = { navController.navigateSafely(Screen.CloudQueue.route) }
            )
        }

        SettingsSubsection(
            title = stringResource(R.string.settings_sing_auto_section),
            addBottomSpace = developerMode
        ) {
            SwitchSettingItem(
                title = stringResource(R.string.settings_sing_auto_title),
                subtitle = stringResource(R.string.settings_sing_auto_subtitle),
                checked = cloudSettings.autoPrepareEnabled,
                onCheckedChange = { on -> viewModel.updateSettings { it.copy(autoPrepareEnabled = on) } },
                leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = tint) }
            )
            if (cloudSettings.autoPrepareEnabled) {
                AutoPrepareBudgetItem(
                    storedMicroUsd = cloudSettings.autoPrepareMonthlyBudgetMicroUsd,
                    monthlyCapMicroUsd = cloudSettings.effectiveMonthlyCapMicroUsd,
                    parallelWorkers = cloudSettings.parallelWorkers,
                    onCommit = { picked ->
                        viewModel.updateSettings { it.copy(autoPrepareMonthlyBudgetMicroUsd = picked) }
                    }
                )
                AutoPrepareStatusItem(status = autoStatus)
            }
        }

        if (developerMode) {
            SettingsSubsection(
                title = stringResource(R.string.settings_section_advanced),
                addBottomSpace = false
            ) {
                AutoPrepareDailyCapItem(
                    stored = cloudSettings.autoPrepareDailyCap,
                    onCommit = { picked ->
                        viewModel.updateSettings { it.copy(autoPrepareDailyCap = picked) }
                    }
                )
                AutoPrepareWhyItem(viewModel = viewModel)
            }
        }
    }
}

/**
 * The numbers behind the Prepare ahead sliders, apart from Compose so they can be tested: the budget
 * moves in quarter-dollar steps from $0 to $3, the daily limit in whole songs from 1 to 30.
 */
internal object SingAutoPrepareSettings {
    const val BUDGET_STEP_MICRO_USD = 250_000L
    const val BUDGET_MAX_STEP = 12
    /** Stops strictly between the budget slider's two ends. */
    const val BUDGET_STOPS_BETWEEN = BUDGET_MAX_STEP - 1
    const val DAILY_CAP_MIN = 1
    const val DAILY_CAP_MAX = 30
    /** Stops strictly between the daily limit slider's two ends. */
    const val DAILY_CAP_STOPS_BETWEEN = DAILY_CAP_MAX - DAILY_CAP_MIN - 1

    /** The slider position (a count of quarter dollars) for a stored budget; an off-grid value snaps to the nearest step. */
    fun budgetStep(microUsd: Long): Float =
        ((microUsd + BUDGET_STEP_MICRO_USD / 2) / BUDGET_STEP_MICRO_USD)
            .coerceIn(0L, BUDGET_MAX_STEP.toLong())
            .toFloat()

    fun budgetMicroUsd(step: Float): Long =
        step.roundToInt().coerceIn(0, BUDGET_MAX_STEP) * BUDGET_STEP_MICRO_USD

    /** What the budget can really buy: the shared monthly cap guards every job too, so it can't outrun that. */
    fun spendableMicroUsd(budget: Long, monthlyCapMicroUsd: Long): Long =
        minOf(budget, monthlyCapMicroUsd).coerceAtLeast(0L)

    fun dailyCap(value: Float): Int = value.roundToInt().coerceIn(DAILY_CAP_MIN, DAILY_CAP_MAX)

    fun dailyCapValue(cap: Int): Float = cap.coerceIn(DAILY_CAP_MIN, DAILY_CAP_MAX).toFloat()
}

/**
 * "Monthly budget": a $0.00–$3.00 slider in $0.25 steps. It follows the finger on a local value and
 * writes the setting when the finger lifts; the caption turns the amount into songs as it moves.
 */
@Composable
private fun AutoPrepareBudgetItem(
    storedMicroUsd: Long,
    monthlyCapMicroUsd: Long,
    parallelWorkers: Int,
    onCommit: (Long) -> Unit,
) {
    var step by remember(storedMicroUsd) { mutableFloatStateOf(SingAutoPrepareSettings.budgetStep(storedMicroUsd)) }
    val budget = SingAutoPrepareSettings.budgetMicroUsd(step)
    val songs = CloudAutoPrepare.songsPerMonth(SingAutoPrepareSettings.spendableMicroUsd(budget, monthlyCapMicroUsd), parallelWorkers)
    AutoPrepareSliderItem(
        title = stringResource(R.string.settings_sing_auto_budget_title),
        valueText = CloudCost.format(budget),
        caption = if (songs <= 0) {
            stringResource(R.string.settings_sing_auto_budget_caption_none)
        } else {
            pluralStringResource(R.plurals.settings_sing_auto_budget_caption, songs, songs)
        },
        value = step,
        valueRange = 0f..SingAutoPrepareSettings.BUDGET_MAX_STEP.toFloat(),
        steps = SingAutoPrepareSettings.BUDGET_STOPS_BETWEEN,
        onValueChange = { step = it },
        onValueChangeFinished = {
            val picked = SingAutoPrepareSettings.budgetMicroUsd(step)
            if (picked != storedMicroUsd) onCommit(picked)
        }
    )
}

/** Developer mode: the most songs prepared in one day, whatever the budget would still allow. */
@Composable
private fun AutoPrepareDailyCapItem(
    stored: Int,
    onCommit: (Int) -> Unit,
) {
    var value by remember(stored) { mutableFloatStateOf(SingAutoPrepareSettings.dailyCapValue(stored)) }
    val cap = SingAutoPrepareSettings.dailyCap(value)
    AutoPrepareSliderItem(
        title = stringResource(R.string.settings_sing_auto_daily_title),
        valueText = cap.toString(),
        caption = stringResource(R.string.settings_sing_auto_daily_caption),
        value = value,
        valueRange = SingAutoPrepareSettings.DAILY_CAP_MIN.toFloat()..SingAutoPrepareSettings.DAILY_CAP_MAX.toFloat(),
        steps = SingAutoPrepareSettings.DAILY_CAP_STOPS_BETWEEN,
        onValueChange = { value = it },
        onValueChangeFinished = {
            val picked = SingAutoPrepareSettings.dailyCap(value)
            if (picked != stored) onCommit(picked)
        }
    )
}

/** A settings row like [SliderSettingsItem] with a caption under the title, which that one has no room for. */
@Composable
private fun AutoPrepareSliderItem(
    title: String,
    valueText: String,
    caption: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = valueText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false
                )
            }
            Text(
                text = caption,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps
            )
        }
    }
}

/**
 * Developer mode: why the last automatic run picked the songs it did, one line each (its ranked list with the reasons
 * behind each score). It only reads the list while developer mode shows it.
 */
@Composable
private fun AutoPrepareWhyItem(viewModel: CloudStudioViewModel) {
    val lines by viewModel.autoPrepareWhy.collectAsStateWithLifecycle()
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.settings_sing_auto_why_title),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (lines.isEmpty()) {
                Text(
                    text = stringResource(R.string.settings_sing_auto_why_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                lines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** What auto-prepare did lately ("3 songs today · $0.12 of $1.00 this month", "Waiting for Wi-Fi"); the engine words it. */
@Composable
private fun AutoPrepareStatusItem(status: String) {
    if (status.isBlank()) return
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Icon(
                Icons.Rounded.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = status,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
