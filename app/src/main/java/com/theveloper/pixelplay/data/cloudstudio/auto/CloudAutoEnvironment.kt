package com.theveloper.pixelplay.data.cloudstudio.auto

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.BatteryManager
import android.os.PowerManager
import com.theveloper.pixelplay.data.service.PlaybackActivityTracker
import com.theveloper.pixelplay.data.worker.AutomaticStudioPolicy

/** Why automatic preparing waits right now. [label] is what Settings › Sing says. */
enum class AutoBlock(val label: String) {
    NOT_ON_WIFI("Waiting for Wi-Fi"),
    NOT_CHARGING("Waiting for the charger"),
    BATTERY_LOW("Waiting for the battery to charge"),
    TOO_HOT("Waiting for the phone to cool down"),
    STORAGE_LOW("Waiting for free storage"),
    PLAYING("Waiting until the music stops"),
}

/** The phone's state, read once, for [CloudAutoConditions]. */
data class AutoConditions(
    val unmetered: Boolean,
    val charging: Boolean,
    /** 0–100, or -1 when unknown. */
    val batteryPercent: Int,
    /** `PowerManager.THERMAL_STATUS_*`. */
    val thermalStatus: Int,
    val freeBytes: Long,
    val playbackActive: Boolean,
)

/**
 * When automatic preparing may use the phone: on Wi-Fi, on a charger, battery not low, not hot, enough storage, and
 * not while music plays. Pure over [AutoConditions], so it is tested; [LiveCloudAutoEnvironment] reads the real ones.
 */
object CloudAutoConditions {
    /** Below this a charger isn't enough: "battery not low". */
    const val LOW_BATTERY_PERCENT = 15
    /** `PowerManager.THERMAL_STATUS_MODERATE`: the same line the on-device automatic studio keeps. */
    const val HOT_THERMAL_STATUS = 2

    /** The first thing that holds automatic preparing back, in the order a person would fix it, or null. */
    fun block(conditions: AutoConditions): AutoBlock? = when {
        !conditions.unmetered -> AutoBlock.NOT_ON_WIFI
        !conditions.charging -> AutoBlock.NOT_CHARGING
        conditions.batteryPercent in 0 until LOW_BATTERY_PERCENT -> AutoBlock.BATTERY_LOW
        conditions.thermalStatus >= HOT_THERMAL_STATUS -> AutoBlock.TOO_HOT
        conditions.freeBytes < AutomaticStudioPolicy.MIN_FREE_BYTES -> AutoBlock.STORAGE_LOW
        conditions.playbackActive -> AutoBlock.PLAYING
        else -> null
    }

    /**
     * What a job's own steps (preparing and uploading) need, checked by the engine before each one: Wi-Fi, a charger,
     * battery not low. Heat, storage and playback are the automatic worker's to check before it starts a run; the
     * person's own jobs and a Sing never pass through this.
     */
    fun workAllowed(conditions: AutoConditions): Boolean =
        conditions.unmetered && conditions.charging && conditions.batteryPercent !in 0 until LOW_BATTERY_PERCENT
}

/** Reads the phone's real conditions. */
interface CloudAutoEnvironment {
    fun conditions(): AutoConditions
}

class LiveCloudAutoEnvironment(private val context: Context) : CloudAutoEnvironment {
    override fun conditions(): AutoConditions {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val power = context.getSystemService(PowerManager::class.java)
        return AutoConditions(
            unmetered = connectivity != null && !connectivity.isActiveNetworkMetered,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            thermalStatus = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE,
            freeBytes = context.filesDir.usableSpace,
            playbackActive = PlaybackActivityTracker.isPlaybackActive,
        )
    }
}
