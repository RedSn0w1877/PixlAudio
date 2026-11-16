package com.theveloper.pixelplay.ui.theme

import android.content.Context
import android.graphics.Paint
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorProducer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.theveloper.pixelplay.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt
import androidx.compose.ui.text.font.Typeface as ComposeTypeface

/*
 * Expressive Google Sans Flex toolkit.
 *
 * Google Sans Flex is a variable font: one file, six continuous axes (see [FlexAxis]). The app
 * used it mostly as five static weights; this file makes the other axes cheap and safe to use:
 *
 *  - opsz  tied to the drawn size ([FlexSpec.atSize], [flexStyle]) — big text gets the tight,
 *          high-contrast display cut, small text the open, sturdy text cut.
 *  - wdth  "fit by width" ([FlexFitText]) — hero titles keep their size and change width
 *          instead: long names condense to fit, short ones widen to fill the line.
 *  - wght+wdth  emphasis motion ([FlexEmphasisText], [FlexRampText]) — selection animates
 *          weight and width together through a few pre-built instances.
 *  - GRAD  legibility over artwork ([FlexMath.gradeForBackdrop]) — heavier strokes, same advances.
 *  - ROND  the house roundness (100) for chips and labels ([FlexPresets.Chip]).
 *  - scroll-linked collapsing titles ([FlexCollapsingTitle]) — wide heavy display at the top,
 *          condensed in the bar, size continuous in graphicsLayer, axes in quantised steps.
 *
 * Performance contract:
 *  - A Typeface is built once per quantised [FlexSpec] per process ([FlexFonts]) and wrapped as a
 *    loaded FontFamily, so Compose never resolves or loads a font while animating. (Compose keeps
 *    only a small LRU of resolved typefaces; a continuously animated `FontVariation` value creates
 *    a new Font every frame and reloads it on the main thread — this file exists to avoid that.)
 *  - Animated values are read only in layout / draw lambdas; the step index is a derivedStateOf,
 *    so a 300 ms selection animation re-measures at most `steps` times and never recomposes.
 *  - Ramps warm their instances on Dispatchers.Default as soon as they are remembered.
 */

/**
 * Process-wide cache of Google Sans Flex instances, one per [FlexSpec.cacheKey].
 *
 * Built the way Compose itself applies variation settings to a resource font (the resource
 * typeface with the settings set through a Paint), so glyphs match `Font(resId, variationSettings)`
 * exactly. The base resource is loaded once. Thread-safe; [warmUp] builds off the main thread.
 */
object FlexFonts {
    private val families = ConcurrentHashMap<Long, FontFamily>()

    @Volatile
    private var baseTypeface: android.graphics.Typeface? = null

    /** Soft ceiling: quantisation keeps real use far below it; past it the cache starts over. */
    private const val MAX_ENTRIES = 512

    /** The loaded family for [spec]; builds it (≈ sub-millisecond once the base is loaded) on a miss. */
    fun family(context: Context, spec: FlexSpec): FontFamily {
        val key = spec.cacheKey
        families[key]?.let { return it }
        if (families.size >= MAX_ENTRIES) families.clear()
        val built = build(context, spec)
        return families.putIfAbsent(key, built) ?: built
    }

    fun isLoaded(spec: FlexSpec): Boolean = families.containsKey(spec.cacheKey)

    /** Builds every spec in [specs] on Dispatchers.Default. Safe to call repeatedly. */
    suspend fun warmUp(context: Context, specs: Collection<FlexSpec>) {
        if (specs.all { isLoaded(it) }) return
        val app = context.applicationContext
        withContext(Dispatchers.Default) {
            for (spec in specs) {
                ensureActive()
                if (!isLoaded(spec)) family(app, spec)
            }
        }
    }

    private fun base(context: Context): android.graphics.Typeface {
        baseTypeface?.let { return it }
        return synchronized(this) {
            baseTypeface ?: context.applicationContext.resources.getFont(R.font.gflex_variable)
                .also { baseTypeface = it }
        }
    }

    private fun build(context: Context, spec: FlexSpec): FontFamily {
        val base = base(context)
        val paint = Paint()
        paint.typeface = base
        val applied = paint.setFontVariationSettings(spec.toVariationSettings())
        if (!applied) Timber.w("FlexFonts: variation settings not applied for %s", spec)
        return FontFamily(ComposeTypeface(paint.typeface ?: base))
    }
}

