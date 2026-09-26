package com.theveloper.pixelplay.presentation.lyrics.background

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import com.theveloper.pixelplay.ui.theme.rememberPowerSaveMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/** Track-change crossfade (Apple: +0.02 alpha per 33 ms tick ≈ 1.7 s, linear). */
private const val CROSSFADE_MS = 1_700

/** The background redraws at 30 fps at most; motion this slow through this much blur never steps. */
private const val FRAME_INTERVAL_NANOS = 33_333_333L

/** Slack so a 60 Hz display (16.67 ms frames) lands on every 2nd frame rather than every 3rd. */
private const val FRAME_SLACK_NANOS = 2_000_000L

/** Rotation rates in rad/s: Apple's per-33 ms increments × 30 (spec §2.2). */
private val ROTATION_RATES = floatArrayOf(0.09f, -0.24f, -0.18f, 0.12f)

/** Reduced motion: every sprite turns at 0.03 rad/s and nothing orbits. */
private const val REDUCED_MOTION_RATE = 0.03f

/** Orbit angle = rotation × 0.75, so phases wrap at 8π where both rotation and orbit repeat. */
private const val ORBIT_FACTOR = 0.75f
private const val PHASE_WRAP = Math.PI * 8.0

/**
 * Exposes what callers need from the background — today, how bright the current art is, so the
 * lyrics can switch to the bright-art treatment (spec §1.2: SrcOver instead of Plus, inactive
 * alpha 0.50). Reading [isBrightArt] in composition changes at most once per track.
 */
@Stable
class LyricsBackgroundState {
    /** Graded mean luma (0..1) of the current art, or null when there is no art. */
    var artLuma: Float? by mutableStateOf(null)
        internal set

    private val bright = derivedStateOf {
        (artLuma ?: 0f) > LyricsBackgroundGrade.BRIGHT_ART_LUMA
    }

    /** True when the art is bright enough that the lyrics should use normal blending. */
    val isBrightArt: Boolean get() = bright.value
}

@Composable
fun rememberLyricsBackgroundState(): LyricsBackgroundState = remember { LyricsBackgroundState() }

/**
 * The animated artwork behind the lyrics: four slowly turning, pre-blurred copies of the art,
 * twisted and over-saturated (spec §2).
 *
 * - API 33+: one AGSL pass (twist + composite + grade + overlays + dither).
 * - API 30–32: the sprites inside one `saveLayer` with a ColorMatrix grade; no twist.
 * - No art: the player's flowing Material gradient.
 *
 * Performance: blur and grading happen once per track on tiny bitmaps off the main thread. The
 * sprites live in their own layer that is invalidated at most every 33 ms, and only while
 * [visible], the host is at least STARTED and power-save is off. Nothing here recomposes per
 * frame, and lyric animation never invalidates this layer.
 *
 * @param artUri album-art URI of the current track, or null.
 * @param visible false while the lyrics sheet is hidden; the animation stops.
 * @param state exposes the art's luma for the bright-art exception.
 * @param colorScheme used for the first-frame fill and the no-art gradient.
 * @param brightArtScrim draw the 35% black scrim over bright art (spec §1.2).
 */
