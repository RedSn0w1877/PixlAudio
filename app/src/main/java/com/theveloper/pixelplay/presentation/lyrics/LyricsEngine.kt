package com.theveloper.pixelplay.presentation.lyrics

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import com.theveloper.pixelplay.presentation.lyrics.model.PreparedLyrics
import com.theveloper.pixelplay.presentation.lyrics.model.Row
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sqrt

// =============================================================================================
// Springs (spec §3.1)
// =============================================================================================

/**
 * Web spring constants (`m·x″ + c·x′ + k·x = 0`) converted to Compose's unit-mass
 * `spring(dampingRatio, stiffness)`: `stiffness = k / m`, `ζ = c / (2·√(k·m))`.
 */
object LyricsSprings {
    /** Mass of AMLL's line springs. */
    const val LINE_MASS = 0.9f

    fun dampingRatio(mass: Float, stiffness: Float, damping: Float): Float = damping / (2f * sqrt(stiffness * mass))

    fun stiffness(mass: Float, stiffness: Float): Float = stiffness / mass

    /** Normal playback: `c = 2.2·√k` at m = 0.9, so `ζ = 1.1/√0.9` whatever k is. */
    val NormalDampingRatio: Float = 1.1f / sqrt(LINE_MASS)

    /**
     * Web stiffness for normal playback: `gap = clamp(Δstart, 100, 800)`,
     * `ratio = (1 − (gap − 100)/700)^0.2`, `k = 170 + 50·ratio`. Lines that come quickly get a
     * stiffer, faster spring.
     */
    fun normalPlaybackWebStiffness(gapMs: Long): Float {
        val gap = gapMs.coerceIn(100L, 800L).toFloat()
        val ratio = (1f - (gap - 100f) / 700f).coerceIn(0f, 1f).pow(0.2f)
        return 170f + 50f * ratio
    }

    /** Compose stiffness for normal playback (188.9 … 244.4). */
    fun normalPlaybackStiffness(gapMs: Long): Float = stiffness(LINE_MASS, normalPlaybackWebStiffness(gapMs))

    /** Seek, interlude, first/last line, snap-back: web (0.9, 90, 15) → ζ 0.8333, stiffness 100. */
    val SlowDampingRatio: Float = dampingRatio(LINE_MASS, 90f, 15f)
    val SlowStiffness: Float = stiffness(LINE_MASS, 90f)

    /** Song ended: web (0.9, 140, 22) → ζ 0.980, stiffness 155.6. */
    val EndedDampingRatio: Float = dampingRatio(LINE_MASS, 140f, 22f)
    val EndedStiffness: Float = stiffness(LINE_MASS, 140f)

    /** Line scale: web (2, 100, 25) → ζ 0.884, stiffness 50. */
    val ScaleDampingRatio: Float = dampingRatio(2f, 100f, 25f)
    val ScaleStiffness: Float = stiffness(2f, 100f)

    /** Background-vocal expand and slide (not from a web source). */
    const val BackgroundDampingRatio = 0.9f
    const val BackgroundStiffness = 150f

    val Slow = FloatSpringSpec(SlowDampingRatio, SlowStiffness, 0.5f)
    val Ended = FloatSpringSpec(EndedDampingRatio, EndedStiffness, 0.5f)
    val Scale = FloatSpringSpec(ScaleDampingRatio, ScaleStiffness, 0.0005f)
    val Background = FloatSpringSpec(BackgroundDampingRatio, BackgroundStiffness, 0.001f)

    private val normalCache = HashMap<Int, FloatSpringSpec>()

    /** Normal-playback spec for a start gap, cached by stiffness (allocates only on first use). */
    fun normal(gapMs: Long): FloatSpringSpec {
        val stiffness = normalPlaybackStiffness(gapMs)
        val key = round(stiffness * 10f).toInt()
        return normalCache.getOrPut(key) { FloatSpringSpec(NormalDampingRatio, stiffness, 0.5f) }
    }
}

// =============================================================================================
// Blur (spec §1, §1.3)
// =============================================================================================

object LyricsBlurMath {
    const val MAX_SIGMA_DP = 5f
    const val SIGMA_PER_STEP_DP = 0.8f
    // 1.5 px, not the spec's 0.25: every row's σ retargets on each line change, and at 0.25 px a
    // 400 ms tween minted a new BlurEffect every ~3 frames per blurred row (≈8 re-filters per
    // cascade frame). The eye can't resolve sub-2 px radius steps under that motion.
    const val RADIUS_QUANTUM_PX = 1.5f
    const val FALLBACK_ALPHA_STEP = 0.06f
    const val FALLBACK_MAX_DISTANCE = 4

    /** σ in dp (a CSS `filter: blur` value) → Android/Compose blur radius in px. Skia: `σ = 0.57735·r + 0.5`. */
    fun sigmaDpToRadiusPx(sigmaDp: Float, density: Float): Float =
        ((sigmaDp * density - 0.5f) / 0.57735f).coerceAtLeast(0f)

    /** A CSS text-shadow blur B (dp) has σ = B/2. */
    fun cssShadowBlurToRadiusPx(blurDp: Float, density: Float): Float = sigmaDpToRadiusPx(blurDp / 2f, density)

    /** Depth blur for a line [distance] rows from the hot lines: `min(5, (1 + d) × 0.8) × strength` dp. */
    fun depthSigmaDp(distance: Int, strength: Float): Float =
        if (distance <= 0) 0f else min(MAX_SIGMA_DP, (1 + distance) * SIGMA_PER_STEP_DP) * strength

    /** API 30 substitute for blur: multiply the inactive alpha by `1 − 0.06·min(d, 4)`. */
    fun fallbackAlphaFactor(distance: Int): Float =
        if (distance <= 0) 1f else 1f - FALLBACK_ALPHA_STEP * min(distance, FALLBACK_MAX_DISTANCE)

    /** Rounds a blur radius to [RADIUS_QUANTUM_PX] steps so layers are not re-filtered every frame. */
    fun quantizeRadiusPx(radiusPx: Float): Float = round(radiusPx / RADIUS_QUANTUM_PX) * RADIUS_QUANTUM_PX
}

