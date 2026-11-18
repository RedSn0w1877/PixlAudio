package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The saved-mix format (docs/brain-spec.md, 8.2, stage 2a): a mix that can hold tracks from outside
 * the library. It is one `served_list` row; this is what its `itemsBlob` holds.
 *
 * A library track is kept as its song id (the library row is the truth about it, and a song that has
 * left the library is simply dropped when the mix is read back). A track from outside the library
 * carries its parsed radio row, so the mix is complete in itself: it is registered in `playable` from
 * this row whenever it is shown or played, and a restart needs no network and no radio cache.
 */
@Serializable
data class StoredMixItem(
    val rid: Long,
    /** The library song id; null for a track from outside the library, which has [row]. */
    val songId: String? = null,
    val row: StoredRadioRow? = null,
    val arm: Int = Arm.NONE,
    val slot: Int = -1,
    val whyKey: Int = WhyLine.NONE,
    val whyArg: Long = 0L,
    /** The name the reason is about ("Fans of X also play this"); display only. */
    val whyName: String = "",
    val retrQ: Int = -1,
    val radioRank: Int = -1,
) {
    val isOutsideLibrary: Boolean get() = songId == null
}

@Serializable
data class StoredMix(
    /** `MixPlanner.KIND_DAILY` or `KIND_YOUR`. */
    val kind: Int,
    val builtAtMs: Long,
    /** The tie seed the list was drawn with. */
    val seed: Long,
    /** How many times the listener asked for another list that day (0 for the list of the day). */
    val regenerate: Int = 0,
    /** The Familiar / Blend / Discover position it was built with. */
    val novelty: Int = Novelty.BLEND,
    /** What the taste adjustments and blocked artists looked like then: a change rebuilds the day's mix. */
    val tasteSignature: String = "",
    /** How many seed lists the pool had; a mix built with none is built again once radios are cached. */
    val seedLists: Int = 0,
    val items: List<StoredMixItem>,
) {
    val novelCount: Int get() = items.count { it.isOutsideLibrary }
}

/** `served_list.itemsBlob`: gzip over compact JSON. A 60-track mix is about 8 KB. */
object MixCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(mix: StoredMix): ByteArray {
        val out = ByteArrayOutputStream(4096)
        GZIPOutputStream(out).use { it.write(json.encodeToString(StoredMix.serializer(), mix).toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    /** Null for a blob that cannot be read: the caller builds a fresh list. */
    fun decode(blob: ByteArray): StoredMix? {
        if (blob.isEmpty()) return null
        return runCatching {
            val text = GZIPInputStream(ByteArrayInputStream(blob)).use { it.readBytes().toString(Charsets.UTF_8) }
            json.decodeFromString(StoredMix.serializer(), text)
        }.getOrNull()
    }
}
