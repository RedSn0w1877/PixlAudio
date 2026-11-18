@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.ui.theme.FlexPresets
import com.theveloper.pixelplay.ui.theme.flexStyle

private val GroupOuter = 24.dp
private val GroupInner = 6.dp
private val GroupTop: Shape = RoundedCornerShape(topStart = GroupOuter, topEnd = GroupOuter, bottomStart = GroupInner, bottomEnd = GroupInner)
private val GroupMiddle: Shape = RoundedCornerShape(GroupInner)
private val GroupBottom: Shape = RoundedCornerShape(topStart = GroupInner, topEnd = GroupInner, bottomStart = GroupOuter, bottomEnd = GroupOuter)

/**
 * "How picks work" (docs/brain-spec.md, 8.9): the promises the engine keeps, the Autoplay switch and
 * the way to Taste changes. Static text; the stage 1 version says only what is true of stage 1.
 */
@Composable
fun HowPicksWorkSheet(
    autoplayEnabled: Boolean,
    onAutoplayChange: (Boolean) -> Unit,
    onOpenTasteChanges: () -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Text(
                text = stringResource(R.string.brain_how_picks_work),
                style = flexStyle(MaterialTheme.typography.headlineMedium, FlexPresets.HeroDisplay),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.brain_how_intro),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                PromiseRow(Icons.Rounded.Verified, stringResource(R.string.brain_promise_paid_title), null, GroupTop)
                PromiseRow(
                    Icons.Rounded.Repeat,
                    stringResource(R.string.brain_promise_repeat_title),
                    stringResource(R.string.brain_promise_repeat_body),
                    GroupMiddle,
                )
                PromiseRow(
                    Icons.Rounded.Visibility,
                    stringResource(R.string.brain_promise_why_title),
                    stringResource(R.string.brain_promise_why_body),
                    GroupMiddle,
                )
                PromiseRow(
                    Icons.AutoMirrored.Rounded.PlaylistAdd,
                    stringResource(R.string.brain_promise_playlists_title),
                    stringResource(R.string.brain_promise_playlists_body),
                    GroupMiddle,
                )
                PromiseRow(
                    Icons.Rounded.Smartphone,
                    stringResource(R.string.brain_promise_phone_title),
                    stringResource(R.string.brain_promise_phone_body),
                    GroupBottom,
                )
            }

            Spacer(Modifier.height(16.dp))

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    shape = GroupTop,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(GroupTop)
                        .clickable(role = Role.Switch) { onAutoplayChange(!autoplayEnabled) },
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.brain_how_autoplay_title), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.brain_how_autoplay_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        // The row itself is the control: the switch only shows the state.
                        Switch(checked = autoplayEnabled, onCheckedChange = null)
                    }
                }
                Surface(
                    shape = GroupBottom,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(GroupBottom)
                        .clickable(role = Role.Button, onClick = onOpenTasteChanges),
                ) {
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.brain_how_taste_changes), style = MaterialTheme.typography.titleMedium)
                            Text(
                                stringResource(R.string.brain_how_taste_changes_body),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PromiseRow(icon: ImageVector, title: String, body: String?, shape: Shape) {
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                if (body != null) {
                    Text(
                        body,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