// =============================================================================================
// Cascade (spec §3.3)
// =============================================================================================

object LyricsCascade {
    const val BASE_STEP_MS = 50f
    const val STEP_DECAY = 1.05f

    /**
     * Stagger delays for a scroll-target change. Walks rows in index order; each row whose
     * current bottom (`top + height`) is ≥ 0 — visible or below the viewport — gets the running
     * delay, which then grows by `step` (50 ms). From [targetRow] on, `step /= 1.05` after each
     * row. Rows above the viewport get 0. Zero-height rows (collapsed background vocals,
     * interludes at rest) take the running delay without consuming a step.
     *
     * @param out receives delays in ms, `out[i]` for row `i`.
     */
    fun computeDelays(tops: FloatArray, heights: FloatArray, count: Int, targetRow: Int, out: FloatArray) {
        var delay = 0f
        var step = BASE_STEP_MS
        for (i in 0 until count) {
            val h = heights[i].coerceAtLeast(0f)
            if (tops[i] + h < 0f) {
                out[i] = 0f
                continue
            }
            out[i] = delay
            if (h < 0.5f) continue
            delay += step
            if (i >= targetRow) step /= STEP_DECAY
        }
    }
}

// =============================================================================================
// Configuration and per-row output state
// =============================================================================================

/**
 * @param density px per dp.
 * @param blurSupported `RenderEffect` blur is available (API 31+). When false, depth is shown
 *   through [LyricRowMotion.depthAlpha] instead.
 * @param blurEnabled the `animatedLyricsBlurEnabled` preference (and not increased contrast).
 * @param blurStrength multiplier on the spec's σ table; 1 gives 1.6 / 2.4 / 3.2 / 4.0 / 4.8 dp.
 * @param reducedMotion springs snap and the stagger is 0; activeness still fades.
 */
@Immutable
data class LyricsEngineConfig(
    val density: Float = 1f,
    val blurSupported: Boolean = true,
    val blurEnabled: Boolean = true,
    val blurStrength: Float = 1f,
    val reducedMotion: Boolean = false,
)

/**
 * Per-row motion outputs, written by [LyricsEngine.step] only when a value changes. Read them in
 * placement (`y`, `expand`), the layer block (`scale`, `blurRadiusPx`, `depthAlpha`, `presence`)
 * or draw (`hot`, `activeness`), never in composition.
 */
@Stable
class LyricRowMotion internal constructor() {
    internal val yState: MutableFloatState = mutableFloatStateOf(LyricsEngine.OFFSCREEN_Y)
    internal val scaleState: MutableFloatState = mutableFloatStateOf(1f)
    internal val blurState: MutableFloatState = mutableFloatStateOf(0f)
    internal val depthAlphaState: MutableFloatState = mutableFloatStateOf(1f)
    internal val activenessState: MutableFloatState = mutableFloatStateOf(0f)
    internal val hotState = mutableStateOf(false)
    internal val expandState: MutableFloatState = mutableFloatStateOf(1f)
    internal val presenceState: MutableFloatState = mutableFloatStateOf(1f)
    internal val prefetchState = mutableStateOf(false)

    /**
     * Top of the row in px from the top of the lyrics viewport, **excluding**
     * [LyricsEngine.scrollOffset] (add it in placement). Includes the background-vocal slide.
     */
    val y: Float get() = yState.floatValue

    /** Layer scale. Pivot `(0, 0.5)`, or `(1, 0.5)` for duet / RTL lines. */
    val scale: Float get() = scaleState.floatValue

    /** `RenderEffect` blur radius in px, quantised to 0.25 px. 0 = no effect. */
    val blurRadiusPx: Float get() = blurState.floatValue

    /** API 30 depth falloff: multiply the inactive alpha by this. 1 when blur is supported. */
    val depthAlpha: Float get() = depthAlphaState.floatValue

    /** Line activeness `a` in 0..1 (300 ms up, 450 ms down). */
    val activeness: Float get() = activenessState.floatValue

    /** Line hot (`start − 250 ≤ t < end`), or interlude in progress. Read `clock.nowMs` in draw only when true. */
    val hot: Boolean get() = hotState.value

    /** Height factor: interlude expand, background-vocal expand; 1 for other lines. Read in placement. */
    val expand: Float get() = expandState.floatValue

    /** Alpha multiplier for grouped background vocals (0 while collapsed); 1 for other rows. */
    val presence: Float get() = presenceState.floatValue

    /**
     * The line turns hot within about [LyricsEngine.PREFETCH_LEAD_MS] (and has not ended): build
     * its expensive draw caches (word pieces) now, a second ahead of the line-change frame.
     * Latches true for the life of the row.
     */
    val prefetch: Boolean get() = prefetchState.value

    /** False until the engine has positioned the row for the first time. */
    val isPlaced: Boolean get() = y < LyricsEngine.OFFSCREEN_Y / 2f
}

// =============================================================================================
// Animation channels: primitive arrays, no per-frame allocation.
// =============================================================================================

internal class SpringChannel(size: Int, private val restDelta: Float, private val restVelocity: Float) {
    val value = FloatArray(size)
    val velocity = FloatArray(size)
    val target = FloatArray(size)
    val active = BooleanArray(size)
    private val from = FloatArray(size)
    private val v0 = FloatArray(size)
    private val startNanos = LongArray(size)
    private val specs = arrayOfNulls<FloatSpringSpec>(size)

    fun snap(i: Int, v: Float) {
        value[i] = v
        velocity[i] = 0f
        target[i] = v
        from[i] = v
        v0[i] = 0f
        active[i] = false
    }

    /** Restarts toward [newTarget] from the current value and velocity (call [update] first this frame). */
    fun animateTo(i: Int, newTarget: Float, spec: FloatSpringSpec, nowNanos: Long) {
        if (!active[i] && value[i] == newTarget) {
            target[i] = newTarget
            return
        }
        from[i] = value[i]
        v0[i] = velocity[i]
        target[i] = newTarget
        specs[i] = spec
        startNanos[i] = nowNanos
        active[i] = true
    }

    /** Moves the whole motion by [delta] without changing its shape. */
    fun shift(i: Int, delta: Float) {
        value[i] += delta
        from[i] += delta
        target[i] += delta
    }

