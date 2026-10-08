package com.theveloper.pixelplay.presentation.screens

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.view.HapticFeedbackConstantsCompat
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.preferences.AccentColor
import com.theveloper.pixelplay.data.preferences.AccentPreset
import com.theveloper.pixelplay.presentation.components.AdaptiveModalBottomSheet
import com.theveloper.pixelplay.presentation.utils.LocalAppHapticsConfig
import com.theveloper.pixelplay.presentation.utils.performAppCompatHapticFeedback
import com.theveloper.pixelplay.presentation.viewmodel.ColorSchemePair
import com.theveloper.pixelplay.ui.glass.LocalGlassModeEnabled
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.components.LiquidButton
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette
import com.theveloper.pixelplay.ui.theme.DarkColorScheme
import com.theveloper.pixelplay.ui.theme.LightColorScheme
import com.theveloper.pixelplay.ui.theme.LocalPixelPlayDarkTheme
import com.theveloper.pixelplay.ui.theme.generateAccentColorSchemePair
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/*
 * Settings › Appearance › Accent Color (2026-10-07 batch, DECISIONS › Accent colour): one settings
 * row with Dynamic (Material You, the default), ten presets and Custom as two rows of six swatches,
 * laid out like ThemeSelectorItem (icon, title, description) with the grid where its value badge is.
 * A choice re-themes the whole app at once (ThemeStateHolder.accentScheme → PixelPlayTheme).
 *
 * The swatches are plain fills (no glass on glass in glass mode) and show the named colour itself,
 * as on iOS: the app around the row shows the accent scheme's tone of it, which in dark mode is
 * pastel and nearly equal for Red/Pink or Blue/Indigo, so tones would make the grid hard to read.
 * It also means the row builds no schemes at all.
 */

/** Swatch, ring and touch-target sizes: six 48 dp cells fit a 360 dp phone's text column. */
private val SwatchCellHeight = 48.dp
private val SwatchSize = 32.dp
private val SwatchRingSize = 42.dp
private const val SwatchesPerRow = 6

/** The grid, row by row: Dynamic, Blue … Graphite, then Custom (null). */
private val SwatchRows: List<List<AccentPreset?>> =
    (AccentPreset.entries + listOf<AccentPreset?>(null)).chunked(SwatchesPerRow)

/** Hue wheel for the Custom cell and the picker's hue bar. Size-independent, so built once. */
private val HueColors: List<Color> =
    listOf(0f, 60f, 120f, 180f, 240f, 300f, 360f).map { Color.hsv(it, 1f, 1f) }
private val HueSweepBrush = Brush.sweepGradient(HueColors)
private val HueBarBrush = Brush.horizontalGradient(HueColors)
private val ValueShadeBrush = Brush.verticalGradient(listOf(Color.Transparent, Color.Black))

@Composable
fun AccentColorSettingItem(
    selectedHex: String,
    onSelect: (String) -> Unit
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val selectedPreset = remember(selectedHex) { AccentColor.presetFor(selectedHex) }
    val selectedName = stringResource(selectedPreset?.labelRes ?: R.string.settings_accent_color_custom)
    val title = stringResource(R.string.settings_accent_color_title)
    val subtitle = stringResource(R.string.settings_accent_color_subtitle)
    val dynamicPrimary = rememberDynamicPrimary()
    val view = LocalView.current
    val haptics = LocalAppHapticsConfig.current
    val choose: (String) -> Unit = remember(view, haptics, onSelect) {
        { hex ->
            performAppCompatHapticFeedback(view, haptics, HapticFeedbackConstantsCompat.CLOCK_TICK)
            onSelect(hex)
        }
    }
    val openPicker = remember { { showPicker = true } }
    val leadingIcon: @Composable () -> Unit = {
        Icon(Icons.Outlined.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.secondary)
    }

    if (LocalGlassModeEnabled.current) {
        val palette = LocalGlassPalette.current
        GlassFlatSettingRow(
            title = title,
            subtitle = subtitle,
            leadingIcon = leadingIcon,
            onClick = null,
            trailing = {
                GlassText(
                    text = selectedName,
                    modifier = Modifier.widthIn(max = 132.dp),
                    style = GlassType.Label,
                    color = palette.accent,
                    maxLines = 1
                )
            },
            below = {
                AccentSwatchGrid(
                    selectedHex = selectedHex,
                    selectedPreset = selectedPreset,
                    dynamicPrimary = dynamicPrimary,
                    ringColor = palette.primary,
                    onSelect = choose,
                    onCustom = openPicker
                )
            }
        )
    } else {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
        ) {
            Row(modifier = Modifier.padding(16.dp)) {
                Box(
                    modifier = Modifier.padding(end = 16.dp).size(24.dp),
                    contentAlignment = Alignment.Center
                ) { leadingIcon() }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AccentSwatchGrid(
                        selectedHex = selectedHex,
                        selectedPreset = selectedPreset,
                        dynamicPrimary = dynamicPrimary,
                        ringColor = MaterialTheme.colorScheme.onSurface,
                        onSelect = choose,
                        onCustom = openPicker
                    )
                }
            }
        }
    }

    if (showPicker) {
        // Start from the current accent's own colour; with Dynamic, from the wallpaper primary.
        val initialArgb = AccentColor.seedOrNull(selectedHex) ?: dynamicPrimary.toArgb()
        AccentColorPickerSheet(
            initialArgb = initialArgb,
            onDismiss = { showPicker = false },
            onApply = { hex ->
                showPicker = false
                choose(hex)
            }
        )
    }
}

