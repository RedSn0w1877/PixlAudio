package com.theveloper.pixelplay.data.haptics

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.HapticGenerator
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.theveloper.pixelplay.data.diagnostics.DiagnosticsLog
import com.theveloper.pixelplay.data.preferences.MusicHapticsEngineChoice
import com.theveloper.pixelplay.data.preferences.MusicHapticsSettings
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Makes the phone vibrate along with the music (Settings > Playback > Music haptics).
 *
 * Two engines, chosen in Settings:
 *  - APP (default): a [TeeAudioProcessor] in the player's audio chain hands the decoded PCM to a
 *    [BeatDetector] (zero allocation on the audio thread); detected kicks, snares and bass become
 *    [VibrationEffect] primitives on a background thread, delayed to match the audio output latency
 *    and rate-limited to about 25 events per second.
 *  - SYSTEM (experimental): Android's [HapticGenerator] on the player's audio session (API 31+).
 *    It cannot honour Strength or Follows, and it only produces haptics when the output path
 *    carries haptic channels, which Media3 does not request, so it is opt-in.
 *
 * Everything is gated: it only runs while enabled, supported, the app is in the foreground, audio
 * is local (not cast / Spotify Connect), Battery Saver and Do Not Disturb are off, the device is
 * not hot, no call is active and the system's touch-haptics switch is on.
 */