@Composable
fun LyricsArtworkBackground(
    artUri: String?,
    modifier: Modifier = Modifier,
    visible: Boolean = true,
    state: LyricsBackgroundState = rememberLyricsBackgroundState(),
    colorScheme: ColorScheme = MaterialTheme.colorScheme,
    brightArtScrim: Boolean = true,
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val aspectBucket = ArtworkSpriteBaker.aspectBucket(
        configuration.screenWidthDp,
        configuration.screenHeightDp
    )

    val reducedMotion = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) == 0f
    }
    val animator = remember {
        BackgroundAnimator(
            initial = ArtworkSpriteBaker.peek(artUri, aspectBucket),
            reducedMotion = reducedMotion
        )
    }
    val painter = remember { BackgroundPainter() }

    // Lifecycle: stop at ON_STOP.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState = lifecycle.currentStateAsState()
    val started by remember(lifecycleState) {
        derivedStateOf { lifecycleState.value.isAtLeast(Lifecycle.State.STARTED) }
    }
    val powerSave by rememberPowerSaveMode()
    val onScreen = visible && started

    // Bake (or fetch) the sprites whenever the art or the view's aspect changes.
    LaunchedEffect(artUri, aspectBucket) {
        val uri = artUri?.takeIf { it.isNotBlank() }
        val set = if (uri == null) {
            null
        } else {
            val cached = ArtworkSpriteBaker.peek(uri, aspectBucket)
            cached ?: ArtworkSpriteBaker.bake(context, uri, aspectBucket)
        }
        // A set that was already cached at first composition is shown as-is (show() is a no-op);
        // anything else crossfades — from the first-frame fill when nothing was shown yet.
        animator.show(set)
        state.artLuma = set?.meanLuma
    }

    // Frame loop: advances the motion and the crossfade; publishes at most every 33 ms.
    LaunchedEffect(animator, onScreen, powerSave, animator.generation) {
        if (!onScreen) {
            animator.finishFade()
            return@LaunchedEffect
        }
        val motion = !powerSave
        if (!motion && animator.fadeDone) return@LaunchedEffect
        var lastFrame = -1L
        while (isActive) {
            if (lastFrame >= 0L) {
                // Sleep until the next 33 ms slot rather than asking for every vsync: frames that
                // publish nothing would still wake the main thread and hold the panel at 120 Hz.
                val waitMs = (FRAME_INTERVAL_NANOS - FRAME_SLACK_NANOS - (System.nanoTime() - lastFrame)) / 1_000_000L
                if (waitMs > 0L) delay(waitMs)
            }
            val keepGoing = withFrameNanos { now ->
                // Integrates over the real frame-time delta, so the motion speed is unchanged.
                val dt = if (lastFrame < 0L) 0f else ((now - lastFrame) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastFrame = now
                animator.step(dt, motion)
                animator.publish()
                motion || !animator.fadeDone
            }
            if (!keepGoing) break
        }
        animator.publish()
    }

    // First frame / loading fill: surfaceContainerLowest darkened ×0.5.
    val base = colorScheme.surfaceContainerLowest
    val baseFill = remember(base) {
        Color(base.red * 0.5f, base.green * 0.5f, base.blue * 0.5f, 1f)
    }

    // No art → the player's flowing gradient, crossfaded in and out.
    val showGradient by remember(animator) {
        derivedStateOf { animator.resolved && animator.current == null }
    }
    val gradientAlpha = animateFloatAsState(
        targetValue = if (showGradient) 1f else 0f,
        animationSpec = tween(durationMillis = CROSSFADE_MS, easing = LinearEasing),
        label = "lyricsBackgroundGradient"
    )
    val gradientPresent by remember(gradientAlpha) { derivedStateOf { gradientAlpha.value > 0f } }

    Box(
        modifier = modifier
            .drawBehind { drawRect(baseFill) }
    ) {
        if (onScreen && (showGradient || gradientPresent)) {
            LyricsFlowingGradient(
                animator = animator,
                colorScheme = colorScheme,
                alpha = { gradientAlpha.value },
                modifier = Modifier.fillMaxSize(),
            )
        }
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                // Own, cached (offscreen) layer: re-rasterised only when the 30 fps tick below
                // re-records it. Every other frame — lyric cascades, drags, the controls — HWUI
                // just blits the cached texture instead of re-running the full-screen shader.
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    animator.tick.intValue // Subscribe this draw (only) to the 30 fps tick.
                    painter.draw(this, animator, brightArtScrim)
                }
        )
    }
}

/**
 * The no-art fallback: the player's flowing gradient (same three blobs, same 50 s loop), but on
 * this background's 30 fps tick and with its brushes built once per size, so a frame neither
 * allocates nor redraws at display rate.
 */
