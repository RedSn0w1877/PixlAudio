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
) {
    /** The item's brain context, or null when it has none (a track the listener chose, an old snapshot). */
    fun brainTag(): BrainItemTag? {
        val tag = BrainItemTag(
            surface = brainSurface,
            surfaceId = brainSurfaceId,
            recId = brainRecId ?: 0L,
            slot = brainSlot ?: -1,
            arm = brainArm,
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
