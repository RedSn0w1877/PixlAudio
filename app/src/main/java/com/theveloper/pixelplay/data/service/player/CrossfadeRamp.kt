package com.theveloper.pixelplay.data.service.player

import kotlinx.coroutines.delay

/**
 * How [InstrumentalCrossfadeController.crossfadeToInstrumental] places the switch from the vocals to the
 * instrumental.
 *
 * @property startAtPositionMs the player position to start fading at (the next gap between sung words),
 *   or null to start as soon as the instrumental is in step. A position already passed starts at once.
 * @property fadeMs the length of the fade, clamped to [CrossfadeRamp.MIN_FADE_MS]..[CrossfadeRamp.MAX_FADE_MS].
 * @property maxWaitMs the longest the start may wait for [startAtPositionMs] (a paused or seeking player
 *   never gets there).
 */
data class CrossfadeRequest(
    val startAtPositionMs: Long? = null,
    val fadeMs: Long = CrossfadeRamp.DEFAULT_FADE_MS,
    val maxWaitMs: Long = CrossfadeRamp.DEFAULT_MAX_WAIT_MS,
)

/**
 * The timing maths of the Sing crossfade, apart from any player so it is unit-tested with virtual time:
 * a linear equal-gain ramp, the wait for a position, and the seek lead that lines the instrumental up.
 */
internal object CrossfadeRamp {
    const val DEFAULT_FADE_MS = 700L
    const val MIN_FADE_MS = 300L
    const val MAX_FADE_MS = 700L

    /** A ramp changes the volumes this often. */
    const val STEP_MS = 16L

    /** Waiting for a position looks at the player this often. */
    const val POSITION_POLL_MS = 8L

    /** The longest a switch waits for its planned start. */
    const val DEFAULT_MAX_WAIT_MS = 1_200L

    /** The furthest ahead an aligning seek aims (a seek never takes longer than this to land). */
    const val MAX_SEEK_LEAD_MS = 400L

    fun clampFadeMs(fadeMs: Long): Long = fadeMs.coerceIn(MIN_FADE_MS, MAX_FADE_MS)

    /**
     * Moves two volumes in step: the outgoing one from [outFrom] to [outTo], the incoming one from [inFrom]
     * to [inTo], over [durationMs]. With the vocals and the instrumental both at [outFrom] / [inTo] equal to
     * the player's own volume the two always add up to that volume (equal gain), which keeps the instrumental
     * steady and fades only the vocals out of the correlated mix.
     *
     * The progress comes from [clock], not from counting steps, so a slow frame cannot stretch the fade.
     * Returns false, leaving the volumes where they were, as soon as [stillValid] says the ramp no longer
     * applies (the song changed, the player went away); true once both volumes sit exactly on their targets.
     */
    suspend fun ramp(
        durationMs: Long,
        outFrom: Float,
        outTo: Float,
        inFrom: Float,
        inTo: Float,
        setOut: (Float) -> Unit,
        setIn: (Float) -> Unit,
        stillValid: () -> Boolean,
        clock: () -> Long,
    ): Boolean {
        val duration = durationMs.coerceAtLeast(1L)
        val startedAt = clock()
        while (true) {
            if (!stillValid()) return false
            val t = ((clock() - startedAt).toFloat() / duration).coerceIn(0f, 1f)
            if (t >= 1f) {
                setOut(outTo)
                setIn(inTo)
                return true
            }
            setOut(outFrom + (outTo - outFrom) * t)
            setIn(inFrom + (inTo - inFrom) * t)
            delay(STEP_MS)
        }
    }

    /**
     * Waits until [position] reaches [targetMs], for at most [maxWaitMs]. A target already passed returns
     * at once ("if already late, start now"); the hard stop starts the switch anyway. False only when
     * [stillValid] stopped being true.
     */
    suspend fun awaitPosition(
        position: () -> Long,
        targetMs: Long,
        maxWaitMs: Long,
        stillValid: () -> Boolean,
        clock: () -> Long,
    ): Boolean {
        val giveUpAt = clock() + maxWaitMs
        while (position() < targetMs) {
            if (!stillValid()) return false
            if (clock() >= giveUpAt) break
            delay(POSITION_POLL_MS)
        }
        return stillValid()
    }

    /**
     * Where to aim the instrumental's next seek. A seek takes time to land, and the main player keeps going
     * meanwhile, so the instrumental ends up behind it: [driftMs] (instrumental minus main) is the
     * [previousLeadMs] it was aimed ahead by, less that lag. Aiming the lag ahead cancels it.
     */
    fun nextSeekLead(previousLeadMs: Long, driftMs: Long): Long =
        (previousLeadMs - driftMs).coerceIn(0L, MAX_SEEK_LEAD_MS)
}
