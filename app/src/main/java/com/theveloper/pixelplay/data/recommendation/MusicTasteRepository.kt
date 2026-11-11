package com.theveloper.pixelplay.data.recommendation

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.musicTasteStore by preferencesDataStore("music_intelligence")

/**
 * What listening history imported from Spotify / YouTube Music adds to learning (see `data.history`), kept in
 * its own file: [MusicTasteRepository.record] rewrites the live store on every track change and must not drag
 * this (possibly large) snapshot along each time.
 */
private val Context.importedTasteStore by preferencesDataStore("music_intelligence_imported")

/**
 * The imported contribution, already weighted (Spotify 1.0, YouTube Music 0.35) and time-decayed. Persisted with
 * Gson by field name (kept by R8, see proguard-rules.pro).
 */
data class ImportedTasteSnapshot(
    val signals: Map<String, MusicRecommendationEngine.Signal> = emptyMap(),
    val history: Map<String, MusicRecommendationEngine.History> = emptyMap(),
    val artistPriors: Map<String, Double> = emptyMap(),
    val builtAtMs: Long = 0L
)

data class MusicIntelligenceState(
    val learningEnabled: Boolean = true,
    val discoveryEnabled: Boolean = true,
    val exploration: Float = 0.25f,
    val learnedSongs: Int = 0,
    val sessions: Int = 0,
    val completions: Int = 0,
    val skips: Int = 0,
    val lastReport: String = "No recommendation refresh yet."
)

