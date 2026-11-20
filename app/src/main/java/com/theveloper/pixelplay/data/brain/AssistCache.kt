package com.theveloper.pixelplay.data.brain

import android.content.Context
import com.theveloper.pixelplay.brain.AssistBatch
import com.theveloper.pixelplay.data.ai.claude.ClaudeConfig
import com.theveloper.pixelplay.data.youtube.MusicVideoKind
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioRow
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What the optional batch jobs ("Let Taizo help the engine", docs/brain-spec.md, 9.4) brought back, cached forever by (model id, prompt
 * version, input hash) in one small file. The jobs themselves are [AssistantHelp]; this holds their validated answers and gives them to the
 * three places that may use them, each only while the switch is on:
 *  - an island's name on Your sound (an answer is used only when the island's genres are still the ones it was written for);
 *  - a song's genres, as tag source 10 (the assembler reads them, the tag merge treats them as the weakest source);
 *  - suggested songs by artists the listener likely does not know, which an assistant mix may draw from (arm ASK).
 *
 * An answer that did not validate is stored as "asked, nothing usable", so the same input is never asked twice. Nothing in the file is
 * ever shown or used unless it passed [AssistBatch]; with the switch off the file is not read.
 */
@Singleton
class AssistCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val switch: BrainSwitch,
) {
    @Serializable
    class IslandAnswer(val name: String = "", val line: String = "")

    @Serializable
    class TagAnswer(val atlasId: Int, val conf: Int)

    @Serializable
    class AskAnswer(
        val videoId: String,
        val title: String,
        val artist: String,
        val album: String = "",
        val year: Int = 0,
        val durationSec: Int = 0,
        val artUrl: String = "",
        val rid: Long = 0L,
    )

    @Serializable
    class Data(
        /** Island answers by [AssistBatch.cacheKey] of the island's own input (hex). */
        val islands: Map<String, IslandAnswer> = emptyMap(),
        /** Tag answers by the key of the track's input (hex); an empty list is "asked, nothing usable". */
        val tags: Map<String, List<TagAnswer>> = emptyMap(),
        /** Suggested songs by the key of the week's input (hex). */
        val ask: Map<String, List<AskAnswer>> = emptyMap(),
        /** The newest ask key: what an assistant mix draws from. */
        val askLatest: String = "",
        /** The recordings whose genres the tagging job wrote, so turning the switch off can take them back out. */
        val tagRids: List<Long> = emptyList(),
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()

    @Volatile
    private var cached: Data? = null

    private fun file(): File = File(context.filesDir, FILE)

    fun read(): Data = cached ?: synchronized(lock) {
        cached ?: (runCatching { json.decodeFromString(Data.serializer(), file().readText()) }.getOrNull() ?: Data()).also { cached = it }
    }

    /** Applies [change] to the data and saves it. Small file, rare writes (the worker's own runs). */
    fun update(change: (Data) -> Data) {
        synchronized(lock) {
            val next = change(read())
            cached = next
            runCatching { file().writeText(json.encodeToString(Data.serializer(), next)) }
        }
    }

    // ---- keys -------------------------------------------------------------------------------------------

    fun keyOf(kind: String, input: String): String = java.lang.Long.toHexString(AssistBatch.cacheKey(ClaudeConfig.MODEL, kind, input))

    // ---- uses -------------------------------------------------------------------------------------------

    /** The assistant-written name of an island whose own genres are [genreNames], or null: the switch is off, nothing validated, or it was written for other genres. */
    suspend fun islandName(islandId: Int, genreNames: List<String>): String? {
        if (genreNames.isEmpty() || !switch.assistHelpAllowed()) return null
        return read().islands[keyOf("island", islandInput(islandId, genreNames))]?.name?.takeIf { it.isNotBlank() }
    }

    /** The tag claims the assistant gave a track whose input was [input], or empty. Not gated: the assembler gates by [tagSourceOn]. */
    fun tagsFor(input: String): List<TagAnswer> = read().tags[keyOf("tag", input)].orEmpty()

    /** The songs the assistant suggested this week, as rows a pool can draw from (arm ASK), when the switch is on. */
    suspend fun suggestedRows(): List<com.theveloper.pixelplay.data.brain.store.StoredRadioRow> {
        if (!switch.assistHelpAllowed()) return emptyList()
        val d = read()
        return d.ask[d.askLatest].orEmpty().filter { it.rid != 0L && it.videoId.isNotBlank() }.map { a ->
            com.theveloper.pixelplay.data.brain.store.StoredRadioRow(
                row = RadioRow(
                    videoId = a.videoId, title = a.title, artists = listOf(RadioArtistRef(a.artist)), album = a.album.ifBlank { null },
                    year = a.year, durationSec = a.durationSec, thumbnailUrl = a.artUrl.ifBlank { null }, kind = MusicVideoKind.ATV,
                ),
                rid = a.rid,
            )
        }
    }

    companion object {
        const val FILE = "assist_cache.json"

        /** The input an island's name is cached under: its stable id and the names of its genres (never its artists, which drift). */
        fun islandInput(islandId: Int, genreNames: List<String>): String = "$islandId|" + genreNames.joinToString("|")
    }
}
