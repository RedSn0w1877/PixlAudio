package com.theveloper.pixelplay.data.service.player

import android.os.SystemClock
import androidx.media3.common.Player
import com.theveloper.pixelplay.brain.HostVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Brings the music down while the host speaks and back up after (Endless Listening, spoken notes).
 *
 * Why this is not a system duck and not a plain volume write. [DualPlayerEngine] builds its players with `handleAudioFocus = false` and
 * handles focus itself, and it pauses on a transient loss, so asking the system to duck would stop the music. And the master player's
 * `volume` is one number with many writers: ReplayGain, the Spatial level lift, Sing, and every transition (which writes absolute values,
 * `1f` at its ends). So the duck keeps to these rules:
 *
 *  - It is the *only* writer while it holds the volume, and it holds it only at a calm moment: [DualPlayerEngine.canDuckForHost] is false while
 *    a transition runs, while focus is lost, and while Sing or a Spatial render owns the volume.
 *  - It remembers the volume it found ([base]) and moves between `base` and `base * DUCK_PERMILLE`; it never writes an absolute number of its
 *    own, so a ReplayGain level survives.
 *  - Its own writes are recognised and kept from the rest of the app ([onMainVolumeChanged] is asked first by the service's player
 *    listener), so ReplayGain does not take a ducked value for a volume the person chose.
 *  - If anything else changes the volume while it holds it, the duck lets go **without writing**: that writer's value stands, and the host
 *    stops talking ([onEnded] says why).
 *  - Anything that is about to read or write the volume ([DualPlayerEngine.performTransition], `cancelNext`, a rebuilt player, a focus loss,
 *    release) calls [abort] first, which puts `base` back at once, so no one ever reads a ducked value as the level.
 *
 * Main thread only, like the engine. The ramp numbers are [HostVoice.ramp] (the same rule on iOS).
 */
class HostDuck(
    private val scope: () -> CoroutineScope,
    private val player: () -> Player?,
    private val now: () -> Long = { SystemClock.elapsedRealtime() },
) {
    private var job: Job? = null
    private var base = 1f
    private var lastWrite = -1f
    private var prevWrite = -1f
    private var echoUntilMs = 0L
    private var held = false

    /** True from [begin] until the music is back at its level (or the duck let go). */
    val isActive: Boolean get() = held

    /** Called when the duck ended on its own, not through [end]: "transition", "focus", "volume_taken", or the reason given to [abort]. */
    var onEnded: ((String) -> Unit)? = null

    /**
     * Starts bringing the music down. False when it cannot: already held, no player, or nothing audible to bring down. The caller waits
     * [HostVoice.RAMP_DOWN_MS] before it speaks.
     */
    fun begin(): Boolean {
        val p = player() ?: return false
        if (held) return false
        val current = p.volume
        if (current < MIN_AUDIBLE) return false
        base = current
        lastWrite = current
        prevWrite = current
        held = true
        run(HostVoice.PERMILLE_FULL, HostVoice.DUCK_PERMILLE, HostVoice.RAMP_DOWN_MS, release = false)
        return true
    }

    /** Brings the music back over [HostVoice.RAMP_UP_MS] (or [HostVoice.RAMP_UP_QUICK_MS] when [quick]). The duck is over when it lands. */
    fun end(quick: Boolean = false) {
        if (!held) return
        val p = player()
        if (p == null) {
            clear()
            return
        }
        val from = permilleOf(p.volume)
        run(from, HostVoice.PERMILLE_FULL, if (quick) HostVoice.RAMP_UP_QUICK_MS else HostVoice.RAMP_UP_MS, release = true)
    }

    /**
     * Gives the level back at once, if the volume on the player is still the duck's own, and tells [onEnded] why. Safe to call when the
     * duck is not held.
     */
    fun abort(reason: String) {
        if (!held) return
        job?.cancel()
        job = null
        val p = player()
        if (p != null && ours(p.volume)) write(p, base)
        clear()
        onEnded?.invoke(reason)
    }

    /**
     * The service's player listener asks this before anyone else sees a volume change. True: the change is the duck's own, nobody else
     * should read it. A change that is not the duck's, while it is held, ends the duck with that writer's value left alone.
     */
    fun onMainVolumeChanged(volume: Float): Boolean {
        if (!held && now() > echoUntilMs) return false
        if (ours(volume)) return true
        if (held) {
            job?.cancel()
            job = null
            clear()
            onEnded?.invoke("volume_taken")
        }
        return false
    }

    private fun ours(volume: Float): Boolean = abs(volume - lastWrite) < EPSILON || abs(volume - prevWrite) < EPSILON

    private fun permilleOf(volume: Float): Int {
        if (base <= 0f) return HostVoice.PERMILLE_FULL
        val p = (volume / base * HostVoice.PERMILLE_FULL).toInt()
        return p.coerceIn(HostVoice.DUCK_PERMILLE, HostVoice.PERMILLE_FULL)
    }

    private fun run(fromPermille: Int, toPermille: Int, rampMs: Long, release: Boolean) {
        job?.cancel()
        val startedAt = now()
        job = scope().launch {
            while (true) {
                val p = player() ?: break
                val elapsed = now() - startedAt
                val permille = HostVoice.ramp(fromPermille, toPermille, elapsed, rampMs)
                write(p, base * permille / HostVoice.PERMILLE_FULL)
                if (elapsed >= rampMs) break
                delay(STEP_MS)
            }
            if (release) clear()
        }
    }

    private fun write(p: Player, volume: Float) {
        prevWrite = lastWrite
        lastWrite = volume
        p.volume = volume
    }

    /** The duck is over; the echo of its last write is still let through for a moment so ReplayGain never sees it. */
    private fun clear() {
        held = false
        echoUntilMs = now() + ECHO_MS
    }

    private companion object {
        const val STEP_MS = 24L
        const val EPSILON = 0.002f
        const val MIN_AUDIBLE = 0.05f
        const val ECHO_MS = 400L
    }
}
