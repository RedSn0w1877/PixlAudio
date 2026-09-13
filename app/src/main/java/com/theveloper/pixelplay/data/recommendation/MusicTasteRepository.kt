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
class MusicTasteRepository @Inject constructor(@ApplicationContext context: Context) {
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

    suspend fun signals(): Map<String, MusicRecommendationEngine.Signal> {
        val prefs = data.first()
        return if (prefs[LEARNING] != false) decode(prefs[SIGNALS]) else emptyMap()
    }

    suspend fun record(songId: String, listenedMs: Long, durationMs: Long, voluntary: Boolean, changedTrack: Boolean, timestamp: Long) {
        if (songId.isBlank() || listenedMs < 5_000) return
        store.edit { prefs ->
            if (prefs[LEARNING] == false) return@edit
            val signals = decode(prefs[SIGNALS]).toMutableMap()
            signals[songId] = MusicRecommendationEngine.record(
                signals[songId] ?: MusicRecommendationEngine.Signal(), listenedMs, durationMs, voluntary, changedTrack, timestamp
            )
            prefs[SIGNALS] = gson.toJson(signals.entries.sortedByDescending { it.value.lastPlayedMs }
                .take(5_000).associate { it.key to it.value })
        }
    }

    suspend fun setLearning(enabled: Boolean) { store.edit { it[LEARNING] = enabled } }
    suspend fun setDiscovery(enabled: Boolean) { store.edit { it[DISCOVERY] = enabled } }
    suspend fun setExploration(value: Float) { store.edit { it[EXPLORATION] = value.coerceIn(0f, 0.6f) } }
    suspend fun saveReport(report: String) { store.edit { it[REPORT] = report.take(12_000) } }
    suspend fun resetLearning() { store.edit { it.remove(SIGNALS); it[REPORT] = "Learning reset. Your library and play history are unchanged." } }

    private fun decode(raw: String?): Map<String, MusicRecommendationEngine.Signal> =
        if (raw.isNullOrBlank()) emptyMap() else runCatching {
            gson.fromJson<Map<String, MusicRecommendationEngine.Signal>>(raw, signalType).orEmpty()
        }.getOrDefault(emptyMap())

    private companion object {
        val SIGNALS = stringPreferencesKey("feedback_v1")
        val LEARNING = booleanPreferencesKey("learning_enabled")
        val DISCOVERY = booleanPreferencesKey("discovery_enabled")
        val EXPLORATION = floatPreferencesKey("exploration_fraction")
        val REPORT = stringPreferencesKey("last_report")
    }
}
