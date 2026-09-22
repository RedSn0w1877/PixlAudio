package com.theveloper.pixelplay.data.remix

import com.theveloper.pixelplay.data.remix.model.RemixState

/**
 * A mutable snapshot of every parameter the graph needs for one block.
 *
 * Reused, never reallocated: the audio thread must not allocate, and this is filled once per
 * block. Plain fields rather than a data class for the same reason.
 */
class RemixParamsSnapshot(val maxStems: Int = RemixState.MAX_STEMS) {
    var stemCount: Int = 0
    var rate: Float = 1f
    var masterGain: Float = 1f

    val stemX = FloatArray(maxStems)
    val stemY = FloatArray(maxStems)
    val stemZ = FloatArray(maxStems)
    val stemGain = FloatArray(maxStems) { 1f }
    val stemSend = FloatArray(maxStems) { 0.2f }

    var filterMode: Int = FILTER_LOW_PASS
    var cutoffHz: Float = 18_000f
    var resonance: Float = 0.707f
    var bits: Int = 16
    var decim: Int = 1

    var reverbMix: Float = 0.15f
    var rt60: Float = 1.8f
    var damp: Float = 0.4f
    var preDelayMs: Float = 20f

    var yaw: Float = 0f
    var pitch: Float = 0f

    var decayFrames: Int = 0

    companion object {
        const val FILTER_LOW_PASS = 0
        const val FILTER_BAND_PASS = 1
        const val FILTER_HIGH_PASS = 2
    }
}

/**
 * The hand-off between the UI thread and the audio thread.
 *
 * Every field is `@Volatile` and written by Compose (a drag, a slider, an applied preset). The DSP
 * thread calls [snapshotInto] **exactly once per block** and then reads nothing but the snapshot.
 *
 * That once-per-block rule is not a style preference. Reading a volatile twice inside one block
 * can return two different values and put a step in the middle of a buffer — which is precisely
 * the discontinuity the smoothers exist to remove. The same rule is why
 * `MidSideVocalProcessor.kt:65` reads its attenuation once per `queueInput` and not per sample.
 *
 * No locks: a drag produces 60–120 writes a second, and blocking the audio thread behind a UI
 * thread's lock for even one block is a dropout.
 */
class RemixParams {
    @Volatile var stemCount: Int = 0
    @Volatile var rate: Float = 1f
    @Volatile var masterGain: Float = 1f

    private val stemX = FloatArray(RemixState.MAX_STEMS)
    private val stemY = FloatArray(RemixState.MAX_STEMS)
    private val stemZ = FloatArray(RemixState.MAX_STEMS)
    private val stemGain = FloatArray(RemixState.MAX_STEMS) { 1f }
    private val stemSend = FloatArray(RemixState.MAX_STEMS) { 0.2f }

    @Volatile var filterMode: Int = RemixParamsSnapshot.FILTER_LOW_PASS
    @Volatile var cutoffHz: Float = 18_000f
    @Volatile var resonance: Float = 0.707f
    @Volatile var bits: Int = 16
    @Volatile var decim: Int = 1

    @Volatile var reverbMix: Float = 0.15f
    @Volatile var rt60: Float = 1.8f
    @Volatile var damp: Float = 0.4f
    @Volatile var preDelayMs: Float = 20f

    @Volatile var yaw: Float = 0f
    @Volatile var pitch: Float = 0f

    @Volatile var decayFrames: Int = 0

    /**
     * Float arrays are not volatile element-wise, but each element is written by one thread and
     * read by another as a plain 32-bit store/load: worst case the audio thread sees a puck's
     * previous position for one more block, which is inaudible after smoothing. Tearing cannot
     * produce a garbage float because 32-bit stores are atomic on every ABI we ship.
     */
    fun setStemPosition(index: Int, x: Float, y: Float, z: Float) {
        if (index !in 0 until RemixState.MAX_STEMS) return
        stemX[index] = x
        stemY[index] = y
        stemZ[index] = z
    }

    fun setStemGain(index: Int, linear: Float) {
        if (index !in 0 until RemixState.MAX_STEMS) return
        stemGain[index] = linear
    }

    fun setStemSend(index: Int, send: Float) {
        if (index !in 0 until RemixState.MAX_STEMS) return
        stemSend[index] = send
    }

    fun stemPositionX(index: Int): Float = stemX[index]
    fun stemPositionY(index: Int): Float = stemY[index]
    fun stemPositionZ(index: Int): Float = stemZ[index]

    fun snapshotInto(target: RemixParamsSnapshot) {
        target.stemCount = stemCount.coerceIn(0, target.maxStems)
        target.rate = rate
        target.masterGain = masterGain
        for (i in 0 until target.maxStems) {
            target.stemX[i] = stemX[i]
            target.stemY[i] = stemY[i]
            target.stemZ[i] = stemZ[i]
            target.stemGain[i] = stemGain[i]
            target.stemSend[i] = stemSend[i]
        }
        target.filterMode = filterMode
        target.cutoffHz = cutoffHz
        target.resonance = resonance
        target.bits = bits
        target.decim = decim
        target.reverbMix = reverbMix
        target.rt60 = rt60
        target.damp = damp
        target.preDelayMs = preDelayMs
        target.yaw = yaw
        target.pitch = pitch
        target.decayFrames = decayFrames
    }
}