/**
 * The Dynamic cell's colour: Material You's primary for the app's light/dark setting (API 31+), the
 * static scheme's on API 30. Remembered like PixelPlayTheme's dynamic scheme; a wallpaper change
 * recreates the Activity.
 */
@Composable
private fun rememberDynamicPrimary(): Color {
    val isDark = LocalPixelPlayDarkTheme.current
    val context = LocalContext.current
    val uiMode = LocalConfiguration.current.uiMode
    return remember(context, isDark, uiMode) {
        val dynamic = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (isDark) dynamicDarkColorScheme(context).primary else dynamicLightColorScheme(context).primary
            }.getOrNull()
        } else {
            null
        }
        dynamic ?: if (isDark) DarkColorScheme.primary else LightColorScheme.primary
    }
}

@Composable
private fun AccentSwatchGrid(
    selectedHex: String,
    selectedPreset: AccentPreset?,
    dynamicPrimary: Color,
    ringColor: Color,
    onSelect: (String) -> Unit,
    onCustom: () -> Unit
) {
    val customSeed = if (selectedPreset == null) AccentColor.seedOrNull(selectedHex) else null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SwatchRows.forEach { rowCells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowCells.forEach { preset ->
                    if (preset != null) {
                        val fill = if (preset == AccentPreset.DYNAMIC) {
                            dynamicPrimary
                        } else {
                            Color(AccentColor.seedOrNull(preset.hex) ?: 0)
                        }
                        val label = stringResource(
                            if (preset == AccentPreset.DYNAMIC) {
                                R.string.settings_accent_color_dynamic_description
                            } else {
                                preset.labelRes
                            }
                        )
                        AccentSwatchCell(
                            label = label,
                            selected = preset == selectedPreset,
                            fill = fill,
                            ringColor = ringColor,
                            onClick = { if (preset.hex != selectedHex) onSelect(preset.hex) },
                            glyph = if (preset == AccentPreset.DYNAMIC) Icons.Rounded.Wallpaper else null
                        )
                    } else {
                        AccentSwatchCell(
                            label = stringResource(R.string.settings_accent_color_custom),
                            selected = customSeed != null,
                            fill = customSeed?.let { Color(it) },
                            ringColor = ringColor,
                            onClick = onCustom,
                            glyph = if (customSeed == null) Icons.Rounded.Add else null
                        )
                    }
                }
            }
        }
    }
}

/**
 * One swatch: a 48 dp radio target with a 32 dp fill ([fill], or the hue wheel when null), a ring and a
 * check when [selected], else an optional [glyph] (Dynamic's wallpaper, Custom's plus).
 */
