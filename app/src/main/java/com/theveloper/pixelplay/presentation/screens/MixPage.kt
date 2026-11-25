@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.redesign.TwoToneTitle
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded

/*
 * The mix page (redesign batch 3, project/AndroidMix.dc.html), shared by Daily Mix, Your Mix and the other made-for-you lists.
 *
 *   cover    400 dp edge to edge behind the top: the mix's container colour with a 320 dp Flower (accent) at right -90 / top -110,
 *            a 150 dp Cookie9 (onContainer) at left 30 / top 60 and a 220 dp circle (primaryContainer) at left -90 / top 200; a
 *            scrim surface 40 % -> 0 (18 %) -> 0 (46 %) -> 62 % (70 %) -> 100 %. No blur: the frame's blurred copy is the fade.
 *   buttons  status bar + 20 dp, 16 dp gutter: Back left, Save (+) and More right, 48 dp circles surfaceContainerHigh, 8 dp apart.
 *   block    starts 118 dp under the buttons (14 dp gaps): tag pill 26 dp (13 dp corners, onContainer / container, 12/26 sp w700
 *            0.3 sp), title 52/56 sp w800 -1.2 sp (first word wdth 125, the rest wdth 75 in onContainer), meta 14/20
 *            onSurfaceVariant, "Why these?" card (surfaceContainer, 20 dp corners, 12/14 dp padding, 18 dp sparkle in the accent,
 *            10 dp gap, 14/20 sp with the lead in w600 onContainer), then the actions row (56 dp, 10 dp gap): Remix
 *            (container / onContainer, 28/28/28/8 corners, 20 dp icon, 15 sp medium) and the connected Shuffle (56 dp,
 *            primaryContainer, 28/8/8/28) + Play (76 x 56 dp, primary, 8/28/28/8, 30 dp icon), 2 dp apart.
 */

/** Which colours a mix page wears. */
enum class MixPageTone { Tertiary, Primary, Secondary }

@Immutable
private data class MixColors(val container: Color, val onContainer: Color, val accent: Color)

@Composable
private fun mixColors(tone: MixPageTone): MixColors {
    val s = MaterialTheme.colorScheme
    return when (tone) {
        MixPageTone.Tertiary -> MixColors(s.tertiaryContainer, s.onTertiaryContainer, s.tertiary)
        MixPageTone.Primary -> MixColors(s.primaryContainer, s.onPrimaryContainer, s.primary)
        MixPageTone.Secondary -> MixColors(s.secondaryContainer, s.onSecondaryContainer, s.secondary)
    }
}

/** "Daily Mix 1" -> ("Daily", " Mix 1"): the first word wide, the rest narrow. */
fun splitMixTitle(title: String): Pair<String, String> {
    val t = title.trim()
    val i = t.indexOf(' ')
    return if (i <= 0) t to "" else t.substring(0, i) to t.substring(i)
}

/** "Made for you · 50 songs · 2 h 41 min" from the real songs. */
@Composable
fun mixMetaLine(songs: List<Song>): String {
    val totalMs = remember(songs) { songs.sumOf { it.duration.coerceAtLeast(0L) } }
    val minutes = (totalMs / 60_000L).toInt()
    val parts = ArrayList<String>(3)
    parts.add(stringResource(R.string.mix_made_for_you))
    parts.add(pluralStringResource(R.plurals.mix_song_count, songs.size, songs.size))
    if (minutes > 0) {
        parts.add(
            if (minutes >= 60) stringResource(R.string.mix_duration_hours, minutes / 60, minutes % 60)
            else stringResource(R.string.mix_duration_minutes, minutes)
        )
    }
    return parts.joinToString(" · ")
}

/** "Why these?": the list's own note when it has one, else what is true of its songs (how many are new to the listener). */
@Composable
fun mixWhyLine(note: String?, songCount: Int, novelCount: Int): String? = when {
    !note.isNullOrBlank() -> note
    songCount == 0 -> null
    novelCount > 0 -> pluralStringResource(R.plurals.mix_why_new, novelCount, novelCount)
    else -> stringResource(R.string.mix_why_familiar)
}

/** The cover drawn behind the top 400 dp (first item of the list, under the block). */
@Composable
private fun MixCover(tone: MixPageTone, modifier: Modifier = Modifier) {
    val c = mixColors(tone)
    val surface = MaterialTheme.colorScheme.surface
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val flower = MaterialShapes.Flower.toShape()
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val scrim = remember(surface) {
        Brush.verticalGradient(
            0f to surface.copy(alpha = 0.4f),
            0.18f to surface.copy(alpha = 0f),
            0.46f to surface.copy(alpha = 0f),
            0.70f to surface.copy(alpha = 0.62f),
            1f to surface,
        )
    }
    Box(modifier.fillMaxWidth().height(400.dp).background(c.container)) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 90.dp, y = (-110).dp).size(320.dp).clip(flower).background(c.accent))
        Box(Modifier.offset(x = 30.dp, y = 60.dp).size(150.dp).clip(cookie).background(c.onContainer))
        Box(Modifier.offset(x = (-90).dp, y = 200.dp).size(220.dp).clip(CircleShape).background(if (tone == MixPageTone.Primary) c.accent else primaryContainer))
        Box(Modifier.matchParentSize().background(scrim))
    }
}

