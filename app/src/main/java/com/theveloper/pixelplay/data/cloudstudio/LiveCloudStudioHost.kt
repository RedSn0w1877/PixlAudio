package com.theveloper.pixelplay.data.cloudstudio

import android.content.Context
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.cache.RetainedAudioFiles
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.LyricsDocCodec
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import com.theveloper.pixelplay.utils.LyricsUtils
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app around [CloudStudioEngine] (design §7.1 "small hooks", §7.5): the library, the lyrics store, the
 * instrumental store (`tais_stems`) and permanent downloads of streamed songs.
 *
 * - Streamed (Spotify / YouTube) songs are downloaded permanently first, through the same proxy and offline store the
 *   Download button uses (owner decision), so the instrumental lines up with the file that plays from then on.
 * - Results land beside the TAIS renders as `<songId>_cloud_inst.m4a` (or `.flac`), which
 *   [TaisInstrumentalIndex.bestAvailableFile] picks up for Sing and the crossfade, and lyrics are saved through
 *   [LyricsRepository] with source `cloud` (`cloud-ai` for AI-written ones).
 */
class LiveCloudStudioHost(
    private val context: Context,
    private val musicRepository: MusicRepository,
    private val lyricsRepository: LyricsRepository,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyStreamProxy: SpotifyStreamProxy,
    private val spotifyDao: SpotifyDao,
    private val instrumentalIndex: TaisInstrumentalIndex,
    /** `AutomaticStudioManager.noteLyricsUpdated`: the lyrics screen reloads a song it is showing. */
    private val onLyricsUpdated: (String) -> Unit,
    private val scope: CoroutineScope,
) : CloudStudioHost {

    private suspend fun appSong(id: String): Song? = musicRepository.getSong(id).first()

    override suspend fun song(id: String): CloudSong? = appSong(id)?.toCloudSong()

    override suspend fun librarySongs(): List<CloudSong> = musicRepository.getAllSongsOnce().map { it.toCloudSong() }

    override suspend fun audioSource(song: CloudSong): String {
        val appSong = appSong(song.id) ?: throw IOException("This song is no longer in your library.")
        val spotifyId = appSong.spotifyId
        if (spotifyId != null) {
            // The same retries as TAIS's lyric sync: the proxy resolves YouTube on demand and can be slow.
            for (attempt in 1..3) {
                currentCoroutineContext().ensureActive()
                val file = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    audioCacheManager.downloadAndCache(spotifyStreamProxy, spotifyId, isPermanent = true)
                }
                if (file != null) return file.absolutePath
                if (attempt < 3) delay(attempt * 2_000L)
            }
            throw IOException("Couldn't download this song for the cloud. Check your connection; it will try again.")
        }
        return appSong.contentUriString.ifBlank { appSong.path }.ifBlank {
            throw IOException("This song has no audio file on this phone.")
        }
    }

    override suspend fun streamIdentity(song: CloudSong): String? {
        val spotifyId = appSong(song.id)?.spotifyId ?: return null
        return withContext(Dispatchers.IO) { spotifyDao.getMatchedVideoId(spotifyId) }
    }

    override suspend fun lyricsFacts(song: CloudSong): CloudLyricsFacts {
        val appSong = appSong(song.id) ?: return CloudLyricsFacts.NONE
        val stored = runCatching { lyricsRepository.getStoredLyrics(appSong)?.first }.getOrNull()
            ?: appSong.lyrics?.takeIf { it.isNotBlank() }?.let(LyricsUtils::parseLyrics)
        val userSynced = runCatching { lyricsRepository.isUserSynced(appSong) }.getOrDefault(false)
        return facts(stored, userSynced)
    }

    override suspend fun hasInstrumental(songId: String): Boolean = withContext(Dispatchers.IO) {
        TaisInstrumentalIndex.bestAvailableFile(context, songId) != null
    }

    override suspend fun installInstrumental(staged: File, songId: String, flac: Boolean): Unit = withContext(Dispatchers.IO) {
        val directory = TaisInstrumentalIndex.stemsDirectory(context).apply { mkdirs() }
        val destination = File(directory, songId + if (flac) TaisInstrumentalIndex.CLOUD_FLAC_SUFFIX else TaisInstrumentalIndex.CLOUD_M4A_SUFFIX)
        val other = File(directory, songId + if (flac) TaisInstrumentalIndex.CLOUD_M4A_SUFFIX else TaisInstrumentalIndex.CLOUD_FLAC_SUFFIX)
        try {
            RetainedAudioFiles.copyAtomically(staged, destination)
        } finally {
            staged.delete()
        }
        other.delete()
        instrumentalIndex.refresh()
    }

    override suspend fun saveLyrics(doc: LyricsDoc, song: CloudSong, replaceUserSynced: Boolean): CloudLyricsSaveOutcome {
        val appSong = appSong(song.id) ?: return CloudLyricsSaveOutcome.UNUSABLE
        // Right before writing (design §7.5 step 3): the person's own sync, and catalog lyrics that arrived since.
        // Fails closed: if either can't be read, nothing is written now (the engine tries again), so the person's own
        // sync is never replaced by mistake.
        val userSynced = try {
            lyricsRepository.isUserSynced(appSong)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Couldn't read the lyrics stored for this song.")
        }
        val stored = try {
            lyricsRepository.getStoredLyrics(appSong)?.first
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Couldn't read the lyrics stored for this song.")
        }
        // Word timing that doesn't hold together across lines still leaves good line timing: keep that.
        val usable = if (CloudLyrics.isUsable(doc) && LyricsDocCodec.isValid(doc)) doc else {
            doc.copy(lines = doc.lines.map { it.copy(syllables = emptyList()) })
                .takeIf { CloudLyrics.isUsable(it) && LyricsDocCodec.isValid(it) }
                ?: return CloudLyricsSaveOutcome.UNUSABLE
        }
        if (!CloudLyrics.shouldImport(CloudLyrics.level(stored), CloudLyrics.level(usable), userSynced, replaceUserSynced)) {
            return if (userSynced && !replaceUserSynced) CloudLyricsSaveOutcome.KEPT_USER_SYNCED else CloudLyricsSaveOutcome.KEPT_BETTER
        }
        return try {
            lyricsRepository.updateLyrics(appSong, LyricsDocCodec.encode(usable), usable.metadata.source ?: CloudLyrics.SOURCE)
            CloudLyricsSaveOutcome.SAVED
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // A write that failed is tried again later, not counted as unusable lyrics.
            throw IOException("Couldn't save the lyrics.")
        }
    }

    override fun instrumentalImported(songId: String) {
        scope.launch { instrumentalIndex.refresh() }
    }

    /** The lyrics screen reloads when it shows this song (the same signal automatic lyric sync sends). */
    override fun lyricsImported(songId: String) = onLyricsUpdated(songId)

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 90_000L

        /** The facts of stored lyrics (pure; the same rules as the iOS host). */
        fun facts(lyrics: Lyrics?, userSynced: Boolean): CloudLyricsFacts {
            val level = CloudLyrics.level(lyrics)
            val state = when {
                userSynced && level != CloudLyrics.Level.NONE -> CloudLyricsState.USER_SYNCED
                level == CloudLyrics.Level.NONE -> CloudLyricsState.NONE
                level == CloudLyrics.Level.WORD_SYNCED -> CloudLyricsState.WORD_SYNCED
                else -> CloudLyricsState.TEXT_OR_LINE_SYNCED
            }
            val request = CloudLyrics.requestLines(lyrics)
            val reference = lyrics?.document?.metadata?.durationMs?.takeIf { it > 0 }
            return CloudLyricsFacts(state, request?.lines, request?.hasLineTimes ?: false, reference)
        }
    }
}

/** A library song as Cloud Studio sees it. Spotify (and YouTube-matched) songs are the streamed ones. */
fun Song.toCloudSong(): CloudSong {
    val streamed = spotifyId != null
    return CloudSong(
        id = id,
        title = title,
        artist = displayArtist,
        album = album,
        durationMs = duration,
        isStreamed = streamed,
        hasAudioSource = streamed || contentUriString.isNotBlank() || path.isNotBlank(),
    )
}
