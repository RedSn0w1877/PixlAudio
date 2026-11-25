@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.theveloper.pixelplay.presentation.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.components.redesign.TwoToneTitle
import com.theveloper.pixelplay.presentation.navigation.Screen
import com.theveloper.pixelplay.presentation.screens.setup.LocalSetupReduceMotion
import com.theveloper.pixelplay.presentation.screens.setup.rememberPageEntrance
import com.theveloper.pixelplay.presentation.screens.setup.setupEntrance
import com.theveloper.pixelplay.presentation.viewmodel.EndlessIntroViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.GoogleSansRounded
import com.theveloper.pixelplay.ui.theme.readReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.launch

/*
 * The introduction of Endless Listening, redesign batch 3 (project/AndroidEndlessIntro1/2/3.dc.html; 390 x 844, 1 px = 1 dp).
 * First open of the Home entry shows it; a long press on the entry and Settings › Endless Listening › How it works replay it.
 *
 *   header   safe area + 20 dp, 16 dp gutter, 48 dp: Close (page 1) or Back (pages 2-3) as a 48 dp surfaceContainerHigh circle;
 *            "Skip" (pages 1-2) 15 sp medium in primary, 48 dp tall, 18 dp side padding: skips straight to the station.
 *   art      a 390 x 340 dp stage, 28 dp under the header, scaled to the width. Page 1: the infinity loop (34 dp stroke,
 *            primaryContainer) with four shapes riding it: ONE looping phase read in graphicsLayer only, so it never recomposes
 *            or allocates; it runs only on screen, in the foreground and without Reduce Motion. Page 2: the host's note cards
 *            fanned (-7 / 4 / -3 degrees) dealt in with the setup flow's spring. Page 3: you ask, the host answers, chips pop in.
 *   words    24 dp gutter, 34 dp under the art, 14 dp apart: eyebrow 14/20 w600 0.4 sp in the page colour, title 48/48 sp w800
 *            (line one wdth 125 / -1.6 sp, line two wdth 75 / -0.8 sp in the page colour), body 16/24 onSurfaceVariant.
 *   footer   24 dp gutter, 32 dp from the bottom, 64 dp: dots (active 28 x 8 in the page colour, others 8 x 8 outlineVariant,
 *            6 dp apart, springing) and the 64 dp pill (32 dp corners, 30/26 dp padding, 17 sp w600, 22 dp arrow); on page 3 the
 *            pill fills the row: "Start listening" with a 24 dp play icon.
 * Page colours: 1 primary, 2 tertiary, 3 secondary (with their on-colours).
 */

