package com.theveloper.pixelplay.data.cache

import android.content.Context
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.database.SongCacheDao
import com.theveloper.pixelplay.data.database.SongCacheEntity
import com.theveloper.pixelplay.data.database.SongCacheState
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.worker.SongDownloadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Temporary listening cache and explicitly retained, offline audio are separate storage tiers. */
@Singleton
class AudioCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songCacheDao: SongCacheDao,
    private val workManager: WorkManager
) {
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutationLock = Mutex()
    private val downloadLocks = ConcurrentHashMap<String, Mutex>()
    private val removalPreferences = context.getSharedPreferences("offline_download_removals", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.MINUTES)
        .build()

    val cacheDir: File by lazy { File(context.cacheDir, "audio_cache").apply { mkdirs() } }
    val downloadsDir: File by lazy { File(context.filesDir, "downloads").apply { mkdirs() } }

    init {
        managerScope.launch {
            songCacheDao.getAllEntries().forEach { entry ->
                // Release between files so a large first-upgrade migration cannot block
                // playback until the entire downloaded library has been copied.
                mutationLock.withLock {
                    try {
                        songCacheDao.get(entry.songId)?.let { repairEntryLocked(it) }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Timber.w(error, "Could not repair offline audio for %s", entry.songId)
                    }
                }
                kotlinx.coroutines.yield()
            }
            songCacheDao.getAllCompleteFlow().collect { rows ->
                SongCacheStateCache.update(rows.associate {
                    it.songId to if (it.isPermanent) SongCacheState.DOWNLOADED else SongCacheState.CACHED
                })
            }
        }
    }

    /** Checked before the network, including on the first offline launch. */
    suspend fun getPlayableFile(songId: String): File? = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            val entity = songCacheDao.get(songId) ?: return@withLock null
            val repaired = repairEntryLocked(entity) ?: return@withLock null
            if (!repaired.isComplete) return@withLock null
            songCacheDao.touch(songId, System.currentTimeMillis())
            File(repaired.filePath)
        }
    }

    fun maybeAutoCache(songId: String) {
        managerScope.launch {
            if (getPlayableFile(songId) != null) return@launch
            SongDownloadWorker.enqueue(workManager, songId, isPermanent = false)
        }
    }

    /** Save the user's intent first, so an older automatic job cannot downgrade the download. */
    fun requestDownload(songId: String) {
        managerScope.launch {
            val ready = mutationLock.withLock {
                val repaired = songCacheDao.get(songId)?.let { repairEntryLocked(it) }
                if (repaired?.isComplete == true) return@withLock retainLocked(repaired) != null
                val now = System.currentTimeMillis()
                songCacheDao.upsert((repaired ?: SongCacheEntity(
                    songId, "", 0L, true, false, now, now
                )).copy(isPermanent = true, isComplete = false))
                false
            }
            if (!ready) SongDownloadWorker.enqueue(workManager, songId, isPermanent = true)
        }
    }

    fun removeDownload(songId: String) {
        managerScope.launch {
            // Cancelling a WorkManager dependency would also cancel unrelated downloads behind
            // it in the shared queue. A timestamp lets just this song's older jobs become no-ops.
            removalPreferences.edit().putLong(songId, System.currentTimeMillis()).commit()
            downloadLocks.getOrPut(songId) { Mutex() }.withLock {
                mutationLock.withLock mutation@ {
                    val entity = songCacheDao.get(songId) ?: return@mutation
                    if (!entity.isPermanent) return@mutation
                    val file = File(entity.filePath)
                    if (file.isFile && !file.delete()) {
                        Timber.w("Could not remove offline audio for %s", songId)
                        return@mutation
                    }
                    songCacheDao.delete(songId)
                }
            }
        }
    }

    fun wasRemovedAfter(songId: String, enqueuedAt: Long): Boolean =
        removalPreferences.getLong(songId, 0L) > enqueuedAt

    /** Reuse/promote a complete file before starting the proxy; rendering therefore also works offline. */
    suspend fun downloadAndCache(
        spotifyStreamProxy: SpotifyStreamProxy,
        songId: String,
        isPermanent: Boolean
    ): File? = withContext(Dispatchers.IO) {
        downloadLocks.getOrPut(songId) { Mutex() }.withLock download@ {
            val local = mutationLock.withLock {
                val existing = songCacheDao.get(songId)?.let { repairEntryLocked(it) }
                if (existing?.isComplete == true) {
                    if (isPermanent || existing.isPermanent) retainLocked(existing)
                    else File(existing.filePath)
                } else {
                    if (isPermanent && existing?.isPermanent != true) {
                        val now = System.currentTimeMillis()
                        songCacheDao.upsert(SongCacheEntity(songId, "", 0L, true, false, now, now))
                    }
                    null
                }
            }
            if (local != null) return@download local
            if (!spotifyStreamProxy.ensureReady()) return@download null
            val proxyUrl = spotifyStreamProxy.getProxyUrl(songId)
            if (proxyUrl.isBlank()) return@download null
            val pending = try {
                File.createTempFile("download_", ".part", downloadsDir)
            } catch (error: java.io.IOException) {
                Timber.w(error, "Not enough writable storage for offline audio")
                return@download null
            }
            try {
                val contentType = AudioDownloadTransfer.fetch(client, proxyUrl, pending)
                currentCoroutineContext().ensureActive()
                mutationLock.withLock {
                    val previous = songCacheDao.get(songId)
                    val permanent = isPermanent || previous?.isPermanent == true
                    val target = File(if (permanent) downloadsDir else cacheDir, "$songId${extensionFor(contentType)}")
                    if (target.parentFile == pending.parentFile) RetainedAudioFiles.publish(pending, target)
                    else RetainedAudioFiles.copyAtomically(pending, target)
                    val now = System.currentTimeMillis()
                    songCacheDao.upsert(SongCacheEntity(
                        songId, target.absolutePath, target.length(), permanent, true,
                        previous?.createdAt ?: now, now
                    ))
                    if (!permanent) evictIfNeededLocked(excludingSongId = songId)
                    target
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Timber.w(error, "Could not save offline audio for %s", songId)
                null
            } finally {
                pending.delete()
            }
        }
    }

    /** Preserve a missing permanent row's intent/path; an external volume may only be temporarily unavailable. */
    private suspend fun repairEntryLocked(entity: SongCacheEntity): SongCacheEntity? {
        val file = File(entity.filePath)
        val valid = file.isFile && file.length() > 0L &&
            (entity.sizeBytes <= 0L || file.length() == entity.sizeBytes)
        if (!valid) {
            if (entity.isPermanent) {
                val unavailable = entity.copy(isComplete = false)
                if (entity.isComplete) songCacheDao.upsert(unavailable)
                return unavailable
            }
            songCacheDao.delete(entity.songId)
            return null
        }
        val restored = if (!entity.isComplete) entity.copy(isComplete = true) else entity
        if (restored != entity) songCacheDao.upsert(restored)
        if (restored.isPermanent && file.parentFile?.canonicalFile != downloadsDir.canonicalFile) {
            retainLocked(restored)?.let { return songCacheDao.get(entity.songId) }
        }
        return restored
    }

    /** Copy, fsync, publish, update the row, and only then remove the old cache/external-files copy. */
    private suspend fun retainLocked(entity: SongCacheEntity): File? {
        val source = File(entity.filePath)
        if (!source.isFile || source.length() == 0L) return null
        return try {
            val target = File(downloadsDir, "${entity.songId}.${source.extension.ifBlank { "m4a" }}")
            RetainedAudioFiles.copyAtomically(source, target)
            songCacheDao.upsert(entity.copy(
                filePath = target.absolutePath, sizeBytes = target.length(), isPermanent = true, isComplete = true
            ))
            if (source.canonicalFile != target.canonicalFile) source.delete()
            target
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Timber.w(error, "Could not retain cached audio for %s", entity.songId)
            songCacheDao.upsert(entity.copy(isPermanent = true))
            null
        }
    }

    private suspend fun evictIfNeededLocked(excludingSongId: String) {
        var total = songCacheDao.getAutoCacheTotalBytes()
        if (total <= MAX_AUTO_CACHE_BYTES) return
        for (entry in songCacheDao.getAutoCacheEntriesOldestFirst()) {
            if (total <= MAX_AUTO_CACHE_BYTES) break
            if (entry.songId == excludingSongId) continue
            val current = songCacheDao.get(entry.songId) ?: continue
            if (current.isPermanent) continue
            val file = File(current.filePath)
            if (file.exists() && !file.delete()) continue
            songCacheDao.delete(entry.songId)
            total -= current.sizeBytes
        }
    }

    private fun extensionFor(contentType: String?): String = when {
        contentType?.contains("webm", ignoreCase = true) == true -> ".webm"
        contentType?.contains("ogg", ignoreCase = true) == true -> ".ogg"
        contentType?.contains("mpeg", ignoreCase = true) == true -> ".mp3"
        else -> ".m4a"
    }

    companion object {
        private const val MAX_AUTO_CACHE_BYTES = 1_000_000_000L
    }
}

