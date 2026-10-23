package com.theveloper.pixelplay.data.tais

import android.content.Context
import com.theveloper.pixelplay.data.cache.RetainedAudioFiles
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Where a Sing preview clip sits in its song, in song time: it holds the song's instrumental from [startMs] (inclusive)
 * to [endMs] (exclusive). The clip's own position is the song's minus [startMs].
 */
data class SingClipWindow(val startMs: Long, val endMs: Long) {
    val lengthMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    /** The song is at a point the clip holds. */
    fun covers(songMs: Long): Boolean = songMs >= startMs && songMs < endMs

    /** Where the clip is when the song is at [songMs] (clamped to the clip). */
    fun clipPositionMs(songMs: Long): Long = (songMs - startMs).coerceIn(0, lengthMs)
}

/**
 * Sing preview clips on disk: the cloud's instrumental of about a minute of a song, played until the song's full
 * instrumental arrives ([com.theveloper.pixelplay.data.cloudstudio.CloudStudioEngine]).
 *
 * They are transient on purpose: in the caches (never `tais_stems`, so [TaisInstrumentalIndex] and every "has an
 * instrumental" check ignore them), named `<songId>_clip_<startMs>.flac|m4a`, and deleted when the song's full
 * instrumental is imported, when the song changes, and at app start. A clip the instrumental player is reading is
 * marked in use, so a delete asked for meanwhile waits until the player lets go of it (a seek reopens the file).
 */
object SingClipStore {
    private const val DIRECTORY = "sing_clips"
    private const val MARKER = "_clip_"

    private val inUse: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val doomed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun directory(context: Context): File = File(context.cacheDir, DIRECTORY)

    /** `<songId>_clip_<startMs>.<ext>`: the song by its canonical id, so a Spotify alias and its library row agree. */
    fun fileName(songId: String, startMs: Long, flac: Boolean): String =
        TaisInstrumentalIndex.canonicalSongId(songId) + MARKER + startMs + if (flac) ".flac" else ".m4a"

    /** The song id and start of a clip's file name (pure), or null for any other file. */
    fun parse(name: String): Pair<String, Long>? {
        val stem = name.removeSuffix(".flac").removeSuffix(".m4a").takeIf { it != name } ?: return null
        val at = stem.lastIndexOf(MARKER).takeIf { it > 0 } ?: return null
        val start = stem.substring(at + MARKER.length).toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return stem.substring(0, at) to start
    }

    /** The clip of [songId] that starts at [startMs], if it is on disk. */
    fun find(directory: File, songId: String, startMs: Long): File? =
        sequenceOf(true, false).map { File(directory, fileName(songId, startMs, it)) }.firstOrNull { it.isFile && it.length() > 0 }

    /** Moves a verified clip into place atomically (the staged file always goes). */
    fun install(directory: File, staged: File, songId: String, startMs: Long, flac: Boolean): File {
        directory.mkdirs()
        val destination = File(directory, fileName(songId, startMs, flac))
        try {
            RetainedAudioFiles.copyAtomically(staged, destination)
        } finally {
            staged.delete()
        }
        return destination
    }

    /** Every clip of [songId] goes (one in use goes as soon as the player lets go of it). */
    fun discard(directory: File, songId: String) {
        val canonical = TaisInstrumentalIndex.canonicalSongId(songId)
        directory.listFiles()?.forEach { file ->
            if (parse(file.name)?.first == canonical) delete(file)
        }
    }

    /** App start: nothing plays a clip yet, so every one left from before goes. */
    fun clearAll(directory: File) {
        directory.listFiles()?.forEach { if (it.absolutePath !in inUse) it.delete() }
    }

    /** The instrumental player opened [file]: a delete waits until [release]. */
    fun markInUse(file: File) {
        inUse += file.absolutePath
    }

    /** The player let go of [file]: a delete asked for meanwhile happens now. */
    fun release(file: File) {
        val path = file.absolutePath
        inUse -= path
        if (doomed.remove(path)) file.delete()
    }

    /** Deletes [file] now, or once the player lets go of it. */
    fun delete(file: File) {
        val path = file.absolutePath
        if (path in inUse) doomed += path else file.delete()
    }
}
