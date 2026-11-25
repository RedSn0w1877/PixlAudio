@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.theveloper.pixelplay.presentation.components.redesign

import android.text.format.DateFormat
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.presentation.components.SmartImage
import com.theveloper.pixelplay.ui.theme.FlexFitPolicy
import com.theveloper.pixelplay.ui.theme.FlexFitText
import com.theveloper.pixelplay.ui.theme.FlexFonts
import com.theveloper.pixelplay.ui.theme.FlexSpec
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.flexColor
import com.theveloper.pixelplay.ui.theme.flexStyle
import kotlinx.coroutines.delay
import java.util.Date

/*
 * Home, redesign batch 3 (project/Main.dc.html, project/AndroidHomeScrolled.dc.html; 390 x 844 frames, 1 px = 1 dp).
 *
 *   header     status bar + 12 dp, 16 dp gutter. Left column 8 dp apart: time chip (28 dp, 14 dp corners, surfaceContainerHigh,
 *              10/12 dp padding, 16 dp icon, 6 dp gap, 13/18 medium onSurfaceVariant), the greeting on two lines (60/56 sp, w800:
 *              line one wdth 125 / -1.8 sp, line two wdth 75 / -0.8 sp in primary; both fitted to the width, ROND 0), the sub line
 *              14/20 onSurfaceVariant (only with a real play count). Right column 8 dp apart: Ask Taizo (48 dp circle,
 *              secondaryContainer, a 34 dp Sunny in onSecondaryContainer with the sparkle), Settings (48 dp circle surfaceContainerHigh).
 *   your mix   164 dp, 32 dp corners, primaryContainer; 230 dp Clover4 in primary at right -46 / top -50; 104 dp Cookie9 in tertiary at
 *              right 92 / top 84; tag 26 dp pill in onPrimary... (the frame's #381E72) 12/26 sp w600 0.3 sp at 16/16; title 38/40 w800
 *              wdth 125 -1 sp and line 14/20 at left 16 / bottom 16; 64 dp play button, 22 dp corners, primary / onPrimary, 30 dp icon.
 *   endless    72 dp pill (36 dp corners) tertiaryContainer, 10/14 dp padding, 14 dp gap; 52 dp onTertiaryContainer circle with the
 *              30 dp infinity in tertiaryContainer; "Endless Listening" 19/24 w750 wdth 112, line 13/18; 24 dp chevron.
 *   sections   title 30/32 sp w800 -0.6 sp: wide part wdth 125, accent part wdth 75 in primary; "See all" 14 sp medium primary;
 *              12 dp to the content. Blocks are 20 dp apart (24 dp once scrolled, the frame's own spacing).
 *   mixes      148 dp squares, 28 dp corners, 10 dp apart; a 120-130 dp expressive shape at right -24..-34 / top -28..-30; title
 *              20/24 w800 wdth 110, line 12/16, at left 14 / bottom 12. Tones cycle tertiary, secondary, primary.
 *   rewind     2 columns, 8 dp gaps; 64 dp tiles, 16 dp corners, surfaceContainer; 64 dp art 16/4/4/16; 12 dp; title 14/18 medium.
 *   fresh      124 dp items 12 dp apart: art clipped to Sunny / Clover4 / Flower, 8 dp, title 14/20 medium, artist 13/18.
 *   taizo      28 dp corners, secondaryContainer, 14/16 dp padding, 40 dp Sunny avatar, 14 dp; title 16/24 medium, example 14/20.
 *   collapsed  status bar + 72 dp, surface at 94 %, the greeting on one line 26/32 sp (wdth 125 + 75), the two buttons 8 dp apart.
 */

/** Wide and narrow cuts of the wordplay, ROND 0 (the house default). */
private val WideSpec = FlexSpec(weight = 800f, width = 125f)
private val NarrowSpec = FlexSpec(weight = 800f, width = 75f)
private val MixTitleSpec = FlexSpec(weight = 800f, width = 110f)
private val EndlessTitleSpec = FlexSpec(weight = 750f, width = 112f)

/** Line one of the greeting: as wide as the font allows (125), condensed to stay on the line, two lines at most. */
private val GreetingWidePolicy = FlexFitPolicy(minWidth = 50f, maxWidth = 125f, multiLineMaxWidth = 100f, maxLines = 2)

/** Line two: the narrow cut (75) and narrower when it has to. */
private val GreetingNarrowPolicy = FlexFitPolicy(minWidth = 25f, maxWidth = 75f, multiLineMaxWidth = 75f, maxLines = 2)