    /** Evaluates the closed-form spring at [nowNanos]; returns whether it is still moving. */
    fun update(i: Int, nowNanos: Long): Boolean {
        if (!active[i]) return false
        val spec = specs[i] ?: run { snap(i, target[i]); return false }
        val dt = (nowNanos - startNanos[i]).coerceAtLeast(0L)
        val v = spec.getValueFromNanos(dt, from[i], target[i], v0[i])
        val vel = spec.getVelocityFromNanos(dt, from[i], target[i], v0[i])
        if ((abs(v - target[i]) < restDelta && abs(vel) < restVelocity) || dt > MAX_SPRING_NANOS) {
            snap(i, target[i])
            return false
        }
        value[i] = v
        velocity[i] = vel
        return true
    }

    companion object {
        const val MAX_SPRING_NANOS = 10_000_000_000L
    }
}

internal class TweenChannel(size: Int) {
    val value = FloatArray(size)
    val to = FloatArray(size)
    val active = BooleanArray(size)
    private val from = FloatArray(size)
    private val startNanos = LongArray(size)
    private val durationNanos = LongArray(size)
    private val easings = arrayOfNulls<Easing>(size)

    fun snap(i: Int, v: Float) {
        value[i] = v
        from[i] = v
        to[i] = v
        active[i] = false
    }

    /** Starts a tween from the current value unless it is already heading to [target]. */
    fun animateTo(i: Int, target: Float, durationMs: Long, easing: Easing, nowNanos: Long) {
        if (to[i] == target) return
        if (durationMs <= 0L) {
            snap(i, target)
            return
        }
        from[i] = value[i]
        to[i] = target
        startNanos[i] = nowNanos
        durationNanos[i] = durationMs * 1_000_000L
        easings[i] = easing
        active[i] = true
    }

    fun update(i: Int, nowNanos: Long): Boolean {
        if (!active[i]) return false
        val f = ((nowNanos - startNanos[i]).toFloat() / durationNanos[i].toFloat()).coerceIn(0f, 1f)
        if (f >= 1f) {
            snap(i, to[i])
            return false
        }
        val e = easings[i]?.transform(f) ?: f
        value[i] = from[i] + (to[i] - from[i]) * e
        return true
    }
}

// =============================================================================================
// The engine
// =============================================================================================

/**
 * The lyrics motion engine (spec §3–4): one plain object advanced by [step] from a single frame
 * loop, never one `Animatable` per line.
 *
 * ```
 * LaunchedEffect(engine) {
 *     while (isActive) withFrameNanos { clock.tick(it); engine.step(it) }  // suspend while !engine.needsFrame
 * }
 * ```
 *
 * Inputs: [setLyrics], [setViewport], [setRowHeight] (from measure), [setConfig], the clock's
 * `isPlaying`, and the gesture calls ([onDragStart], [onDrag], [onDragEnd], [onLineTapped]).
 * Outputs: [rows] (per-row snapshot state) and [scrollOffset].
 *
 * Every per-frame computation runs on primitive arrays; the hot set comes from a binary search
 * over the pre-sorted start times. Snapshot state is written only when a value moves past its
 * epsilon (y 0.25 px, scale 0.0005, blur 0.25 px steps, activeness/expand 0.002) or settles.
 */
@Stable
class LyricsEngine(private val clock: LyricsClock) {

    companion object {
        /** Where rows sit before the first layout (renderers may skip them: see [LyricRowMotion.isPlaced]). */
        const val OFFSCREEN_Y = 1_000_000f

        const val LEAD_IN_MS = 250L
        /** How far ahead of turning hot a line is asked to build its word pieces. */
        const val PREFETCH_LEAD_MS = 1_000L
        const val INACTIVE_SCALE = 0.97f
        const val BACKGROUND_COLLAPSED_SCALE = 0.75f
        const val BACKGROUND_EXPAND_FROM_SCALE = 0.8f
        const val BACKGROUND_SLIDE_FRACTION = 0.8f
        const val ACTIVATE_MS = 300L
        const val DEACTIVATE_MS = 450L
        const val BLUR_TWEEN_MS = 400L
        const val BLUR_DRAG_MS = 250L
        const val SNAP_MARGIN_DP = 300f
        const val FIRST_SHOW_START_FACTOR = 2f
        const val MIN_FLING_VELOCITY_PX_S = 100f
        const val FLING_FRICTION_MULTIPLIER = 0.733f
        const val FLING_ABS_VELOCITY_THRESHOLD = 50f
        const val SNAP_BACK_IDLE_MS = 4_500L
        const val SNAP_BACK_MIN_IDLE_MS = 500L
        const val TAP_SLOW_WINDOW_MS = 1_500L
        const val BOTTOM_LIMIT_FRACTION = 0.5f

        private const val Y_EPS = 0.25f
        private const val SCALE_EPS = 0.0005f
        private const val ACTIVENESS_EPS = 0.002f
        private const val EXPAND_EPS = 0.002f
        private const val MS = 1_000_000L

        /** Index of the first element of [sorted] greater than [key] (the upper bound). */
        internal fun upperBound(sorted: LongArray, key: Long): Int {
            var lo = 0
            var hi = sorted.size
            while (lo < hi) {
                val mid = (lo + hi) ushr 1
                if (sorted[mid] <= key) lo = mid + 1 else hi = mid
            }
            return lo
        }
    }

    // ---- configuration ---------------------------------------------------------------------

    var config: LyricsEngineConfig = LyricsEngineConfig()
        private set

    fun setConfig(config: LyricsEngineConfig) {
        this.config = config
    }

    // ---- model -----------------------------------------------------------------------------

    var prepared: PreparedLyrics? = null
        private set

    /** One motion object per row of [PreparedLyrics.rows], replaced on [setLyrics]. */
    var rows: List<LyricRowMotion> = emptyList()
        private set

