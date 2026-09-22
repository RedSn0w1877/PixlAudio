package com.theveloper.pixelplay.data.remix.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The complete, serializable description of a remix: where each stem sits in space, the loop
 * region, tape rate, filter and reverb, and the listener's orientation.
 *
 * Two rules hold this file together:
 *
 * 1. **Positions are metres in a listener-centred right-handed frame** (+x right, +y up, −z
 *    forward), never UI coordinates. The stage converts pixels to metres; the DSP and this JSON
 *    speak physics. Otherwise "x = 0.7" has no meaning once the stage is resized.
 * 2. **[sanitized] is the only way a state reaches the engine.** Saved files and the AI endpoint
 *    are both untrusted input — a returned `rate` of 50 or an `rt60` of 1e9 would blow up the DSP
 *    thread. Every numeric field is clamped there, and that function is the main test target.
 */
@Serializable
data class RemixState(
    /** Schema version. Bump when a field changes meaning, not when one is added. */
    val v: Int = VERSION,
    val songId: String = "",
    val loop: LoopState = LoopState(),
    /** Tape speed. Pitch follows rate, like a turntable. */
    val rate: Float = 1f,
    val stems: List<StemState> = emptyList(),
    val filter: FilterState = FilterState(),
    val reverb: ReverbState = ReverbState(),
    val listener: ListenerState = ListenerState(),
    val masterGain: Float = 1f,
) {
    fun sanitized(): RemixState = copy(
        v = VERSION,
        songId = songId.take(MAX_ID_LENGTH),
        loop = loop.sanitized(),
        rate = rate.clamp(MIN_RATE, MAX_RATE, 1f),
        stems = stems.take(MAX_STEMS).map { it.sanitized() },
        filter = filter.sanitized(),
        reverb = reverb.sanitized(),
        listener = listener.sanitized(),
        masterGain = masterGain.clamp(0f, 2f, 1f),
    )

    companion object {
        const val VERSION = 1

        const val MIN_RATE = 0.6f
        const val MAX_RATE = 1.4f
        const val MAX_STEMS = 4
        const val MIN_LOOP_MS = 200
        const val MAX_LOOP_MS = 30_000
        const val MIN_XFADE_MS = 30
        const val MAX_XFADE_MS = 50
        const val MAX_DECAY_MS = 300
        const val MAX_DISTANCE_M = 12f
        private const val MAX_ID_LENGTH = 256

        /** Lenient on read (forward compatibility), compact on write. */
        val json: Json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
            isLenient = true
        }

        /** Never throws: a corrupt preset yields null rather than taking the screen down. */
        fun decode(text: String): RemixState? =
            runCatching { json.decodeFromString<RemixState>(text).sanitized() }.getOrNull()

        fun encode(state: RemixState): String = json.encodeToString(state.sanitized())
    }
}

@Serializable
data class LoopState(
    val startMs: Int = 0,
    val endMs: Int = 8_000,
    val enabled: Boolean = true,
    /** Equal-power crossfade at the seam. Below ~30 ms the splice starts to tick. */
    val xfadeMs: Int = 40,
    /** Optional fade of the dry signal into the seam. The reverb tail is deliberately not cut. */
    val decayMs: Int = 0,
) {
    val lengthMs: Int get() = endMs - startMs

    fun sanitized(): LoopState {
        val start = startMs.coerceAtLeast(0)
        val length = (endMs - start)
            .coerceIn(RemixState.MIN_LOOP_MS, RemixState.MAX_LOOP_MS)
        return copy(
            startMs = start,
            endMs = start + length,
            xfadeMs = xfadeMs.coerceIn(RemixState.MIN_XFADE_MS, RemixState.MAX_XFADE_MS),
            decayMs = decayMs.coerceIn(0, RemixState.MAX_DECAY_MS),
        )
    }
}

/**
 * One stem on the stage. [kind] is open rather than an enum because the available set depends on
 * what produced the stems: four from the cloud separator, two from an existing instrumental, or
 * the mid/side pair that works on any song with no separation at all.
 */
