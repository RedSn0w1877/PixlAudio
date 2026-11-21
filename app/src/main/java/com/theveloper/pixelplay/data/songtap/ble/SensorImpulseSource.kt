package com.theveloper.pixelplay.data.songtap.ble

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import com.theveloper.pixelplay.songtap.AccelSample
import com.theveloper.pixelplay.songtap.ImpulseState
import com.theveloper.pixelplay.songtap.SongTapImpulse
import com.theveloper.pixelplay.songtap.SongTapSensitivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** The monotonic clock for signal and motion timing, the wall clock for the timestamp of a signed offer. */
@Singleton
class SystemSongTapClock @Inject constructor() : SongTapClock {
    override fun elapsedMs(): Long = SystemClock.elapsedRealtime()
    override fun wallMs(): Long = System.currentTimeMillis()
}

/**
 * "The phone was jerked": the accelerometer, read at 50 readings a second and only between [start] and [stop]
 * (that is, only while Song Tap is armed on screen). The jerk rule itself (a sharp change between two readings,
 * debounced) is `SongTapImpulse` in the core; this class converts the sensor's m/s^2 to milli g and hands it over.
 */
@Singleton
class SensorImpulseSource @Inject constructor(
    @ApplicationContext private val context: Context,
) : ImpulseSource, SensorEventListener {

    private val lock = Any()
    private var manager: SensorManager? = null
    private var callback: ((Long) -> Unit)? = null
    private var state = ImpulseState.INITIAL
    @Volatile private var profile = SongTapSensitivity.DEFAULT

    override fun setSensitivity(profile: SongTapSensitivity) {
        this.profile = profile
    }

    override fun start(onImpulse: (Long) -> Unit) {
        synchronized(lock) {
            stopLocked()
            val sm = context.getSystemService(SensorManager::class.java) ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            callback = onImpulse
            state = ImpulseState.INITIAL
            manager = sm
            // 20 ms between readings (50 a second); the batching delay is zero so a jerk is seen when it happens.
            sm.registerListener(this, sensor, SAMPLING_PERIOD_US, 0)
        }
    }

    override fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        manager?.unregisterListener(this)
        manager = null
        callback = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        if (v.size < 3) return
        val tMs = event.timestamp / 1_000_000L
        val sample = AccelSample(tMs, toMilliG(v[0]), toMilliG(v[1]), toMilliG(v[2]))
        val hit: ((Long) -> Unit)?
        synchronized(lock) {
            val step = SongTapImpulse.step(state, sample, profile)
            state = step.state
            hit = if (step.impulse) callback else null
        }
        hit?.invoke(tMs)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun toMilliG(ms2: Float): Int = (ms2 * (1000f / SensorManager.GRAVITY_EARTH)).toInt()

    private companion object {
        const val SAMPLING_PERIOD_US = 20_000
    }
}
