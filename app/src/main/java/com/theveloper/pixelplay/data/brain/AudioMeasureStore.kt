package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.AudioFeatures
import com.theveloper.pixelplay.brain.Flow
import com.theveloper.pixelplay.brain.TrackDna
import com.theveloper.pixelplay.data.brain.store.AudioMeasureDao
import com.theveloper.pixelplay.data.brain.store.AudioMeasureEntity
import com.theveloper.pixelplay.data.brain.store.AudioMeasureLookup
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The stage 5 measurements (`audio_measure`) and the flow facts the planners read (docs/brain-spec.md 3.3, 6.5): tempo, loudness and
 * energy per recording, from the DNA row and the measurement together. A trusted measured tempo wins over the row's; the row's
 * loudness wins (Deezer's `gain`), the measured one fills the gap.
 */
@Singleton
class AudioMeasureStore @Inject constructor(
    private val dao: AudioMeasureDao,
    private val dna: TrackDnaDao,
) : AudioMeasureLookup, FlowFactsSource {

    override suspend fun measured(rid: Long): AudioFeatures.Result? = try {
        withContext(Dispatchers.IO) { dao.get(rid)?.takeIf { it.version == AudioFeatures.VERSION }?.result() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.d(e, "brain: reading the measurement of %d failed", rid)
        null
    }

    suspend fun row(rid: Long): AudioMeasureEntity? = try {
        withContext(Dispatchers.IO) { dao.get(rid) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** True when [rid] should be measured now: never measured, measured by an older estimator, or a failure whose wait is over. */
    suspend fun wants(rid: Long, nowMs: Long): Boolean {
        val r = row(rid) ?: return true
        return AudioMeasurePolicy.wants(r.version, r.failures, r.measuredAtMs, nowMs)
    }

    suspend fun save(row: AudioMeasureEntity) {
        try {
            withContext(Dispatchers.IO) { dao.upsert(row) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: saving the measurement of %d failed", row.rid)
        }
    }

    /** One more failed attempt for [rid]. */
    suspend fun failed(rid: Long, durationMs: Long, nowMs: Long) {
        val old = row(rid)
        save(
            AudioMeasureEntity(
                rid = rid, version = AudioFeatures.VERSION, bpmX10 = 0, tempoConf = 0, lufsX10 = AudioFeatures.LOUDNESS_UNKNOWN,
                onsetX1000 = 0, analysedMs = 0, windowStartMs = 0L, durationMs = durationMs, measuredAtMs = nowMs,
                failures = (old?.takeIf { it.version == AudioFeatures.VERSION }?.failures ?: 0) + 1,
            )
        )
    }

    /**
     * Flow facts for [rids] (6.5): only the recordings something is known about, so an empty map means "no flow" and the planner
     * builds exactly the list it built before stage 5.
     */
    override suspend fun flowFacts(rids: Collection<Long>): Map<Long, Flow.Facts> = try {
        withContext(Dispatchers.IO) {
            val list = rids.filter { it != 0L }.distinct()
            val out = HashMap<Long, Flow.Facts>(list.size * 2)
            val measured = HashMap<Long, AudioFeatures.Result>()
            for (chunk in list.chunked(800)) {
                for (m in dao.getAll(chunk)) {
                    if (m.version == AudioFeatures.VERSION) m.result()?.let { measured[m.rid] = it }
                }
                for (row in dna.getAll(chunk)) {
                    val facts = AudioMeasurePolicy.facts(row.tempoBpmX10, row.tempoSource, row.gainDbX10, row.energy, measured.remove(row.rid))
                    if (facts != null) out[row.rid] = facts
                }
            }
            // A measured track that has no DNA row yet still steps by tempo and loudness.
            for ((rid, m) in measured) AudioMeasurePolicy.facts(0, TrackDna.TEMPO_UNKNOWN, TrackDna.GAIN_UNKNOWN, -1, m)?.let { out[rid] = it }
            out
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "brain: reading flow facts failed; the list goes without")
        emptyMap()
    }
}

/** The pure rules of [AudioMeasureStore] and the measurer, apart for the tests. */
object AudioMeasurePolicy {
    const val RETRY_AFTER_MS = 24L * 60 * 60 * 1000
    const val GIVE_UP_FAILURES = 3
    const val GIVE_UP_MS = 30L * 24 * 60 * 60 * 1000

    fun wants(version: Int, failures: Int, measuredAtMs: Long, nowMs: Long): Boolean = when {
        version != AudioFeatures.VERSION -> true
        failures <= 0 -> false
        failures >= GIVE_UP_FAILURES -> nowMs - measuredAtMs >= GIVE_UP_MS
        else -> nowMs - measuredAtMs >= RETRY_AFTER_MS
    }

    /** What flow reads for one recording; null when nothing is known. */
    fun facts(dnaTempoX10: Int, dnaTempoSource: Int, dnaGainX10: Int, dnaEnergy: Int, measured: AudioFeatures.Result?): Flow.Facts? {
        val deezerTempo = if (dnaTempoSource == TrackDna.TEMPO_ON_DEVICE || dnaTempoSource == TrackDna.TEMPO_ON_DEVICE_WEAK) 0 else dnaTempoX10
        val chosen = AudioFeatures.chooseTempo(deezerTempo, measured)
        val tempo = if (chosen[0] > 0) chosen[0] else dnaTempoX10.coerceAtLeast(0)
        val gain = AudioFeatures.chooseGain(dnaGainX10, measured)[0]
        val energy = if (dnaEnergy in 0..255) dnaEnergy else -1
        if (tempo <= 0 && gain == TrackDna.GAIN_UNKNOWN && energy < 0) return null
        return Flow.Facts(tempo, gain, energy)
    }
}

/** Where the planners get their flow facts (6.5, stage 5); an interface so the tests that build a planner need no database. */
interface FlowFactsSource {
    suspend fun flowFacts(rids: Collection<Long>): Map<Long, Flow.Facts>

    companion object {
        val NONE: FlowFactsSource = object : FlowFactsSource {
            override suspend fun flowFacts(rids: Collection<Long>): Map<Long, Flow.Facts> = emptyMap()
        }
    }
}