private val GreetingBase = TextStyle(fontSize = 60.sp, lineHeight = 56.sp, letterSpacing = (-1.8).sp)
private val GreetingNarrowBase = TextStyle(fontSize = 60.sp, lineHeight = 56.sp, letterSpacing = (-0.8).sp)

private val ChipShape = RoundedCornerShape(14.dp)
private val HeroShape = RoundedCornerShape(32.dp)
private val HeroPlayShape = RoundedCornerShape(22.dp)
private val EndlessShape = RoundedCornerShape(36.dp)
private val MixSquareShape = RoundedCornerShape(28.dp)
private val RewindShape = RoundedCornerShape(16.dp)
private val RewindArtShape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp, topEnd = 4.dp, bottomEnd = 4.dp)
private val TaizoCardShape = RoundedCornerShape(28.dp)
private val TagShape = RoundedCornerShape(13.dp)
private val OutlineChipShape = RoundedCornerShape(12.dp)

/** One greeting's resolved words. */
@Immutable
data class HomeGreetingWords(val first: String, val second: String, val sub: String?, val chip: String, val hour: Int)

/**
 * The words of Home's header for now: the line set and index come from [HomeWordplay]; the time chip ticks once a minute (one
 * coroutine, aligned to the minute). [weekPlays] and [topSong] / [topSongPlays] are the listener's real counts (null = unknown,
 * then the sub line is not drawn).
 */
@Composable
fun rememberHomeGreeting(weekPlays: Int?, topSong: String?, topSongPlays: Int): HomeGreetingWords {
    val context = LocalContext.current
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            val t = System.currentTimeMillis()
            value = t
            delay(60_000L - t % 60_000L + 50L)
        }
    }
    val calendar = remember { java.util.Calendar.getInstance() }
    val minuteKey = now / 60_000L
    val (hour, epochDay) = remember(minuteKey) {
        calendar.timeInMillis = now
        val offset = calendar.get(java.util.Calendar.ZONE_OFFSET) + calendar.get(java.util.Calendar.DST_OFFSET)
        calendar.get(java.util.Calendar.HOUR_OF_DAY) to Math.floorDiv(now + offset, 86_400_000L)
    }
    val streakPlays = if (topSong.isNullOrBlank()) 0 else topSongPlays
    val morningA = stringArrayResource(R.array.home_wp_morning_a)
    val morningB = stringArrayResource(R.array.home_wp_morning_b)
    val afternoonA = stringArrayResource(R.array.home_wp_afternoon_a)
    val afternoonB = stringArrayResource(R.array.home_wp_afternoon_b)
    val eveningA = stringArrayResource(R.array.home_wp_evening_a)
    val eveningB = stringArrayResource(R.array.home_wp_evening_b)
    val lateA = stringArrayResource(R.array.home_wp_late_a)
    val lateB = stringArrayResource(R.array.home_wp_late_b)
    val streakA = stringArrayResource(R.array.home_wp_streak_a)
    val streakB = stringArrayResource(R.array.home_wp_streak_b)
    val pick = remember(epochDay, hour, streakPlays) {
        HomeWordplay.greeting(epochDay, hour, streakPlays) { set ->
            when (set) {
                HomeWordplay.GreetingSet.MORNING -> minOf(morningA.size, morningB.size)
                HomeWordplay.GreetingSet.AFTERNOON -> minOf(afternoonA.size, afternoonB.size)
                HomeWordplay.GreetingSet.EVENING -> minOf(eveningA.size, eveningB.size)
                HomeWordplay.GreetingSet.LATE_NIGHT -> minOf(lateA.size, lateB.size)
                HomeWordplay.GreetingSet.STREAK -> minOf(streakA.size, streakB.size)
            }
        }
    }
    val (a, b) = when (pick.set) {
        HomeWordplay.GreetingSet.MORNING -> morningA to morningB
        HomeWordplay.GreetingSet.AFTERNOON -> afternoonA to afternoonB
        HomeWordplay.GreetingSet.EVENING -> eveningA to eveningB
        HomeWordplay.GreetingSet.LATE_NIGHT -> lateA to lateB
        HomeWordplay.GreetingSet.STREAK -> streakA to streakB
    }
    val subIndex = HomeWordplay.subLine(epochDay, 3)
    val sub = weekPlays?.takeIf { it > 0 }?.let { plays ->
        pluralStringResource(
            when (subIndex) {
                0 -> R.plurals.home_wp_plays_0
                1 -> R.plurals.home_wp_plays_1
                else -> R.plurals.home_wp_plays_2
            },
            plays, plays,
        )
    }
    val time = remember(minuteKey) { DateFormat.getTimeFormat(context).format(Date(now)) }
    val chip = if (pick.set == HomeWordplay.GreetingSet.STREAK && !topSong.isNullOrBlank()) {
        stringResource(R.string.home_wp_chip_repeat, time, topSong)
    } else {
        time
    }
    return HomeGreetingWords(a.getOrElse(pick.index) { a.first() }, b.getOrElse(pick.index) { b.first() }, sub, chip, hour)
}