/** Dedicated durable DataStore: survives cache cleanup; only playback feedback is retained locally. */
@Singleton
class MusicTasteRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val store = context.musicTasteStore
    private val gson = Gson()
    private val signalType = object : TypeToken<Map<String, MusicRecommendationEngine.Signal>>() {}.type
    private val data = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }

    val state = data.map { prefs ->
        val signals = decode(prefs[SIGNALS])
        MusicIntelligenceState(
            learningEnabled = prefs[LEARNING] ?: true,
            discoveryEnabled = prefs[DISCOVERY] ?: true,
            exploration = prefs[EXPLORATION] ?: 0.25f,
            learnedSongs = signals.size,
            sessions = signals.values.sumOf { it.sessions },
            completions = signals.values.sumOf { it.completions },
            skips = signals.values.sumOf { it.earlySkips },
            lastReport = prefs[REPORT] ?: "No recommendation refresh yet."
        )
    }.flowOn(Dispatchers.IO)

    /**
     * Live playback feedback plus the imported history's sessions/completions/skips for the same songs
     * (empty while learning is off).
     */
    suspend fun signals(): Map<String, MusicRecommendationEngine.Signal> {
        val prefs = data.first()
        if (prefs[LEARNING] == false) return emptyMap()
        val live = decode(prefs[SIGNALS])
        val imported = importedTaste().signals
        if (imported.isEmpty()) return live
        if (live.isEmpty()) return imported
        val merged = HashMap<String, MusicRecommendationEngine.Signal>(live.size + imported.size)
        merged.putAll(live)
        // Imported entries are keyed by the library row id; live feedback for a streamed song may sit under its
        // `spotify_` alias instead. Fold such an alias into the row id so the two add up rather than one hiding
        // the other (readers look the row id up first).
        for ((key, signal) in live) {
            val canonical = com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex.canonicalSongId(key)
            if (canonical != key && canonical in imported) {
                merged.remove(key)
                val existing = merged[canonical]
                merged[canonical] = if (existing == null) signal else mergeSignal(existing, signal)
            }
        }
        for ((songId, signal) in imported) {
            val existing = merged[songId]
            merged[songId] = if (existing == null) signal else mergeSignal(existing, signal)
        }
        return merged
    }

    /** The playback feedback recorded in this app only, without imported history. */
    suspend fun liveSignals(): Map<String, MusicRecommendationEngine.Signal> {
        val prefs = data.first()
        return if (prefs[LEARNING] != false) decode(prefs[SIGNALS]) else emptyMap()
    }

    // ─── Imported listening history ────────────────────────────────────

    private val importedStore = context.importedTasteStore
    private val importedData = importedStore.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    @Volatile private var importedCache: Pair<String, ImportedTasteSnapshot>? = null

    suspend fun importedTaste(): ImportedTasteSnapshot {
        val raw = importedData.first()[IMPORTED] ?: return ImportedTasteSnapshot()
        importedCache?.let { (cachedRaw, cached) -> if (cachedRaw == raw) return cached }
        val parsed = runCatching { gson.fromJson(raw, ImportedTasteSnapshot::class.java) }.getOrNull()
            ?.let { sanitize(it) } ?: ImportedTasteSnapshot()
        importedCache = raw to parsed
        return parsed
    }

    /** Play counts and last plays from imported history, by library song id (merged into engagement by readers). */
    suspend fun importedHistory(): Map<String, MusicRecommendationEngine.History> = importedTaste().history

    /** Imported artist affinity (normalised name → -1..1), including artists with no song in the library. */
    suspend fun importedArtistPriors(): Map<String, Double> = importedTaste().artistPriors

    suspend fun saveImportedTaste(snapshot: ImportedTasteSnapshot) {
        val encoded = gson.toJson(snapshot)
        importedStore.edit { it[IMPORTED] = encoded }
        importedCache = encoded to snapshot
    }

    suspend fun clearImportedTaste() {
        importedStore.edit { it.remove(IMPORTED) }
        importedCache = null
    }

    /** Gson leaves missing fields null even when Kotlin says they cannot be. */
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    private fun sanitize(snapshot: ImportedTasteSnapshot): ImportedTasteSnapshot = ImportedTasteSnapshot(
        signals = snapshot.signals?.filterValues { it != null } ?: emptyMap(),
        history = snapshot.history?.filterValues { it != null } ?: emptyMap(),
        artistPriors = snapshot.artistPriors?.filterValues { it != null } ?: emptyMap(),
        builtAtMs = snapshot.builtAtMs
    )

    private fun mergeSignal(a: MusicRecommendationEngine.Signal, b: MusicRecommendationEngine.Signal) =
        MusicRecommendationEngine.Signal(
            sessions = (a.sessions.toLong() + b.sessions).coerceAtMost(1_000_000L).toInt(),
            completions = (a.completions.toLong() + b.completions).coerceAtMost(1_000_000L).toInt(),
            earlySkips = (a.earlySkips.toLong() + b.earlySkips).coerceAtMost(1_000_000L).toInt(),
            voluntaryPlays = (a.voluntaryPlays.toLong() + b.voluntaryPlays).coerceAtMost(1_000_000L).toInt(),
            lastPlayedMs = maxOf(a.lastPlayedMs, b.lastPlayedMs),
            listenedMs = a.listenedMs + b.listenedMs
        )

    suspend fun record(songId: String, listenedMs: Long, durationMs: Long, voluntary: Boolean, changedTrack: Boolean, timestamp: Long) {
        if (songId.isBlank() || listenedMs < 5_000) return
        store.edit { prefs ->
            if (prefs[LEARNING] == false) return@edit
            val signals = decode(prefs[SIGNALS]).toMutableMap()
            signals[songId] = MusicRecommendationEngine.record(
                signals[songId] ?: MusicRecommendationEngine.Signal(), listenedMs, durationMs, voluntary, changedTrack, timestamp
            )
            // Only a full table needs the recency sort; below the cap nothing is dropped.
            val kept = if (signals.size <= MAX_SIGNALS) signals else {
                signals.entries.sortedByDescending { it.value.lastPlayedMs }
                    .take(MAX_SIGNALS).associate { it.key to it.value }
            }
            val encoded = gson.toJson(kept)
            prefs[SIGNALS] = encoded
            // This process just wrote it: the next read (every track change, every Home refresh)
            // reuses the decoded map instead of parsing up to 5,000 entries again.
            decodedCache = encoded to kept
        }
    }

    /** Learning from listening and the Home mixes both on. They default on; this repairs a stored "off". */
    suspend fun ensureLearningOn() { store.edit { it[LEARNING] = true; it[DISCOVERY] = true } }

    suspend fun setLearning(enabled: Boolean) { store.edit { it[LEARNING] = enabled } }
    suspend fun setDiscovery(enabled: Boolean) { store.edit { it[DISCOVERY] = enabled } }
    suspend fun setExploration(value: Float) { store.edit { it[EXPLORATION] = value.coerceIn(0f, 0.6f) } }
    suspend fun saveReport(report: String) { store.edit { it[REPORT] = report.take(12_000) } }
    suspend fun resetLearning() { store.edit { it.remove(SIGNALS); it[REPORT] = "Learning reset. Your library and play history are unchanged." } }

    @Volatile private var decodedCache: Pair<String, Map<String, MusicRecommendationEngine.Signal>>? = null

    private fun decode(raw: String?): Map<String, MusicRecommendationEngine.Signal> {
        if (raw.isNullOrBlank()) return emptyMap()
        decodedCache?.let { (cachedRaw, cachedMap) -> if (cachedRaw == raw) return cachedMap }
        val parsed = runCatching {
            gson.fromJson<Map<String, MusicRecommendationEngine.Signal>>(raw, signalType).orEmpty()
        }.getOrDefault(emptyMap())
        decodedCache = raw to parsed
        return parsed
    }

    private companion object {
        const val MAX_SIGNALS = 5_000
        val SIGNALS = stringPreferencesKey("feedback_v1")
        val LEARNING = booleanPreferencesKey("learning_enabled")
        val DISCOVERY = booleanPreferencesKey("discovery_enabled")
        val EXPLORATION = floatPreferencesKey("exploration_fraction")
        val REPORT = stringPreferencesKey("last_report")
        val IMPORTED = stringPreferencesKey("imported_taste_v1")
    }
}