@Composable
private fun LyricsFlowingGradient(
    animator: BackgroundAnimator,
    colorScheme: ColorScheme,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    val colorA = colorScheme.primary.copy(alpha = 0.55f)
    val colorB = colorScheme.tertiary.copy(alpha = 0.50f)
    val colorC = colorScheme.secondary.copy(alpha = 0.42f)
    Spacer(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha() }
            .drawWithCache {
                val w = size.width
                val h = size.height
                val radius = kotlin.math.min(w, h) * 0.78f
                // Centred on the origin; each frame only translates the canvas.
                fun blob(color: Color) = Brush.radialGradient(
                    0f to color,
                    0.55f to color.copy(alpha = color.alpha * 0.7f),
                    1f to Color.Transparent,
                    center = Offset.Zero,
                    radius = radius,
                )
                val brushA = blob(colorA)
                val brushB = blob(colorB)
                val brushC = blob(colorC)
                onDrawBehind {
                    animator.tick.intValue // Subscribe this draw (only) to the 30 fps tick.
                    val turn = (animator.elapsedSeconds / FLOWING_GRADIENT_PERIOD_S) % 1f
                    val angleA = turn * TWO_PI
                    val angleB = turn * TWO_PI * 0.63f + 1.7f
                    val angleC = turn * TWO_PI * 0.47f + 3.4f
                    translate(w * (0.5f + 0.26f * cos(angleC + 2.1f)), h * (0.5f + 0.26f * sin(angleC))) {
                        drawCircle(brushC, radius, Offset.Zero)
                    }
                    translate(w * (0.5f + 0.30f * cos(angleB + PI_F)), h * (0.68f + 0.20f * sin(angleB))) {
                        drawCircle(brushB, radius, Offset.Zero)
                    }
                    translate(w * (0.5f + 0.32f * cos(angleA)), h * (0.32f + 0.22f * sin(angleA * 1.3f))) {
                        drawCircle(brushA, radius, Offset.Zero)
                    }
                }
            }
    )
}

private const val FLOWING_GRADIENT_PERIOD_S = 50f
private const val PI_F = Math.PI.toFloat()
private const val TWO_PI = 2f * PI_F


/**
 * Motion + crossfade clock. Integrates every frame but publishes to the draw only through [tick],
 * so snapshot writes happen at ≤ 30 Hz. Main thread only.
 */
internal class BackgroundAnimator(initial: SpriteSet?, val reducedMotion: Boolean) {

    /** The set fading in (or shown). Null = no art. */
    var current: SpriteSet? by mutableStateOf(initial)
        private set

    /** The set fading out, only during a crossfade. */
    var previous: SpriteSet? by mutableStateOf(null)
        private set

    /** False until we know whether the track has art (first frame shows the plain fill). */
    var resolved: Boolean by mutableStateOf(initial != null)
        private set

    /** Bumped when a crossfade starts; restarts the frame loop. */
    var generation: Int by mutableIntStateOf(0)
        private set

    /** Draw subscription: bumped at ≤ 30 Hz. */
    val tick = mutableIntStateOf(0)

    // Published values, read by the draw after it reads [tick].
    val phases = FloatArray(4)
    var fade: Float = 1f
        private set

    private val phaseAccum = DoubleArray(4)
    private var timeAccum = 0.0

    /** Motion time in seconds, published with [phases] (drives the no-art gradient). */
    var elapsedSeconds: Float = 0f
        private set
    private var pendingFade = 1f
    val fadeDone: Boolean get() = pendingFade >= 1f
    var publishedFadeDone: Boolean = true
        private set

    private val rates = FloatArray(4) { k ->
        if (reducedMotion) REDUCED_MOTION_RATE * (if (ROTATION_RATES[k] < 0f) -1f else 1f) else ROTATION_RATES[k]
    }

    /** Starts a crossfade to [next] (null = no art). No-op if [next] is already the target. */
    fun show(next: SpriteSet?) {
        if (resolved && next === current) return
        // Mid-fade: keep whichever set is contributing more as the outgoing one.
        val outgoing = if (previous != null && pendingFade < 0.5f) previous else current
        if (outgoing === next) {
            // Bounced back to the set that is mostly on screen already: just settle on it.
            previous = null
            pendingFade = 1f
        } else {
            previous = outgoing
            pendingFade = if (outgoing == null && next == null) 1f else 0f
        }
        current = next
        resolved = true
        publish()
        generation++
    }