    private var rowCount = 0
    private var lineCount = 0
    private var rowLine = IntArray(0)            // line index, -1 for interlude rows
    private var lineRow = IntArray(0)
    private var lineGroupLead = IntArray(0)
    private var lineIsLeader = BooleanArray(0)
    private var rowIsBg = BooleanArray(0)        // grouped background vocal
    private var rowBgAbove = BooleanArray(0)
    private var rowLeaderOrdinal = IntArray(0)   // ordinal among leader rows, -1 otherwise
    private var rowNextLeaderOrdinal = IntArray(0)
    private var rowG0 = LongArray(0)
    private var rowG1 = LongArray(0)
    private var interludeRows = IntArray(0)
    private var leaderRows = IntArray(0)
    private var starts = LongArray(0)
    private var ends = LongArray(0)
    private var maxLineDurationMs = 0L
    private var lastEndMs = 0L

    // ---- hot set ---------------------------------------------------------------------------

    private var lineHot = BooleanArray(0)
    private var groupHot = BooleanArray(0)       // by leader line index
    private var hotList = IntArray(0)
    private var hotCount = 0

    // ---- layout ----------------------------------------------------------------------------

    private var rowHeight = FloatArray(0)        // measured full height, -1 = unknown
    private var effHeight = FloatArray(0)
    private var prefix = FloatArray(0)           // size rowCount + 1
    private var targetY = FloatArray(0)
    private var tops = FloatArray(0)             // scratch for the cascade
    private var delays = FloatArray(0)
    private var viewportHeight = 0f
    private var anchorY = 0f
    private var minOffset = 0f
    private var maxOffset = 0f

    // ---- motion ----------------------------------------------------------------------------

    private var y = SpringChannel(0, 0.1f, 5f)
    private var scale = SpringChannel(0, 0.0002f, 0.002f)
    private var bgExpand = SpringChannel(0, 0.001f, 0.01f)
    private var activeness = TweenChannel(0)
    private var presence = TweenChannel(0)
    private var sigma = TweenChannel(0)
    private var depthAlpha = TweenChannel(0)
    private var rowSpec = arrayOfNulls<FloatSpringSpec>(0)
    private var pendingAt = LongArray(0)
    private var pendingSpec = arrayOfNulls<FloatSpringSpec>(0)

    // ---- published shadows (last written values) ---------------------------------------------

    private var pubY = FloatArray(0)
    private var pubScale = FloatArray(0)
    private var pubBlur = FloatArray(0)
    private var pubDepthAlpha = FloatArray(0)
    private var pubActiveness = FloatArray(0)
    private var pubExpand = FloatArray(0)
    private var pubPresence = FloatArray(0)
    private var pubPrefetch = BooleanArray(0)
    private var pubHot = BooleanArray(0)

    // ---- lifecycle flags -------------------------------------------------------------------

    private var laidOut = false
    private var animateInPending = false
    private var snapChannelsPending = false
    private var resizePending = false
    private var lastScrollTarget = -1
    private var slowUntilNanos = -1L
    private var tapPending = false
    private var resetScrollStatePending = false

    /** Current scroll-target row (-1 before the first step). */
    val scrollTargetRow: Int get() = lastScrollTarget

    /**
     * The row layout is anchored on (the frozen row during a user scroll, else the scroll
     * target), or -1 before the first step. Plain field reads: safe in measure.
     */
    internal val layoutAnchorRow: Int get() = if (userScroll) frozenAnchorRow else lastScrollTarget

    /** [scrollOffset] without a snapshot read, for the measure pass. */
    internal val rawScrollOffset: Float get() = offset

    /**
     * The scroll target at lyrics time [t] before the first [step] has run (what the first
     * layout will anchor on), or -1 with no rows. Touches only the hot-set scratch that every
     * step recomputes.
     */
    internal fun predictScrollTargetRow(t: Long): Int {
        if (prepared == null || rowCount == 0) return -1
        updateHotSet(t)
        return resolveScrollTargetRow(t)
    }

    // ---- user scroll -----------------------------------------------------------------------

    private val scrollOffsetState = mutableFloatStateOf(0f)
    private val userScrollingState = mutableStateOf(false)
    private var offset = 0f
    private var userScroll = false
    private var frozenAnchorRow = 0
    private var dragging = false
    private var flinging = false
    private var flingFrom = 0f
    private var flingVelocity = 0f
    private var flingStartNanos = -1L
    private var flingDurationNanos = 0L
    private var scrollEndPending = false
    private var scrollEndedNanos = -1L
    private val decay = FloatExponentialDecaySpec(FLING_FRICTION_MULTIPLIER, FLING_ABS_VELOCITY_THRESHOLD)

    /** User scroll offset in px, added to every row's [LyricRowMotion.y] in placement. Snapshot state. */
    val scrollOffset: Float get() = scrollOffsetState.floatValue

    /** True between the start of a drag and the snap-back to auto-follow. Snapshot state. */
    val isUserScrolling: Boolean get() = userScrollingState.value

    /** Whether everything is settled: no spring, tween, pending cascade, fling or scroll timer. */
    var isAtRest: Boolean = true
        private set

    /** The frame loop may suspend when this is false (paused and settled). */
    val needsFrame: Boolean
        get() = clock.isPlaying || !isAtRest || tapPending || resizePending ||
            (prepared != null && !laidOut && layoutInputsReady())

    // =========================================================================================
    // Inputs
    // =========================================================================================

