@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.theveloper.pixelplay.presentation.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.ThumbUp
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.ThumbDown
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.brain.WhyFacts
import com.theveloper.pixelplay.data.brain.BrainFeedback
import com.theveloper.pixelplay.presentation.viewmodel.WhySheetState
import com.theveloper.pixelplay.ui.theme.FlexPresets
import com.theveloper.pixelplay.ui.theme.flexStyle

/**
 * The sheet behind a pick's reason line (docs/brain-spec.md, 8.8): the song, the reason in full, the
 * feedback controls of 8.5 (Like, Not for me, Less from the artist) and the way to "How picks work".
 * Stage 1 has the arm reasons; the supporting facts of stage 2 are not shown, not guessed.
 */
@Composable
fun WhyThisSongSheet(
    state: WhySheetState,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onNotForMe: () -> Unit,
    onLessFromArtist: (String) -> Unit,
    onHowPicksWork: () -> Unit,
    onDismiss: () -> Unit,
    onMoreLikeThis: (() -> Unit)? = null,
    onDontCount: (() -> Unit)? = null,
) {
    val song = state.song
    val reason = whyLineText(state.whyKey, state.whyArg)
    val artist = BrainFeedback.artistOf(song).takeIf { it.isNotEmpty() }
    AdaptiveModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .navigationBarsPadding(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmartImage(
                    model = song.albumArtUriString,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = song.displayArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.brain_why_sheet_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = reason ?: stringResource(R.string.brain_why_sheet_basis),
                style = flexStyle(MaterialTheme.typography.headlineSmall, FlexPresets.HeroDisplay),
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.semantics { heading() },
            )
            if (reason != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.brain_why_sheet_basis),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Up to two supporting facts (8.8), each a true statement read from the listener's own plays.
            for (fact in state.facts) {
                val text = when (fact.key) {
                    WhyFacts.KEY_ARTIST_FINISHED -> stringResource(R.string.brain_why_fact_artist_finished, fact.a, fact.b)
                    WhyFacts.KEY_TRACK_FINISHED -> stringResource(R.string.brain_why_fact_track_finished, fact.a)
                    WhyFacts.KEY_YEAR -> stringResource(R.string.brain_why_fact_year, fact.a)
                    else -> null
                } ?: continue
                Spacer(Modifier.height(4.dp))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                FilledTonalButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.weight(1f).height(52.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (isFavorite) R.string.brain_liked else R.string.brain_like), maxLines = 1)
                }
                OutlinedButton(
                    onClick = {
                        onNotForMe()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f).height(52.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.ThumbDown, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.brain_not_for_me), maxLines = 1)
                }
            }
            if (onMoreLikeThis != null && artist != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onMoreLikeThis()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.ThumbUp, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.brain_more_like_this), maxLines = 1)
                }
            }
            if (artist != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onLessFromArtist(artist)
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.Block, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.brain_less_from, artist), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (onDontCount != null) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        onDontCount()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Rounded.VisibilityOff, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.brain_dont_count), maxLines = 1)
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onHowPicksWork, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Rounded.Info, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.brain_how_picks_work))
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
