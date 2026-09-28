package com.theveloper.pixelplay.ui.glass

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import kotlin.math.abs

/**
 * The light that glass rims catch.
 *
 * A single shared gravity listener for the whole app (not one per glass element — the previous
 * approach in the reference kit registered a listener per surface), feeding one quantized angle
 * that every specular highlight reads **in the draw phase**. Tilting the phone therefore costs a
 * few highlight re-draws per second and zero recomposition.
 *
 * The angle is quantized to [STEP_DEGREES] buckets and published only when the bucket changes, so
 * sensor noise on a phone lying flat on a table produces no invalidations at all, and every
 * [Highlight] object is preallocated per bucket: the draw lambdas never allocate.
 */
@Stable
class GlassLight internal constructor() {

    private var bucket by mutableIntStateOf(REST_BUCKET)

    /** Current light angle in degrees. Snapshot-state read: call from draw lambdas only. */
    val angleDegrees: Float get() = bucket * STEP_DEGREES

    // One preallocated Highlight per angle bucket, rebuilt only when the palette's highlight
    // colour changes (a theme flip), never per frame.
    private var tableColor: Color = Color.Unspecified
    private var table: Array<Highlight> = emptyArray()

    /**
     * The rim highlight for the current light angle. Read from `drawBackdrop(highlight = …)`;
     * the read subscribes that element's draw to light changes.
     */
    fun highlight(palette: GlassPalette): Highlight {
        val color = palette.highlight
        if (color != tableColor || table.isEmpty()) {
            tableColor = color
            table = Array(BUCKETS) { index ->
                Highlight(style = HighlightStyle.Default(color = color, angle = index * STEP_DEGREES))
            }
        }
        return table[bucket.coerceIn(0, BUCKETS - 1)]
    }

    /** Same as [highlight], with a width/alpha override for surfaces that animate their rim. */
    fun highlight(palette: GlassPalette, alpha: Float, width: Dp = 0.5.dp): Highlight {
        val base = highlight(palette)
        if (alpha >= 0.999f && width == base.width) return base
        return base.copy(width = width, blurRadius = width / 2f, alpha = alpha.coerceIn(0f, 1f))
    }

    internal fun onGravity(x: Float, y: Float) {
        // Roll (left/right tilt) swings the light across the top edge; pitch is ignored so
        // holding the phone at a normal reading angle doesn't pin the light to one side.
        val roll = (x / SensorManager.GRAVITY_EARTH).coerceIn(-1f, 1f)
        val degrees = REST_DEGREES + roll * SWING_DEGREES
        val next = (degrees / STEP_DEGREES).toInt().coerceIn(0, BUCKETS - 1)
        if (abs(next - bucket) >= 1) bucket = next
    }

    internal fun reset() {
        bucket = REST_BUCKET
    }

    companion object {
        private const val STEP_DEGREES = 3f
        private const val BUCKETS = 61 // 0..180°
        private const val REST_DEGREES = 45f
        private const val SWING_DEGREES = 40f
        private const val REST_BUCKET = (REST_DEGREES / STEP_DEGREES).toInt()
    }
}

val LocalGlassLight = staticCompositionLocalOf { GlassLight() }

/**
 * Creates the app's [GlassLight] and keeps its sensor registered only while it is useful:
 * activity started, glass on the [GlassTier.Refractive] tier (the flat rims of lower tiers
 * don't have an angle), and system animations not disabled.
 */
@Composable
internal fun rememberGlassLight(tier: GlassTier, enabled: Boolean): GlassLight {
    val light = remember { GlassLight() }
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val active = enabled && tier == GlassTier.Refractive && animationsEnabled(context)

    DisposableEffect(light, lifecycleOwner, active) {
        if (!active) {
            light.reset()
            return@DisposableEffect onDispose { }
        }
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensorManager == null || sensor == null) {
            return@DisposableEffect onDispose { }
        }

        val listener = object : SensorEventListener {
            // Light low-pass on top of the gravity sensor; accelerometer fallbacks are noisy.
            private var fx = 0f
            private var fy = SensorManager.GRAVITY_EARTH
            override fun onSensorChanged(event: SensorEvent) {
                fx += (event.values[0] - fx) * 0.25f
                fy += (event.values[1] - fy) * 0.25f
                light.onGravity(fx, fy)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        var registered = false
        fun register() {
            if (!registered) {
                // ~15 Hz with batching: the angle is quantized to 3° anyway.
                registered = sensorManager.registerListener(listener, sensor, 66_000, 132_000)
            }
        }
        fun unregister() {
            if (registered) {
                sensorManager.unregisterListener(listener)
                registered = false
            }
        }

        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> register()
                Lifecycle.Event.ON_STOP -> unregister()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) register()

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            unregister()
            light.reset()
        }
    }
    return light
}

private fun animationsEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)
