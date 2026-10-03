package com.theveloper.pixelplay.data.network.lyrics

import android.os.ParcelFileDescriptor
import com.kyant.taglib.TagLib
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Song
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * The ISRC of a song, for exact-recording lyrics lookups (BiniLyrics).
 *
 * - Spotify tracks: `external_ids.isrc`, already stored on the imported row.
 * - Local files: the tag TagLib exposes as `ISRC` (ID3v2 `TSRC`, MP4
 *   `----:com.apple.iTunes:ISRC`, Vorbis/FLAC `ISRC`), read straight from the file without
 *   artwork. Only runs on a lyrics cache miss, so at most once per song per session.
 *
 * Never throws (other than cancellation); a missing or malformed ISRC is `null`.
 */
@Singleton
class SongIsrcResolver @Inject constructor(
    private val spotifyDao: SpotifyDao,
) {
    suspend fun isrcFor(song: Song): String? = withContext(Dispatchers.IO) {
        try {
            song.spotifyId?.takeIf { it.isNotBlank() }?.let { id ->
                BiniLyricsSource.normalizeIsrc(spotifyDao.getSongBySpotifyId(id)?.isrc)?.let { return@withContext it }
            }
            readTagIsrc(song.path)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Timber.tag(TAG).d(t, "No ISRC for %s", song.id)
            null
        }
    }

    private fun readTagIsrc(path: String): String? {
        if (path.isBlank()) return null
        val file = File(path)
        if (!file.isFile || !file.canRead()) return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            // detachFd hands the descriptor to TagLib, which closes it.
            val properties = TagLib.getMetadata(fd.detachFd(), readPictures = false)?.propertyMap
            properties?.get("ISRC")?.firstNotNullOfOrNull { BiniLyricsSource.normalizeIsrc(it) }
        }
    }

    private companion object {
        const val TAG = "SongIsrcResolver"
    }
}
