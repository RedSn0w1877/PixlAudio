package com.theveloper.pixelplay.data.tais.lyrics

/** Frame-exact audio windows and global CTC alignment, independent of Android/model execution. */
internal object CtcAlignmentCore {
    const val STRIDE_SAMPLES = 320
    const val RECEPTIVE_SAMPLES = 400
    private const val CORE_FRAMES = 800 // 16 seconds at 50 Hz
    private const val CONTEXT_FRAMES = 100 // 2 seconds on each side
    private const val MAX_PATH_CELLS = 64L * 1024 * 1024

    data class Window(val firstFrame: Int, val endFrame: Int, val inputStartSample: Int, val inputEndSample: Int) {
        val localFirstFrame: Int get() = firstFrame - inputStartSample / STRIDE_SAMPLES
        val keptFrames: Int get() = endFrame - firstFrame
    }

    fun windows(sampleCount: Int): List<Window> {
        if (sampleCount < RECEPTIVE_SAMPLES) return emptyList()
        val totalFrames = (sampleCount - RECEPTIVE_SAMPLES) / STRIDE_SAMPLES + 1
        return (0 until totalFrames step CORE_FRAMES).map { first ->
            val end = minOf(first + CORE_FRAMES, totalFrames)
            val inputFirst = maxOf(0, first - CONTEXT_FRAMES)
            val inputEnd = minOf(totalFrames, end + CONTEXT_FRAMES)
            Window(first, end, inputFirst * STRIDE_SAMPLES,
                (inputEnd - 1) * STRIDE_SAMPLES + RECEPTIVE_SAMPLES)
        }
    }

    /** Conservative admission check, not a calibrated probability of correctness. */
    fun acceptsWordEvidence(scores: List<Float>): Boolean = scores.isNotEmpty() &&
        scores.all { it.isFinite() && it in 0f..1f } && scores.average() >= 0.45 &&
        scores.count { it >= 0.3f }.toDouble() / scores.size >= 0.75

    /** Blank states remain available for the whole intro, interludes and outro. */
    fun align(logProbs: Array<FloatArray>, extended: IntArray, blankId: Int = 0,
              checkCancelled: () -> Unit = {}): IntArray? {
        val frames = logProbs.size
        val length = extended.size
        if (frames == 0 || length == 0 || frames < (length - 1) / 2) return null
        require(frames.toLong() * length <= MAX_PATH_CELLS) {
            "This song is too long for safe on-device alignment. Existing lyrics were kept."
        }
        val negInf = Float.NEGATIVE_INFINITY
        var prev = FloatArray(length) { negInf }
        var curr = FloatArray(length)
        val backptr = Array(frames) { ByteArray(length) }
        prev[0] = logProbs[0][extended[0]]
        if (length > 1) prev[1] = logProbs[0][extended[1]]
        for (t in 1 until frames) {
            if (t % 64 == 0) checkCancelled()
            val lp = logProbs[t]
            for (s in 0 until length) {
                var best = prev[s]
                var move: Byte = 0
                if (s >= 1 && prev[s - 1] > best) { best = prev[s - 1]; move = 1 }
                if (s >= 2 && extended[s] != blankId && extended[s] != extended[s - 2] && prev[s - 2] > best) {
                    best = prev[s - 2]; move = 2
                }
                curr[s] = if (best == negInf) negInf else best + lp[extended[s]]
                backptr[t][s] = move
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        var s = if (length == 1 || prev[length - 1] >= prev[length - 2]) length - 1 else length - 2
        if (!prev[s].isFinite()) return null
        val path = IntArray(frames)
        path[frames - 1] = s
        for (t in frames - 1 downTo 1) { s -= backptr[t][s]; path[t - 1] = s }
        return path
    }
}