private const val PAGES = 3

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EndlessIntroScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: EndlessIntroViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) { readReduceMotion(context) }
    val pagerState = rememberPagerState(pageCount = { PAGES })
    val scope = rememberCoroutineScope()
    val motion = remember { MotionScheme.expressive() }
    val scheme = MaterialTheme.colorScheme
    val tones = listOf(
        PageTone(scheme.primary, scheme.onPrimary),
        PageTone(scheme.tertiary, scheme.onTertiary),
        PageTone(scheme.secondary, scheme.onSecondary),
    )
    val page = pagerState.currentPage
    val tone = tones[page]

    val close = {
        viewModel.markSeen()
        navController.popBackStack()
        Unit
    }
    val start = {
        viewModel.markSeen()
        if (!playerViewModel.brainSession.endless.active.value) playerViewModel.startEndlessListening()
        navController.navigate(Screen.EndlessStation.route) {
            popUpTo(Screen.EndlessIntro.route) { inclusive = true }
            launchSingleTop = true
        }
        Unit
    }

    // Back steps through the pages, then leaves (seen). A rotation or a theme change recreates the screen and is neither.
    BackHandler {
        if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) } else close()
    }

    CompositionLocalProvider(LocalSetupReduceMotion provides reduceMotion) {
        Box(Modifier.fillMaxSize().background(scheme.surface), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp).height(48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page == 0) {
                        IntroCircleButton(Icons.Rounded.Close, stringResource(R.string.endless_intro_close_cd), close)
                    } else {
                        IntroCircleButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.common_back)) {
                            scope.launch { pagerState.animateScrollToPage(page - 1) }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (page < PAGES - 1) {
                        Text(
                            text = stringResource(R.string.endless_intro_skip),
                            style = SkipStyle,
                            color = scheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(24.dp))
                                .clickable(role = Role.Button, onClick = start)
                                .padding(horizontal = 18.dp, vertical = 14.dp),
                        )
                    }
                }
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { p ->
                    val entrance = rememberPageEntrance()
                    Column(Modifier.fillMaxSize()) {
                        Spacer(Modifier.height(28.dp))
                        IntroStage {
                            when (p) {
                                0 -> InfinityArt()
                                1 -> NotesArt(entrance)
                                else -> SteerArt(entrance)
                            }
                        }
                        Spacer(Modifier.height(34.dp))
                        IntroWords(page = p, tone = tones[p], entrance = entrance)
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp).height(64.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    Dots(current = page, color = tone.color, motion = motion)
                    if (page < PAGES - 1) {
                        Spacer(Modifier.weight(1f))
                        Row(
                            modifier = Modifier
                                .height(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(tone.color)
                                .clickable(role = Role.Button) { scope.launch { pagerState.animateScrollToPage(page + 1) } }
                                .padding(start = 30.dp, end = 26.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(stringResource(R.string.endless_intro_next), style = PillStyle, color = tone.on)
                            Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = null, tint = tone.on, modifier = Modifier.size(22.dp))
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                                .clip(RoundedCornerShape(32.dp))
                                .background(tone.color)
                                .clickable(role = Role.Button, onClick = start),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = tone.on, modifier = Modifier.size(24.dp))
                            Text(stringResource(R.string.endless_intro_start), style = PillStyle, color = tone.on)
                        }
                    }
                }
            }
        }
    }
}

@Immutable
private data class PageTone(val color: Color, val on: Color)

@Composable
private fun IntroCircleButton(icon: androidx.compose.ui.graphics.vector.ImageVector, cd: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(48.dp).clip(CircleShape).background(scheme.surfaceContainerHigh).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = cd, tint = scheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** The 390 x 340 dp stage of the frames, scaled to the width the phone has (never above 1.2x). */
@Composable
private fun IntroStage(content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth().height(340.dp), contentAlignment = Alignment.Center) {
        val scale = (maxWidth / 390.dp).coerceIn(0.7f, 1.2f)
        Box(
            Modifier
                .requiredSize(390.dp, 340.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
        ) { content() }
    }
}

@Composable
private fun IntroWords(page: Int, tone: PageTone, entrance: State<Float>) {
    val scheme = MaterialTheme.colorScheme
    val (a, b, body) = when (page) {
        0 -> Triple(R.string.endless_intro2_title_1a, R.string.endless_intro2_title_1b, R.string.endless_intro2_body_1)
        1 -> Triple(R.string.endless_intro2_title_2a, R.string.endless_intro2_title_2b, R.string.endless_intro2_body_2)
        else -> Triple(R.string.endless_intro2_title_3a, R.string.endless_intro2_title_3b, R.string.endless_intro2_body_3)
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.endless_eyebrow), style = EyebrowStyle, color = tone.color, modifier = Modifier.setupEntrance(entrance, 0))
        Column(Modifier.setupEntrance(entrance, 1).semantics(mergeDescendants = true) { heading() }) {
            TwoToneTitle(wide = stringResource(a), accent = "", fontSize = 48f, lineHeight = 48f, letterSpacing = -1.6f, maxLines = 1)
            TwoToneTitle(wide = "", accent = stringResource(b), fontSize = 48f, lineHeight = 48f, letterSpacing = -0.8f, accentColor = tone.color, maxLines = 1)
        }
        Text(stringResource(body), style = BodyStyle, color = scheme.onSurfaceVariant, modifier = Modifier.setupEntrance(entrance, 2))
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Dots(current: Int, color: Color, motion: MotionScheme) {
    val pageCd = stringResource(R.string.endless_intro_page_cd, current + 1, PAGES)
    val idle = MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier.semantics { contentDescription = pageCd },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until PAGES) {
            val width by animateDpAsState(if (i == current) 28.dp else 8.dp, motion.fastSpatialSpec(), label = "EndlessDot")
            Box(Modifier.size(width = width, height = 8.dp).background(if (i == current) color else idle, RoundedCornerShape(4.dp)))
        }
    }
}

