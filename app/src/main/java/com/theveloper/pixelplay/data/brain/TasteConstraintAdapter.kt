package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.data.blocking.BlockedArtistIndex
import com.theveloper.pixelplay.data.blocking.BlockedArtistsRepository
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteAdjustments
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The listener's hard constraints as the brain reads them (docs/brain-spec.md, 5.11, 13.1): blocked
 * artists and the taste adjustments ("Not for me", mute, boost, reduce). They are applied outside the
 * scorer, so an explicit instruction is exact: music that is excluded or blocked is never a candidate,
 * and a boost or reduce enters the value as `ln(product)`.
 *
 * The stores and screens of [TasteAdjustments] and [BlockedArtistsRepository] stay as they are; this
 * is the one place the brain asks them, so every surface that builds a list reads the same thing.
 * One snapshot answers a whole pool: take it once, off the main thread, and ask it for every song.
 */
class TasteConstraints(
    private val blocked: BlockedArtistIndex,
    private val adjustments: TasteAdjustments,
) {
    /** False for a song the listener muted, blocked, or sent away with "Not for me". */
    fun allows(song: Song): Boolean =
        !blocked.isBlocked(song) && !adjustments.excludes(song) && !adjustments.blocks(song)

    /** `ln(product of the boost and reduce strengths)` of [song], 0 for none (5.11). */
    fun boostLn(song: Song): Double {
        if (adjustments.isEmpty) return 0.0
        val multiplier = adjustments.combinedMultiplier(song)
        return if (multiplier == 1.0 || multiplier <= 0.0) 0.0 else BrainMath.ln(multiplier)
    }
}

@Singleton
class TasteConstraintAdapter @Inject constructor(
    private val blockedArtists: BlockedArtistsRepository,
    private val tasteAdjustments: TasteAdjustmentRepository,
) {
    suspend fun snapshot(nowMs: Long): TasteConstraints =
        TasteConstraints(blockedArtists.current, tasteAdjustments.snapshot(nowMs))

    /** Changes whenever the adjustments or the blocked artists change: what a saved list was built from. */
    fun signature(): String = "${tasteAdjustments.version.value}:${blockedArtists.current.size}"
}
