package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.brain.Ranker
import com.theveloper.pixelplay.brain.ServedVector
import com.theveloper.pixelplay.data.brain.store.PlayEventDao
import com.theveloper.pixelplay.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The top factors of a pick (docs/brain-spec.md, 9.3 `explain_track`, 8.8 "Why this song"): which named features of the vector the pick was
 * served with moved its score the most, under the weights it was ranked with. Only the fixed names of [AssistantRules.FACTOR_NAMES] leave
 * this class: no raw weight and no raw value. One place, so the assistant's `explain_track` and the "Why this song" sheet say the same thing.
 *
 * Reads the stored vector and the in-memory model; off the main thread; never throws (a pick with no stored vector has no factors, and the
 * sheet shows none rather than guessing).
 */
@Singleton
class ServedFactors @Inject constructor(
    private val engine: BrainEngine,
    private val clock: BrainClock,
    private val events: PlayEventDao,
    private val identity: TrackResolver,
) {
    /**
     * The factors of [x] (a dequantised served vector). [modelVersion] is what ranked the pick (1 learned, 0 the prior, -1 not recorded =
     * the prior): the learned weights are used only for a pick they ranked, and only while the gate still has them on.
     */
    suspend fun top(x: DoubleArray, modelVersion: Int): List<AssistantRules.Factor> = engine.withState { st ->
        val p0 = Affinity.p0(st.affinity, clock.nowMs())
        val learned = modelVersion == BrainEventRecorder.MODEL_LEARNED && st.learner.useLearned
        AssistantRules.topFactors(x, Ranker.weightsFor(st.learner.model, learned, p0, null))
    }

    /** The factors of the pick [servedHex] (a vector as it travels in the queue), or null when it holds none. */
    suspend fun topOfHex(servedHex: String?, modelVersion: Int): List<AssistantRules.Factor>? {
        val x = ServedVector.dequantize(ServedVector.fromHex(servedHex)) ?: return null
        return runCatching { top(x, modelVersion) }.getOrNull()
    }

    /**
     * The factors of [song] from the newest played event of its recording that stored a vector: for the sheet of a song with no vector of
     * its own in the queue (a played pick). Null when the log holds none.
     */
    suspend fun topOfLastPlay(song: Song): List<AssistantRules.Factor>? = withContext(Dispatchers.IO) {
        try {
            val facts = LibraryTrackCatalog.factsOf(song) ?: return@withContext null
            val rid = identity.resolveTrack(facts.keys, facts.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
            if (rid == 0L) return@withContext null
            val event = events.byRecordingsBlocking(listOf(rid)).filter { it.servedX != null }.maxByOrNull { it.startedAtMs }
                ?: return@withContext null
            val x = ServedVector.dequantize(event.servedX) ?: return@withContext null
            top(x, event.modelVersion)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
