package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EventFlags
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.TasteAction
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentMath
import com.theveloper.pixelplay.data.recommendation.TasteAdjustmentRepository
import com.theveloper.pixelplay.data.recommendation.TasteAdjustments
import com.theveloper.pixelplay.data.recommendation.TasteRequest
import com.theveloper.pixelplay.data.recommendation.TasteSetBy
import com.theveloper.pixelplay.data.recommendation.TasteTarget
import com.theveloper.pixelplay.presentation.viewmodel.ListeningStatsTracker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The listener's explicit feedback on one track (docs/brain-spec.md, 8.5, 5.11). "Not for me" is a
 * mute in the existing taste changes, so it shows in the Taste changes screen and is undone there or
 * from the bar that follows it; the play in flight is flagged, which is what gives the recording and
 * its artist their negative in the event log. Nothing here deletes a song or a play.
 */
@Singleton
class BrainFeedback @Inject constructor(
    private val tasteAdjustments: TasteAdjustmentRepository,
    private val tracker: dagger.Lazy<ListeningStatsTracker>,
) {
    class NotForMe(
        /** The id of the mute, for Undo; null when it could not be stored (too many active changes). */
        val adjustmentId: String?,
        /** ONE artist name, for "Less from ..."; blank when the track names nobody. */
        val artistName: String,
    )

    /**
     * Flags the play in flight as "Not for me" when it is [songId]; dropped when another track is
     * playing. Call it BEFORE skipping, so the play that ends carries the flag.
     */
    fun flagNotForMe(songId: String) {
        tracker.get().markFlag(songId, EventFlags.NOT_FOR_ME)
    }

    /** Mutes [song] in everything the app picks by itself. */
    suspend fun mute(song: Song): NotForMe {
        val artist = artistOf(song)
        val outcome = tasteAdjustments.apply(
            listOf(
                TasteRequest(
                    action = TasteAction.MUTE,
                    target = TasteTarget.SONG,
                    key = song.id,
                    altKey = song.spotifyId,
                    label = if (artist.isEmpty()) song.title else "${song.title} - $artist",
                    strength = TasteAdjustmentMath.DEFAULT_STRENGTH,
                    reason = REASON_NOT_FOR_ME,
                    expiresDays = null,
                )
            ),
            TasteSetBy.USER,
        )
        return NotForMe(outcome.changes.firstOrNull { it.changed }?.adjustmentId, artist)
    }

    /** Takes one change back. False when it was already gone. */
    suspend fun undo(adjustmentId: String): Boolean = tasteAdjustments.remove(adjustmentId)

    /** "Less from X": the existing reduce on an artist. Returns the id of the change, or null. */
    suspend fun lessFrom(artistName: String): String? {
        val key = TasteAdjustments.nameKey(artistName)
        if (key.isEmpty()) return null
        val outcome = tasteAdjustments.apply(
            listOf(
                TasteRequest(
                    action = TasteAction.REDUCE,
                    target = TasteTarget.ARTIST,
                    key = key,
                    altKey = null,
                    label = artistName,
                    strength = TasteAdjustmentMath.DEFAULT_STRENGTH,
                    reason = REASON_LESS_FROM,
                    expiresDays = null,
                )
            ),
            TasteSetBy.USER,
        )
        return outcome.changes.firstOrNull { it.changed }?.adjustmentId
    }

    companion object {
        /**
         * ONE artist name for a taste change about [song]: its primary credit. A song rebuilt from a
         * queue item has no credit list, only a display line ("A, B"); its first part is taken, which is
         * the part `TasteAdjustments.artistKeys` matches a reduce against. "" when it names nobody.
         */
        fun artistOf(song: Song): String {
            val credited = (song.artists.firstOrNull { it.isPrimary } ?: song.artists.firstOrNull())?.name
            val name = credited?.takeIf { it.isNotBlank() } ?: song.artist.substringBefore(',').trim()
            return if (name.isBlank() || name == EngineTrack.UNKNOWN_ARTIST) "" else name
        }

        // Shown in the Taste changes screen as the reason of the change.
        const val REASON_NOT_FOR_ME = "Not for me"
        const val REASON_LESS_FROM = "Less from this artist"
    }
}