    /**
     * Installs a new model. [animateIn] (song change, first show): every line starts at
     * `2 × viewportHeight` and cascades up. Otherwise (a rebuild of the same song) rows snap into
     * place with no stagger. Writes no snapshot state, so it may be called from composition.
     */
    fun setLyrics(prepared: PreparedLyrics?, animateIn: Boolean) {
        if (prepared === this.prepared) return
        this.prepared = prepared
        val lines = prepared?.lines
        val modelRows = prepared?.rows
        lineCount = lines?.size ?: 0
        rowCount = modelRows?.size ?: 0
        val n = rowCount

        rowLine = IntArray(n) { -1 }
        lineRow = IntArray(lineCount)
        lineGroupLead = IntArray(lineCount)
        lineIsLeader = BooleanArray(lineCount)
        rowIsBg = BooleanArray(n)
        rowBgAbove = BooleanArray(n)
        rowLeaderOrdinal = IntArray(n) { -1 }
        rowNextLeaderOrdinal = IntArray(n) { -1 }
        rowG0 = LongArray(n)
        rowG1 = LongArray(n)
        starts = LongArray(lineCount)
        ends = LongArray(lineCount)

        lines?.forEachIndexed { i, line ->
            starts[i] = line.startMs
            ends[i] = line.endMs
            lineGroupLead[i] = line.groupLeadIndex.coerceIn(0, lineCount - 1)
            lineIsLeader[i] = line.isGroupLead
        }
        val interludes = ArrayList<Int>()
        val leaders = ArrayList<Int>()
        modelRows?.forEachIndexed { r, row ->
            when (row) {
                is Row.Line -> {
                    val l = row.lineIndex
                    rowLine[r] = l
                    lineRow[l] = r
                    val line = lines!![l]
                    rowIsBg[r] = line.isGroupedBackground
                    rowBgAbove[r] = line.bgAbove
                    if (line.isGroupLead) {
                        rowLeaderOrdinal[r] = leaders.size
                        leaders += r
                    }
                }
                is Row.Interlude -> {
                    rowG0[r] = row.startMs
                    rowG1[r] = row.endMs
                    rowNextLeaderOrdinal[r] = leaders.size // the next leader gets this ordinal
                    interludes += r
                }
            }
        }
        interludeRows = interludes.toIntArray()
        leaderRows = leaders.toIntArray()
        maxLineDurationMs = prepared?.maxLineDurationMs ?: 0L
        lastEndMs = prepared?.lastEndMs ?: 0L

        lineHot = BooleanArray(lineCount)
        groupHot = BooleanArray(lineCount)
        hotList = IntArray(lineCount)
        hotCount = 0

        rowHeight = FloatArray(n) { -1f }
        effHeight = FloatArray(n)
        prefix = FloatArray(n + 1)
        targetY = FloatArray(n)
        tops = FloatArray(n)
        delays = FloatArray(n)

        y = SpringChannel(n, 0.1f, 5f)
        scale = SpringChannel(n, 0.0002f, 0.002f)
        bgExpand = SpringChannel(n, 0.001f, 0.01f)
        activeness = TweenChannel(n)
        presence = TweenChannel(n)
        sigma = TweenChannel(n)
        depthAlpha = TweenChannel(n)
        rowSpec = arrayOfNulls(n)
        pendingAt = LongArray(n) { -1L }
        pendingSpec = arrayOfNulls(n)
        for (r in 0 until n) {
            y.snap(r, OFFSCREEN_Y)
            scale.snap(r, 1f)
            bgExpand.snap(r, 0f)
            presence.snap(r, if (rowIsBg[r]) 0f else 1f)
            depthAlpha.snap(r, 1f)
        }

        rows = List(n) { LyricRowMotion() }
        pubY = FloatArray(n) { OFFSCREEN_Y }
        pubScale = FloatArray(n) { 1f }
        pubBlur = FloatArray(n)
        pubDepthAlpha = FloatArray(n) { 1f }
        pubActiveness = FloatArray(n)
        pubExpand = FloatArray(n) { 1f }
        pubPresence = FloatArray(n) { 1f }
        pubHot = BooleanArray(n)
        pubPrefetch = BooleanArray(n)

        laidOut = false
        animateInPending = animateIn
        snapChannelsPending = !animateIn
        resizePending = false
        lastScrollTarget = -1
        slowUntilNanos = -1L
        tapPending = false
        // Scroll state resets on the next step (no snapshot writes here).
        userScroll = false
        dragging = false
        flinging = false
        scrollEndPending = false
        scrollEndedNanos = -1L
        offset = 0f
        resetScrollStatePending = true
        isAtRest = false
    }

    /**
     * Measured full height of [row] in px (for interlude and background rows: the expanded height).
     * The view may pass an estimate for a row it has not measured yet; it only does so for rows
     * that are off-screen, and replaces it with the real height a few frames later.
     */
    fun setRowHeight(row: Int, heightPx: Float) {
        if (row !in 0 until rowCount) return
        rowHeight[row] = heightPx.coerceAtLeast(0f)
    }

    /**
     * Viewport height and the anchor (top of the active line, normally `0.25 × height`, at least
     * header height + 16 dp), both in px.
     */
    fun setViewport(heightPx: Float, anchorPx: Float) {
        if (heightPx == viewportHeight && anchorPx == anchorY) return
        viewportHeight = heightPx
        anchorY = anchorPx
        if (laidOut) resizePending = true
    }

    /** Tap on a line: leave user scroll now, and move with the slow spring and no stagger. */
    fun onLineTapped(@Suppress("UNUSED_PARAMETER") lineIndex: Int) {
        tapPending = true
    }

    fun onDragStart() {
        dragging = true
        flinging = false
        scrollEndPending = false
        scrollEndedNanos = -1L
        if (!userScroll) {
            userScroll = true
            userScrollingState.value = true
            frozenAnchorRow = lastScrollTarget.coerceAtLeast(0)
            // A user scroll starts with the stagger off: fire every delayed retarget now.
            for (r in 0 until rowCount) if (pendingAt[r] >= 0L) pendingAt[r] = 0L
        }
    }

    /** Drag by [dy] px (positive = content moves down), applied directly with no spring. */
    fun onDrag(dy: Float) {
        if (!userScroll || !dragging) onDragStart()
        setOffset((offset + dy).coerceIn(minOffset, maxOffset))
    }

    /** End of a drag. Below 100 px/s there is no coasting. */
    fun onDragEnd(velocityPxPerSec: Float) {
        if (!dragging) return
        dragging = false
        if (abs(velocityPxPerSec) >= MIN_FLING_VELOCITY_PX_S) {
            flinging = true
            flingFrom = offset
            flingVelocity = velocityPxPerSec
            flingStartNanos = -1L
        } else {
            scrollEndPending = true
        }
    }

    /** Accessibility / programmatic scroll: a drag of [dy] with no fling. */
    fun scrollBy(dy: Float) {
        onDragStart()
        onDrag(dy)
        onDragEnd(0f)
    }

    // =========================================================================================
    // Frame
    // =========================================================================================