/** True while the screen is in the foreground (the loop stops when the app is not). */
@Composable
private fun rememberForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

/** The figure eight of page 1, in the 390 x 340 stage: centre (195, 170), half width 167, half height 70. */
private const val LoopCx = 195f
private const val LoopCy = 170f
private const val LoopA = 167f
private const val LoopB = 70f

/** Page 1: shapes riding the infinity loop. One phase, read in graphicsLayer; the path is built once per size. */
@Composable
private fun InfinityArt() {
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = LocalSetupReduceMotion.current
    val foreground = rememberForeground()
    val phase = remember { Animatable(0.08f) }
    LaunchedEffect(foreground, reduceMotion) {
        if (foreground && !reduceMotion) {
            phase.animateTo(phase.value + 1f, infiniteRepeatable(tween(durationMillis = 14_000, easing = LinearEasing)))
        }
    }
    val track = scheme.primaryContainer
    Box(
        Modifier
            .requiredSize(390.dp, 340.dp)
            .drawWithCache {
                val unit = size.width / 390f
                val path = Path()
                val steps = 96
                for (i in 0..steps) {
                    val t = (i.toFloat() / steps) * 2f * PI.toFloat()
                    val x = (LoopCx + LoopA * sin(t)) * unit
                    val y = (LoopCy + LoopB * sin(2f * t)) * unit
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                path.close()
                val stroke = Stroke(width = 34f * unit, cap = StrokeCap.Round)
                onDrawBehind { drawPath(path, track, style = stroke) }
            }
    ) {
        Rider(phase, 0.00f, 120.dp, MaterialShapes.Flower.toShape(), scheme.tertiary)
        Rider(phase, 0.25f, 64.dp, MaterialShapes.SoftBurst.toShape(), scheme.secondaryContainer)
        Rider(phase, 0.50f, 110.dp, MaterialShapes.Clover4Leaf.toShape(), scheme.secondary)
        Rider(phase, 0.75f, 96.dp, MaterialShapes.Cookie9Sided.toShape(), scheme.primary) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(40.dp))
        }
    }
}

