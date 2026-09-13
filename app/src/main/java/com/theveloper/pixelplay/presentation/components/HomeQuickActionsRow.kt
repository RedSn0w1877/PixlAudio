package com.theveloper.pixelplay.presentation.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList

/** One tappable shortcut in the [HomeQuickActionsRow]. */
@Immutable
data class HomeQuickAction(
    val label: String,
    @DrawableRes val iconRes: Int,
    val onClick: () -> Unit
)

/**
 * A horizontally-scrolling row of shortcut chips shown under the home greeting, so common
 * actions (shuffle everything, jump to Recently Played/Stats/DJ Mashup) don't need a trip
 * through the bottom nav + a second screen first.
 */
@Composable
fun HomeQuickActionsRow(
    actions: ImmutableList<HomeQuickAction>,
    modifier: Modifier = Modifier
) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 16.dp)
    ) {
        items(actions, key = { it.label }) { action ->
            HomeQuickActionChip(action)
        }
    }
}

@Composable
private fun HomeQuickActionChip(action: HomeQuickAction) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(colors.secondaryContainer)
            .clickable(onClick = action.onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(
            painter = painterResource(action.iconRes),
            contentDescription = null,
            tint = colors.onSecondaryContainer,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = action.label,
            style = MaterialTheme.typography.labelLarge,
            color = colors.onSecondaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