    fun step(dtSeconds: Float, motion: Boolean) {
        if (motion) {
            for (k in 0 until 4) {
                phaseAccum[k] = (phaseAccum[k] + rates[k] * dtSeconds) % PHASE_WRAP
            }
            timeAccum = (timeAccum + dtSeconds) % FLOWING_GRADIENT_PERIOD_S
        }
        if (pendingFade < 1f) {
            pendingFade = (pendingFade + dtSeconds * 1000f / CROSSFADE_MS).coerceAtMost(1f)
        }
    }

    fun finishFade() {
        pendingFade = 1f
        publish()
    }

    fun publish() {
        for (k in 0 until 4) phases[k] = phaseAccum[k].toFloat()
        elapsedSeconds = timeAccum.toFloat()
        fade = pendingFade
        publishedFadeDone = fadeDone
        if (fadeDone && previous != null) previous = null
        tick.intValue++
    }
}

/** Holds the draw-time objects so a frame allocates nothing. */
internal class BackgroundPainter {
    private val spritePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gradeLayerPaint = Paint().apply {
        setColorFilter(ColorMatrixColorFilter(LyricsBackgroundGrade.GRADE))
    }
    private val alphaLayerPaint = Paint()
    private val shaderPaint = Paint()
    private val overlayPaint = Paint()
    private var noisePaint: Paint? = null
    private val rect = RectF()

    /** Per sprite: centreX, centreY, angle (rad), art size on screen (px). */
    private val geometry = FloatArray(16)

    /** Per sprite: s·cosθ, s·sinθ, centreX, centreY — the tier-B uniforms. */
    private val xf = FloatArray(16)

    fun draw(scope: DrawScope, animator: BackgroundAnimator, brightArtScrim: Boolean) {
        scope.drawIntoCanvas { canvas ->
            drawOn(canvas.nativeCanvas, scope.size.width, scope.size.height, animator, brightArtScrim)
        }
    }

    /** Draws the background into [nc] as a `w × h` surface. */
    fun drawOn(nc: android.graphics.Canvas, w: Float, h: Float, animator: BackgroundAnimator, brightArtScrim: Boolean) {
        if (w <= 0f || h <= 0f) return
        val current = animator.current
        val previous = animator.previous
        if (current == null && previous == null) return

        // prev at 1 under cur at f is a linear crossfade; with no incoming art prev fades out.
        val f = animator.fade
        val currentAlpha = if (current != null) f else 0f
        val previousAlpha = when {
            previous == null -> 0f
            current != null -> 1f
            else -> 1f - f
        }

        if (previous != null && previousAlpha > 0f) {
            drawSet(nc, previous, previousAlpha, w, h, animator, brightArtScrim)
        }
        if (current != null && currentAlpha > 0f) {
            drawSet(nc, current, currentAlpha, w, h, animator, brightArtScrim)
        }
    }

