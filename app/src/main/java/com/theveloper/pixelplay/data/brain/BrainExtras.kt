package com.theveloper.pixelplay.data.brain

import android.os.Bundle
import com.theveloper.pixelplay.brain.Arm

/**
 * The context a queue item carries about where it came from (docs/brain-spec.md, 4.2). It travels in
 * the `MediaItem`'s metadata extras, so the service, the queue UI and the event recorder all read the
 * same thing, and it is saved with the queue (see `PlaybackQueueItemSnapshot`), so it survives a
 * process restart.
 *
 * An item with a [arm] is engine-owned: it sits under the Autoplay line, and the engine may reorder or
 * replace it. An item without one was chosen by the listener and is never touched.
 */
data class BrainItemTag(
    /** `Surface` name: "autoplay", "station", ... */
    val surface: String? = null,
    /** Shelf id, mix id, hashed playlist id, or a station's seed rid. 40 characters at most. */
    val surfaceId: String? = null,
    /** The list this pick was part of; 0 when the play was not a recommendation. */
    val recId: Long = 0L,
    /** Position in that list; -1 when the play was not a recommendation. */
    val slot: Int = -1,
    /** `Arm` name: "RADIO", "FAV", ...; null for a track the listener chose. */
    val arm: String? = null,
    /** The reason key of the pick (`WhyLine.nameOf`), for the line under the row. */
    val whyKey: String? = null,
    /** The name the reason is about ("Fans of X also play this"); display only. */
    val whyArg: String? = null,
    /** ONE artist name: the item's primary artist. */
    val primaryArtist: String? = null,
    /**
     * The listener put this pick in the queue themselves (they played a Home row): it has an [arm],
     * because it was a recommendation, but it is theirs like a playlist's track, not the engine's tail.
     */
    val chosen: Boolean = false,
    /** Retrieval percentile x 1000; -1 unknown. */
    val retrQ: Int = -1,
    /** Best radio rank; -1 for none. */
    val radioRank: Int = -1,
) {
    val isEngineOwned: Boolean get() = !chosen && arm != null && Arm.codeOf(arm) != Arm.NONE

    val isEmpty: Boolean
        get() = surface == null && surfaceId == null && recId == 0L && slot < 0 && arm == null &&
            whyKey == null && whyArg == null && primaryArtist == null && !chosen && retrQ < 0 && radioRank < 0
}

object BrainExtras {
    const val SURFACE = "brain.surface"
    const val SURFACE_ID = "brain.surfaceId"
    const val REC_ID = "brain.recId"
    const val SLOT = "brain.slot"
    const val ARM = "brain.arm"
    const val RETR_Q = "brain.retrQ"
    const val RADIO_RANK = "brain.radioRank"

    /** The name of the artist the pick is explained by ("Fans of X also play this"); display only. */
    const val WHY_ARG = "brain.whyArg"

    /** ONE artist name: the item's primary artist, so the recorder never has to split a display string. */
    const val PRIMARY_ARTIST = "brain.primaryArtist"

    /** The reason key of the pick (`WhyLine.nameOf`), for the line under an engine row. */
    const val WHY_KEY = "brain.whyKey"

    /** True on a pick the listener queued themselves (a Home row's card): see [BrainItemTag.chosen]. */
    const val CHOSEN = "brain.chosen"

    const val MAX_SURFACE_ID_LENGTH = 40

    fun read(extras: Bundle?): BrainItemTag? {
        extras ?: return null
        val tag = BrainItemTag(
            surface = extras.getString(SURFACE),
            surfaceId = extras.getString(SURFACE_ID),
            recId = extras.getLong(REC_ID, 0L),
            slot = extras.getInt(SLOT, -1),
            arm = extras.getString(ARM),
            whyKey = extras.getString(WHY_KEY),
            whyArg = extras.getString(WHY_ARG),
            primaryArtist = extras.getString(PRIMARY_ARTIST),
            chosen = extras.getBoolean(CHOSEN, false),
            retrQ = extras.getInt(RETR_Q, -1),
            radioRank = extras.getInt(RADIO_RANK, -1),
        )
        return tag.takeUnless { it.isEmpty }
    }

    fun write(extras: Bundle, tag: BrainItemTag?) {
        tag ?: return
        tag.surface?.let { extras.putString(SURFACE, it) }
        tag.surfaceId?.let { extras.putString(SURFACE_ID, it.take(MAX_SURFACE_ID_LENGTH)) }
        if (tag.recId != 0L) extras.putLong(REC_ID, tag.recId)
        if (tag.slot >= 0) extras.putInt(SLOT, tag.slot)
        tag.arm?.let { extras.putString(ARM, it) }
        tag.whyKey?.takeIf { it.isNotEmpty() }?.let { extras.putString(WHY_KEY, it) }
        tag.whyArg?.takeIf { it.isNotEmpty() }?.let { extras.putString(WHY_ARG, it) }
        tag.primaryArtist?.takeIf { it.isNotEmpty() }?.let { extras.putString(PRIMARY_ARTIST, it) }
        if (tag.chosen) extras.putBoolean(CHOSEN, true)
        if (tag.retrQ >= 0) extras.putInt(RETR_Q, tag.retrQ)
        if (tag.radioRank >= 0) extras.putInt(RADIO_RANK, tag.radioRank)
    }

    /**
     * True when the item was put in the queue by the engine (it sits under the Autoplay line, and the
     * engine may reorder or replace it). A pick the listener queued themselves is not.
     */
    fun isEngineOwned(extras: Bundle?): Boolean {
        val arm = extras?.getString(ARM) ?: return false
        return Arm.codeOf(arm) != Arm.NONE && !extras.getBoolean(CHOSEN, false)
    }
}