    fun step(frameNanos: Long) {
        val now = frameNanos
        if (prepared == null || rowCount == 0) {
            if (offset != 0f) setOffset(0f)
            if (userScrollingState.value) userScrollingState.value = false
            isAtRest = true
            return
        }
        if (resetScrollStatePending) {
            resetScrollStatePending = false
            setOffset(0f)
            if (userScrollingState.value) userScrollingState.value = false
        }

        val t = clock.currentMs
        val seek = clock.consumeSeek()
        val playing = clock.isPlaying
        val reduced = config.reducedMotion

        // 1. Evaluate every channel at `now` so retargets start from current values.
        for (r in 0 until rowCount) {
            y.update(r, now)
            scale.update(r, now)
            bgExpand.update(r, now)
            activeness.update(r, now)
            presence.update(r, now)
            sigma.update(r, now)
            depthAlpha.update(r, now)
        }

        // 2. Hot set, then the non-positional targets.
        updateHotSet(t)
        val snapChannels = snapChannelsPending
        snapChannelsPending = false
        for (r in 0 until rowCount) {
            val line = rowLine[r]
            if (line < 0) continue
            val hot = lineHot[line]
            val a = if (hot) 1f else 0f
            if (snapChannels) activeness.snap(r, a)
            else activeness.animateTo(r, a, if (hot) ACTIVATE_MS else DEACTIVATE_MS, EmphasisMath.EaseOut, now)

            if (rowIsBg[r]) {
                val gh = groupHot[lineGroupLead[line]]
                val target = if (gh) 1f else 0f
                if (snapChannels) presence.snap(r, target)
                else presence.animateTo(r, target, if (gh) ACTIVATE_MS else DEACTIVATE_MS, EmphasisMath.EaseOut, now)
                if (bgExpand.target[r] != target || (snapChannels && bgExpand.value[r] != target)) {
                    if (reduced || snapChannels) bgExpand.snap(r, target)
                    else bgExpand.animateTo(r, target, LyricsSprings.Background, now)
                }
            } else {
                val target = if (!playing || hot) 1f else INACTIVE_SCALE
                if (scale.target[r] != target || (snapChannels && scale.value[r] != target)) {
                    if (reduced || snapChannels) scale.snap(r, target)
                    else scale.animateTo(r, target, LyricsSprings.Scale, now)
                }
            }
        }

        // 3. Effective heights and prefix sums.
        var inputsReady = viewportHeight > 0f
        prefix[0] = 0f
        for (r in 0 until rowCount) {
            val h = rowHeight[r]
            if (h < 0f) inputsReady = false
            val full = h.coerceAtLeast(0f)
            effHeight[r] = when {
                rowLine[r] < 0 -> full * InterludeTimeline.expand(t, rowG0[r], rowG1[r])
                rowIsBg[r] -> full * bgExpand.value[r].coerceIn(0f, 1f)
                else -> full
            }
            prefix[r + 1] = prefix[r] + effHeight[r]
        }

        // 4. Scroll target and user-scroll bookkeeping.
        val target = resolveScrollTargetRow(t)
        val targetChanged = laidOut && target != lastScrollTarget
        var event: FloatSpringSpec? = null
        var eventStagger = false

        if (tapPending) {
            tapPending = false
            slowUntilNanos = now + TAP_SLOW_WINDOW_MS * MS
            if (userScroll && !dragging) {
                snapBack()
                event = LyricsSprings.Slow
            }
        }
        val forcedSlow = slowUntilNanos >= 0L && now <= slowUntilNanos

        if (userScroll) {
            if (scrollEndPending) {
                scrollEndPending = false
                scrollEndedNanos = now
            }
            stepFling(now)
            val idle = if (scrollEndedNanos >= 0L && !dragging && !flinging) now - scrollEndedNanos else -1L
            val snap = when {
                seek && !dragging -> true
                idle < 0L -> false
                idle >= SNAP_BACK_IDLE_MS * MS -> true
                targetChanged && idle >= SNAP_BACK_MIN_IDLE_MS * MS && isRowInViewport(target) -> true
                else -> false
            }
            if (snap) {
                snapBack()
                event = LyricsSprings.Slow
            }
        } else if (targetChanged) {
            val noStagger = seek || forcedSlow || reduced
            event = when {
                seek || forcedSlow -> LyricsSprings.Slow
                t >= lastEndMs -> LyricsSprings.Ended
                rowLine[target] < 0 || (lastScrollTarget >= 0 && rowLine[lastScrollTarget] < 0) -> LyricsSprings.Slow
                leaderRows.isNotEmpty() && (target == leaderRows.first() || target == leaderRows.last()) -> LyricsSprings.Slow
                else -> normalSpecFor(target)
            }
            eventStagger = !noStagger
            if (forcedSlow) slowUntilNanos = -1L
        }
        if (resizePending) {
            resizePending = false
            if (event == null) event = LyricsSprings.Slow
            eventStagger = false
        }

        // 5. Layout.
        if (inputsReady) {
            val anchorRow = (if (userScroll) frozenAnchorRow else target).coerceIn(0, rowCount - 1)
            val base = anchorY - prefix[anchorRow]
            for (r in 0 until rowCount) targetY[r] = base + prefix[r]
            maxOffset = prefix[anchorRow]
            minOffset = BOTTOM_LIMIT_FRACTION * viewportHeight - (base + prefix[rowCount])
            if (minOffset > maxOffset) minOffset = maxOffset
            if (userScroll) {
                val clamped = offset.coerceIn(minOffset, maxOffset)
                if (clamped != offset) setOffset(clamped)
            }

            if (!laidOut) {
                initialLayout(now, target, reduced)
            } else if (event != null) {
                scheduleCascade(now, target, event, eventStagger && !reduced)
            }
            syncYTargets(now, reduced)
        }
        lastScrollTarget = target

        // 6. Depth: blur (API 31+) or the alpha falloff (API 30).
        updateDepthTargets(now, target, snapChannels)

        // 7. Publish.
        publish(t)
    }

    // =========================================================================================
    // Internals
    // =========================================================================================

    private fun layoutInputsReady(): Boolean {
        if (viewportHeight <= 0f) return false
        for (r in 0 until rowCount) if (rowHeight[r] < 0f) return false
        return true
    }

