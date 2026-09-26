package com.theveloper.pixelplay.ui.glass.utils

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Ported from the Backdrop library's own catalog app (utils/UISensor.kt, Apache-2.0) via NexHome —
 * tracks the phone's tilt via the accelerometer so glass highlights can point "up" relative to
 * gravity, exactly like real light hitting real glass, instead of a fixed baked-in angle.
 */
@Stable
interface UISensor {
    val gravityAngle: Float
    val gravity: Offset
    fun start()
    fun stop()
}

/**
 * The app-wide sensor. Defaults to a static 45° sensor so glass never crashes when
 * [ProvideUISensor] is missing (previews, Material 3 mode).
 */
val LocalUISensor = staticCompositionLocalOf<UISensor> { StaticUISensor }

/**
 * Registers ONE accelerometer listener for the whole app and provides it as [LocalUISensor].
 *
 * PixlAudio change: the listener runs only while the lifecycle is STARTED (NexHome's kept sampling
 * at game rate in the background, which a music app spends most of its life in), and only when
 * [enabled]. Pass `false` below API 33 (gravity only feeds the AGSL rim shader, which doesn't exist
 * there), under battery saver (owner decision G5: highlights keep a static angle) and outside glass
 * mode; the static 45° sensor is provided instead.
 */
@Composable
fun ProvideUISensor(enabled: Boolean, content: @Composable () -> Unit) {
    val sensor = if (enabled) rememberUISensor() else StaticUISensor
    CompositionLocalProvider(LocalUISensor provides sensor, content = content)
}

/** Creates a sensor that samples while the lifecycle is STARTED. Prefer [LocalUISensor] in kit components. */
@Composable
fun rememberUISensor(): UISensor {
    val context = LocalContext.current.applicationContext
    val uiSensor = remember(context) { UISensorImpl(context) }

    LifecycleStartEffect(uiSensor) {
        uiSensor.start()
        onStopOrDispose { uiSensor.stop() }
    }

    return uiSensor
}

private object StaticUISensor : UISensor {
    override val gravityAngle: Float = 45f
    override val gravity: Offset = Offset.Zero
    override fun start() = Unit
    override fun stop() = Unit
}

private class UISensorImpl(context: Context) : UISensor {

    override var gravityAngle: Float by mutableFloatStateOf(45f)
        private set

    override var gravity: Offset by mutableStateOf(Offset.Zero)
        private set

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    // Filtered values live in plain fields; only changes past a dead band are published to
    // snapshot state. Every heavy glass highlight reads gravityAngle, so publishing raw sensor
    // noise would re-render every glass surface on screen continuously, even while idle.
    private var filteredX = 0f
    private var filteredY = 0f
    private var registered = false

    private val listener = object : SensorEventListener {

        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null) return
            if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
                // Low-pass the gravity vector itself (averaging angles breaks at the ±180° wrap).
                val alpha = 0.2f
                filteredX += (event.values[0] - filteredX) * alpha
                filteredY += (event.values[1] - filteredY) * alpha
                val x = filteredX
                val y = filteredY

                // Lying flat, the in-plane component is pure noise and its angle swings wildly;
                // hold the last angle until the phone is actually tilted.
                if (sqrt(x * x + y * y) >= MIN_TILT_FOR_ANGLE) {
                    val angle = atan2(y, x) * (180f / PI).toFloat()
                    val delta = ((angle - gravityAngle + 540f) % 360f) - 180f
                    if (abs(delta) >= ANGLE_DEAD_BAND_DEGREES) gravityAngle = angle
                }

                val norm = sqrt(x * x + y * y + 9.81f * 9.81f)
                val gx = x / norm
                val gy = y / norm
                val current = gravity
                val dx = gx - current.x
                val dy = gy - current.y
                if (dx * dx + dy * dy >= GRAVITY_DEAD_BAND * GRAVITY_DEAD_BAND) gravity = Offset(gx, gy)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    override fun start() {
        if (registered) return
        val manager = sensorManager ?: return
        val sensor = accelerometer ?: return
        registered = manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        if (!registered) return
        registered = false
        sensorManager?.unregisterListener(listener)
    }
}

private const val ANGLE_DEAD_BAND_DEGREES = 3f
private const val GRAVITY_DEAD_BAND = 0.03f
private const val MIN_TILT_FOR_ANGLE = 1.5f // m/s² of in-plane gravity
