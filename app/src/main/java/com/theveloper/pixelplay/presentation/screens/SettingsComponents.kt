package com.theveloper.pixelplay.presentation.screens

import com.theveloper.pixelplay.presentation.components.AdaptivePressSurface
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.controls.SegmentOption
import com.theveloper.pixelplay.ui.glass.controls.LiquidSegmented
import com.theveloper.pixelplay.ui.glass.controls.GlassSection
import com.theveloper.pixelplay.ui.glass.components.LiquidToggle
import com.theveloper.pixelplay.ui.glass.components.LiquidSlider
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.LocalGlassModeEnabled
import com.theveloper.pixelplay.presentation.components.glassClear
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.ai.GeminiModel
import com.theveloper.pixelplay.data.worker.SyncProgress
import com.theveloper.pixelplay.presentation.viewmodel.LyricsRefreshProgress
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.sp
import androidx.core.view.HapticFeedbackConstantsCompat
import com.theveloper.pixelplay.presentation.components.subcomps.TightWrapText
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback

@Composable
fun SettingsSection(title: String, icon: @Composable () -> Unit, content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
            )
        }
        content()
    }
}

@Composable
fun SettingsItem(
        title: String,
        subtitle: String,
        leadingIcon: @Composable () -> Unit,
        trailingIcon: @Composable () -> Unit = {},
        onClick: () -> Unit
) {
    if (LocalGlassModeEnabled.current) {
        GlassFlatSettingRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = leadingIcon,
            onClick = onClick,
            trailing = {
                Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    trailingIcon()
                }
            }
        )
        return
    }
    Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier =
                    Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onClick)
    ) {
        Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp).fillMaxWidth()
        ) {
            Box(
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                    contentAlignment = Alignment.Center
            ) { leadingIcon() }

            Column(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                trailingIcon()
            }
        }
    }
}

