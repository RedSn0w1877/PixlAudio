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
    /** Stage 2b: which of the made-for-you lists this is (see [MixSpec.surfaceId]); empty for a list saved by stage 2a. */
    val mixId: String = "",
    /** The island's stable id for a Daily Mix 1..K; 0 otherwise. */
    val islandId: Int = 0,
    /** The island's number, 1..K, in the order of the stable ids; 0 for the other lists. */
    val number: Int = 0,
    /** "Soft pop & R&B · Sabrina Carpenter, Chappell Roan": real genres and artists, never invented; empty when nothing is known. */
    val subtitle: String = "",
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

/** The made-for-you lists (docs/brain-spec.md, 8.2). [MixPlanner.KIND_DAILY], `KIND_YOUR` and `KIND_FIRST_LISTEN` come from the pure core. */
object MixKinds {
    const val DAILY = com.theveloper.pixelplay.brain.MixPlanner.KIND_DAILY
    const val YOUR = com.theveloper.pixelplay.brain.MixPlanner.KIND_YOUR
    const val FIRST_LISTEN = com.theveloper.pixelplay.brain.MixPlanner.KIND_FIRST_LISTEN
    const val JUST_DROPPED = 3
    const val ON_REPEAT = 4
    const val REDISCOVER = 5

    /** Planned by [com.theveloper.pixelplay.brain.StationPlanner]; the others are [com.theveloper.pixelplay.brain.ListMixes]. */
    fun usesPlanner(kind: Int): Boolean = kind == DAILY || kind == YOUR || kind == FIRST_LISTEN
}

/** One list to build or read: its kind and, for a Daily Mix of an island, which island. */
data class MixSpec(val kind: Int, val islandId: Int = 0) {
    /** The `served_list.surfaceId`; "daily" and "your" are what stage 2a wrote. */
    val surfaceId: String
        get() = when (kind) {
            MixKinds.DAILY -> if (islandId > 0) "daily:$islandId" else "daily"
            MixKinds.YOUR -> "your"
            MixKinds.FIRST_LISTEN -> "first"
            MixKinds.JUST_DROPPED -> "dropped"
            MixKinds.ON_REPEAT -> "repeat"
            else -> "rediscover"
        }

    /** The `Surface` code its plays are logged under. */
    val surface: Int
        get() = when (kind) {
            MixKinds.DAILY -> com.theveloper.pixelplay.brain.Surface.DAILY_MIX
            MixKinds.YOUR -> com.theveloper.pixelplay.brain.Surface.YOUR_MIX
            else -> com.theveloper.pixelplay.brain.Surface.NAMED_MIX
        }

    companion object {
        /** The spec of a saved id, or null for one this version does not know. */
        fun of(surfaceId: String): MixSpec? = when {
            surfaceId == "daily" -> MixSpec(MixKinds.DAILY)
            surfaceId.startsWith("daily:") -> surfaceId.substring(6).toIntOrNull()?.let { MixSpec(MixKinds.DAILY, it) }
            surfaceId == "your" -> MixSpec(MixKinds.YOUR)
            surfaceId == "first" -> MixSpec(MixKinds.FIRST_LISTEN)
            surfaceId == "dropped" -> MixSpec(MixKinds.JUST_DROPPED)
            surfaceId == "repeat" -> MixSpec(MixKinds.ON_REPEAT)
            surfaceId == "rediscover" -> MixSpec(MixKinds.REDISCOVER)
            else -> null
        }
    }
}
