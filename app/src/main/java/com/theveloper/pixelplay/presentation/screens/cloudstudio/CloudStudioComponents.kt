package com.theveloper.pixelplay.presentation.screens.cloudstudio

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.data.cloudstudio.CloudCheck
import com.theveloper.pixelplay.presentation.components.CollapsibleCommonTopBar
import com.theveloper.pixelplay.presentation.components.CollapsingHeaderHeight
import com.theveloper.pixelplay.presentation.components.MiniPlayerHeight
import com.theveloper.pixelplay.presentation.components.WithCollapsingHeader
import com.theveloper.pixelplay.presentation.components.glassClear
import com.theveloper.pixelplay.presentation.components.rememberCollapseFraction
import com.theveloper.pixelplay.presentation.components.rememberCollapsingHeaderContentPadding
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A Cloud Studio screen: the app's collapsing settings header over one lazy list (the Experimental screen's layout).
 * The per-frame header height is read only by the header and the list's measure pass, never by the list's items.
 */
@Composable
internal fun CloudScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val minPx = with(density) { (64.dp + statusBar).toPx() }
    val maxPx = with(density) { 180.dp.toPx() }
    val headerHeight = remember { CollapsingHeaderHeight(maxPx) }
    val fraction = rememberCollapseFraction(headerHeight, minPx, maxPx)

    val nestedScroll = remember(minPx, maxPx) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val delta = available.y
                val down = delta < 0
                if (!down && (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)) {
                    return Offset.Zero
                }
                val previous = headerHeight.value
                val next = (previous + delta).coerceIn(minPx, maxPx)
                val consumed = next - previous
                if (consumed.roundToInt() != 0) headerHeight.snapTo(next)
                return if (down && next == minPx) Offset.Zero else Offset(0f, consumed)
            }
        }
    }
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val expand = headerHeight.value > (minPx + maxPx) / 2 &&
                listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
            val target = if (expand) maxPx else minPx
            if (headerHeight.value != target) {
                scope.launch { headerHeight.animateTo(target, spring(stiffness = Spring.StiffnessMedium)) }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .nestedScroll(nestedScroll)
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = rememberCollapsingHeaderContentPadding(
                height = headerHeight, extraTop = 8.dp, bottom = MiniPlayerHeight + 36.dp
            ),
            content = content,
        )
        WithCollapsingHeader(headerHeight, fraction) { collapse, height ->
            CollapsibleCommonTopBar(title = title, collapseFraction = collapse, headerHeight = height, onBackClick = onBack)
        }
    }
}

/** A labelled group of rows (the app's settings sections: primary label, 4 dp between rows). */
@Composable
internal fun CloudSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
    }
}

/** A plain settings panel (the iOS `SettingsPanel`): a surface-container card with 16 dp padding. */
@Composable
internal fun CloudPanel(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        color = glassClear(color),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            content = content,
        )
    }
}

/**
 * A single-line field. [secret] hides what is typed (with a show toggle), turns off suggestions and learning, and
 * keeps the value out of the keyboard's memory, as for a password.
 */
@Composable
internal fun CloudTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Ascii,
) {
    var revealed by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = if (placeholder.isNotEmpty()) ({ Text(placeholder) }) else null,
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        visualTransformation = if (secret && !revealed) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = if (secret) KeyboardType.Password else keyboardType,
            imeAction = ImeAction.Next,
        ),
        trailingIcon = if (secret) ({
            IconButton(onClick = { revealed = !revealed }) {
                Icon(
                    imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                    contentDescription = if (revealed) "Hide" else "Show",
                )
            }
        }) else null,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** One Test connection result: a tick or a cross, the sentence, and the detail. */
@Composable
internal fun CloudCheckLine(title: String, check: CloudCheck) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = if (check.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Cancel,
            contentDescription = if (check.ok) "Passed" else "Failed",
            tint = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(check.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            check.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Secondary text inside a panel. */
@Composable
internal fun CloudCaption(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
