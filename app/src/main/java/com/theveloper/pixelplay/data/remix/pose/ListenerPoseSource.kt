package com.theveloper.pixelplay.data.remix.pose

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import timber.log.Timber

/** Where the listener's head is pointing. Yaw/pitch/roll in radians, relative to a zeroed front. */
fun interface PoseSink {
    fun onPose(yaw: Float, pitch: Float, roll: Float)
}

/**
 * A swappable source of listener orientation.
 *
 * Deliberately an interface with three implementations rather than one sensor call, because the
 * obvious source is not available: `Sensor.TYPE_HEAD_TRACKER` is documented as "typically not
 * available for apps to use", and on shipping Pixel + Buds the head pose is consumed inside the
 * platform's own spatializer. So the app probes for it, expects nothing, and ships a phone-motion
 * default that works on every device.
 */
interface ListenerPoseSource {
    val id: String
    fun isAvailable(): Boolean
    fun start(sink: PoseSink)
    fun stop()

    companion object {
        const val ID_HEADSET = "headset"
        const val ID_DEVICE = "device"
        const val ID_MANUAL = "manual"
    }
}

/**
 * Best-effort head tracking from a connected headset.
 *
 * Expected to find nothing on essentially every device today — `getDynamicSensorList` comes back
 * empty because the tracker is wired to the audio framework, not to apps. It costs ~60 lines and
 * lights up for free if that ever changes, which is the only honest way to offer the feature.
 * Needs no new permission: `BLUETOOTH_CONNECT` is already in the manifest, and reading a dynamic
 * sensor below 200 Hz does not require `HIGH_SAMPLING_RATE_SENSORS`.
 */
class HeadTrackerPoseSource(context: Context) : ListenerPoseSource, SensorEventListener {
    override val id: String = ListenerPoseSource.ID_HEADSET

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var sink: PoseSink? = null

    private fun tracker(): Sensor? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val manager = sensorManager ?: return null
        return runCatching {
            manager.getDynamicSensorList(Sensor.TYPE_HEAD_TRACKER).firstOrNull()
        }.getOrNull()
    }

    override fun isAvailable(): Boolean = tracker() != null

    override fun start(sink: PoseSink) {
        val sensor = tracker()
        if (sensor == null) {
            Timber.i("Remix pose: no head tracker exposed to apps on this device")
            return
        }
        this.sink = sink
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        sensorManager?.unregisterListener(this)
        sink = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Per the AOSP head-tracker HID spec: values[0..2] are an axis-angle rotation vector
        // (radians) from the reference frame to the head, values[3..5] angular velocity.
        if (event.values.size < 3) return
        val rx = event.values[0]
        val ry = event.values[1]
        val rz = event.values[2]
        val angle = kotlin.math.sqrt(rx * rx + ry * ry + rz * rz)
        if (angle < 1e-6f) {
            sink?.onPose(0f, 0f, 0f)
            return
        }
        val axisX = rx / angle
        val axisY = ry / angle
        val axisZ = rz / angle
        val s = kotlin.math.sin(angle / 2f)
        val qw = kotlin.math.cos(angle / 2f)
        val qx = axisX * s
        val qy = axisY * s
        val qz = axisZ * s
        sink?.onPose(
            yaw = atan2(2f * (qw * qy + qx * qz), 1f - 2f * (qy * qy + qx * qx)),
            pitch = asin((2f * (qw * qx - qy * qz)).coerceIn(-1f, 1f)),
            roll = atan2(2f * (qw * qz + qx * qy), 1f - 2f * (qx * qx + qz * qz)),
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/**
 * The real default: turn the phone to look around.
 *
 * `TYPE_GAME_ROTATION_VECTOR` rather than `TYPE_ROTATION_VECTOR` because it ignores the
 * magnetometer — no compass calibration prompt, and no sudden jump when the field correction
 * kicks in, which on a spatial mix sounds like the room lurching.
 *
 * The first reading becomes "straight ahead", so the stage is always centred wherever the user
 * happens to be holding the phone.
 */
class DeviceRotationPoseSource(context: Context) : ListenerPoseSource, SensorEventListener {
    override val id: String = ListenerPoseSource.ID_DEVICE

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private var sink: PoseSink? = null
    private var referenceYaw: Float? = null
    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private fun sensor(): Sensor? = sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    override fun isAvailable(): Boolean = sensor() != null

    override fun start(sink: PoseSink) {
        val sensor = sensor() ?: return
        this.sink = sink
        referenceYaw = null
        sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
    }

    override fun stop() {
        sensorManager?.unregisterListener(this)
        sink = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val rawYaw = orientation[0]
        val reference = referenceYaw ?: rawYaw.also { referenceYaw = it }
        var yaw = rawYaw - reference
        if (yaw > Math.PI) yaw -= (2 * Math.PI).toFloat()
        if (yaw < -Math.PI) yaw += (2 * Math.PI).toFloat()
        sink?.onPose(yaw = yaw, pitch = orientation[1], roll = orientation[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    /** Re-centres the stage on wherever the phone is pointing now. */
    fun recentre() {
        referenceYaw = null
    }
}

/** A heading the user drags. Always available — a tablet flat on a desk still works. */
class ManualPoseSource : ListenerPoseSource {
    override val id: String = ListenerPoseSource.ID_MANUAL

    private var sink: PoseSink? = null
    private var yaw = 0f

    override fun isAvailable(): Boolean = true

    override fun start(sink: PoseSink) {
        this.sink = sink
        sink.onPose(yaw, 0f, 0f)
    }

    override fun stop() {
        sink = null
    }

    fun setYaw(radians: Float) {
        yaw = radians
        sink?.onPose(yaw, 0f, 0f)
    }
}

/**
 * Smooths whatever source is live and hands the result to the engine.
 *
 * Two jobs: a low-pass so a jittery sensor does not warble the inter-aural delay (which is
 * audible long before the visual heading looks wrong), and a deadband so a perfectly still phone
 * produces a perfectly still image.
 */
class PoseFilter(
    private val smoothing: Float = 0.25f,
    private val deadbandRadians: Float = DEADBAND_DEGREES * (Math.PI / 180f).toFloat(),
) {
    var yaw: Float = 0f
        private set
    var pitch: Float = 0f
        private set
    var roll: Float = 0f
        private set

    fun accept(rawYaw: Float, rawPitch: Float, rawRoll: Float) {
        yaw = step(yaw, rawYaw)
        pitch = step(pitch, rawPitch)
        roll = step(roll, rawRoll)
    }

    fun reset() {
        yaw = 0f
        pitch = 0f
        roll = 0f
    }

    private fun step(current: Float, target: Float): Float {
        var delta = target - current
        // Shortest way round the circle, so crossing ±180° doesn't spin the room.
        if (delta > Math.PI) delta -= (2 * Math.PI).toFloat()
        if (delta < -Math.PI) delta += (2 * Math.PI).toFloat()
        if (abs(delta) < deadbandRadians) return current
        return current + smoothing * delta
    }

    private companion object {
        const val DEADBAND_DEGREES = 1.5f
    }
}