/** The page's top block: cover, tag, title, meta, "Why these?" and the actions (the list's first item). */
@Composable
fun MixPageHero(
    tone: MixPageTone,
    tag: String,
    title: String,
    meta: String,
    why: String?,
    onShuffle: () -> Unit,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    onRemix: (() -> Unit)? = null,
    remixBusy: Boolean = false,
) {
    val c = mixColors(tone)
    val scheme = MaterialTheme.colorScheme
    val (wide, accent) = remember(title) { splitMixTitle(title) }
    Box(modifier.fillMaxWidth()) {
        MixCover(tone)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                // 20 dp + the 48 dp buttons + 14 dp + the frame's 118 dp spacer + 14 dp
                .padding(start = 16.dp, end = 16.dp, top = 214.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = tag,
                    style = TagStyle,
                    color = c.container,
                    maxLines = 1,
                    modifier = Modifier
                        .height(26.dp)
                        .clip(RoundedCornerShape(13.dp))
                        .background(c.onContainer)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                TwoToneTitle(
                    wide = wide,
                    accent = accent,
                    fontSize = 52f,
                    lineHeight = 56f,
                    letterSpacing = -1.2f,
                    accentColor = c.onContainer,
                )
                Text(meta, style = MetaStyle, color = scheme.onSurfaceVariant)
            }
            if (!why.isNullOrBlank()) {
                val lead = stringResource(R.string.mix_why_these)
                val text = remember(lead, why, c.onContainer) {
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = c.onContainer)) { append(lead) }
                        append(' ')
                        append(why)
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(scheme.surfaceContainer)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = c.accent, modifier = Modifier.padding(top = 1.dp).size(18.dp))
                    Text(text, style = MetaStyle, color = scheme.onSurface)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onRemix != null) {
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RemixShape)
                            .background(c.container)
                            .clickable(enabled = !remixBusy, role = Role.Button, onClick = onRemix)
                            .padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (remixBusy) {
                            ContainedLoadingIndicator(modifier = Modifier.size(24.dp))
                        } else {
                            Icon(Icons.Rounded.Sync, contentDescription = null, tint = c.onContainer, modifier = Modifier.size(20.dp))
                        }
                        Text(stringResource(R.string.mix_remix), style = ButtonStyle, color = c.onContainer, maxLines = 1)
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupIconButton(Icons.Rounded.Shuffle, stringResource(R.string.common_shuffle), ShuffleShape, scheme.primaryContainer, scheme.onPrimaryContainer, 56, 24, onShuffle)
                    GroupIconButton(Icons.Rounded.PlayArrow, stringResource(R.string.mix_play_cd, title), PlayShape, scheme.primary, scheme.onPrimary, 76, 30, onPlay)
                }
            }
        }
    }
}

@Composable
private fun GroupIconButton(
    icon: ImageVector,
    cd: String,
    shape: RoundedCornerShape,
    container: Color,
    content: Color,
    width: Int,
    iconSize: Int,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(width.dp)
            .height(56.dp)
            .clip(shape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = cd, tint = content, modifier = Modifier.size(iconSize.dp))
    }
}

/** The buttons floating over the page: Back, and Save / More when the page has them. */
@Composable
fun MixPageTopButtons(onBack: () -> Unit, modifier: Modifier = Modifier, onSave: (() -> Unit)? = null, onMore: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 16.dp, end = 16.dp, top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back), onBack, scheme.surfaceContainerHigh, scheme.onSurface)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (onSave != null) CircleButton(Icons.Rounded.Add, stringResource(R.string.mix_save_cd), onSave, scheme.surfaceContainerHigh, scheme.onSurface)
            if (onMore != null) CircleButton(Icons.Rounded.MoreVert, stringResource(R.string.mix_more_cd), onMore, scheme.surfaceContainerHigh, scheme.onSurface)
        }
    }
}

@Composable
private fun CircleButton(icon: ImageVector, cd: String, onClick: () -> Unit, container: Color, content: Color) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = cd, tint = content, modifier = Modifier.size(24.dp))
    }
}

private val RemixShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomEnd = 28.dp, bottomStart = 8.dp)
private val ShuffleShape = RoundedCornerShape(topStart = 28.dp, bottomStart = 28.dp, topEnd = 8.dp, bottomEnd = 8.dp)
private val PlayShape = RoundedCornerShape(topStart = 8.dp, bottomStart = 8.dp, topEnd = 28.dp, bottomEnd = 28.dp)
private val TagStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
private val MetaStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
private val ButtonStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
