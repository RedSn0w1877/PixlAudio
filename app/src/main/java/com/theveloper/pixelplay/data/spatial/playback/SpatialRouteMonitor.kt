package com.theveloper.pixelplay.data.spatial.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.theveloper.pixelplay.presentation.viewmodel.legacyMediaRouteIndex
import timber.log.Timber

/**
 * Where this phone's media audio goes, as the raw [AudioDeviceInfo] type the route rule needs (§6.1; the UI's
 * `AudioOutputCategory` is too coarse: it puts line out with wired headphones and LE broadcast with earbuds).
 *
 * Its own callbacks rather than `ConnectivityStateHolder`'s, because that holder lives and dies with the app's UI and
 * Spatial audio must follow the route with only the playback service running (a Bluetooth button, Android Auto, the
 * watch). Started only while a profile is selected; main thread.
 */
internal class SpatialRouteMonitor(
    private val context: Context,
    private val onChanged: () -> Unit,
) {
    /** The media output's device type; null for the phone itself (or a route that cannot be read). */
    var deviceType: Int? = null
        private set

    private var started = false
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val audioManager by lazy { context.getSystemService(AudioManager::class.java) }
    private val mediaAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = update()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = update()
    }

    // The Output Switcher moves media without adding or removing a device; playback configurations change with it.
    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = update()
    }

    fun start() {
        if (started) return
        started = true
        runCatching { audioManager?.registerAudioDeviceCallback(deviceCallback, handler) }
        runCatching { audioManager?.registerAudioPlaybackCallback(playbackCallback, handler) }
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { audioManager?.unregisterAudioDeviceCallback(deviceCallback) }
        runCatching { audioManager?.unregisterAudioPlaybackCallback(playbackCallback) }
    }

    private fun update() {
        if (!started) return
        val type = try {
            currentDeviceType()
        } catch (e: Exception) {
            Timber.tag("SpatialRoute").d(e, "Route not readable")
            null
        }
        if (type != deviceType) {
            deviceType = type
            onChanged()
        }
    }

    private fun currentDeviceType(): Int? {
        val manager = audioManager ?: return null
        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.getAudioDevicesForAttributes(mediaAttributes).firstOrNull()
        } else {
            val sinks = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { it.isSink }
            legacyMediaRouteIndex(sinks.map { it.type })?.let(sinks::get)
        }
        return device?.type?.takeUnless { it == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || it == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
    }
}
