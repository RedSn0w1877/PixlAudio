package com.theveloper.pixelplay.data.lyrics.sync

import android.content.Context
import com.theveloper.pixelplay.data.model.Voice
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Unsaved tap-sync sessions, one JSON file per song at
 * `filesDir/lyrics_sync_drafts/{sha1(songId)}.json`, written atomically (temp file, fsync, rename).
 * Drafts are not backed up; they are deleted after a successful save or "Start over" and pruned
 * after 60 days.
 */
@Singleton
class LyricsSyncDraftStore internal constructor(private val directory: File) {

    @Inject
    constructor(@ApplicationContext context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    private val mutex = Mutex()

    fun fileFor(songId: String): File = File(directory, "${sha1(songId)}.json")

    suspend fun load(songId: String): SyncDraft? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val file = fileFor(songId)
            if (!file.isFile) return@withLock null
            val draft = try {
                if (file.length() > MAX_FILE_BYTES) null else decode(file.readText(Charsets.UTF_8))
            } catch (e: IOException) {
                Timber.w(e, "Could not read lyrics sync draft")
                return@withLock null
            }
            if (draft == null || draft.songId != songId) {
                Timber.w("Discarding unreadable lyrics sync draft %s", file.name)
                file.delete()
                null
            } else {
                draft
            }
        }
    }

    /** Returns false when the draft could not be written (the previous file is left intact). */
    suspend fun save(draft: SyncDraft, nowMs: Long = System.currentTimeMillis()): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) return@withLock false
            val target = fileFor(draft.songId)
            var temporary: File? = null
            try {
                val bytes = encode(draft, nowMs).toByteArray(Charsets.UTF_8)
                temporary = File.createTempFile("draft-", ".tmp", directory)
                FileOutputStream(temporary).use { stream ->
                    stream.write(bytes)
                    stream.fd.sync()
                }
                try {
                    Files.move(
                        temporary.toPath(), target.toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
                    )
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
                true
            } catch (e: IOException) {
                Timber.w(e, "Could not write lyrics sync draft")
                false
            } finally {
                temporary?.delete()
            }
        }
    }

    suspend fun delete(songId: String) = withContext(Dispatchers.IO) {
        mutex.withLock { fileFor(songId).delete() }
        Unit
    }

    suspend fun exists(songId: String): Boolean = withContext(Dispatchers.IO) { fileFor(songId).isFile }

    /** Deletes drafts (and stray temp files) older than [maxAgeMs]. Returns how many were removed. */
    suspend fun pruneOlderThan(
        maxAgeMs: Long = MAX_AGE_MS,
        nowMs: Long = System.currentTimeMillis(),
    ): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val files = directory.listFiles() ?: return@withLock 0
            var removed = 0
            for (file in files) {
                if (!file.isFile) continue
                if (!file.name.endsWith(".json") && !file.name.endsWith(".tmp")) continue
                if (nowMs - file.lastModified() > maxAgeMs && file.delete()) removed++
            }
            removed
        }
    }

    companion object {
        const val DIRECTORY_NAME = "lyrics_sync_drafts"
        const val MAX_AGE_MS = 60L * 24 * 60 * 60 * 1_000
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val FORMAT = "pixelplay-lyrics-sync-draft"
        private const val FORMAT_VERSION = 1

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        internal fun encode(draft: SyncDraft, nowMs: Long = System.currentTimeMillis()): String =
            json.encodeToString(
                StoredSyncDraft.serializer(),
                StoredSyncDraft(
                    format = FORMAT,
                    formatVersion = FORMAT_VERSION,
                    songId = draft.songId,
                    title = draft.title,
                    artist = draft.artist,
                    album = draft.album,
                    durationMs = draft.durationMs,
                    cursor = draft.cursor,
                    nudgeMs = draft.nudgeMs,
                    version = draft.version,
                    voices = draft.voices,
                    lines = draft.lines,
                    tokens = draft.tokens,
                    savedAtMs = nowMs,
                ),
            )

        /** Null for anything that isn't a structurally sound draft of a known format version. */
        internal fun decode(raw: String): SyncDraft? {
            val stored = try {
                json.decodeFromString(StoredSyncDraft.serializer(), raw)
            } catch (_: Exception) {
                return null
            }
            if (stored.format != FORMAT || stored.formatVersion != FORMAT_VERSION) return null
            val draft = SyncDraft(
                songId = stored.songId,
                durationMs = stored.durationMs.coerceAtLeast(0L),
                lines = stored.lines.toPersistentList(),
                tokens = stored.tokens.toPersistentList(),
                cursor = stored.cursor,
                voices = stored.voices.ifEmpty { listOf(Voice()) },
                nudgeMs = stored.nudgeMs.coerceIn(-LyricsTapSync.MAX_NUDGE_MS, LyricsTapSync.MAX_NUDGE_MS),
                version = stored.version,
                title = stored.title,
                artist = stored.artist,
                album = stored.album,
            )
            return draft.takeIf(LyricsTapSync::isConsistent)
        }

        internal fun sha1(value: String): String {
            val digest = MessageDigest.getInstance("SHA-1").digest(value.toByteArray(Charsets.UTF_8))
            val hex = "0123456789abcdef"
            return buildString(digest.size * 2) {
                for (byte in digest) {
                    val v = byte.toInt() and 0xFF
                    append(hex[v ushr 4]).append(hex[v and 0x0F])
                }
            }
        }
    }
}

/** On-disk shape of a [SyncDraft]; plain lists instead of persistent ones. */
@Serializable
internal data class StoredSyncDraft(
    val format: String,
    val formatVersion: Int,
    val songId: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0L,
    val cursor: Int = 0,
    val nudgeMs: Int = 0,
    val version: Int = 1,
    val voices: List<Voice> = listOf(Voice()),
    val lines: List<SyncLine>,
    val tokens: List<SyncToken>,
    val savedAtMs: Long = 0L,
)
