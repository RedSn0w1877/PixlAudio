package com.theveloper.pixelplay.data.brain

import android.content.Context
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.WeeklyNote
import com.theveloper.pixelplay.data.ai.AiHandler
import com.theveloper.pixelplay.data.ai.claude.ClaudeEffort
import com.theveloper.pixelplay.data.ai.claude.ClaudeService
import com.theveloper.pixelplay.data.ai.local.AiRoute
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * The weekly note on First Listen (docs/brain-spec.md, 9.3 and 9.4, stage 4 milestone 2): two or three sentences about this week's list.
 *
 * ONE cloud call a week, over a few evidence rows ([WeeklyNote.Evidence]), through the same door every assistant feature uses
 * ([ClaudeService]: the built-in key, the monthly budget, the connection check) and only on the automatic route ([AiHandler.currentRoute] is
 * Claude, which is the assistant's consent and its switches). Never at app start (it runs when the list is opened), never offline (the
 * call is not even attempted, and not counted as the week's), and when it does not run, or its answer is not clearly true of the evidence
 * ([WeeklyNote.check]), the template is shown instead. The answer is kept for the week, so reopening the list asks for nothing.
 *
 * Nothing about the listener leaves the phone except the evidence rows: counts, the first three artists of the list and one genre name.
 */
@Singleton
class WeeklyNoteRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val claude: ClaudeService,
    private val aiHandler: AiHandler,
    private val trackDnaDao: TrackDnaDao,
    private val atlasProvider: AtlasProvider,
    private val yourSound: YourSoundRepository,
) {
    @Serializable
    private class Stored(
        /** The week's list (its `builtAtMs`): a new list is a new week. */
        val week: Long = 0L,
        /** The cloud call of this week was made (or tried): no second one. */
        val asked: Boolean = false,
        val cloudHash: Long = 0L,
        val cloudText: String = "",
    )

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /** The note to show for this week's First Listen, or null when there is nothing true to say. Off the main thread. */
    suspend fun noteFor(view: MixView): String? = withContext(Dispatchers.IO) {
        try {
            val evidence = evidenceOf(view) ?: return@withContext null
            val template = WeeklyNote.template(evidence)
            if (template.isEmpty()) return@withContext null
            mutex.withLock {
                val hash = WeeklyNote.evidenceHash(evidence)
                var stored = read()?.takeIf { it.week == view.builtAtMs } ?: Stored(week = view.builtAtMs)
                if (stored.cloudText.isNotEmpty() && stored.cloudHash == hash) return@withLock stored.cloudText
                if (!stored.asked && mayAsk()) {
                    // Counted before the call: a crash or a double tap cannot make a second one this week.
                    stored = Stored(view.builtAtMs, asked = true)
                    write(stored)
                    val text = ask(evidence)
                    if (text != null) {
                        stored = Stored(view.builtAtMs, asked = true, cloudHash = hash, cloudText = text)
                        write(stored)
                        return@withLock text
                    }
                }
                template
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.d(e, "brain: the weekly note could not be made")
            null
        }
    }

    /** The assistant's own consent and switches (the automatic route), the monthly budget and a working connection. */
    private suspend fun mayAsk(): Boolean = aiHandler.currentRoute() is AiRoute.Claude && claude.canUseNow()

    private suspend fun ask(e: WeeklyNote.Evidence): String? {
        val answer = try {
            claude.complete(system = SYSTEM, prompt = WeeklyNote.evidenceLines(e), effort = ClaudeEffort.LOW, maxTokens = MAX_TOKENS).text
        } catch (ex: CancellationException) {
            throw ex
        } catch (ex: Exception) {
            Timber.d(ex, "brain: the weekly note's cloud call failed")
            return null
        }
        return WeeklyNote.check(answer, e)
    }

    /** What is known about the list, from the list itself and Your sound's novelty figure; null for an empty list. */
    private suspend fun evidenceOf(view: MixView): WeeklyNote.Evidence? {
        if (view.songs.isEmpty()) return null
        val artists = ArrayList<String>(3)
        for (s in view.songs) {
            val a = BrainFeedback.artistOf(s)
            if (a.isNotEmpty() && a !in artists) artists.add(a)
            if (artists.size == 3) break
        }
        val share = runCatching { yourSound.load().novelty?.permille }.getOrNull()?.let { (it + 5) / 10 } ?: -1
        return WeeklyNote.Evidence(
            tracks = view.songs.size,
            newSongs = view.novelIds.size,
            artists = artists,
            genre = adjacentGenreOf(view),
            island = "",
            newSharePct = share,
        )
    }

    /** The genre most of the list's adjacent-genre picks are tagged with, or "" when none is: nothing is guessed. */
    private suspend fun adjacentGenreOf(view: MixView): String {
        val rids = view.songs.filter { view.reasons[it.id]?.key == ADJACENT_KEY }.mapNotNull { view.rids[it.id] }.distinct()
        if (rids.isEmpty()) return ""
        val atlas = atlasProvider.loadedOrNull() ?: return ""
        val votes = HashMap<Int, Int>()
        for (row in trackDnaDao.getAll(rids)) {
            val v = Tags.decode(row.tags)
            if (v.isEmpty) continue
            val top = v.tags.maxByOrNull { it.q } ?: continue
            votes.merge(top.atlasId, 1, Int::plus)
        }
        val best = votes.entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key }).firstOrNull() ?: return ""
        return atlas.genre(best.key)?.name.orEmpty()
    }

    private fun file(): File = File(context.filesDir, FILE)

    private fun read(): Stored? = runCatching { json.decodeFromString(Stored.serializer(), file().readText()) }.getOrNull()

    private fun write(s: Stored) {
        runCatching { file().writeText(json.encodeToString(Stored.serializer(), s)) }
    }

    companion object {
        const val FILE = "weekly_note.json"
        private const val ADJACENT_KEY = "adjacent_genre"
        private const val MAX_TOKENS = 320L

        /** The instruction. It never names the vendor or the model; the evidence rows follow as the user turn. */
        internal val SYSTEM = """
            You write one short note for the top of a music app's weekly list called First Listen.
            Write two or three plain sentences in the second person, sentence case, at most 400 characters in all.
            Use only the facts in the evidence rows you are given. Do not add any number, name, genre or claim that is not in them.
            Put [[double square brackets]] around every artist name and every genre name you write.
            Do not use lists, headings, emoji or the words learn, learned or learning. Do not mention any company or product.
            If a row is missing, say nothing about it.
        """.trimIndent()
    }
}