    private fun drawSet(
        nc: android.graphics.Canvas,
        set: SpriteSet,
        alpha: Float,
        w: Float,
        h: Float,
        animator: BackgroundAnimator,
        brightArtScrim: Boolean
    ) {
        computeGeometry(set, animator, w, h)
        val scrim = if (brightArtScrim && set.isBright) LyricsBackgroundGrade.BRIGHT_ART_SCRIM else 0f
        val shader = set.shader
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shader != null) {
            drawSetShader(nc, set, shader, alpha, scrim, w, h)
        } else {
            drawSetColorMatrix(nc, set, alpha, scrim, w, h)
        }
    }

    private fun computeGeometry(set: SpriteSet, animator: BackgroundAnimator, w: Float, h: Float) {
        for (k in 0 until 4) {
            val theta = set.initialAngles[k] + animator.phases[k]
            val orbit = ORBIT_FACTOR * (if (animator.reducedMotion) set.initialAngles[k] else theta)
            val cx: Float
            val cy: Float
            when (k) {
                0 -> { cx = w / 2f; cy = h / 2f }
                1 -> { cx = w / 2.5f; cy = h / 2.5f }
                2 -> { cx = w / 2f + 0.25f * w * cos(orbit); cy = h / 2f + 0.25f * w * sin(orbit) }
                else -> { cx = w / 2f + 0.05f * w + 0.25f * w * cos(orbit); cy = h / 2f + 0.25f * w * sin(orbit) }
            }
            val i = k * 4
            geometry[i] = cx
            geometry[i + 1] = cy
            geometry[i + 2] = theta
            geometry[i + 3] = ArtworkSpriteBaker.spriteArtSize(k, w, h)
        }
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawSetShader(
        nc: android.graphics.Canvas,
        set: SpriteSet,
        shader: Shader,
        alpha: Float,
        scrim: Float,
        w: Float,
        h: Float
    ) {
        val runtimeShader = shader as? RuntimeShader ?: return drawSetColorMatrix(nc, set, alpha, scrim, w, h)
        for (k in 0 until 4) {
            val i = k * 4
            val texelsPerPx = ArtworkSpriteBaker.ART_TEXELS / geometry[i + 3]
            val theta = geometry[i + 2]
            xf[i] = texelsPerPx * cos(theta)
            xf[i + 1] = texelsPerPx * sin(theta)
            xf[i + 2] = geometry[i]
            xf[i + 3] = geometry[i + 1]
        }
        LyricsBackgroundShader.update(runtimeShader, w, h, xf, alpha, scrim)
        shaderPaint.setShader(runtimeShader)
        nc.drawRect(0f, 0f, w, h, shaderPaint)
    }

    /**
     * Tier A: sprites inside a ColorMatrix-graded layer (the filter clamps on restore), then the
     * overlays. A partially faded set gets one extra layer so the crossfade stays exact.
     */
    private fun drawSetColorMatrix(
        nc: android.graphics.Canvas,
        set: SpriteSet,
        alpha: Float,
        scrim: Float,
        w: Float,
        h: Float
    ) {
        val fading = alpha < 0.999f
        if (fading) {
            alphaLayerPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)
            nc.saveLayer(0f, 0f, w, h, alphaLayerPaint)
        }

        nc.saveLayer(0f, 0f, w, h, gradeLayerPaint)
        for (k in 0 until 4) {
            val bitmap = set.sprites[k]
            val i = k * 4
            // The art part spans geometry[i+3] px; the padding scales with it.
            val half = geometry[i + 3] * bitmap.width / ArtworkSpriteBaker.ART_TEXELS / 2f
            nc.save()
            nc.translate(geometry[i], geometry[i + 1])
            nc.rotate(Math.toDegrees(geometry[i + 2].toDouble()).toFloat())
            rect.set(-half, -half, half, half)
            nc.drawBitmap(bitmap, null, rect, spritePaint)
            nc.restore()
        }
        nc.restore()

        overlayPaint.color = android.graphics.Color.argb(alphaByte(LyricsBackgroundGrade.BLACK_OVERLAY), 0, 0, 0)
        nc.drawRect(0f, 0f, w, h, overlayPaint)
        overlayPaint.color = android.graphics.Color.argb(alphaByte(LyricsBackgroundGrade.WHITE_OVERLAY), 255, 255, 255)
        nc.drawRect(0f, 0f, w, h, overlayPaint)
        if (scrim > 0f) {
            overlayPaint.color = android.graphics.Color.argb(alphaByte(scrim), 0, 0, 0)
            nc.drawRect(0f, 0f, w, h, overlayPaint)
        }
        nc.drawRect(0f, 0f, w, h, noisePaint())

        if (fading) nc.restore()
    }

    /** 64×64 grey noise, tiled at 3% to break up banding on 8-bit panels (tier A only). */
    private fun noisePaint(): Paint {
        noisePaint?.let { return it }
        val size = 64
        val random = Random(0x5EED)
        val pixels = IntArray(size * size) {
            val v = random.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        val tile = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        return Paint().apply {
            setShader(BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT))
            alpha = alphaByte(0.03f)
        }.also { noisePaint = it }
    }

    private fun alphaByte(alpha: Float): Int = (alpha * 255f).roundToInt().coerceIn(0, 255)
}