    private fun updateHotSet(t: Long) {
        for (k in 0 until hotCount) {
            val l = hotList[k]
            lineHot[l] = false
            groupHot[lineGroupLead[l]] = false
        }
        hotCount = 0
        if (lineCount == 0) return
        var j = upperBound(starts, t + LEAD_IN_MS) - 1
        val minStart = t - maxLineDurationMs
        while (j >= 0 && starts[j] >= minStart) {
            if (t < ends[j]) {
                lineHot[j] = true
                hotList[hotCount++] = j
            }
            j--
        }
        for (k in 0 until hotCount) groupHot[lineGroupLead[hotList[k]]] = true
    }

    /**
     * The lowest-index hot lead line; else the interlude containing `t`; else the last line with
     * `start ≤ t` (its group lead); else line 0's group lead.
     */
    private fun resolveScrollTargetRow(t: Long): Int {
        var best = Int.MAX_VALUE
        for (k in 0 until hotCount) {
            val l = hotList[k]
            if (lineIsLeader[l] && l < best) best = l
        }
        if (best != Int.MAX_VALUE) return lineRow[best]
        for (r in interludeRows) if (t >= rowG0[r] && t < rowG1[r]) return r
        if (lineCount == 0) return 0
        val j = upperBound(starts, t) - 1
        return lineRow[lineGroupLead[if (j >= 0) j else 0]]
    }

    private fun normalSpecFor(targetRow: Int): FloatSpringSpec {
        val ord = rowLeaderOrdinal[targetRow]
        if (ord <= 0) return LyricsSprings.Slow
        val line = rowLine[targetRow]
        val prevLine = rowLine[leaderRows[ord - 1]]
        return LyricsSprings.normal(starts[line] - starts[prevLine])
    }

    private fun isRowInViewport(row: Int): Boolean {
        if (row !in 0 until rowCount) return false
        val top = y.value[row] + offset
        return top >= 0f && top < viewportHeight
    }

    private fun snapMarginPx(): Float = SNAP_MARGIN_DP * config.density

    private fun canSkip(row: Int, desired: Float): Boolean {
        val margin = snapMarginPx()
        val h = rowHeight[row].coerceAtLeast(0f)
        val cur = y.value[row] + offset
        val dst = desired + offset
        val bothAbove = cur + h < -margin && dst + h < -margin
        val bothBelow = cur > viewportHeight + margin && dst > viewportHeight + margin
        return bothAbove || bothBelow
    }

    private fun initialLayout(now: Long, target: Int, reduced: Boolean) {
        laidOut = true
        val animate = animateInPending && !reduced
        animateInPending = false
        if (!animate) {
            for (r in 0 until rowCount) {
                y.snap(r, targetY[r])
                pendingAt[r] = -1L
                rowSpec[r] = LyricsSprings.Slow
            }
            return
        }
        val margin = snapMarginPx()
        val startY = FIRST_SHOW_START_FACTOR * viewportHeight
        for (r in 0 until rowCount) {
            val h = rowHeight[r].coerceAtLeast(0f)
            val offscreen = targetY[r] + h < -margin || targetY[r] > viewportHeight + margin
            y.snap(r, if (offscreen) targetY[r] else startY)
        }
        scheduleCascade(now, target, LyricsSprings.Slow, stagger = true)
    }

    /** §3.3: sets a pending `(startAt, spec)` per row; [syncYTargets] fires them when due. */
    private fun scheduleCascade(now: Long, target: Int, spec: FloatSpringSpec, stagger: Boolean) {
        if (stagger) {
            for (r in 0 until rowCount) {
                tops[r] = y.value[r] + offset
            }
            LyricsCascade.computeDelays(tops, effHeight, rowCount, target, delays)
        }
        for (r in 0 until rowCount) {
            val d = if (stagger) delays[r] else 0f
            pendingAt[r] = now + (d * MS).toLong()
            pendingSpec[r] = spec
        }
    }

    private fun syncYTargets(now: Long, reduced: Boolean) {
        for (r in 0 until rowCount) {
            val desired = targetY[r]
            if (pendingAt[r] >= 0L) {
                if (now >= pendingAt[r]) {
                    val spec = pendingSpec[r] ?: LyricsSprings.Slow
                    pendingAt[r] = -1L
                    pendingSpec[r] = null
                    rowSpec[r] = spec
                    retargetY(r, desired, spec, now, reduced)
                }
            } else if (abs(y.target[r] - desired) > 0.01f) {
                retargetY(r, desired, rowSpec[r] ?: LyricsSprings.Slow, now, reduced)
            }
        }
    }

    private fun retargetY(r: Int, desired: Float, spec: FloatSpringSpec, now: Long, reduced: Boolean) {
        if (reduced || canSkip(r, desired)) y.snap(r, desired) else y.animateTo(r, desired, spec, now)
    }

    private fun stepFling(now: Long) {
        if (!flinging) return
        if (flingStartNanos < 0L) {
            flingStartNanos = now
            flingDurationNanos = decay.getDurationNanos(flingFrom, flingVelocity)
        }
        val dt = now - flingStartNanos
        val raw = decay.getValueFromNanos(dt, flingFrom, flingVelocity)
        val clamped = raw.coerceIn(minOffset, maxOffset)
        setOffset(clamped)
        if (clamped != raw || dt >= flingDurationNanos) {
            flinging = false
            scrollEndedNanos = now
        }
    }

    /** Folds the user offset into the springs and returns to auto-follow. */
    private fun snapBack() {
        val off = offset
        if (off != 0f) for (r in 0 until rowCount) y.shift(r, off)
        setOffset(0f)
        userScroll = false
        userScrollingState.value = false
        dragging = false
        flinging = false
        scrollEndPending = false
        scrollEndedNanos = -1L
    }

    private fun setOffset(value: Float) {
        offset = value
        if (scrollOffsetState.floatValue != value) scrollOffsetState.floatValue = value
    }