/** The time chip's icon: sun by day, twilight in the evening, moon late. */
private fun timeIcon(hour: Int): ImageVector = when (HomeWordplay.timeBucket(hour)) {
    HomeWordplay.TimeBucket.MORNING, HomeWordplay.TimeBucket.AFTERNOON -> Icons.Rounded.WbSunny
    HomeWordplay.TimeBucket.EVENING -> Icons.Rounded.WbTwilight
    HomeWordplay.TimeBucket.LATE_NIGHT -> Icons.Rounded.Bedtime
}

/** A section title in two cuts: [wide] at wdth 125, [accent] at wdth 75 in [accentColor]. One AnnotatedString, built once per text. */
@Composable
fun TwoToneTitle(
    wide: String,
    accent: String,
    modifier: Modifier = Modifier,
    fontSize: Float = 30f,
    lineHeight: Float = 32f,
    letterSpacing: Float = -0.6f,
    color: Color = MaterialTheme.colorScheme.onSurface,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    maxLines: Int = 2,
) {
    val context = LocalContext.current.applicationContext
    val families = remember(fontSize) {
        FlexFonts.family(context, WideSpec.atSize(fontSize)) to FlexFonts.family(context, NarrowSpec.atSize(fontSize))
    }
    val text = remember(wide, accent, families, accentColor) {
        buildAnnotatedString {
            withStyle(SpanStyle(fontFamily = families.first)) { append(wide) }
            withStyle(SpanStyle(fontFamily = families.second, color = accentColor)) { append(accent) }
        }
    }
    val style = remember(fontSize, lineHeight, letterSpacing) {
        TextStyle(
            fontFamily = families.first,
            fontWeight = FontWeight(800),
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp,
            letterSpacing = letterSpacing.sp,
        )
    }
    Text(
        text = text,
        style = style,
        color = color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() },
    )
}

/** The expanded header (see the file comment). [jobCount] > 0 adds the running-jobs button under Settings. */
@Composable
fun HomeRedesignHeader(
    words: HomeGreetingWords,
    onAskTaizo: () -> Unit,
    onSettings: () -> Unit,
    jobCount: Int,
    onJobs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = Redesign.Gutter, end = Redesign.Gutter, top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .height(28.dp)
                    .clip(ChipShape)
                    .background(scheme.surfaceContainerHigh)
                    .padding(start = 10.dp, end = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(timeIcon(words.hour), contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Text(words.chip, style = ChipText, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.semantics(mergeDescendants = true) { heading() }) {
                FlexFitText(
                    text = words.first,
                    style = GreetingBase,
                    color = flexColor(scheme.onSurface),
                    spec = WideSpec,
                    policy = GreetingWidePolicy,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlexFitText(
                    text = words.second,
                    style = GreetingNarrowBase,
                    color = flexColor(scheme.primary),
                    spec = NarrowSpec,
                    policy = GreetingNarrowPolicy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            words.sub?.let { Text(it, style = SubText, color = scheme.onSurfaceVariant) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HomeHeaderButtons(onAskTaizo, onSettings, jobCount, onJobs, vertical = true)
        }
    }
}

@Composable
private fun HomeHeaderButtons(onAskTaizo: () -> Unit, onSettings: () -> Unit, jobCount: Int, onJobs: () -> Unit, vertical: Boolean) {
    val scheme = MaterialTheme.colorScheme
    TaizoCircleButton(onAskTaizo)
    CircleIconButton(
        icon = Icons.Rounded.Settings,
        contentDescription = stringResource(R.string.common_settings),
        onClick = onSettings,
        container = scheme.surfaceContainerHigh,
        content = scheme.onSurface,
    )
    if (jobCount > 0) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHigh)
                .clickable(role = Role.Button, onClick = onJobs),
            contentAlignment = Alignment.Center,
        ) {
            BadgedBox(badge = { Badge { Text(jobCount.toString()) } }) {
                Icon(Icons.Rounded.HourglassTop, contentDescription = stringResource(R.string.topbar_cd_active_jobs), tint = scheme.onSurface)
            }
        }
    }
}

/** Ask Taizo: 48 dp secondaryContainer circle, a 34 dp Sunny in onSecondaryContainer, the sparkle cut out of it. */
@Composable
private fun TaizoCircleButton(onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sunny = MaterialShapes.Sunny.toShape()
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(scheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(34.dp).clip(sunny).background(scheme.onSecondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = stringResource(R.string.home_ask_taizo), tint = scheme.secondaryContainer, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CircleIconButton(icon: ImageVector, contentDescription: String, onClick: () -> Unit, container: Color, content: Color, size: Dp = 48.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = content, modifier = Modifier.size(24.dp))
    }
}

/** The collapsed header drawn over the list once the big greeting scrolled away; [alpha] comes from the caller's animation. */
@Composable
fun HomeCollapsedHeader(
    words: HomeGreetingWords,
    alpha: () -> Float,
    onAskTaizo: () -> Unit,
    onSettings: () -> Unit,
    jobCount: Int,
    onJobs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val background = remember(scheme.surface) { scheme.surface.copy(alpha = 0.94f) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                val a = alpha()
                this.alpha = a
                translationY = (1f - a) * -24.dp.toPx()
            }
            .background(background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = Redesign.Gutter),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TwoToneTitle(
                wide = words.first,
                accent = " " + words.second,
                fontSize = 26f,
                lineHeight = 32f,
                letterSpacing = -0.4f,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HomeHeaderButtons(onAskTaizo, onSettings, jobCount, onJobs, vertical = false)
            }
        }
    }
}

