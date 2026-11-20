package com.theveloper.pixelplay.data.model

import com.theveloper.pixelplay.data.brain.BrainItemTag
import kotlinx.serialization.Serializable

@Serializable
data class PlaybackQueueItemSnapshot(
    val mediaId: String,
    val uri: String,
    val title: String? = null,
    val artist: String? = null,
    val albumTitle: String? = null,
    val artworkUri: String? = null,
    val durationMs: Long? = null,
    // Where the item came from (docs/brain-spec.md, 4.2). Optional with defaults, so a queue saved by an
    // earlier version decodes unchanged, and a queue with no engine items encodes exactly as before.
    val brainSurface: String? = null,
    val brainSurfaceId: String? = null,
    val brainRecId: Long? = null,
    val brainSlot: Int? = null,
    val brainArm: String? = null,
    // The reason line and the rest of an engine pick's context (8.8), so a restored queue still says why.
    val brainWhyKey: String? = null,
    val brainWhyArg: String? = null,
    val brainPrimaryArtist: String? = null,
    val brainChosen: Boolean? = null,
    val brainRetrQ: Int? = null,
    val brainRadioRank: Int? = null,
    // How the list was ranked, fixed when it was built (5.12 point 8): the control flag and the model version.
    val brainControl: Boolean? = null,
    val brainModel: Int? = null,
    val brainExplore: Boolean? = null,
    // The feature vector the pick was served with (hex, stage 4), so a restored queue still trains the learner on what the ranker saw.
    val brainServedX: String? = null,
) {
    /** The item's brain context, or null when it has none (a track the listener chose, an old snapshot). */
    fun brainTag(): BrainItemTag? {
        val tag = BrainItemTag(
            surface = brainSurface,
            surfaceId = brainSurfaceId,
            recId = brainRecId ?: 0L,
            slot = brainSlot ?: -1,
            arm = brainArm,
            whyKey = brainWhyKey,
            whyArg = brainWhyArg,
            primaryArtist = brainPrimaryArtist,
            chosen = brainChosen == true,
            retrQ = brainRetrQ ?: -1,
            radioRank = brainRadioRank ?: -1,
            control = brainControl == true,
            modelVersion = brainModel ?: -1,
            explore = brainExplore == true,
            servedX = brainServedX,
        )
        return tag.takeUnless { it.isEmpty }
    }

    fun withBrainTag(tag: BrainItemTag?): PlaybackQueueItemSnapshot =
        if (tag == null || tag.isEmpty) this else copy(
            brainSurface = tag.surface,
            brainSurfaceId = tag.surfaceId,
            brainRecId = tag.recId.takeIf { it != 0L },
            brainSlot = tag.slot.takeIf { it >= 0 },
            brainArm = tag.arm,
            brainWhyKey = tag.whyKey,
            brainWhyArg = tag.whyArg,
            brainPrimaryArtist = tag.primaryArtist,
            brainChosen = if (tag.chosen) true else null,
            brainRetrQ = tag.retrQ.takeIf { it >= 0 },
            brainRadioRank = tag.radioRank.takeIf { it >= 0 },
            brainControl = if (tag.control) true else null,
            brainModel = tag.modelVersion.takeIf { it >= 0 },
            brainExplore = if (tag.explore) true else null,
            brainServedX = tag.servedX,
        )
}

@Serializable
data class PlaybackQueueSnapshot(
    val items: List<PlaybackQueueItemSnapshot>,
    val currentMediaId: String? = null,
    val currentIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val playWhenReady: Boolean = false,
    val repeatMode: Int = 0,
    val shuffleEnabled: Boolean = false,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
)
