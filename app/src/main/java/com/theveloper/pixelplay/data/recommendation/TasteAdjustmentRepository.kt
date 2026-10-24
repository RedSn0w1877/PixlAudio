package com.theveloper.pixelplay.data.recommendation

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Its own small file: the explicit taste adjustments never ride along with the per-play feedback store. */
private val Context.tasteAdjustmentStore by preferencesDataStore("taste_adjustments")

/**
 * The persisted explicit taste adjustments (see [TasteAdjustments]). Kept in a DataStore as one JSON
 * array rather than a Room table: it is tiny, needs no schema migration, and a reset is one write.
 */
@Singleton
class TasteAdjustmentRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.tasteAdjustmentStore
    private val data = store.data.catch { if (it is IOException) emit(emptyPreferences()) else throw it }
    private val writeLock = Mutex()
    @Volatile private var decoded: Pair<String, List<TasteAdjustment>>? = null

    private val _version = MutableStateFlow(0L)

    /** Bumps on every change (and starts at 0): what caches built from the adjustments key on. */
    val version: StateFlow<Long> = _version.asStateFlow()

    /** The adjustments that have not expired, newest first, for the Settings list. */
    val activeFlow: Flow<List<TasteAdjustment>> = data
        .map { prefs -> decode(prefs[KEY]).filter { it.isActive(System.currentTimeMillis()) }.sortedByDescending { it.createdAtMs } }
        .flowOn(Dispatchers.IO)

    /** Active adjustments, newest first. */
    suspend fun list(nowMs: Long = System.currentTimeMillis()): List<TasteAdjustment> =
        decode(data.first()[KEY]).filter { it.isActive(nowMs) }.sortedByDescending { it.createdAtMs }

    /** The snapshot the engine reads. [TasteAdjustments.NONE] when nothing is adjusted. */
    suspend fun snapshot(nowMs: Long = System.currentTimeMillis()): TasteAdjustments =
        TasteAdjustments.of(decode(data.first()[KEY]), nowMs)

    suspend fun apply(requests: List<TasteRequest>, setBy: TasteSetBy): TasteOutcome = writeLock.withLock {
        val now = System.currentTimeMillis()
        val outcome = TasteAdjustmentLogic.apply(
            current = decode(data.first()[KEY]),
            requests = requests,
            nowMs = now,
            setBy = setBy,
            newId = { newId() },
        )
        if (outcome.changed) write(outcome.adjustments)
        outcome
    }

    /** Removes one adjustment by id. False when there is no such (active) adjustment. */
    suspend fun remove(id: String): Boolean = writeLock.withLock {
        val now = System.currentTimeMillis()
        val current = decode(data.first()[KEY]).filter { it.isActive(now) }
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return@withLock false
        write(next)
        true
    }

    /** Removes every adjustment. Returns how many were active. */
    suspend fun clear(): Int = writeLock.withLock {
        val now = System.currentTimeMillis()
        val count = decode(data.first()[KEY]).count { it.isActive(now) }
        store.edit { it.remove(KEY) }
        decoded = null
        _version.value = _version.value + 1
        count
    }

    private suspend fun write(list: List<TasteAdjustment>) {
        val encoded = TasteAdjustmentCodec.encode(list)
        store.edit { it[KEY] = encoded }
        decoded = encoded to list
        _version.value = _version.value + 1
    }

    private fun decode(raw: String?): List<TasteAdjustment> {
        if (raw.isNullOrBlank()) return emptyList()
        decoded?.let { (cachedRaw, cached) -> if (cachedRaw == raw) return cached }
        val parsed = TasteAdjustmentCodec.decode(raw)
        decoded = raw to parsed
        return parsed
    }

    private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(8)

    private companion object {
        val KEY = stringPreferencesKey("adjustments_v1")
    }
}
