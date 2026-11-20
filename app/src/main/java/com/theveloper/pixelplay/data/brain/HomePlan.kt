package com.theveloper.pixelplay.data.brain

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.util.AtomicFile
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The Home plan (docs/brain-spec.md, 8.1, stage 2): what Home shows, decided in the background, saved as
 * `files/brain/home_plan.json` (under 50 KB) and read on launch before anything else. Home never waits for the
 * brain: with no plan it shows Jump back in and the library rows.
 *
 * Titles are keys and arguments, never finished text, so the strings stay in the resources (decision D16).
 */
@Serializable
@Immutable
data class PlanItem(
    val rid: Long,
    /** A hash of the folded primary artist name: only for the page rules (an artist at most twice a shelf). */
    val aid: Long = 0L,
    /** The library song id; null for a track from outside the library, which has [row]. */
    val songId: String? = null,
    val row: StoredRadioRow? = null,
    val arm: Int = Arm.NONE,
    val whyKey: Int = WhyLine.NONE,
    val whyName: String = "",
    val slot: Int = -1,
    /** The slot was an exploration slot (5.10): carried into the play's tag. */
    val explore: Boolean = false,
    /** The feature vector the pick was served with (hex, stage 4); empty for none. */
    val sx: String = "",
)

/** A made-for-you list as a card; the list itself is a `served_list` row, read by [MixSpec.of] with [mixId]. */
@Serializable
@Immutable
data class PlanMix(
    val mixId: String,
    val kind: Int,
    /** 1..K for a Daily Mix of an island, else 0. */
    val number: Int = 0,
    val subtitle: String = "",
    val count: Int,
    /** Up to three album arts. */
    val covers: List<String> = emptyList(),
    val builtAtMs: Long = 0L,
)

@Serializable
@Immutable
data class PlanStation(
    /** [StationCard.KIND_GENRE] or [StationCard.KIND_MOOD]. */
    val kind: Int,
    val atlasId: Int,
    val name: String,
    val count: Int,
    val covers: List<String> = emptyList(),
)

@Serializable
@Immutable
data class PlanShelf(
    /** A `HomePlanner` shelf type. */
    val type: Int,
    val id: String,
    /** "because_listened", "new_from_loved", "on_repeat", ...: the key of the title; the sentence is in the resources. */
    val titleKey: String,
    val titleArg: String = "",
    val subtitleKey: String = "",
    val items: List<PlanItem> = emptyList(),
    val mixes: List<PlanMix> = emptyList(),
    val stations: List<PlanStation> = emptyList(),
    val recId: Long = 0L,
    /** The shelf was ranked with the prior as a control (5.12 point 8), decided when its list was built. */
    val control: Boolean = false,
    /** 1 when the learned weights ranked it, 0 for the prior and for a shelf nothing ranked. */
    val model: Int = 0,
)

@Serializable
@Immutable
data class PlanHero(
    /** A `HomePlanner` hero kind. */
    val kind: Int,
    /** For a mix hero: its id. */
    val mixId: String = "",
    /** For a release hero: the track. */
    val item: PlanItem? = null,
    val artistName: String = "",
)

@Serializable
@Immutable
data class HomePlan(
    val planVersion: Int = VERSION,
    val generatedAtMs: Long,
    /** `HomePlanner.planKey`: the local day (04:00) and daypart it was planned for. */
    val planKey: Long,
    /** What the taste adjustments looked like then: a change plans again. */
    val taste: String = "",
    val daypart: Int = 0,
    val weekday: Int = 0,
    val hero: PlanHero? = null,
    /** The made-for-you cards, in order (also the hero's pool). */
    val mixes: List<PlanMix> = emptyList(),
    val shelves: List<PlanShelf> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}

/**
 * Reads and writes `home_plan.json`. One small file, atomic writes, no database: Home reads it before anything else.
 * A file that cannot be read is no plan.
 */
@Singleton
class HomePlanStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val lock = Mutex()
    private val file: AtomicFile by lazy {
        val dir = File(context.filesDir, "brain").also { it.mkdirs() }
        AtomicFile(File(dir, FILE_NAME))
    }

    suspend fun read(): HomePlan? = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val f = File(context.filesDir, "brain/$FILE_NAME")
                if (!f.isFile || f.length() > MAX_BYTES) return@withLock null
                val plan = json.decodeFromString(HomePlan.serializer(), file.readFully().toString(Charsets.UTF_8))
                plan.takeIf { it.planVersion == HomePlan.VERSION }
            } catch (e: Exception) {
                Timber.w(e, "brain: home_plan.json could not be read")
                null
            }
        }
    }

    suspend fun write(plan: HomePlan) = withContext(Dispatchers.IO) {
        lock.withLock {
            var out: java.io.FileOutputStream? = null
            try {
                val bytes = json.encodeToString(HomePlan.serializer(), plan).toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_BYTES) {
                    Timber.w("brain: the Home plan is %d bytes, over the %d limit; not saved", bytes.size, MAX_BYTES)
                    return@withLock
                }
                out = file.startWrite()
                out.write(bytes)
                file.finishWrite(out)
            } catch (e: Exception) {
                out?.let { file.failWrite(it) }
                Timber.w(e, "brain: saving the Home plan failed")
            }
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) { lock.withLock { runCatching { file.delete() } } }

    companion object {
        const val FILE_NAME = "home_plan.json"
        /** The spec asks for under 50 KB. */
        const val MAX_BYTES = 50_000L
    }
}