/** One shape on the loop at [offset] of a lap ahead of [phase]; moved by a transform only. */
@Composable
private fun Rider(phase: Animatable<Float, androidx.compose.animation.core.AnimationVector1D>, offset: Float, size: Dp, shape: Shape, color: Color, content: @Composable () -> Unit = {}) {
    Box(
        modifier = Modifier
            .size(size)
            .graphicsLayer {
                val t = (phase.value + offset) * 2f * PI.toFloat()
                val unit = 1.dp.toPx()
                translationX = (LoopCx + LoopA * sin(t)) * unit - this.size.width / 2f
                translationY = (LoopCy + LoopB * sin(2f * t)) * unit - this.size.height / 2f
                rotationZ = cos(t) * 12f
            }
            .clip(shape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Page 2: the host's note cards, fanned and dealt in one at a time (spring, transform only). */
@Composable
private fun NotesArt(entrance: State<Float>) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.requiredSize(390.dp, 340.dp)) {
        Box(Modifier.offset(45.dp, 20.dp).size(300.dp).clip(MaterialShapes.Cookie9Sided.toShape()).background(scheme.surfaceContainerHigh))
        Box(
            Modifier.offset(60.dp, 56.dp).size(250.dp, 96.dp).setupEntrance(entrance, 0, 48.dp).rotate(-7f)
                .clip(RoundedCornerShape(24.dp)).background(scheme.secondaryContainer)
        )
        Row(
            modifier = Modifier.offset(56.dp, 120.dp).width(270.dp).setupEntrance(entrance, 1, 48.dp).rotate(4f)
                .clip(RoundedCornerShape(24.dp)).background(scheme.tertiaryContainer).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HostBadge(40.dp)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.endless_art_up_next), style = NoteEyebrowStyle, color = scheme.onTertiaryContainer)
                Text(stringResource(R.string.endless_art_note_1), style = NoteStyle, color = scheme.onTertiaryContainer)
            }
        }
        Text(
            text = stringResource(R.string.endless_art_note_2),
            style = SmallStyle,
            color = scheme.onPrimaryContainer,
            modifier = Modifier.offset(82.dp, 232.dp).width(230.dp).setupEntrance(entrance, 2, 48.dp).rotate(-3f)
                .clip(RoundedCornerShape(24.dp)).background(scheme.primaryContainer).padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

/** Page 3: you ask, the host answers, the station's chips pop in. */
@Composable
private fun SteerArt(entrance: State<Float>) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.requiredSize(390.dp, 340.dp)) {
        Box(Modifier.offset(65.dp, 40.dp).size(260.dp).clip(MaterialShapes.SoftBurst.toShape()).background(scheme.secondaryContainer))
        Text(
            text = stringResource(R.string.endless_art_ask),
            style = BubbleStyle,
            color = scheme.onPrimaryContainer,
            modifier = Modifier.align(Alignment.TopEnd).offset((-28).dp, 36.dp).widthIn(max = 220.dp).setupEntrance(entrance, 0)
                .clip(RoundedCornerShape(24.dp, 24.dp, 6.dp, 24.dp)).background(scheme.primaryContainer).padding(horizontal = 16.dp, vertical = 12.dp),
        )
        Row(
            modifier = Modifier.offset(28.dp, 116.dp).widthIn(max = 250.dp).setupEntrance(entrance, 1)
                .clip(RoundedCornerShape(24.dp, 24.dp, 24.dp, 6.dp)).background(scheme.surfaceContainerHigh)
                .padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HostBadge(32.dp)
            Text(stringResource(R.string.endless_art_reply), style = NoteStyle, color = scheme.onSurface)
        }
        Row(
            modifier = Modifier.fillMaxWidth().offset(y = 196.dp).padding(horizontal = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            ArtChip(R.string.endless_art_chip_1, scheme.secondary, scheme.onSecondary, Modifier.setupEntrance(entrance, 2))
            ArtChip(R.string.endless_art_chip_2, scheme.tertiaryContainer, scheme.onTertiaryContainer, Modifier.setupEntrance(entrance, 3))
            ArtChip(R.string.endless_art_chip_3, scheme.secondaryContainer, scheme.onSecondaryContainer, Modifier.setupEntrance(entrance, 4))
        }
    }
}

@Composable
private fun ArtChip(label: Int, container: Color, content: Color, modifier: Modifier) {
    Text(
        text = stringResource(label),
        style = ChipStyle,
        color = content,
        maxLines = 1,
        modifier = modifier.height(36.dp).clip(RoundedCornerShape(12.dp)).background(container).padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/** The host's mark: a mic on a Flower (tertiary). Also used by the station's notes. */
@Composable
internal fun HostBadge(size: Dp) {
    val scheme = MaterialTheme.colorScheme
    Box(Modifier.size(size).clip(MaterialShapes.Flower.toShape()).background(scheme.tertiary), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.Mic, contentDescription = null, tint = scheme.onTertiary, modifier = Modifier.size(size * 0.5f))
    }
}

private val SkipStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
private val PillStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp)
private val EyebrowStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.4.sp)
private val BodyStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
private val NoteEyebrowStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp)
private val NoteStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 20.sp)
private val SmallStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp)
private val BubbleStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 22.sp)
private val ChipStyle = TextStyle(fontFamily = GoogleSansRounded, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