/** A section header: two-tone title, optional "See all" (or "Show less") on the right, optional line under it. */
@Composable
fun HomeSectionHeader(
    wide: String,
    accent: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = Redesign.Gutter), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TwoToneTitle(wide = wide, accent = accent, modifier = Modifier.weight(1f))
            if (actionLabel != null && onAction != null) {
                Text(
                    text = actionLabel,
                    style = RedesignType.Label,
                    color = scheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(role = Role.Button, onClick = onAction)
                        .padding(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = SubText, color = scheme.onSurfaceVariant)
    }
}

/** The day's mix as the hero card. [onPlay] null hides the play button (nothing to play yet). */
@Composable
fun HomeYourMixHero(onOpen: () -> Unit, onPlay: (() -> Unit)?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val clover = MaterialShapes.Clover4Leaf.toShape()
    val cookie = MaterialShapes.Cookie9Sided.toShape()
    val titleStyle = flexStyle(HeroTitleBase, WideSpec)
    Box(
        modifier = modifier
            .padding(horizontal = Redesign.Gutter)
            .fillMaxWidth()
            .height(164.dp)
            .clip(HeroShape)
            .background(scheme.primaryContainer)
            .clickable(role = Role.Button, onClick = onOpen),
    ) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = 46.dp, y = (-50).dp).size(230.dp).clip(clover).background(scheme.primary))
        Box(Modifier.align(Alignment.TopEnd).offset(x = (-92).dp, y = 84.dp).size(104.dp).clip(cookie).background(scheme.tertiary))
        Text(
            text = stringResource(R.string.home_your_mix_tag),
            style = TagText,
            color = scheme.onPrimaryContainer,
            modifier = Modifier
                .padding(16.dp)
                .height(26.dp)
                .clip(TagShape)
                .background(scheme.onPrimary)
                .padding(horizontal = 10.dp, vertical = 5.dp),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.home_your_mix_name), style = titleStyle, color = scheme.onPrimaryContainer, maxLines = 1)
            Text(stringResource(R.string.home_your_mix_line), style = SubText, color = scheme.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onPlay != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(64.dp)
                    .clip(HeroPlayShape)
                    .background(scheme.primary)
                    .clickable(role = Role.Button, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = stringResource(R.string.home_your_mix_play_cd), tint = scheme.onPrimary, modifier = Modifier.size(30.dp))
            }
        }
    }
}