    /**
     * Distance d (in lead rows) from the hot lines: rows above the target get
     * `activeIdx − i + 1`, rows below the last hot lead get `i − lastHotIdx`. Blur is 0 for hot
     * rows, the target, background vocals, interludes, during user scroll and when disabled.
     */
    private fun updateDepthTargets(now: Long, target: Int, snap: Boolean) {
        val cfg = config
        val on = cfg.blurEnabled && !userScroll
        val duration = when {
            snap -> 0L
            userScroll -> BLUR_DRAG_MS
            else -> BLUR_TWEEN_MS
        }
        val easing: Easing = if (userScroll) LinearEasing else FastOutSlowInEasing

        val activeOrd: Int
        var lastHotOrd: Int
        if (target in 0 until rowCount && rowLine[target] < 0) {
            activeOrd = rowNextLeaderOrdinal[target]
            lastHotOrd = activeOrd - 1
        } else {
            activeOrd = if (target in 0 until rowCount) rowLeaderOrdinal[target].coerceAtLeast(0) else 0
            lastHotOrd = activeOrd
            for (k in 0 until hotCount) {
                val l = hotList[k]
                if (lineIsLeader[l]) lastHotOrd = max(lastHotOrd, rowLeaderOrdinal[lineRow[l]])
            }
        }

        for (r in 0 until rowCount) {
            val ord = rowLeaderOrdinal[r]
            var d = 0
            if (on && ord >= 0 && r != target && !lineHot[rowLine[r]]) {
                d = when {
                    ord < activeOrd -> activeOrd - ord + 1
                    ord > lastHotOrd -> ord - lastHotOrd
                    else -> 1
                }
            }
            val sigmaTarget = if (cfg.blurSupported) LyricsBlurMath.depthSigmaDp(d, cfg.blurStrength) else 0f
            val alphaTarget = if (cfg.blurSupported) 1f else LyricsBlurMath.fallbackAlphaFactor(d)
            sigma.animateTo(r, sigmaTarget, duration, easing, now)
            depthAlpha.animateTo(r, alphaTarget, duration, easing, now)
        }
    }

    private fun publish(t: Long) {
        val density = config.density
        var moving = flinging || dragging || userScroll || tapPending || resizePending
        for (r in 0 until rowCount) {
            val m = rows[r]
            val line = rowLine[r]
            val isBg = rowIsBg[r]
            val bgP = bgExpand.value[r].coerceIn(0f, 1f)

            // y (+ background slide)
            var yv = y.value[r]
            if (isBg && yv < OFFSCREEN_Y / 2f) {
                val h = rowHeight[r].coerceAtLeast(0f)
                val dir = if (rowBgAbove[r]) 1f else -1f
                yv += (1f - bgP) * dir * BACKGROUND_SLIDE_FRACTION * h
            }
            val ySettled = !y.active[r] && !bgExpand.active[r]
            writeFloat(m.yState, pubY, r, yv, Y_EPS, ySettled)

            // scale
            val sv = when {
                line < 0 -> 1f
                isBg -> if (bgP < 0.001f && !bgExpand.active[r] && !groupHot[lineGroupLead[line]]) {
                    BACKGROUND_COLLAPSED_SCALE
                } else {
                    BACKGROUND_EXPAND_FROM_SCALE + (1f - BACKGROUND_EXPAND_FROM_SCALE) * bgP
                }
                else -> scale.value[r]
            }
            writeFloat(m.scaleState, pubScale, r, sv, SCALE_EPS, !scale.active[r] && !bgExpand.active[r])

            // blur, quantised
            val s = sigma.value[r]
            val radius = if (s > 0f) LyricsBlurMath.quantizeRadiusPx(LyricsBlurMath.sigmaDpToRadiusPx(s, density)) else 0f
            if (radius != pubBlur[r]) {
                pubBlur[r] = radius
                m.blurState.floatValue = radius
            }
            writeFloat(m.depthAlphaState, pubDepthAlpha, r, depthAlpha.value[r], ACTIVENESS_EPS, !depthAlpha.active[r])

            // activeness, hot
            if (line >= 0) {
                writeFloat(m.activenessState, pubActiveness, r, activeness.value[r], ACTIVENESS_EPS, !activeness.active[r])
            }
            val hot = if (line >= 0) lineHot[line] else InterludeTimeline.isActive(t, rowG0[r], rowG1[r])
            if (hot != pubHot[r]) {
                pubHot[r] = hot
                m.hotState.value = hot
            }
            if (line >= 0 && !pubPrefetch[r] && t + LEAD_IN_MS + PREFETCH_LEAD_MS >= starts[line] && t < ends[line]) {
                pubPrefetch[r] = true
                m.prefetchState.value = true
            }

            // expand, presence
            val ex = when {
                line < 0 -> InterludeTimeline.expand(t, rowG0[r], rowG1[r])
                isBg -> bgP
                else -> 1f
            }
            writeFloat(m.expandState, pubExpand, r, ex, EXPAND_EPS, line < 0 || !bgExpand.active[r])
            val pr = if (isBg) presence.value[r] else 1f
            writeFloat(m.presenceState, pubPresence, r, pr, ACTIVENESS_EPS, !presence.active[r])

            if (y.active[r] || scale.active[r] || bgExpand.active[r] || activeness.active[r] ||
                presence.active[r] || sigma.active[r] || depthAlpha.active[r] || pendingAt[r] >= 0L
            ) moving = true
        }
        if (!laidOut && layoutInputsReady()) moving = true
        isAtRest = !moving
    }

    private fun writeFloat(state: MutableFloatState, shadow: FloatArray, i: Int, v: Float, eps: Float, settled: Boolean) {
        val old = shadow[i]
        if (abs(v - old) >= eps || (settled && v != old)) {
            shadow[i] = v
            state.floatValue = v
        }
    }

    // ---- test / debug accessors (no snapshot reads) -----------------------------------------

    internal fun rowTargetY(row: Int): Float = targetY[row]
    internal fun rowSpringY(row: Int): Float = y.value[row]
    internal fun rowPendingAtNanos(row: Int): Long = pendingAt[row]
    internal fun isLineHot(line: Int): Boolean = lineHot[line]
    internal fun rowSigmaTargetDp(row: Int): Float = sigma.to[row]
    internal val isLaidOut: Boolean get() = laidOut
}