@Composable
fun SwitchSettingItem(
        title: String,
        subtitle: String,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
        leadingIcon: @Composable (() -> Unit)? = null,
        enabled: Boolean = true
) {
    val view = LocalView.current
    val appHapticsConfig = LocalAppHapticsConfig.current

    if (LocalGlassModeEnabled.current) {
        val onToggle: (Boolean) -> Unit = { newValue ->
            if (enabled) {
                performAppCompatHapticFeedback(
                    view,
                    appHapticsConfig,
                    HapticFeedbackConstantsCompat.GESTURE_START
                )
                onCheckedChange(newValue)
            }
        }
        GlassFlatSettingRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = leadingIcon,
            enabled = enabled,
            // Like the Material row, only the toggle is the touch target (the kit toggle inspects
            // pointers without consuming them, so a clickable row would fire a second time).
            onClick = null,
            trailing = {
                LiquidToggle(
                    selected = { checked },
                    onSelect = onToggle,
                    enabled = enabled
                )
            }
        )
        return
    }

    Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leadingIcon != null) {
                Box(
                        modifier = Modifier.padding(end = 4.dp).size(24.dp),
                        contentAlignment = Alignment.Center
                ) { leadingIcon() }
            }

            Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color =
                                if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
                Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                                if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            Switch(
                checked = checked,
                onCheckedChange = { newValue ->
                    if (enabled) {
                        performAppCompatHapticFeedback(
                            view,
                            appHapticsConfig,
                            HapticFeedbackConstantsCompat.GESTURE_START
                        )
                        onCheckedChange(newValue)
                    }
                },
                enabled = enabled
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSelectorItem(
        label: String,
        description: String,
        options: Map<String, String>,
        selectedKey: String,
        onSelectionChanged: (String) -> Unit,
        leadingIcon: @Composable () -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    val selectedOption = options[selectedKey] ?: selectedKey

    if (LocalGlassModeEnabled.current) {
        GlassThemeSelectorRow(
            label = label,
            description = description,
            options = options,
            selectedKey = selectedKey,
            selectedOption = selectedOption,
            leadingIcon = leadingIcon,
            onSelectionChanged = onSelectionChanged,
            onOpenSheet = { showSheet = true }
        )
    } else Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier =
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
                        showSheet = true
                    }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                        contentAlignment = Alignment.Center
                ) { leadingIcon() }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Spacer(modifier = Modifier.height(10.dp))
                    
                    // Selected Value Badge
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = androidx.compose.foundation.shape.CircleShape,
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                             text = selectedOption,
                             style = MaterialTheme.typography.labelMedium,
                             color = MaterialTheme.colorScheme.primary,
                             fontWeight = FontWeight.Bold,
                             modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        AdaptiveModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall, // Larger header
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    fontWeight = FontWeight.Bold
                )
                
                LazyColumn(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .heightIn(max = 400.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(options.entries.toList(), key = { it.key.toString() }) { (key, optionLabel) ->
                        val isSelected = key == selectedKey
                        val containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                        val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
                        
                        AdaptivePressSurface(
                            onClick = {
                                onSelectionChanged(key)
                                showSheet = false
                            },
                            shape = RoundedCornerShape(24.dp),
                            color = containerColor,
                            modifier = Modifier.fillMaxWidth().height(72.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 24.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = optionLabel,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = contentColor,
                                    modifier = Modifier.weight(1f)
                                )
                                
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = stringResource(R.string.common_selected),
                                        tint = contentColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
fun ExpressiveSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    if (LocalGlassModeEnabled.current) {
        GlassSettingsGroup(modifier = modifier, content = content)
        return
    }
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Transparent),
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchableModelSelector(
    label: String,
    description: String,
    models: List<GeminiModel>,
    selectedModelName: String,
    onModelSelected: (String) -> Unit,
    leadingIcon: @Composable () -> Unit
) {
    var showSheet by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    val selectedDisplayName = models.find { it.name == selectedModelName }?.displayName ?: selectedModelName

    Surface(
        color = glassClear(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable { showSheet = true }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(24.dp),
                    contentAlignment = Alignment.Center
                ) { leadingIcon() }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerLowest,
                        shape = CircleShape,
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(
                            text = selectedDisplayName,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        AdaptiveModalBottomSheet(
            onDismissRequest = {
                showSheet = false
                searchQuery = ""
            },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(bottom = 24.dp)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                    fontWeight = FontWeight.Bold
                )

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Search models...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = "Search") },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Rounded.Clear, contentDescription = "Clear")
                            }
                        }
                    },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )

                Spacer(modifier = Modifier.height(4.dp))

                val filteredModels = remember(models, searchQuery) {
                    if (searchQuery.isBlank()) models
                    else models.filter {
                        it.name.contains(searchQuery, ignoreCase = true) ||
                            it.displayName.contains(searchQuery, ignoreCase = true)
                    }
                }

                Text(
                    text = "${filteredModels.size} model${if (filteredModels.size != 1) "s" else ""} available",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
                )

                LazyColumn(
                    modifier = Modifier
                        .padding(horizontal = 16.dp)
                        .heightIn(max = 400.dp)
                ) {
                    items(filteredModels, key = { it.name }) { model ->
                        val isSelected = model.name == selectedModelName
                        Surface(
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surfaceContainerHigh,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable {
                                    onModelSelected(model.name)
                                    showSheet = false
                                    searchQuery = ""
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = model.displayName,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                                                else MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = model.name,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        }
    }
}

@Composable
fun SliderSettingsItem(
        label: String,
        value: Float,
        valueRange: ClosedFloatingPointRange<Float>,
        steps: Int,
        onValueChange: (Float) -> Unit,
        onValueChangeFinished: (() -> Unit)? = null,
        valueText: (Float) -> String
) {
    if (LocalGlassModeEnabled.current) {
        GlassSliderSettingRow(
            label = label,
            value = value,
            valueRange = valueRange,
            steps = steps,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueText = valueText
        )
        return
    }
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
                        text = label,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                        text = valueText(value),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        softWrap = false
                )
            }
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

