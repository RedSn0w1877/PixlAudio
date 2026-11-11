package com.theveloper.pixelplay.presentation.spatial

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Paid
import androidx.compose.material.icons.rounded.SdStorage
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.cloudstudio.CloudCost
import com.theveloper.pixelplay.data.spatial.SpatialAudible
import com.theveloper.pixelplay.data.spatial.SpatialLibraryStats
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import com.theveloper.pixelplay.data.spatial.SpatialRenditionStatus
import com.theveloper.pixelplay.data.spatial.SpatialRouteMode
import com.theveloper.pixelplay.data.spatial.SpatialSettings
import com.theveloper.pixelplay.presentation.components.AdaptiveAlertDialog
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.navigation.navigateSafely
import com.theveloper.pixelplay.presentation.screens.ActionSettingsItem
import com.theveloper.pixelplay.presentation.screens.SettingsItem
import com.theveloper.pixelplay.presentation.screens.SwitchSettingItem
import com.theveloper.pixelplay.presentation.screens.ThemeSelectorItem
import com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioViewModel
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList

/**
 * Settings › Playback › Sound, after Audio quality: Spatial audio's rows (cloud-audio-spec §6.5). The profile row
 * opens the same sheet as the full player; "Cloud renders" writes `spatial.cloud` only, Cloud Studio's own master
 * switch stays on the Cloud processing screen, and while it is off a row says so and leads there (§6.1 "Two
 * switches"). Every row only writes a setting through the holder.
 */