/**
 * A TextStyle drawn with the cached [family]. fontWeight mirrors the spec's wght for semantics
 * and fallback scripts; synthesis is off because the weight is real.
 */
fun flexTextStyle(base: TextStyle, family: FontFamily, spec: FlexSpec): TextStyle = base.merge(
    TextStyle(
        fontFamily = family,
        fontWeight = FontWeight(spec.weight.roundToInt().coerceIn(1, 1000)),
        fontSynthesis = FontSynthesis.None,
        platformStyle = PlatformTextStyle(includeFontPadding = false),
    )
)

private fun TextStyle.sizeSpOr(fallback: Float): Float =
    if (fontSize.isSpecified && fontSize.isSp) fontSize.value else fallback

/**
 * [base] re-cut in Google Sans Flex at [spec], with opsz tied to [base]'s font size when
 * [opticalSizeFromFontSize] (the default). This is the "opsz = size" improvement for display
 * and headline styles, opt-in per use: `MaterialTheme.typography.displayLarge.let { flexStyle(it, FlexPresets.HeroDisplay) }`.
 *
 * It is deliberately not applied to the global [Typography]: those styles would trade Compose's
 * resource-font path for this one across every screen at once, and the visual change (tighter
 * display cut on every headline) deserves to land screen by screen.
 */
@Composable
fun flexStyle(base: TextStyle, spec: FlexSpec, opticalSizeFromFontSize: Boolean = true): TextStyle {
    val context = LocalContext.current.applicationContext
    return remember(base, spec, opticalSizeFromFontSize) {
        val resolved = if (opticalSizeFromFontSize) spec.atSize(base.sizeSpOr(FlexAxis.OPSZ_DEFAULT)) else spec
        flexTextStyle(base, FlexFonts.family(context, resolved), resolved)
    }
}

/**
 * N pre-built instances between two points of the design space, optionally at two font sizes.
 * Pick one per frame with [indexFor]; nothing is allocated after the first use of each step.
 *
 * Seven steps (the default) between e.g. wght 500 → 720 and wdth 100 → 112 is ~37 wght and
 * ~2 wdth per step: at nav-label sizes over a ~250 ms spring the stepping is not visible, and
 * the whole ramp costs seven small Typeface wrappers over one shared font buffer.
 */
@Stable
class FlexRamp internal constructor(
    private val context: Context,
    private val baseStyle: TextStyle,
    val from: FlexSpec,
    val to: FlexSpec,
    val steps: Int,
    /** Font size (sp) at fraction 0 and 1; equal when the ramp only moves axes. */
    val fromSizeSp: Float,
    val toSizeSp: Float,
    private val opticalSizeFromFontSize: Boolean,
) {
    private val styles = arrayOfNulls<TextStyle>(steps)

    fun indexFor(fraction: Float): Int = FlexMath.stepIndex(fraction, steps)

    fun sizeSpAt(index: Int): Float = FlexMath.lerp(fromSizeSp, toSizeSp, FlexMath.stepFraction(index, steps))

    fun specAt(index: Int): FlexSpec {
        val spec = from.lerp(to, FlexMath.stepFraction(index, steps))
        return if (opticalSizeFromFontSize) spec.atSize(sizeSpAt(index)) else spec
    }

    /** The TextStyle of step [index]; built on first use, then reused. */
    fun styleAt(index: Int): TextStyle {
        val i = index.coerceIn(0, steps - 1)
        styles[i]?.let { return it }
        val spec = specAt(i)
        val sized = if (fromSizeSp != toSizeSp) baseStyle.copy(fontSize = sizeSpAt(i).sp) else baseStyle
        return flexTextStyle(sized, FlexFonts.family(context, spec), spec).also { styles[i] = it }
    }

    internal fun allSpecs(): List<FlexSpec> = List(steps) { specAt(it) }
}

/**
 * Remembers a [FlexRamp] from [from] to [to] over [steps] instances and warms all of them on
 * Dispatchers.Default. [toFontSize] makes the ramp also scale the font (collapsing titles).
 */