@Singleton
@OptIn(UnstableApi::class)
class MusicHapticsEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val preferences: UserPreferencesRepository,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val vibrator: Vibrator? by lazy {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
        }.getOrNull()
    }

    private val primitivesSupported: Boolean by lazy {
        runCatching {
            vibrator?.areAllPrimitivesSupported(
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK,
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK,
            ) == true
        }.getOrDefault(false)
    }

    /** True when the phone has a vibration motor at all. Settings greys the switch out otherwise. */
    val isSupported: Boolean get() = runCatching { vibrator?.hasVibrator() == true }.getOrDefault(false)

    /** True when Android's HapticGenerator can run on this device (experimental engine). */
    val isSystemGeneratorAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { HapticGenerator.isAvailable() && AudioManager.isHapticPlaybackSupported() }.getOrDefault(false)

    private val _settings = MutableStateFlow(MusicHapticsSettings())
    val settings: StateFlow<MusicHapticsSettings> = _settings.asStateFlow()

    /** Should the player keep its audio as PCM (no compressed offload) so the analysis can hear it? */
    private val _pcmAnalysisWanted = MutableStateFlow(false)
    val pcmAnalysisWanted: StateFlow<Boolean> = _pcmAnalysisWanted.asStateFlow()

    @Volatile private var follows = MusicHapticsSettings().follows
    @Volatile private var strengthPercent = MusicHapticsSettings.DEFAULT_STRENGTH
    @Volatile private var analysisOpen = false
    @Volatile private var foreground = false
    @Volatile private var remoteOutput = false
    @Volatile private var lastClosedReason: String? = "not started"
    @Volatile private var handler: Handler? = null
    @Volatile private var audioSessionId = 0

    private var thread: HandlerThread? = null
    private var collectJob: Job? = null
    private var generator: HapticGenerator? = null
    private var attached = false
    private var consecutiveFailures = 0
    private var onWantsPcmChanged: ((Boolean) -> Unit)? = null

    private val lifecycleObserver = LifecycleEventObserver { _, _ ->
        foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        requestEvaluate()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = requestEvaluate()
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = stopNow()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = stopNow()
    }

    private val evaluateRunnable = Runnable { evaluateGate() }
    private val tickRunnable = object : Runnable {
        override fun run() {
            evaluateGate()
            if (_settings.value.enabled) handler?.postDelayed(this, TICK_MS)
        }
    }

    /**
     * Starts watching the settings and the system state. Call once from the playback service's
     * `onCreate` (main thread). [onWantsPcmChanged] tells the player to keep PCM output.
     */
    fun attach(scope: CoroutineScope, onWantsPcmChanged: (Boolean) -> Unit) {
        if (attached) return
        attached = true
        this.onWantsPcmChanged = onWantsPcmChanged
        val t = HandlerThread("music-haptics", Process.THREAD_PRIORITY_BACKGROUND).also { it.start() }
        thread = t
        handler = Handler(t.looper)
        foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ProcessLifecycleOwner.get().lifecycle.addObserver(lifecycleObserver)
        val filter = IntentFilter().apply {
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        audioManager.registerAudioDeviceCallback(deviceCallback, handler)
        collectJob = scope.launch {
            preferences.musicHapticsFlow.collect { applySettings(it) }
        }
    }

    /** Stops everything and gives the HapticGenerator back. Call from the service's `onDestroy`. */
    fun release() {
        if (!attached) return
        attached = false
        collectJob?.cancel()
        collectJob = null
        runCatching { ProcessLifecycleOwner.get().lifecycle.removeObserver(lifecycleObserver) }
        runCatching { context.unregisterReceiver(receiver) }
        runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
        analysisOpen = false
        val h = handler
        handler = null
        h?.post { releaseGenerator("service released") }
        stopNowOn(h)
        h?.removeCallbacks(tickRunnable)
        thread?.quitSafely()
        thread = null
        _pcmAnalysisWanted.value = false
        onWantsPcmChanged = null
    }

    /** Cast or Spotify Connect is playing the audio: the phone's motor must stay quiet. */
    fun setRemoteOutput(remote: Boolean) {
        if (remoteOutput == remote) return
        remoteOutput = remote
        if (remote) stopNow()
        requestEvaluate()
    }

    fun onAudioSessionChanged(sessionId: Int) {
        audioSessionId = sessionId
        requestEvaluate()
    }

    /** Playback paused, a seek, or the audio route changed: drop what is pending and silence the motor. */
    fun stopNow() = stopNowOn(handler)

    private fun stopNowOn(h: Handler?) {
        h?.let { handlerRef -> slots.forEach { handlerRef.removeCallbacks(it) } }
        runCatching { vibrator?.cancel() }
    }

    private fun applySettings(new: MusicHapticsSettings) {
        val old = _settings.value
        _settings.value = new
        follows = new.follows
        strengthPercent = new.strengthPercent
        val wantsPcm = new.enabled && new.engine == MusicHapticsEngineChoice.APP && isSupported
        if (_pcmAnalysisWanted.value != wantsPcm) {
            _pcmAnalysisWanted.value = wantsPcm
            onWantsPcmChanged?.invoke(wantsPcm)
        }
        if (new.enabled && !old.enabled) handler?.post(tickRunnable)
        requestEvaluate()
    }

    private fun requestEvaluate() {
        handler?.let {
            it.removeCallbacks(evaluateRunnable)
            it.post(evaluateRunnable)
        }
    }

    /** Runs on the haptics thread. Decides whether the PCM analysis / HapticGenerator may run now. */
    private fun evaluateGate() {
        val s = _settings.value
        val reason = closedReason(s)
        val open = reason == null
        val previous = lastClosedReason
        if (reason != previous) {
            lastClosedReason = reason
            if (open) {
                DiagnosticsLog.event("MusicHaptics", "started (${s.engine.name.lowercase()}, ${s.follows.key}, ${s.strengthPercent}%)")
            } else if (previous == null) {
                DiagnosticsLog.event("MusicHaptics", "stopped: $reason")
            }
        }
        val wasAnalysisOpen = analysisOpen
        analysisOpen = open && s.engine == MusicHapticsEngineChoice.APP
        if (wasAnalysisOpen && !analysisOpen) stopNow()
        reconcileGenerator(open && s.engine == MusicHapticsEngineChoice.SYSTEM)
    }

    private fun closedReason(s: MusicHapticsSettings): String? {
        if (!s.enabled) return "switched off"
        if (!isSupported) return "no vibration motor"
        if (!foreground) return "app in background"
        if (remoteOutput) return "audio plays on another device"
        if (runCatching { powerManager.isPowerSaveMode }.getOrDefault(false)) return "battery saver"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            runCatching { powerManager.currentThermalStatus >= PowerManager.THERMAL_STATUS_SEVERE }.getOrDefault(false)
        ) return "device is hot"
        val filter = runCatching { notificationManager.currentInterruptionFilter }
            .getOrDefault(NotificationManager.INTERRUPTION_FILTER_ALL)
        if (filter != NotificationManager.INTERRUPTION_FILTER_ALL && filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
            return "do not disturb"
        }
        val touchHaptics = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1)
        }.getOrDefault(1)
        if (touchHaptics == 0) return "vibration turned off in system settings"
        val mode = runCatching { audioManager.mode }.getOrDefault(AudioManager.MODE_NORMAL)
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_RINGTONE || mode == AudioManager.MODE_CALL_SCREENING
        ) return "phone call"
        return null
    }

    // ── HapticGenerator (experimental "System" engine) ───────────────────────────────────────

    private var generatorSessionId = 0

    private fun reconcileGenerator(wanted: Boolean) {
        val session = audioSessionId
        if (!wanted || session == 0 || !isSystemGeneratorAvailable) {
            if (generator != null) releaseGenerator("not wanted")
            return
        }
        if (generator != null && generatorSessionId == session) return
        releaseGenerator("session changed")
        try {
            val created = HapticGenerator.create(session)
            created.setEnabled(true)
            generator = created
            generatorSessionId = session
            DiagnosticsLog.event("MusicHaptics", "system HapticGenerator enabled")
        } catch (t: Throwable) {
            DiagnosticsLog.warn("MusicHaptics", "system HapticGenerator failed: ${t.javaClass.simpleName}")
        }
    }

    private fun releaseGenerator(why: String) {
        val g = generator ?: return
        generator = null
        generatorSessionId = 0
        runCatching { g.setEnabled(false) }
        runCatching { g.release() }
        DiagnosticsLog.event("MusicHaptics", "system HapticGenerator released ($why)")
    }

    // ── App analysis engine ──────────────────────────────────────────────────────────────────

    /** One analysis sink per player (the engine has two); each owns its detector and limiter. */
    fun createPcmSink(): TeeAudioProcessor.AudioBufferSink = PcmSink()

    private inner class PcmSink : TeeAudioProcessor.AudioBufferSink {
        private val planner = HapticPlanner(output) { SystemClock.elapsedRealtime() }
        private val detector = BeatDetector(planner)
        private var channels = 0
        private var encoding = C.ENCODING_INVALID
        private var wasOpen = false

        override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
            channels = channelCount
            this.encoding = encoding
            detector.configure(sampleRateHz)
            planner.reset()
            wasOpen = false
        }

        override fun handleBuffer(buffer: ByteBuffer) {
            if (!analysisOpen) {
                wasOpen = false
                return
            }
            if (!wasOpen) {
                detector.reset()
                planner.reset()
                wasOpen = true
            }
            planner.follows = follows
            planner.strengthPercent = strengthPercent
            when (encoding) {
                C.ENCODING_PCM_16BIT -> detector.processPcm16(buffer, channels)
                C.ENCODING_PCM_FLOAT -> detector.processFloat(buffer, channels)
                else -> Unit
            }
        }
    }

    private val output = object : HapticPlanner.Output {
        override fun tap(scale: Float, sharp: Boolean) = post(if (sharp) KIND_CLICK else KIND_TICK, scale)
        override fun rumble(scale: Float) = post(KIND_RUMBLE, scale)
    }

    private inner class Slot : Runnable {
        @Volatile var kind = 0
        @Volatile var scale = 0f
        override fun run() {
            if (analysisOpen) vibrateNow(kind, scale)
        }
    }

    private val slots = Array(SLOT_COUNT) { Slot() }
    private val slotCursor = AtomicInteger()

    /** Called from the audio thread: reuses a preallocated slot, so steady state allocates nothing here. */
    private fun post(kind: Int, scale: Float) {
        val h = handler ?: return
        val slot = slots[slotCursor.getAndIncrement() and (SLOT_COUNT - 1)]
        slot.kind = kind
        slot.scale = scale
        h.postDelayed(slot, OUTPUT_LATENCY_COMPENSATION_MS)
    }

    private val audioAttributes: AudioAttributes by lazy {
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
    }

    @Suppress("DEPRECATION")
    private fun vibrateNow(kind: Int, scale: Float) {
        val v = vibrator ?: return
        try {
            val clamped = scale.coerceIn(0.05f, 1f)
            val effect = if (primitivesSupported) {
                val primitive = when (kind) {
                    KIND_CLICK -> VibrationEffect.Composition.PRIMITIVE_CLICK
                    KIND_TICK -> VibrationEffect.Composition.PRIMITIVE_TICK
                    else -> VibrationEffect.Composition.PRIMITIVE_LOW_TICK
                }
                VibrationEffect.startComposition().addPrimitive(primitive, clamped).compose()
            } else {
                val durationMs = when (kind) {
                    KIND_CLICK -> 18L
                    KIND_TICK -> 10L
                    else -> 30L
                }
                val amplitude = if (v.hasAmplitudeControl()) (clamped * 255f).toInt().coerceIn(1, 255)
                else VibrationEffect.DEFAULT_AMPLITUDE
                VibrationEffect.createOneShot(durationMs, amplitude)
            }
            v.vibrate(effect, audioAttributes)
            consecutiveFailures = 0
        } catch (t: Throwable) {
            consecutiveFailures++
            if (consecutiveFailures == 1 || consecutiveFailures == 20) {
                DiagnosticsLog.warn("MusicHaptics", "vibrate failed (${t.javaClass.simpleName}) x$consecutiveFailures")
            }
        }
    }

    private companion object {
        const val KIND_CLICK = 0
        const val KIND_TICK = 1
        const val KIND_RUMBLE = 2
        const val SLOT_COUNT = 32
        const val TICK_MS = 3_000L

        /**
         * The detector hears audio when it is handed to the output buffer, a little before the
         * speaker plays it. Holding each event back by about this long lines the buzz up with the
         * sound on typical (non-offloaded) outputs.
         */
        const val OUTPUT_LATENCY_COMPENSATION_MS = 110L
    }
}
