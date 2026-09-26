package com.theveloper.pixelplay.ui.glass

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule
import com.theveloper.pixelplay.ui.glass.components.GlassIcon
import com.theveloper.pixelplay.ui.glass.components.GlassPanel
import com.theveloper.pixelplay.ui.glass.components.GlassText
import com.theveloper.pixelplay.ui.glass.theme.GlassType
import com.theveloper.pixelplay.ui.glass.theme.LocalGlassPalette

/**
 * The glass search field (NexHome has none; built from its top bar, research-nexhome-design §10.2):
 * a 52 dp light [GlassPanel] capsule with the top-bar tint and no glint, a 20 dp search glyph in
 * Secondary, a [BasicTextField] in Body 15 with a Tertiary placeholder, and a 40 dp clear orb while
 * there is a query.
 */
@Composable
fun GlassSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    searchIconDescription: String? = null,
    clearDescription: String? = null,
) {
    val palette = LocalGlassPalette.current
    val textStyle = remember(palette.primary) { GlassType.Body.copy(color = palette.primary) }
    val cursor = remember(palette.accent) { SolidColor(palette.accent) }
    GlassPanel(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = Capsule(),
        tint = palette.topBar,
        showHighlight = false,
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(start = 16.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            GlassIcon(
                Icons.Rounded.Search,
                modifier = if (searchIconDescription != null) {
                    Modifier.semantics { contentDescription = searchIconDescription }
                } else {
                    Modifier
                },
                tint = palette.secondary,
                size = 20.dp,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (query.isEmpty()) {
                    GlassText(placeholder, style = GlassType.Body, color = palette.tertiary, maxLines = 1)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = textStyle,
                    cursorBrush = cursor,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch(query) }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier),
                )
            }
            if (query.isNotEmpty()) {
                GlassCircleAction(onClick = { onQueryChange("") }, contentDescription = clearDescription) {
                    GlassIcon(Icons.Rounded.Close, size = 20.dp)
                }
            }
        }
    }
}