@Composable
fun rememberFlexRamp(
    baseStyle: TextStyle,
    from: FlexSpec,
    to: FlexSpec,
    steps: Int = 7,
    toFontSize: TextUnit = TextUnit.Unspecified,
    opticalSizeFromFontSize: Boolean = true,
): FlexRamp {
    val context = LocalContext.current.applicationContext
    val ramp = remember(baseStyle, from, to, steps, toFontSize, opticalSizeFromFontSize) {
        val fromSize = baseStyle.sizeSpOr(FlexAxis.OPSZ_DEFAULT)
        val toSize = if (toFontSize.isSpecified && toFontSize.isSp) toFontSize.value else fromSize
        FlexRamp(
            context = context,
            baseStyle = baseStyle,
            from = from,
            to = to,
            steps = steps.coerceAtLeast(2),
            fromSizeSp = fromSize,
            toSizeSp = toSize,
            opticalSizeFromFontSize = opticalSizeFromFontSize,
        )
    }
    LaunchedEffect(ramp) { FlexFonts.warmUp(context, ramp.allSpecs()) }
    return ramp
}

/**
 * Text drawn from a [FlexRamp] at the step closest to [fraction]. [fraction] is read inside a
 * derivedStateOf consumed by layout only, so animating it re-measures once per step and never
 * recomposes; [color] is read in draw only.
 *
 * With [continuousSize] the font size of a size ramp moves smoothly every frame through a
 * graphicsLayer scale on top of the stepped layout (the font itself still changes per step);
 * [transformOrigin] anchors that scale (start-centre by default, like a collapsing top bar title).
 */
@Composable
fun FlexRampText(
    text: String,
    ramp: FlexRamp,
    fraction: () -> Float,
    color: ColorProducer,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign = TextAlign.Unspecified,
    continuousSize: Boolean = false,
    transformOrigin: TransformOrigin = TransformOrigin(0f, 0.5f),
) {
    val measurer = rememberTextMeasurer(cacheSize = ramp.steps + 1)
    val currentFraction = rememberUpdatedState(fraction)
    val index = remember(ramp) { derivedStateOf { ramp.indexFor(currentFraction.value()) } }
    val policy = remember(text, ramp, maxLines, overflow, textAlign, measurer) {
        RampTextMeasurePolicy(measurer, text, maxLines, overflow, ramp, textAlign, index)
    }
    val layerModifier = if (continuousSize && ramp.fromSizeSp != ramp.toSizeSp) {
        Modifier.graphicsLayer {
            val s = FlexMath.continuousScale(currentFraction.value(), ramp.fromSizeSp, ramp.toSizeSp, ramp.steps)
            scaleX = s
            scaleY = s
            this.transformOrigin = transformOrigin
        }
    } else Modifier
    Layout(
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .then(layerModifier)
            .drawBehind { policy.result.value?.let { drawText(it, color = color()) } },
        measurePolicy = policy,
    )
}

/**
 * A label whose weight and width animate together between [rest] and [emphasized] — nav labels
 * going heavier and wider when selected. The fraction is an expressive fast-effects spring (no
 * overshoot: weight should not bounce past its target), read in layout only.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FlexEmphasisText(
    text: String,
    emphasized: Boolean,
    style: TextStyle,
    color: ColorProducer,
    modifier: Modifier = Modifier,
    rest: FlexSpec = FlexPresets.NavLabel,
    emphasis: FlexSpec = FlexPresets.NavLabelSelected,
    steps: Int = 7,
) {
    val ramp = rememberFlexRamp(baseStyle = style, from = rest, to = emphasis, steps = steps)
    val spec = remember { MotionScheme.expressive().fastEffectsSpec<Float>() }
    val fraction: State<Float> = animateFloatAsState(
        targetValue = if (emphasized) 1f else 0f,
        animationSpec = spec,
        label = "FlexEmphasis",
    )
    FlexRampText(
        text = text,
        ramp = ramp,
        fraction = { fraction.value },
        color = color,
        modifier = modifier,
    )
}

/**
 * Scroll-linked title for artist / playlist heroes: at [collapseFraction] 0 a wide, heavy
 * display at [expandedStyle]'s size; at 1 a condensed, lighter title at [collapsedFontSize].
 * Axes move in [steps] quantised instances, the size moves continuously in graphicsLayer.
 * Pass the scroll read as a lambda (e.g. `{ collapse.value }`) so scrolling never recomposes.
 */