/** The Endless Listening entry pill; a long press replays the introduction. */
@Composable
fun HomeEndlessEntry(onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val titleStyle = flexStyle(EndlessTitleBase, EndlessTitleSpec)
    val howCd = stringResource(R.string.home_endless_how_cd)
    Row(
        modifier = modifier
            .padding(horizontal = Redesign.Gutter)
            .fillMaxWidth()
            .height(72.dp)
            .clip(EndlessShape)
            .background(scheme.tertiaryContainer)
            .combinedClickable(role = Role.Button, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = howCd)
            .padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(52.dp).clip(CircleShape).background(scheme.onTertiaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AllInclusive, contentDescription = null, tint = scheme.tertiaryContainer, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.endless_title), style = titleStyle, color = scheme.onTertiaryContainer, maxLines = 1)
            Text(stringResource(R.string.home_endless_line), style = RedesignType.RowSubtitle, color = scheme.onTertiaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = scheme.onTertiaryContainer, modifier = Modifier.size(24.dp))
    }
}

/** One mix card's words (plan card or the legacy Daily Mix). */
@Immutable
data class HomeMixTile(val key: String, val title: String, val subtitle: String)

/** The tone of the [index]-th mix card: container, content, the decoration's colour, and its shape. */
@Immutable
private data class MixTone(val container: Color, val content: Color, val deco: Color)

@Composable
private fun mixTone(index: Int): MixTone {
    val s = MaterialTheme.colorScheme
    return when (index % 3) {
        0 -> MixTone(s.tertiaryContainer, s.onTertiaryContainer, s.tertiary)
        1 -> MixTone(s.secondaryContainer, s.onSecondaryContainer, s.secondary)
        else -> MixTone(s.primaryContainer, s.onPrimaryContainer, s.primary)
    }
}

@Composable
private fun mixShape(index: Int): Shape = when (index % 3) {
    0 -> MaterialShapes.Flower.toShape()
    1 -> MaterialShapes.SoftBurst.toShape()
    else -> MaterialShapes.Cookie9Sided.toShape()
}