@Serializable
data class StemState(
    val kind: String = StemKind.CENTER,
    val x: Float = 0f,
    val y: Float = 0f,
    @SerialName("z") val z: Float = -1f,
    val gainDb: Float = 0f,
    val muted: Boolean = false,
    /** Reverb send. The engine also scales this by distance; this is the user's offset. */
    val send: Float = 0.2f,
) {
    fun sanitized(): StemState = copy(
        kind = kind.take(32).ifBlank { StemKind.CENTER },
        x = x.clamp(-RemixState.MAX_DISTANCE_M, RemixState.MAX_DISTANCE_M, 0f),
        y = y.clamp(-RemixState.MAX_DISTANCE_M, RemixState.MAX_DISTANCE_M, 0f),
        z = z.clamp(-RemixState.MAX_DISTANCE_M, RemixState.MAX_DISTANCE_M, -1f),
        gainDb = gainDb.clamp(-60f, 12f, 0f),
        send = send.clamp(0f, 1f, 0.2f),
    )
}

object StemKind {
    const val VOCALS = "vocals"
    const val DRUMS = "drums"
    const val BASS = "bass"
    const val OTHER = "other"
    const val INSTRUMENTAL = "instrumental"

    /** Mid/side fallback: works on every song, needs no model and no network. */
    const val CENTER = "center"
    const val SIDES = "sides"
}

@Serializable
data class FilterState(
    /** "lp", "bp" or "hp". */
    val mode: String = "lp",
    val cutoffHz: Float = 18_000f,
    val q: Float = 0.707f,
    /** Bit-crush depth. 16 = off. */
    val bits: Int = 16,
    /** Sample-and-hold decimation. 1 = off. */
    val decim: Int = 1,
) {
    fun sanitized(): FilterState = copy(
        mode = if (mode in ALLOWED_MODES) mode else "lp",
        cutoffHz = cutoffHz.clamp(200f, 18_000f, 18_000f),
        q = q.clamp(0.3f, 12f, 0.707f),
        bits = bits.coerceIn(4, 16),
        decim = decim.coerceIn(1, 16),
    )

    companion object {
        val ALLOWED_MODES = setOf("lp", "bp", "hp")
    }
}

@Serializable
data class ReverbState(
    val mix: Float = 0.15f,
    val rt60: Float = 1.8f,
    val damp: Float = 0.4f,
    val preDelayMs: Float = 20f,
) {
    fun sanitized(): ReverbState = copy(
        mix = mix.clamp(0f, 1f, 0.15f),
        rt60 = rt60.clamp(0.3f, 6f, 1.8f),
        damp = damp.clamp(0f, 0.9f, 0.4f),
        preDelayMs = preDelayMs.clamp(0f, 120f, 20f),
    )
}

@Serializable
data class ListenerState(
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val roll: Float = 0f,
    /** "manual", "device" or "headset" — advisory only; the engine reads live pose. */
    val source: String = "manual",
) {
    fun sanitized(): ListenerState = copy(
        yaw = yaw.wrapRadians(),
        pitch = pitch.clamp(-HALF_PI, HALF_PI, 0f),
        roll = roll.wrapRadians(),
        source = source.take(16).ifBlank { "manual" },
    )

    private companion object {
        const val HALF_PI = (Math.PI / 2).toFloat()
    }
}

/**
 * Clamp that also rejects NaN and infinities — `coerceIn` alone passes NaN straight through, and a
 * single NaN entering a feedback path (the reverb, any filter state) poisons it permanently.
 */
private fun Float.clamp(min: Float, max: Float, fallback: Float): Float =
    if (isNaN() || isInfinite()) fallback else coerceIn(min, max)

private fun Float.wrapRadians(): Float {
    if (isNaN() || isInfinite()) return 0f
    val twoPi = (Math.PI * 2).toFloat()
    var value = this % twoPi
    if (value > Math.PI) value -= twoPi
    if (value < -Math.PI) value += twoPi
    return value
}