@Composable
fun FlexCollapsingTitle(
    text: String,
    collapseFraction: () -> Float,
    expandedStyle: TextStyle,
    collapsedFontSize: TextUnit,
    color: ColorProducer,
    modifier: Modifier = Modifier,
    expanded: FlexSpec = FlexPresets.HeroDisplay,
    collapsed: FlexSpec = FlexPresets.HeroCollapsed,
    maxLines: Int = 2,
    steps: Int = 8,
) {
    val ramp = rememberFlexRamp(
        baseStyle = expandedStyle,
        from = expanded,
        to = collapsed,
        steps = steps,
        toFontSize = collapsedFontSize,
    )
    FlexRampText(
        text = text,
        ramp = ramp,
        fraction = collapseFraction,
        color = color,
        modifier = modifier,
        maxLines = maxLines,
        continuousSize = true,
    )
}

/**
 * Hero title that fits by width instead of by size (artist name, playlist title, song title).
 *
 * The font size of [style] never changes. In layout, against the real max width:
 *  1. Short titles: the widest `wdth` up to [FlexFitPolicy.maxWidth] that still fits ONE line, so
 *     a three-letter name fills the line with presence instead of floating in space.
 *  2. Longer titles: condensed down to [FlexFitPolicy.minWidth] to stay on one line.
 *  3. Still too long: wrapped onto up to [FlexFitPolicy.maxLines] lines at the widest cut
 *     (≤ [FlexFitPolicy.multiLineMaxWidth]) that fits them; ellipsised at the narrowest otherwise.
 *
 * The search measures a handful of times per (text, width) and is memoised; re-layouts with the
 * same constraints cost one map lookup. GRAD for text over artwork goes in [spec].
 */
@Composable
fun FlexFitText(
    text: String,
    style: TextStyle,
    color: ColorProducer,
    modifier: Modifier = Modifier,
    spec: FlexSpec = FlexPresets.HeroDisplay,
    policy: FlexFitPolicy = FlexFitPolicy(),
    textAlign: TextAlign = TextAlign.Unspecified,
) {
    val context = LocalContext.current.applicationContext
    val measurer = rememberTextMeasurer(cacheSize = 12)
    val sized = remember(spec, style) { spec.atSize(style.sizeSpOr(FlexAxis.OPSZ_DEFAULT)) }
    val baseStyle = remember(style, textAlign) {
        if (textAlign == TextAlign.Unspecified) style else style.copy(textAlign = textAlign)
    }
    LaunchedEffect(sized, policy) {
        FlexFonts.warmUp(
            context,
            listOf(policy.minWidth, policy.maxWidth, policy.multiLineMaxWidth).map { sized.copy(width = it) },
        )
    }
    val measurePolicy = remember(text, baseStyle, sized, policy, measurer) {
        FitTextMeasurePolicy(measurer, text, policy) { width ->
            val s = sized.copy(width = width)
            flexTextStyle(baseStyle, FlexFonts.family(context, s), s)
        }
    }
    Layout(
        modifier = modifier
            .semantics { this.text = AnnotatedString(text) }
            .drawBehind { measurePolicy.result.value?.let { drawText(it, color = color()) } },
        measurePolicy = measurePolicy,
    )
}

/** Convenience for a fixed colour. */
fun flexColor(color: Color): ColorProducer = ColorProducer { color }

// ---------------------------------------------------------------------------------------------
// Measure policies
// ---------------------------------------------------------------------------------------------

private abstract class FlexTextMeasurePolicy : MeasurePolicy {
    /** Last layout, read in draw only. Written in measure; draw is invalidated when it changes. */
    val result = mutableStateOf<TextLayoutResult?>(null, referentialEqualityPolicy())

    abstract fun layoutFor(constraints: Constraints): TextLayoutResult

