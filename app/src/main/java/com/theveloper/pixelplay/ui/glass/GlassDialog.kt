package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AlertDialogDefaults
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties

/**
 * Drop-in replacement for Material 3's [AlertDialog], with the same parameters.
 *
 * - **Material 3 mode:** this *is* `AlertDialog(...)` with exactly the arguments given.
 * - **Liquid Glass mode:** the same layout (24dp padding; icon, title, text, then the buttons
 *   right-aligned in a wrapping row) on a pane of glass using the Dialog recipe — colour-controls
 *   filter, blur, depth lens, the plain highlight and a soft shadow. A dialog renders in its own
 *   window, so it refracts the on-demand window snapshot (it registers for one while shown), never
 *   the live page. Buttons on it stay the caller's text buttons: fills, not glass on glass.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    shape: Shape = AlertDialogDefaults.shape,
    containerColor: Color = AlertDialogDefaults.containerColor,
    iconContentColor: Color = AlertDialogDefaults.iconContentColor,
    titleContentColor: Color = AlertDialogDefaults.titleContentColor,
    textContentColor: Color = AlertDialogDefaults.textContentColor,
    tonalElevation: Dp = AlertDialogDefaults.TonalElevation,
    properties: DialogProperties = DialogProperties()
) {
    if (!isGlassEnabled) {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            shape = shape,
            containerColor = containerColor,
            iconContentColor = iconContentColor,
            titleContentColor = titleContentColor,
            textContentColor = textContentColor,
            tonalElevation = tonalElevation,
            properties = properties
        )
        return
    }

    BasicAlertDialog(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        properties = properties
    ) {
        val backdrop = rememberSheetBackdrop()
        val recipe = resolveRecipe(GlassRole.Dialog).withTintColor(containerColor)
        Box(Modifier.liquidGlass(recipe = recipe, shape = shape, backdrop = backdrop)) {
            CompositionLocalProvider(
                LocalGlassLayer provides GlassLayer.OnGlass,
                LocalRecordingBackdrops provides emptySet()
            ) {
                GlassAlertDialogContent(
                    confirmButton = confirmButton,
                    dismissButton = dismissButton,
                    icon = icon,
                    title = title,
                    text = text,
                    iconContentColor = iconContentColor,
                    titleContentColor = titleContentColor,
                    textContentColor = textContentColor
                )
            }
        }
    }
}

/** Material 3's alert-dialog layout, reproduced for the glass pane. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GlassAlertDialogContent(
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)?,
    icon: (@Composable () -> Unit)?,
    title: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    iconContentColor: Color,
    titleContentColor: Color,
    textContentColor: Color
) {
    val typography = MaterialTheme.typography
    Column(Modifier.padding(24.dp)) {
        icon?.let {
            CompositionLocalProvider(LocalContentColor provides iconContentColor) {
                Box(
                    Modifier
                        .padding(bottom = 16.dp)
                        .align(Alignment.CenterHorizontally)
                ) { it() }
            }
        }
        title?.let {
            ProvideColorAndStyle(titleContentColor, typography.headlineSmall) {
                Box(
                    Modifier
                        .padding(bottom = 16.dp)
                        .align(if (icon == null) Alignment.Start else Alignment.CenterHorizontally)
                ) { it() }
            }
        }
        text?.let {
            ProvideColorAndStyle(textContentColor, typography.bodyMedium) {
                Box(
                    Modifier
                        .weight(weight = 1f, fill = false)
                        .padding(bottom = 24.dp)
                        .align(Alignment.Start)
                ) { it() }
            }
        }
        Box(Modifier.align(Alignment.End)) {
            ProvideColorAndStyle(MaterialTheme.colorScheme.primary, typography.labelLarge) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    dismissButton?.invoke()
                    confirmButton()
                }
            }
        }
    }
}

@Composable
private fun ProvideColorAndStyle(
    color: Color,
    style: TextStyle,
    content: @Composable () -> Unit
) {
    CompositionLocalProvider(
        LocalContentColor provides color,
        LocalTextStyle provides LocalTextStyle.current.merge(style),
        content = content
    )
}
