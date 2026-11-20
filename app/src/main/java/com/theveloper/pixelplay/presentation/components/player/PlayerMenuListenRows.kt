@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.components.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.PhonelinkRing
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.spatial.SpatialButtonUi
import com.theveloper.pixelplay.presentation.viewmodel.SingPhase
import com.theveloper.pixelplay.presentation.viewmodel.SingUi
import com.theveloper.pixelplay.presentation.viewmodel.SongTapSettingsViewModel
import com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.roundToInt

private val ListenRowOuter = 24.dp
private val ListenRowInner = 6.dp

/**
 * The "Listen" group at the top of the song options sheet when it is opened for the song that is
 * playing (new UI, stage 3): Spatial audio and Sing moved here from the player's toolbar. Two
 * featured rows on the tonal container, joined like a Material 3 Expressive segmented list (24 dp
 * outer corners, 6 dp between them), each with an expressive shape behind its icon.
 *
 * Spatial audio shows the stored profile (or Off) the way the old button did, collected here so a
 * profile change recomposes only this row. Sing says what Sing is doing, with the lyrics toolbar's
 * own words.
 */
@Composable
internal fun PlayerMenuListenRows(
    singUiFlow: StateFlow<SingUi>,
    onSpatialClick: () -> Unit,
    onSingClick: () -> Unit,
    onSongTapClick: () -> Unit,
    modifier: Modifier = Modifier,
    spatialViewModel: SpatialAudioViewModel = hiltViewModel(),
    songTapSettings: SongTapSettingsViewModel = hiltViewModel(),
) {
    val songTapOn by songTapSettings.allowed.collectAsStateWithLifecycle()
    val holder = spatialViewModel.spatial
    val spatialUi by remember(holder) {
        combine(holder.storedSettings, holder.audible) { stored, heard ->
            stored?.let { SpatialButtonUi.of(it.profileFor(heard.spotifyId), heard.blockedBy) }
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(initialValue = null)
    val sing by singUiFlow.collectAsStateWithLifecycle()

    val singLine = when {
        sing.active -> stringResource(R.string.lyrics_sing_vocals_off)
        sing.phase == SingPhase.CLOUD_WIFI -> stringResource(R.string.lyrics_sing_waiting_wifi)
        sing.phase == SingPhase.CLOUD -> stringResource(R.string.lyrics_sing_preparing)
        sing.rendering && sing.progress != null ->
            stringResource(R.string.lyrics_sing_removing, ((sing.progress ?: 0f) * 100f).roundToInt())
        sing.rendering -> stringResource(R.string.lyrics_sing_removing_queued)
        else -> stringResource(R.string.player_menu_sing_line)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        ListenRow(
            shape = RoundedCornerShape(
                topStart = ListenRowOuter, topEnd = ListenRowOuter,
                bottomStart = ListenRowInner, bottomEnd = ListenRowInner
            ),
            badgeShape = MaterialShapes.Cookie9Sided.toShape(),
            icon = painterResource(R.drawable.rounded_spatial_audio_24),
            title = stringResource(R.string.spatial_ui_audio),
            supporting = stringResource(R.string.player_menu_spatial_line),
            trailingLabel = stringResource(spatialUi?.labelRes ?: R.string.spatial_ui_off),
            trailingEmphasized = spatialUi?.isOn == true && spatialUi?.heldBack != true,
            onClick = onSpatialClick
        )
        ListenRow(
            shape = RoundedCornerShape(ListenRowInner),
            badgeShape = MaterialShapes.Sunny.toShape(),
            icon = rememberVectorPainter(if (sing.active) Icons.Rounded.MicOff else Icons.Rounded.Mic),
            title = stringResource(R.string.lyrics_sing),
            supporting = singLine,
            trailingLabel = null,
            trailingEmphasized = false,
            onClick = onSingClick
        )
        ListenRow(
            shape = RoundedCornerShape(
                topStart = ListenRowInner, topEnd = ListenRowInner,
                bottomStart = ListenRowOuter, bottomEnd = ListenRowOuter
            ),
            badgeShape = MaterialShapes.Cookie6Sided.toShape(),
            icon = rememberVectorPainter(Icons.Rounded.PhonelinkRing),
            title = stringResource(R.string.songtap_title),
            supporting = stringResource(R.string.songtap_menu_supporting),
            trailingLabel = stringResource(if (songTapOn) R.string.songtap_menu_on else R.string.songtap_menu_off),
            trailingEmphasized = songTapOn,
            onClick = onSongTapClick
        )
    }
}

@Composable
private fun ListenRow(
    shape: Shape,
    badgeShape: Shape,
    icon: Painter,
    title: String,
    supporting: String,
    trailingLabel: String?,
    trailingEmphasized: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 72.dp)
            .clip(shape)
            .background(scheme.primaryContainer)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(badgeShape)
                .background(scheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = icon,
                contentDescription = null,
                tint = scheme.onPrimary,
                modifier = Modifier.size(20.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                color = scheme.onPrimaryContainer,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onPrimaryContainer.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (trailingLabel != null) {
            Box(
                modifier = Modifier
                    .height(28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (trailingEmphasized) scheme.primary else scheme.onPrimaryContainer.copy(alpha = 0.12f))
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = trailingLabel,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (trailingEmphasized) scheme.onPrimary else scheme.onPrimaryContainer,
                    maxLines = 1
                )
            }
        }
        Icon(
            imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.onPrimaryContainer
        )
    }
}