    override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
        val layout = layoutFor(constraints)
        result.value = layout
        val w = constraints.constrainWidth(layout.size.width)
        val h = constraints.constrainHeight(layout.size.height)
        val first = layout.firstBaseline.roundToInt()
        val last = layout.lastBaseline.roundToInt()
        return layout(w, h, mapOf(FirstBaseline to first, LastBaseline to last)) {}
    }

    override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        layoutFor(Constraints()).size.width

    override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int): Int =
        layoutFor(Constraints()).size.width

    override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        layoutFor(Constraints(maxWidth = width)).size.height

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int): Int =
        layoutFor(Constraints(maxWidth = width)).size.height
}

private class RampTextMeasurePolicy(
    private val measurer: TextMeasurer,
    private val text: String,
    private val maxLines: Int,
    private val overflow: TextOverflow,
    private val ramp: FlexRamp,
    private val textAlign: TextAlign,
    private val index: State<Int>,
) : FlexTextMeasurePolicy() {
    private val aligned = arrayOfNulls<TextStyle>(ramp.steps)

    private fun styleAt(i: Int): TextStyle {
        aligned[i]?.let { return it }
        val base = ramp.styleAt(i)
        return (if (textAlign == TextAlign.Unspecified) base else base.copy(textAlign = textAlign))
            .also { aligned[i] = it }
    }

    override fun layoutFor(constraints: Constraints): TextLayoutResult = measurer.measure(
        text = text,
        style = styleAt(index.value.coerceIn(0, ramp.steps - 1)),
        overflow = overflow,
        softWrap = maxLines > 1,
        maxLines = maxLines,
        constraints = Constraints(maxWidth = constraints.maxWidth),
    )
}

private class FitTextMeasurePolicy(
    private val measurer: TextMeasurer,
    private val text: String,
    private val policy: FlexFitPolicy,
    private val styleForWidth: (Float) -> TextStyle,
) : FlexTextMeasurePolicy() {
    // Memo of the last fit: the axis chosen for a given max width.
    private var memoMaxWidth = -1
    private var memoAxis = Float.NaN
    private var memoSingleLine = true
    private val candidates = policy.multiLineCandidates()

    private fun singleLine(axis: Float): TextLayoutResult = measurer.measure(
        text = text,
        style = styleForWidth(axis),
        overflow = TextOverflow.Ellipsis,
        softWrap = false,
        maxLines = 1,
    )

    private fun wrapped(axis: Float, maxWidth: Int): TextLayoutResult = measurer.measure(
        text = text,
        style = styleForWidth(axis),
        overflow = TextOverflow.Ellipsis,
        softWrap = true,
        maxLines = policy.maxLines,
        constraints = Constraints(maxWidth = maxWidth),
    )

    override fun layoutFor(constraints: Constraints): TextLayoutResult {
        val maxWidth = constraints.maxWidth
        if (maxWidth == Constraints.Infinity) return singleLine(policy.maxWidth)
        if (maxWidth != memoMaxWidth || memoAxis.isNaN()) {
            solve(maxWidth)
            memoMaxWidth = maxWidth
        }
        return if (memoSingleLine) {
            measurer.measure(
                text = text,
                style = styleForWidth(memoAxis),
                overflow = TextOverflow.Ellipsis,
                softWrap = false,
                maxLines = 1,
                constraints = Constraints(maxWidth = maxWidth),
            )
        } else {
            wrapped(memoAxis, maxWidth)
        }
    }

    private fun solve(maxWidth: Int) {
        val available = maxWidth.toFloat()
        val wLo = singleLine(policy.minWidth).size.width.toFloat()
        val wHi = singleLine(policy.maxWidth).size.width.toFloat()
        val solved = FlexMath.solveSingleLineWidth(wLo, wHi, policy.minWidth, policy.maxWidth, available)
        if (solved != null) {
            var axis: Float = solved
            // The linear estimate is close but not exact; verify and step down a little if needed.
            var tries = 0
            while (axis > policy.minWidth && singleLine(axis).size.width > maxWidth && tries < 4) {
                axis = (axis - 2f).coerceAtLeast(policy.minWidth)
                tries++
            }
            memoAxis = axis
            memoSingleLine = true
            return
        }
        val found = FlexMath.firstFitting(candidates) { candidate ->
            !wrapped(candidate, maxWidth).hasVisualOverflow
        }
        memoAxis = if (found >= 0) candidates[found] else policy.minWidth
        memoSingleLine = false
    }
}
