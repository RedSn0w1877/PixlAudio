package com.theveloper.pixelplay.data.cloudstudio

import android.content.Context
import com.theveloper.pixelplay.data.cache.AudioCacheManager
import com.theveloper.pixelplay.data.cache.AudioDownloadTransfer
import com.theveloper.pixelplay.data.cache.RetainedAudioFiles
import com.theveloper.pixelplay.data.database.SpotifyDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import com.theveloper.pixelplay.data.tais.SingClipStore
import com.theveloper.pixelplay.data.tais.TaisInstrumentalIndex
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
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
import okhttp3.OkHttpClient

/**
 * The app around [CloudStudioEngine] (design §7.1 "small hooks", §7.5): the library, the instrumental store
 * (`tais_stems`) and permanent downloads of streamed songs.
 *
 * - Streamed (Spotify / YouTube) songs are downloaded permanently first, through the same proxy and offline store the
 *   Download button uses (owner decision), so the instrumental lines up with the file that plays from then on.
 * - Results land beside the TAIS renders as `<songId>_cloud_inst.m4a` (or `.flac`), which
 *   [TaisInstrumentalIndex.bestAvailableFile] picks up for Sing and the crossfade. The cloud no longer times lyrics
 *   (2026-10-23), so nothing here touches the lyrics store.
 */
class LiveCloudStudioHost(
    private val context: Context,
    private val musicRepository: MusicRepository,
    private val audioCacheManager: AudioCacheManager,
    private val spotifyStreamProxy: SpotifyStreamProxy,
    private val spotifyDao: SpotifyDao,
    private val instrumentalIndex: TaisInstrumentalIndex,
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

    override suspend fun cachedAudioSource(song: CloudSong): String? {
        val appSong = appSong(song.id) ?: return null
        val spotifyId = appSong.spotifyId
        if (spotifyId == null) return appSong.contentUriString.ifBlank { appSong.path }.takeIf { it.isNotBlank() }
        // Only a complete file already on the phone. Calling downloadAndCache after that check makes it permanent
        // (so it can't be evicted while it uploads) without any network: a cached file is reused, not fetched again.
        if (audioCacheManager.getPlayableFile(spotifyId) == null) return null
        return audioCacheManager.downloadAndCache(spotifyStreamProxy, spotifyId, isPermanent = true)?.absolutePath
    }

    override suspend fun streamIdentity(song: CloudSong): String? {
        val spotifyId = appSong(song.id)?.spotifyId ?: return null
        return withContext(Dispatchers.IO) { spotifyDao.getMatchedVideoId(spotifyId) }
    }

    /**
     * A Spatial audio job's audio, proven to be [videoId]'s ([CloudLibraryAudioFinder]): the complete copy on the phone
     * when its length is exactly that video's stream, else (when allowed) a fresh copy: into the listening cache when
     * the song has none, or a copy for the job alone when its cached file may be another video's (the match changed
     * after it was cached). Never a permanent download: a render is keyed by the video and lined up by media time, so
     * the song's file doesn't have to stay, and the background queue's songs must not pile up in Downloads.
     */
    override suspend fun libraryAudioSource(song: CloudSong, videoId: String, allowDownload: Boolean): String? {
        val appSong = appSong(song.id) ?: return null
        val spotifyId = appSong.spotifyId
            ?: return appSong.contentUriString.ifBlank { appSong.path }.takeIf { it.isNotBlank() }
        return libraryAudio.source(spotifyId, videoId, allowDownload)
    }

    override fun releaseLibraryAudioSource(source: String) = libraryAudio.release(source)

    /** Built on first use (a library job's prepare, off the main thread): it touches the cache directory. */
    private val libraryAudio by lazy {
        CloudLibraryAudioFinder(
            currentMatch = { spotifyId -> withContext(Dispatchers.IO) { spotifyDao.getMatchedVideoId(spotifyId) } },
            streamSource = { spotifyId, videoId -> spotifyStreamProxy.streamSourceFor(spotifyId, videoId) },
            cachedFile = { spotifyId -> audioCacheManager.getPlayableFile(spotifyId) },
            cacheFresh = { spotifyId -> freshCacheCopy(spotifyId) },
            download = { spotifyId, destination -> downloadFromProxy(spotifyId, destination) },
            workDir = File(context.cacheDir, LIBRARY_AUDIO_DIR),
        )
    }

    /** A fresh copy into the listening cache (the proxy's stream as it resolves now), or null when it couldn't be had. */
    private suspend fun freshCacheCopy(spotifyId: String): File? {
        for (attempt in 1..3) {
            currentCoroutineContext().ensureActive()
            val file = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                audioCacheManager.downloadAndCache(spotifyStreamProxy, spotifyId, isPermanent = false)
            }
            if (file != null) return file
            if (attempt < 3) delay(attempt * 2_000L)
        }
        return null
    }

    /** The proxy's stream for the song into [destination] (outside the cache: nothing else ever reads that file). */
    private suspend fun downloadFromProxy(spotifyId: String, destination: File) {
        var lastError: Exception? = null
        for (attempt in 1..3) {
            currentCoroutineContext().ensureActive()
            try {
                if (!spotifyStreamProxy.ensureReady()) throw IOException("The stream proxy isn't running.")
                val url = spotifyStreamProxy.getProxyUrl(spotifyId)
                if (url.isBlank()) throw IOException("The stream proxy isn't running.")
                val done = withTimeoutOrNull(DOWNLOAD_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { AudioDownloadTransfer.fetch(downloadClient, url, destination) }
                    true
                }
                if (done == true) return
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                lastError = error
            }
            destination.delete()
            if (attempt < 3) delay(attempt * 2_000L)
        }
        throw IOException("Couldn't download this song for the cloud. Check your connection; it will try again.", lastError)
    }

    /** A plain client for the local proxy (the app's default one rewrites the User-Agent; nothing here goes upstream). */
    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.MINUTES)
            .build()
    }

    override suspend fun streamItag(song: CloudSong): Int? {
        val spotifyId = appSong(song.id)?.spotifyId ?: return null
        return spotifyStreamProxy.cachedStreamItag(spotifyId)
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

    override fun instrumentalImported(songId: String) {
        scope.launch { instrumentalIndex.refresh() }
    }

    /** A Sing's preview clip goes to the caches ([SingClipStore]), never beside the instrumentals. */
    override suspend fun installClip(staged: File, songId: String, clipStartMs: Long, flac: Boolean): Unit =
        withContext(Dispatchers.IO) {
            SingClipStore.install(SingClipStore.directory(context), staged, songId, clipStartMs, flac)
            Unit
        }

    override suspend fun discardClips(songId: String): Unit = withContext(Dispatchers.IO) {
        SingClipStore.discard(SingClipStore.directory(context), songId)
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 90_000L
        /** Under the caches: a library job's own copy of a song's stream while its upload is prepared. */
        private const val LIBRARY_AUDIO_DIR = "cloud_library_audio"
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