@Composable
private fun RowScope.AccentSwatchCell(
    label: String,
    selected: Boolean,
    fill: Color?,
    ringColor: Color,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.vector.ImageVector?
) {
    val glyphTint = when {
        fill == null -> Color.White
        AccentColor.prefersDarkContent(fill.toArgb()) -> Color.Black
        else -> Color.White
    }
    Box(
        modifier = Modifier
            .weight(1f)
            .height(SwatchCellHeight)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(SwatchRingSize)
                    .border(2.dp, ringColor, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(SwatchSize)
                .drawBehind {
                    if (fill != null) drawCircle(fill) else drawCircle(HueSweepBrush)
                },
            contentAlignment = Alignment.Center
        ) {
            when {
                selected -> Icon(
                    Icons.Rounded.Check,
                    contentDescription = null,
                    tint = glyphTint,
                    modifier = Modifier.size(18.dp)
                )
                glyph != null -> Icon(
                    glyph,
                    contentDescription = null,
                    tint = glyphTint.copy(alpha = 0.85f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

/**
 * The custom picker's state: HSV plus the hex field's text. Drags write the floats, which only the
 * panel's draw and layout lambdas read, so a drag step redraws the panel and recomposes only the hex
 * field and the picked swatch, never the sheet.
 */
@Stable
private class AccentPickerState(hue: Float, saturation: Float, brightness: Float, hexText: String) {
    var hue by mutableFloatStateOf(hue)
        private set
    var saturation by mutableFloatStateOf(saturation)
        private set
    var brightness by mutableFloatStateOf(brightness)
        private set
    var hexText by mutableStateOf(hexText)
        private set

    val argb: Int get() = AccentColor.hsvToArgb(hue, saturation, brightness)
    val isHexValid: Boolean get() = AccentColor.seedOrNull(hexText) != null

    fun setSaturationBrightness(saturation: Float, brightness: Float) {
        this.saturation = saturation.coerceIn(0f, 1f)
        this.brightness = brightness.coerceIn(0f, 1f)
        hexText = AccentColor.toHex(argb)
    }

    fun setHue(hue: Float) {
        this.hue = hue.coerceIn(0f, 359.9f)
        hexText = AccentColor.toHex(argb)
    }

    /** Hex input: kept to `#` and hex digits (7 at most); a complete colour moves the panel too. */
    fun onHexInput(raw: String) {
        val filtered = raw.filter { it == '#' || it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }.take(7)
        hexText = filtered.uppercase()
        val seed = AccentColor.seedOrNull(filtered) ?: return
        val hsv = AccentColor.argbToHsv(seed)
        // A grey has no hue: keep the bar where it is.
        if (hsv[1] > 0f) hue = hsv[0]
        saturation = hsv[1]
        brightness = hsv[2]
    }

    companion object {
        fun from(argb: Int): AccentPickerState {
            val hsv = AccentColor.argbToHsv(argb)
            return AccentPickerState(hsv[0], hsv[1], hsv[2], AccentColor.toHex(argb))
        }

        val Saver = listSaver<AccentPickerState, Any>(
            save = { listOf(it.hue, it.saturation, it.brightness, it.hexText) },
            restore = {
                AccentPickerState(it[0] as Float, it[1] as Float, it[2] as Float, it[3] as String)
            }
        )
    }
}

/**
 * Custom: a saturation/brightness panel, a hue bar and a hex field, with the picked colour and its
 * in-app tone side by side. Nothing is saved until "Use color", so a drag never re-themes the app
 * step by step (and never stores a half-dragged colour). The sheet's own drag is off, so dragging in
 * the panel can't pull the sheet down.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AccentColorPickerSheet(
    initialArgb: Int,
    onDismiss: () -> Unit,
    onApply: (String) -> Unit
) {
    val state = rememberSaveable(saver = AccentPickerState.Saver) { AccentPickerState.from(initialArgb) }
    val isDark = LocalPixelPlayDarkTheme.current
    val glassMode = LocalGlassModeEnabled.current

    // The in-app tone of the picked colour, built off the main thread 120 ms after the last step.
    // Uncached on purpose (generateAccentColorSchemePair, not AccentColorSchemes), so drag steps
    // don't fill the app's memo.
    var preview by remember { mutableStateOf<ColorSchemePair?>(null) }
    LaunchedEffect(state) {
        snapshotFlow { state.argb }
            .distinctUntilChanged()
            .collectLatest { argb ->
                delay(120)
                preview = withContext(Dispatchers.Default) { generateAccentColorSchemePair(argb) }
            }
    }

    AdaptiveModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        sheetGesturesEnabled = false,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_accent_color_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )
            Column(
                modifier = Modifier.padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Above the panel, so the keyboard never covers the field.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PickedSwatch(state)
                    val inApp = preview?.let { if (isDark) it.dark else it.light }
                    InAppSwatch(
                        primary = inApp?.primary,
                        onPrimary = inApp?.onPrimary
                    )
                    AccentHexField(state, modifier = Modifier.weight(1f))
                }
                SaturationBrightnessPanel(state)
                HueBar(state)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val cancel = stringResource(R.string.common_cancel)
                    val apply = stringResource(R.string.settings_accent_color_apply)
                    val onApplyClick: () -> Unit = { if (state.isHexValid) onApply(AccentColor.toHex(state.argb)) }
                    if (glassMode) {
                        val palette = LocalGlassPalette.current
                        LiquidButton(onClick = onDismiss, surfaceColor = palette.tintSubtle) {
                            GlassText(cancel, style = GlassType.BodyStrong, maxLines = 1)
                        }
                        LiquidButton(onClick = onApplyClick, tint = palette.accent) {
                            GlassText(apply, style = GlassType.BodyStrong, maxLines = 1)
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text(cancel) }
                        Button(onClick = onApplyClick, enabled = state.isHexValid) { Text(apply) }
                    }
                }
            }
        }
    }
}

/** The raw picked colour, drawn at draw time so a drag step only redraws it. */
@Composable
private fun PickedSwatch(state: AccentPickerState) {
    val label = stringResource(R.string.settings_accent_color_picked)
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .drawBehind { drawCircle(Color(state.argb)) }
    )
}

/** The accent scheme's primary for the picked colour, with "Aa" in its onPrimary (how buttons will look). */
@Composable
private fun InAppSwatch(primary: Color?, onPrimary: Color?) {
    val label = stringResource(R.string.settings_accent_color_in_app)
    Box(
        modifier = Modifier
            .size(48.dp)
            .semantics { contentDescription = label }
            .drawBehind { if (primary != null) drawCircle(primary) },
        contentAlignment = Alignment.Center
    ) {
        if (primary != null && onPrimary != null) {
            Text(
                text = "Aa",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = onPrimary
            )
        }
    }
}

@Composable
private fun AccentHexField(state: AccentPickerState, modifier: Modifier = Modifier) {
    val invalid = !state.isHexValid
    OutlinedTextField(
        value = state.hexText,
        onValueChange = state::onHexInput,
        modifier = modifier,
        label = { Text(stringResource(R.string.settings_accent_color_hex)) },
        singleLine = true,
        isError = invalid,
        supportingText = if (invalid) {
            { Text(stringResource(R.string.settings_accent_color_invalid_hex)) }
        } else {
            null
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions.Default
    )
}

/**
 * Saturation left to right, brightness top to bottom, for the current hue. The gradient is rebuilt
 * only when the hue changes (drawWithCache reads it); the thumb moves in its layout lambda.
 */
@Composable
private fun SaturationBrightnessPanel(state: AccentPickerState) {
    val label = stringResource(R.string.settings_accent_color_saturation_brightness)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1.6f)
            .clip(RoundedCornerShape(16.dp))
            .semantics { contentDescription = label }
            .drawWithCache {
                val saturationBrush = Brush.horizontalGradient(
                    listOf(Color.White, Color.hsv(state.hue, 1f, 1f))
                )
                onDrawBehind {
                    drawRect(saturationBrush)
                    drawRect(ValueShadeBrush)
                }
            }
            .pointerInput(state) {
                trackDrag { position ->
                    state.setSaturationBrightness(
                        saturation = position.x / size.width.coerceAtLeast(1),
                        brightness = 1f - position.y / size.height.coerceAtLeast(1)
                    )
                }
            }
    ) {
        PickerThumb(
            state = state,
            offset = { width, height ->
                Offset(state.saturation * width, (1f - state.brightness) * height)
            }
        )
    }
}

@Composable
private fun HueBar(state: AccentPickerState) {
    val label = stringResource(R.string.settings_accent_color_hue)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .semantics { contentDescription = label }
            .drawBehind { drawRect(HueBarBrush) }
            .pointerInput(state) {
                trackDrag { position ->
                    state.setHue(360f * position.x / size.width.coerceAtLeast(1))
                }
            }
    ) {
        PickerThumb(
            state = state,
            offset = { width, height -> Offset(state.hue / 360f * width, height / 2f) }
        )
    }
}

/**
 * A 20 dp white ring filled with the picked colour, centred on [offset] (in px, given the parent's
 * size). Position and fill are read in the layout and draw lambdas only.
 */
@Composable
private fun PickerThumb(state: AccentPickerState, offset: (width: Float, height: Float) -> Offset) {
    Box(
        modifier = Modifier
            .layoutThumb(offset)
            .size(20.dp)
            .drawBehind {
                drawCircle(Color.Black.copy(alpha = 0.25f), radius = size.minDimension / 2f + 1.dp.toPx())
                drawCircle(Color.White)
                drawCircle(Color(state.argb), radius = size.minDimension / 2f - 2.5.dp.toPx())
            }
    )
}

/** Places a thumb so its centre sits on [offset] inside the parent (whose size the lambda gets). */
private fun Modifier.layoutThumb(offset: (width: Float, height: Float) -> Offset): Modifier =
    this.then(
        Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
            val parentWidth = constraints.maxWidth
            val parentHeight = constraints.maxHeight
            layout(parentWidth, parentHeight) {
                val centre = offset(parentWidth.toFloat(), parentHeight.toFloat())
                placeable.place(
                    (centre.x - placeable.width / 2f).roundToInt(),
                    (centre.y - placeable.height / 2f).roundToInt()
                )
            }
        }
    )

/** Every pointer down and drag step reports its position (consumed, so nothing behind moves). */
private suspend fun PointerInputScope.trackDrag(onPosition: (Offset) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        onPosition(down.position)
        drag(down.id) { change ->
            change.consume()
            onPosition(change.position)
        }
    }
}