@Composable
fun RefreshLibraryItem(
        isSyncing: Boolean,
        syncProgress: SyncProgress,
        activeOperationLabel: String? = null,
        onFullSync: () -> Unit,
        onRebuild: () -> Unit
) {
    Surface(
            color = glassClear(MaterialTheme.colorScheme.surfaceContainer),
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                        modifier = Modifier.padding(end = 16.dp).size(24.dp),
                        contentAlignment = Alignment.Center
                ) {
                    Icon(
                            imageVector = Icons.Outlined.Sync,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.secondary
                    )
                }

                Column(
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                            text = stringResource(R.string.settings_refresh_library_title),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                            text = stringResource(R.string.settings_refresh_library_subtitle),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            
            // Full Rescan button
            FilledTonalButton(
                    onClick = onFullSync,
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TightWrapText(
                        text = stringResource(R.string.settings_action_full_rescan),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                        lineHeight = 22.sp,
                    )
                }
            }
             
            Spacer(modifier = Modifier.height(8.dp))
            
            // Rebuild Database button - full width, destructive action
            OutlinedButton(
                    onClick = onRebuild,
                    enabled = !isSyncing,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    TightWrapText(
                        text = stringResource(R.string.settings_action_rebuild_database),
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 2,
                        lineHeight = 22.sp,
                    )
                }
            }

            if (isSyncing) {
                Spacer(modifier = Modifier.height(12.dp))
                val phaseLabel = activeOperationLabel ?: syncPhaseLabel(syncProgress.phase)
                if (syncProgress.hasProgress) {
                    LinearProgressIndicator(
                            progress = { syncProgress.progress },
                            modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                            text = stringResource(
                                R.string.settings_sync_progress_detailed,
                                phaseLabel,
                                (syncProgress.progress * 100).toInt(),
                                syncProgress.currentCount,
                                syncProgress.totalCount
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                            text = stringResource(
                                R.string.settings_sync_progres_indeterminate,
                                phaseLabel
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun syncPhaseLabel(phase: SyncProgress.SyncPhase): String =
        stringResource(
                when (phase) {
                    SyncProgress.SyncPhase.IDLE -> R.string.settings_sync_phase_preparing
                    SyncProgress.SyncPhase.FETCHING_MEDIASTORE ->
                            R.string.settings_sync_phase_reading_mediastore
                    SyncProgress.SyncPhase.PROCESSING_FILES ->
                            R.string.settings_sync_phase_reading_processing_tracks
                    SyncProgress.SyncPhase.SAVING_TO_DATABASE ->
                            R.string.settings_sync_phase_saving_db
                    SyncProgress.SyncPhase.SCANNING_LRC -> R.string.settings_sync_phase_scanning_lrc
                    SyncProgress.SyncPhase.CLEANING_CACHE ->
                            R.string.settings_sync_phase_cleaning_cache
                    SyncProgress.SyncPhase.SYNCING_CLOUD ->
                            R.string.settings_sync_phase_syncing_cloud
                    SyncProgress.SyncPhase.COMPLETING -> R.string.settings_sync_phase_completing
                }
        )

@Composable
fun ActionSettingsItem(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    primaryActionLabel: String,
    onPrimaryAction: () -> Unit,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    Surface(
        color = glassClear(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    icon()
                }

                Column(
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Primary Action
            FilledTonalButton(
                onClick = onPrimaryAction,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(primaryActionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }

            // Secondary Action (Optional)
            if (secondaryActionLabel != null && onSecondaryAction != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onSecondaryAction,
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(secondaryActionLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun AiApiKeyItem(
    apiKey: String,
    onApiKeySave: (String) -> Unit,
    title: String,
    subtitle: String
) {
    var localApiKey by remember(apiKey) { mutableStateOf(apiKey) }
    val hasChanges = localApiKey != apiKey
    var showSaved by remember { mutableStateOf(false) }

    LaunchedEffect(showSaved) {
        if (showSaved) {
            kotlinx.coroutines.delay(2000)
            showSaved = false
        }
    }

    Surface(
        color = glassClear(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = localApiKey,
                onValueChange = { localApiKey = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.settings_enter_api_key_placeholder)) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation()
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = {
                        onApiKeySave(localApiKey)
                        showSaved = true
                    },
                    enabled = hasChanges
                ) {
                    Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (showSaved) {
                    Text(
                        text = stringResource(R.string.common_saved),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun AiSystemPromptItem(
    systemPrompt: String,
    defaultPrompt: String,
    onSystemPromptSave: (String) -> Unit,
    onReset: () -> Unit,
    title: String,
    subtitle: String
) {
    var localPrompt by remember(systemPrompt) { mutableStateOf(systemPrompt) }
    val hasChanges = localPrompt != systemPrompt
    val isDefault = systemPrompt == defaultPrompt
    var showSaved by remember { mutableStateOf(false) }
    val presets = listOf(
        stringResource(R.string.settings_preset_professional_curator_name) to
            stringResource(R.string.settings_preset_professional_curator_prompt),
        stringResource(R.string.settings_preset_creative_maverick_name) to
            stringResource(R.string.settings_preset_creative_maverick_prompt),
        stringResource(R.string.settings_preset_strict_librarian_name) to
            stringResource(R.string.settings_preset_strict_librarian_prompt),
        stringResource(R.string.settings_preset_atmospheric_guide_name) to
            stringResource(R.string.settings_preset_atmospheric_guide_prompt),
        stringResource(R.string.settings_preset_sonic_enthusiast_name) to
            stringResource(R.string.settings_preset_sonic_enthusiast_prompt),
        stringResource(R.string.settings_preset_energy_catalyst_name) to
            stringResource(R.string.settings_preset_energy_catalyst_prompt)
    )

    LaunchedEffect(showSaved) {
        if (showSaved) {
            kotlinx.coroutines.delay(2000)
            showSaved = false
        }
    }

    Surface(
        color = glassClear(MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.settings_preset_prompts),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                presets.forEach { preset ->
                    OutlinedButton(
                        onClick = { 
                            localPrompt = preset.second
                        },
                        modifier = Modifier.wrapContentWidth()
                    ) {
                        Text(text = preset.first, maxLines = 1)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = localPrompt,
                onValueChange = { localPrompt = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp, max = 200.dp),
                placeholder = { Text(stringResource(R.string.settings_system_prompt_placeholder)) },
                minLines = 3,
                maxLines = 6
            )
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalButton(
                    onClick = {
                        onSystemPromptSave(localPrompt)
                        showSaved = true
                    },
                    enabled = hasChanges
                ) {
                    Text(stringResource(R.string.common_save), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (!isDefault) {
                    OutlinedButton(onClick = {
                        onReset()
                    }) {
                        Text(stringResource(R.string.common_reset), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (showSaved) {
                    Text(
                        text = stringResource(R.string.common_saved),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------
// Liquid Glass mode (orchestrator decision G3): settings groups are NexHome GlassSections holding
// flat rows (no lens per row: a settings page would otherwise stack two lenses per row). Rows use
// the GlassSettingRow layout and type (min 60, padding 14/10, gap 12, BodyStrong + Caption) with a
// tinted 36 dp icon disc; presses swell and glow through the glass press indication the page
// provides. Toggles are LiquidToggles, sliders LiquidSliders, short choices LiquidSegmented.
// ------------------------------------------------------------------------------------------------

private val GlassFlatRowShape = RoundedCornerShape(20.dp)

/** A settings group in glass mode: one heavy GlassSection around flat rows. */
@Composable
internal fun GlassSettingsGroup(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    GlassSection(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
        spacing = 0.dp
    ) {
        content()
    }
}

/** NexHome's section caption: UPPERCASE Caption in the secondary colour, above a glass group. */
@Composable
internal fun GlassSettingsCaption(title: String, modifier: Modifier = Modifier) {
    GlassText(
        text = title.uppercase(),
        modifier = modifier.padding(start = 16.dp, top = 12.dp, bottom = 8.dp),
        style = GlassType.Caption,
        color = LocalGlassPalette.current.secondary,
        maxLines = 1
    )
}

/**
 * A flat glass settings row: optional icon disc, title + subtitle, trailing slot, and optional
 * [below] content (a slider, a segmented choice). Clickable rows answer with the page's glass press
 * indication (swell + dim glow); a disabled row fades its text.
 */
@Composable
private fun GlassFlatSettingRow(
    title: String,
    subtitle: String?,
    leadingIcon: (@Composable () -> Unit)?,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    below: (@Composable ColumnScope.() -> Unit)? = null
) {
    val palette = LocalGlassPalette.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(GlassFlatRowShape)
            .then(
                if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick)
                else Modifier
            )
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (leadingIcon != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(palette.tintSubtle, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    CompositionLocalProvider(LocalContentColor provides palette.accent) {
                        Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                            leadingIcon()
                        }
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                GlassText(
                    text = title,
                    style = GlassType.BodyStrong,
                    color = if (enabled) palette.primary else palette.tertiary,
                    maxLines = 2
                )
                if (!subtitle.isNullOrEmpty()) {
                    GlassText(
                        text = subtitle,
                        style = GlassType.Caption,
                        color = if (enabled) palette.secondary else palette.quaternary,
                        maxLines = 4
                    )
                }
            }
            trailing?.invoke(this)
        }
        below?.invoke(this)
    }
}

/** A glass slider row: label and value on top, a LiquidSlider (steps and finish callback kept). */
@Composable
private fun GlassSliderSettingRow(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)?,
    valueText: (Float) -> String
) {
    val palette = LocalGlassPalette.current
    GlassFlatSettingRow(
        title = label,
        subtitle = null,
        leadingIcon = null,
        onClick = null,
        trailing = {
            GlassText(
                text = valueText(value),
                style = GlassType.Label,
                color = palette.accent,
                maxLines = 1
            )
        },
        below = {
            LiquidSlider(
                value = { value },
                onValueChange = onValueChange,
                valueRange = valueRange,
                visibilityThreshold = (valueRange.endInclusive - valueRange.start) / 1000f,
                modifier = Modifier.padding(vertical = 6.dp),
                steps = steps,
                onValueChangeFinished = onValueChangeFinished
            )
        }
    )
}

/**
 * A glass choice row: the title, description and current value. With two or three short options,
 * tapping the row opens a NexHome LiquidSegmented under it (a tap or a drag selects); only one
 * choice row is open at a time across the app's settings, so a page never stacks several
 * three-layer segmented controls (layer budget). Longer lists open the (glass) option sheet.
 */
@Composable
private fun GlassThemeSelectorRow(
    label: String,
    description: String,
    options: Map<String, String>,
    selectedKey: String,
    selectedOption: String,
    leadingIcon: @Composable () -> Unit,
    onSelectionChanged: (String) -> Unit,
    onOpenSheet: () -> Unit
) {
    val palette = LocalGlassPalette.current
    val entries = remember(options) { options.entries.toList() }
    val segmented = entries.size in 2..3 && entries.all { it.value.length <= GlassSegmentMaxLabel }
    val expanded = segmented && GlassChoiceExpansion.openKey == label
    if (segmented) {
        DisposableEffect(label) {
            onDispose { GlassChoiceExpansion.close(label) }
        }
    }
    GlassFlatSettingRow(
        title = label,
        subtitle = description,
        leadingIcon = leadingIcon,
        onClick = if (segmented) {
            { GlassChoiceExpansion.toggle(label) }
        } else {
            onOpenSheet
        },
        trailing = {
            GlassText(
                text = selectedOption,
                modifier = Modifier.widthIn(max = 132.dp),
                style = GlassType.Label,
                color = palette.accent,
                maxLines = 1
            )
        },
        below = if (segmented) {
            {
                AnimatedVisibility(
                    visible = expanded,
                    enter = fadeIn(tween(180)) + expandVertically(tween(220)),
                    exit = fadeOut(tween(120)) + shrinkVertically(tween(200))
                ) {
                    val segments = remember(entries) { entries.map { SegmentOption(it.value) } }
                    val selectedIndex = entries.indexOfFirst { it.key == selectedKey }.coerceAtLeast(0)
                    LiquidSegmented(
                        options = segments,
                        selectedIndex = selectedIndex,
                        onSelect = { index -> entries.getOrNull(index)?.let { onSelectionChanged(it.key) } }
                    )
                }
            }
        } else {
            null
        }
    )
}

/** Longest option label a segmented choice takes before the row falls back to the sheet. */
private const val GlassSegmentMaxLabel = 14

/** Which glass choice row has its segmented control open (one at a time). Main thread only. */
@Stable
private object GlassChoiceExpansion {
    var openKey: String? by mutableStateOf(null)
        private set

    fun toggle(key: String) {
        openKey = if (openKey == key) null else key
    }

    fun close(key: String) {
        if (openKey == key) openKey = null
    }
}