@Composable
fun SpatialAudioSettingsRows(
    navController: NavController,
    viewModel: SpatialAudioViewModel = hiltViewModel(),
) {
    val holder = viewModel.spatial
    val settings by holder.settings.collectAsStateWithLifecycle()
    val stats by holder.stats.collectAsStateWithLifecycle()
    val cloudRendersOn by holder.cloudRendersOn.collectAsStateWithLifecycle()
    val ui = remember(settings, stats, cloudRendersOn) { SpatialSettingsUi.of(settings, stats, cloudRendersOn) }
    var showSheet by rememberSaveable { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }

    SpatialAudioSettingsRowsContent(
        ui = ui,
        onOpenProfiles = { showSheet = true },
        onCloudChange = holder::setCloud,
        onOpenCloudProcessing = { navController.navigateSafely(Screen.CloudProcessing.route) },
        onPrepareAheadChange = holder::setPrepareAhead,
        onRemove = { confirmRemove = true },
    )

    if (showSheet) {
        SpatialAudioSheet(onDismissRequest = { showSheet = false }, viewModel = viewModel)
    }
    if (confirmRemove) {
        AdaptiveAlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.spatial_ui_settings_remove_confirm_title)) },
            text = { Text(stringResource(R.string.spatial_ui_settings_remove_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    holder.removeDownloadedRenders()
                }) { Text(stringResource(R.string.common_remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

/** The rows themselves, stateless: what [SpatialAudioSettingsRows] shows for [ui]. */
@Composable
internal fun SpatialAudioSettingsRowsContent(
    ui: SpatialSettingsUi,
    onOpenProfiles: () -> Unit,
    onCloudChange: (Boolean) -> Unit,
    onOpenCloudProcessing: () -> Unit,
    onPrepareAheadChange: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    val tint = MaterialTheme.colorScheme.secondary
    val chevron = @Composable {
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    SettingsItem(
        title = stringResource(R.string.spatial_ui_audio),
        subtitle = stringResource(
            R.string.spatial_ui_settings_profile_subtitle,
            stringResource(SpatialAudioUi.nameRes(ui.profile)),
            stringResource(SpatialAudioUi.lineRes(ui.profile)),
        ),
        leadingIcon = { Icon(painterResource(R.drawable.rounded_spatial_audio_24), null, tint = tint) },
        trailingIcon = chevron,
        onClick = onOpenProfiles,
    )
    SwitchSettingItem(
        title = stringResource(R.string.spatial_ui_settings_cloud_title),
        subtitle = stringResource(R.string.spatial_ui_settings_cloud_subtitle),
        checked = ui.cloudChecked,
        onCheckedChange = onCloudChange,
        leadingIcon = { Icon(Icons.Rounded.Cloud, null, tint = tint) },
    )
    if (ui.masterOff) {
        SettingsItem(
            title = stringResource(R.string.spatial_ui_settings_master_off_title),
            subtitle = stringResource(R.string.spatial_ui_settings_master_off_subtitle),
            leadingIcon = { Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error) },
            trailingIcon = chevron,
            onClick = onOpenCloudProcessing,
        )
    }
    SwitchSettingItem(
        title = stringResource(R.string.spatial_ui_settings_prepare_title),
        subtitle = stringResource(
            if (ui.prepareAheadEnabled) R.string.spatial_ui_settings_prepare_subtitle
            else R.string.spatial_ui_settings_prepare_needs_cloud
        ),
        checked = ui.prepareAheadChecked,
        onCheckedChange = onPrepareAheadChange,
        enabled = ui.prepareAheadEnabled,
        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null, tint = tint) },
    )
    ActionSettingsItem(
        title = stringResource(R.string.spatial_ui_settings_songs_ready, ui.songsReady, ui.gbOnPhone),
        subtitle = stringResource(R.string.spatial_ui_settings_songs_ready_subtitle),
        icon = { Icon(Icons.Rounded.SdStorage, null, tint = tint) },
        primaryActionLabel = stringResource(R.string.spatial_ui_settings_remove),
        onPrimaryAction = onRemove,
        enabled = ui.canRemove,
    )
}

/**
 * Settings › Playback › Advanced (Developer mode only), Spatial audio's rows (§6.5): route mode, the monthly limit of
 * library jobs, the storage budget, songs a day ahead, and the diagnostics of the playing song. Hiding them never
 * changes the values behind them (a stored `always` route is honoured either way, §6.1).
 */
@Composable
fun SpatialAudioDeveloperRows(viewModel: SpatialAudioViewModel = hiltViewModel()) {
    val holder = viewModel.spatial
    val settings by holder.settings.collectAsStateWithLifecycle()
    val stats by holder.stats.collectAsStateWithLifecycle()
    val audible by holder.audible.collectAsStateWithLifecycle()
    val status by holder.currentStatus.collectAsStateWithLifecycle()
    val render by holder.currentRender.collectAsStateWithLifecycle()

    SpatialAudioDeveloperRowsContent(
        settings = settings,
        stats = stats,
        audible = audible,
        status = status,
        render = render,
        onRouteMode = holder::setRouteMode,
        onMonthlyCap = holder::setMonthlyCapUsd,
        onStorageMb = holder::setStorageMb,
        onDailyAhead = holder::setDailyAhead,
    )
}

/** The developer rows, stateless. Sliders follow the finger locally and write when it lifts. */
@Composable
internal fun SpatialAudioDeveloperRowsContent(
    settings: SpatialSettings,
    stats: SpatialLibraryStats,
    audible: SpatialAudible,
    status: SpatialRenditionStatus,
    render: SpatialRender?,
    onRouteMode: (SpatialRouteMode) -> Unit,
    onMonthlyCap: (Int?) -> Unit,
    onStorageMb: (Int) -> Unit,
    onDailyAhead: (Int) -> Unit,
) {
    val tint = MaterialTheme.colorScheme.secondary

    ThemeSelectorItem(
        label = stringResource(R.string.spatial_ui_dev_route_title),
        description = stringResource(R.string.spatial_ui_dev_route_subtitle),
        options = linkedMapOf(
            SpatialRouteMode.HEADPHONES.id to stringResource(R.string.spatial_ui_dev_route_headphones),
            SpatialRouteMode.ALWAYS.id to stringResource(R.string.spatial_ui_dev_route_always),
        ),
        selectedKey = settings.routeMode.id,
        onSelectionChanged = { key -> onRouteMode(SpatialRouteMode.fromId(key)) },
        leadingIcon = { Icon(Icons.Rounded.Headphones, null, tint = tint) },
    )

    val capChoices = SpatialCapOptions.choices(settings.monthlyCapUsd)
    val noLimit = stringResource(R.string.spatial_ui_dev_cap_none)
    val capOptions = LinkedHashMap<String, String>(capChoices.size)
    for (usd in capChoices) {
        capOptions[SpatialCapOptions.keyOf(usd)] =
            if (usd == null) noLimit else stringResource(R.string.spatial_ui_dev_cap_usd, usd)
    }
    ThemeSelectorItem(
        label = stringResource(R.string.spatial_ui_dev_cap_title),
        description = stringResource(R.string.spatial_ui_dev_cap_subtitle, CloudCost.format(stats.spentThisMonthMicroUsd)),
        options = capOptions,
        selectedKey = SpatialCapOptions.keyOf(settings.monthlyCapUsd),
        onSelectionChanged = { key -> SpatialCapOptions.pick(key)?.let { onMonthlyCap(it.usd) } },
        leadingIcon = { Icon(Icons.Rounded.Paid, null, tint = tint) },
    )

    StorageBudgetRow(storedMb = settings.storageMb, onCommit = onStorageMb)
    DailyAheadRow(stored = settings.dailyAhead, sentToday = stats.sentToday, onCommit = onDailyAhead)
    DiagnosticsRow(rows = remember(audible, status, render) { SpatialDiagnostics.rows(audible, status, render) })
}

@Composable
private fun StorageBudgetRow(storedMb: Int, onCommit: (Int) -> Unit) {
    var position by remember(storedMb) { mutableFloatStateOf(SpatialStorageStops.indexOf(storedMb).toFloat()) }
    val label = SpatialFormat.storage(SpatialStorageStops.megabytesAt(position), Locale.getDefault())
    SpatialSliderRow(
        title = stringResource(R.string.spatial_ui_dev_storage_title),
        valueText = if (label.gigabytes != null) {
            stringResource(R.string.spatial_ui_dev_storage_gb, label.gigabytes)
        } else {
            stringResource(R.string.spatial_ui_dev_storage_mb, label.megabytes ?: 0)
        },
        caption = stringResource(R.string.spatial_ui_dev_storage_caption),
        value = position,
        valueRange = 0f..SpatialStorageStops.lastIndex.toFloat(),
        steps = SpatialStorageStops.stepsBetween,
        onValueChange = { position = it },
        onValueChangeFinished = {
            val picked = SpatialStorageStops.megabytesAt(position)
            if (picked != storedMb) onCommit(picked)
        },
        leadingIcon = { Icon(Icons.Rounded.SdStorage, null, tint = MaterialTheme.colorScheme.secondary) },
    )
}

@Composable
private fun DailyAheadRow(stored: Int, sentToday: Int, onCommit: (Int) -> Unit) {
    var position by remember(stored) { mutableFloatStateOf(stored.toFloat()) }
    SpatialSliderRow(
        title = stringResource(R.string.spatial_ui_dev_daily_title),
        valueText = SpatialDailyAhead.songsAt(position).toString(),
        caption = stringResource(R.string.spatial_ui_dev_daily_caption, sentToday),
        value = position,
        valueRange = SpatialDailyAhead.range,
        // Continuous: a hundred stops would draw a hundred ticks.
        steps = 0,
        onValueChange = { position = it },
        onValueChangeFinished = {
            val picked = SpatialDailyAhead.songsAt(position)
            if (picked != stored) onCommit(picked)
        },
        leadingIcon = { Icon(Icons.Rounded.Today, null, tint = MaterialTheme.colorScheme.secondary) },
    )
}

/** A settings row with a slider and a caption under the title, in the look of the other settings rows. */
@Composable
private fun SpatialSliderRow(
    title: String,
    valueText: String,
    caption: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    leadingIcon: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                leadingIcon()
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = valueText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                    Text(
                        text = caption,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Slider(
                value = value,
                onValueChange = onValueChange,
                onValueChangeFinished = onValueChangeFinished,
                valueRange = valueRange,
                steps = steps,
            )
        }
    }
}

/** The playing song's diagnostics (§6.5), one "label: value" line each. */
@Composable
private fun DiagnosticsRow(rows: ImmutableList<SpatialDiagnosticRow>) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)),
    ) {
        Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Rounded.Insights, null, tint = MaterialTheme.colorScheme.secondary)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.spatial_ui_dev_diag_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (rows.isEmpty()) {
                    Text(
                        text = stringResource(R.string.spatial_ui_dev_diag_nothing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                for (row in rows) {
                    Text(
                        text = stringResource(row.labelRes) + ": " + row.value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpatialAudioSettingsRowsPreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SpatialAudioSettingsRowsContent(
                ui = SpatialSettingsUi.of(
                    settings = SpatialSettings(profile = SpatialProfile.STUDIO, cloud = true),
                    stats = SpatialLibraryStats(songsReady = 12, bytesOnPhone = 1_503_238_553L),
                    cloudRendersOn = false,
                ),
                onOpenProfiles = {},
                onCloudChange = {},
                onOpenCloudProcessing = {},
                onPrepareAheadChange = {},
                onRemove = {},
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SpatialAudioDeveloperRowsPreview() {
    MaterialTheme {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            SpatialAudioDeveloperRowsContent(
                settings = SpatialSettings(profile = SpatialProfile.HALL, cloud = true),
                stats = SpatialLibraryStats(songsReady = 3, bytesOnPhone = 98_000_000L, spentThisMonthMicroUsd = 1_240_000L, sentToday = 4),
                audible = SpatialAudible.NOTHING,
                status = SpatialRenditionStatus.NONE,
                render = null,
                onRouteMode = {},
                onMonthlyCap = {},
                onStorageMb = {},
                onDailyAhead = {},
            )
        }
    }
}
