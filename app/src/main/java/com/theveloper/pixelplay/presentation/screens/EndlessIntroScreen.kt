package com.theveloper.pixelplay.presentation.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.presentation.screens.setup.LocalSetupReduceMotion
import com.theveloper.pixelplay.presentation.screens.setup.SetupBottomActions
import com.theveloper.pixelplay.presentation.screens.setup.rememberPageEntrance
import com.theveloper.pixelplay.presentation.screens.setup.setupEntrance
import com.theveloper.pixelplay.presentation.viewmodel.EndlessIntroViewModel
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.ui.theme.ExpTitleTypography
import com.theveloper.pixelplay.ui.theme.readReduceMotion
import kotlin.math.abs
import kotlin.math.floor

/*
 * The introduction of Endless Listening (first open of the card; the card's menu "How it works" replays it). Three pages in a pager, Material 3
 * Expressive springs for the dots, the setup flow's own entrance for the words, and ONE looping value for the five bars: it is read in the draw
 * phase only, so the loop never recomposes anything and allocates nothing per frame. It runs only while this screen is on screen and the app is
 * in the foreground, and not at all when the system's "remove animations" is on (the bars then stand still).
 */

private const val PAGES = 3
private const val BARS = 5

/** Where the bars stand when nothing moves (Reduce Motion, off screen): different heights, so the picture still reads as music. */
private const val STATIC_PHASE = 0.3f

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun EndlessIntroScreen(
    navController: NavController,
    playerViewModel: PlayerViewModel,
    viewModel: EndlessIntroViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val reduceMotion = remember(context) { readReduceMotion(context) }
    val accent by viewModel.accent.collectAsStateWithLifecycle()
    // The app has its own theme mode, so the art's colour is chosen by the surface the screen really has.
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val fallback = MaterialTheme.colorScheme.primary
    val barColor = accent?.let { if (dark) it.second else it.first } ?: fallback
    val pagerState = rememberPagerState(pageCount = { PAGES })
    val motion = remember { MotionScheme.expressive() }

    // However the screen is left (a button, the back gesture), it was seen.
    DisposableEffect(viewModel) { onDispose { viewModel.markSeen() } }

    CompositionLocalProvider(LocalSetupReduceMotion provides reduceMotion) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .background(remember(barColor) { Brush.verticalGradient(listOf(barColor.copy(alpha = 0.14f), Color.Transparent)) }),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                Spacer(Modifier.height(32.dp))
                EndlessBars(
                    color = barColor,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .size(width = 148.dp, height = 132.dp),
                )
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) { page ->
                    val entrance = rememberPageEntrance()
                    IntroPage(
                        title = stringResource(TITLES[page]),
                        body = stringResource(BODIES[page]),
                        entrance = entrance,
                    )
                }
                Dots(current = pagerState.currentPage, motion = motion, modifier = Modifier.align(Alignment.CenterHorizontally))
                Spacer(Modifier.height(12.dp))
                SetupBottomActions(
                    primaryLabel = stringResource(R.string.endless_intro_start),
                    onPrimary = {
                        viewModel.markSeen()
                        playerViewModel.startEndlessListening()
                        navController.popBackStack()
                    },
                    primaryEnabled = true,
                    primaryContainer = null,
                    primaryContent = null,
                    primaryIcon = null,
                    secondaryLabel = stringResource(R.string.endless_intro_not_now),
                    onSecondary = {
                        viewModel.markSeen()
                        navController.popBackStack()
                    },
                )
            }
        }
    }
}

private val TITLES = intArrayOf(R.string.endless_intro_title_1, R.string.endless_intro_title_2, R.string.endless_intro_title_3)
private val BODIES = intArrayOf(R.string.endless_intro_body_1, R.string.endless_intro_body_2, R.string.endless_intro_body_3)

@Composable
private fun IntroPage(title: String, body: String, entrance: State<Float>) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = title,
            style = ExpTitleTypography.displayLarge.copy(fontSize = 38.sp, lineHeight = 42.sp),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .setupEntrance(entrance, index = 1)
                .semantics { heading() },
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.setupEntrance(entrance, index = 2),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Dots(current: Int, motion: MotionScheme, modifier: Modifier = Modifier) {
    val pageCd = stringResource(R.string.endless_intro_page_cd, current + 1, PAGES)
    Row(
        modifier = modifier.semantics { contentDescription = pageCd },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in 0 until PAGES) {
            val width by animateDpAsState(
                targetValue = if (i == current) 24.dp else 8.dp,
                animationSpec = motion.fastSpatialSpec(),
                label = "EndlessDot",
            )
            Box(
                Modifier
                    .size(width = width, height = 8.dp)
                    .background(
                        if (i == current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
            )
        }
    }
}

/**
 * Five bars that rise and fall. One looping phase drives all of them: the loop runs only while this is on screen with the app in the
 * foreground and Reduce Motion is off; the phase is read in the draw block, the colour is one static value, and nothing is allocated per frame.
 */
@Composable
private fun EndlessBars(color: Color, modifier: Modifier = Modifier) {
    val reduceMotion = LocalSetupReduceMotion.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val phase = remember { Animatable(STATIC_PHASE) }
    LaunchedEffect(foreground, reduceMotion) {
        if (foreground && !reduceMotion) {
            phase.animateTo(
                targetValue = STATIC_PHASE + 1f,
                animationSpec = infiniteRepeatable(tween(durationMillis = 2400, easing = LinearEasing)),
            )
        } else {
            phase.snapTo(STATIC_PHASE)
        }
    }
    val description = stringResource(R.string.endless_intro_bars_cd)
    Box(
        modifier = modifier
            .semantics { contentDescription = description }
            .drawBehind {
                val p = phase.value
                val barWidth = size.width / (BARS * 2 - 1)
                val corner = CornerRadius(barWidth / 2f, barWidth / 2f)
                for (i in 0 until BARS) {
                    // A triangle wave of the one phase, shifted for each bar: 0..1 and back, no allocation.
                    val t = p + i * 0.19f
                    val wave = abs(2f * (t - floor(t)) - 1f)
                    val h = size.height * (0.26f + 0.74f * wave)
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(i * 2f * barWidth, size.height - h),
                        size = Size(barWidth, h),
                        cornerRadius = corner,
                    )
                }
            }
    )
}