/** A 148 dp square mix card (or [fill] wide in the grid). */
@Composable
fun HomeMixSquareCard(tile: HomeMixTile, index: Int, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 148.dp) {
    val tone = mixTone(index)
    val shape = mixShape(index)
    val titleStyle = flexStyle(MixTitleBase, MixTitleSpec)
    val decoSize = if (index % 3 == 0) 130.dp else 120.dp
    val decoX = if (index % 3 == 0) 34.dp else 24.dp
    val decoY = if (index % 3 == 0) (-30).dp else (-28).dp
    Box(
        modifier = modifier
            .size(size)
            .clip(MixSquareShape)
            .background(tone.container)
            .clickable(role = Role.Button, onClick = onClick),
    ) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = decoX, y = decoY).size(decoSize).clip(shape).background(tone.deco))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 14.dp, end = 12.dp, bottom = 12.dp)) {
            Text(tile.title, style = titleStyle, color = tone.content, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (tile.subtitle.isNotBlank()) {
                Text(tile.subtitle, style = MixLineText, color = tone.content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Mix & match": the mixes sideways, 10 dp apart. */
@Composable
fun HomeMixShelfRow(tiles: List<HomeMixTile>, onOpen: (HomeMixTile) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Redesign.Gutter),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(tiles.size, key = { tiles[it].key }, contentType = { "mix_square" }) { i ->
            val tile = tiles[i]
            HomeMixSquareCard(tile, i, onClick = { onOpen(tile) })
        }
    }
}

/** "See all" of the mixes: the same cards two to a row. */
@Composable
fun HomeMixGrid(tiles: List<HomeMixTile>, onOpen: (HomeMixTile) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = Redesign.Gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (rowStart in tiles.indices step 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (i in rowStart until minOf(rowStart + 2, tiles.size)) {
                    val tile = tiles[i]
                    Box(Modifier.weight(1f).aspectRatio(1f)) {
                        HomeMixSquareCard(tile, i, onClick = { onOpen(tile) }, modifier = Modifier.matchParentSize(), size = Dp.Unspecified)
                    }
                }
                if (rowStart + 1 >= tiles.size) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** One "Rewind, replay" tile. */
@Immutable
data class HomeRewindTile(val key: String, val title: String, val art: String?)

/** "Rewind, replay": two columns of 64 dp tiles. */
@Composable
fun HomeRewindGrid(tiles: List<HomeRewindTile>, onClick: (HomeRewindTile) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = Redesign.Gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (rowStart in tiles.indices step 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in rowStart until minOf(rowStart + 2, tiles.size)) {
                    val tile = tiles[i]
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(64.dp)
                            .clip(RewindShape)
                            .background(scheme.surfaceContainer)
                            .clickable(role = Role.Button) { onClick(tile) }
                            .padding(end = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SmartImage(tile.art, null, Modifier.size(64.dp), shape = RewindArtShape)
                        Text(tile.title, style = RewindText, color = scheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (rowStart + 1 >= tiles.size) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** "Fresh finds": songs sideways, art clipped to expressive shapes. */
@Composable
fun HomeFreshRow(songs: List<Song>, onPlay: (Song) -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shapes = listOf(MaterialShapes.Sunny.toShape(), MaterialShapes.Clover4Leaf.toShape(), MaterialShapes.Flower.toShape())
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = Redesign.Gutter),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(songs.size, key = { songs[it].id }, contentType = { "fresh_song" }) { i ->
            val song = songs[i]
            Column(
                modifier = Modifier
                    .width(124.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(role = Role.Button) { onPlay(song) },
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SmartImage(song.albumArtUriString, null, Modifier.size(124.dp), shape = shapes[i % shapes.size])
                Column {
                    Text(song.title, style = RedesignType.Label, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.displayArtist, style = RedesignType.RowSubtitle, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** "Can't find the vibe? Ask Taizo" with an example made from a real song (or a plain one). */
@Composable
fun HomeAskTaizoCard(exampleSong: String?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val sunny = MaterialShapes.Sunny.toShape()
    Row(
        modifier = modifier
            .padding(horizontal = Redesign.Gutter)
            .fillMaxWidth()
            .clip(TaizoCardShape)
            .background(scheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).clip(sunny).background(scheme.onSecondaryContainer), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = scheme.secondaryContainer, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.home_taizo_card_title), style = TaizoTitleText, color = scheme.onSecondaryContainer)
            Text(
                text = if (exampleSong.isNullOrBlank()) stringResource(R.string.home_taizo_card_example_plain)
                else stringResource(R.string.home_taizo_card_example, exampleSong),
                style = SubText,
                color = scheme.onSecondaryContainer.copy(alpha = 0.86f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One quiet outlined link at the bottom of Home. */
@Immutable
data class HomeFooterLink(val label: String, val onClick: () -> Unit)

/** The bottom row: the old quick actions and filter chips that have no place of their own any more. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeFooterLinks(links: List<HomeFooterLink>, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = modifier.fillMaxWidth().padding(horizontal = Redesign.Gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (link in links) {
            Text(
                text = link.label,
                style = RedesignType.Label,
                color = scheme.onSurface,
                modifier = Modifier
                    .height(40.dp)
                    .clip(OutlineChipShape)
                    .border(BorderStroke(1.dp, scheme.outline), OutlineChipShape)
                    .clickable(role = Role.Button, onClick = link.onClick)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

/** The rotating section titles of today. */
@Immutable
data class HomeSectionTitles(val mixesA: String, val mixesB: String, val recentA: String, val recentB: String, val freshA: String, val freshB: String)

@Composable
fun rememberHomeSectionTitles(): HomeSectionTitles {
    val mixesA = stringArrayResource(R.array.home_wp_mixes_a)
    val mixesB = stringArrayResource(R.array.home_wp_mixes_b)
    val recentA = stringArrayResource(R.array.home_wp_recent_a)
    val recentB = stringArrayResource(R.array.home_wp_recent_b)
    val freshA = stringArrayResource(R.array.home_wp_fresh_a)
    val freshB = stringArrayResource(R.array.home_wp_fresh_b)
    val day = remember { Math.floorDiv(System.currentTimeMillis() + java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()), 86_400_000L) }
    return remember(day, mixesA, recentA, freshA) {
        val m = HomeWordplay.section(day, HomeWordplay.Section.MIXES, minOf(mixesA.size, mixesB.size))
        val r = HomeWordplay.section(day, HomeWordplay.Section.RECENT, minOf(recentA.size, recentB.size))
        val f = HomeWordplay.section(day, HomeWordplay.Section.FRESH, minOf(freshA.size, freshB.size))
        HomeSectionTitles(mixesA[m], mixesB[m], recentA[r], recentB[r], freshA[f], freshB[f])
    }
}

private val ChipText = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
private val SubText = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
private val TagText = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp)
private val HeroTitleBase = TextStyle(fontSize = 38.sp, lineHeight = 40.sp, letterSpacing = (-1).sp)
private val EndlessTitleBase = TextStyle(fontSize = 19.sp, lineHeight = 24.sp)
private val MixTitleBase = TextStyle(fontSize = 20.sp, lineHeight = 24.sp)
private val MixLineText = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
private val RewindText = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp)
private val TaizoTitleText = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp)
