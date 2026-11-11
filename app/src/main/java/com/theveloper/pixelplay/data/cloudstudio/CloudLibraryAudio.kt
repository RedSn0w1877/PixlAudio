package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.spotify.StreamSource
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Where a Spatial audio job's upload comes from (cloud-audio-spec §6.2 step 3): audio proven to be the job's YouTube
 * video. The render made from it is stored under that video in the shared library, and every device that plays the video
 * downloads it; a render of another video has another intro and length, so its swaps would land in the wrong place.
 *
 * The listening cache is keyed by Spotify id, and a song's match can change after its file was cached (the dashboard's
 * Test playback, a catalog play), so a cached file may be another video's audio. Hence:
 * - the stream proxy is asked for the job video's stream ([StreamSource]: resolved for that video, a stale URL of another
 *   one dropped first); a song that resolves to another video now stops the job ([CloudWrongAudioException]);
 * - a cached file is used when its length is exactly that stream's (the URL's `clen`): it is that stream, byte for byte;
 * - with no cached file, a fresh copy goes into the listening cache, as before, checked the same way;
 * - a cached file that isn't provably that stream (another video's, or another format of the same one) is left as it is
 *   (the person may be playing it) and a copy of the stream is made for the job alone in [workDir], let go with
 *   [release] once the upload is prepared.
 *
 * On Pro / Studio Pro the proxy serves SpotiFLAC's file (the song's recording, found by its ISRC) instead of the video's
 * audio, and that file is the render's source ([StreamSource.isSpotiFLAC]; Hoa, 2026-11-11). It is proven the same way
 * by its length, known once the proxy served it. A phone playing the video's audio instead won't switch to such a render
 * mid-song when the lengths differ (`SpatialRenditionRule.linesUp`); it starts on it from the next play.
 */
class CloudLibraryAudioFinder(
    /** The video the song plays now (its match), or null. */
    private val currentMatch: suspend (spotifyId: String) -> String?,
    /** The proxy's stream for the song while it is the video's; null when it isn't now, or can't be resolved. */
    private val streamSource: suspend (spotifyId: String, videoId: String) -> StreamSource?,
    /** A complete copy already on the phone (listening cache or Downloads), or null. */
    private val cachedFile: suspend (spotifyId: String) -> File?,
    /** A fresh copy into the listening cache (never a permanent download), or null when it couldn't be fetched. */
    private val cacheFresh: suspend (spotifyId: String) -> File?,
    /** Fetches the proxy's stream for the song into the given file; throws when it couldn't. */
    private val download: suspend (spotifyId: String, destination: File) -> Unit,
    private val workDir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    /** The path to prepare from, or null when [allowDownload] is false and nothing on the phone is provably the video. */
    suspend fun source(spotifyId: String, videoId: String, allowDownload: Boolean): String? {
        if (currentMatch(spotifyId) != videoId) throw CloudWrongAudioException(MATCH_CHANGED)
        val stream = streamFor(spotifyId, videoId)
        val cached = cachedFile(spotifyId)
        if (cached != null) {
            if (stream.contentLength != null && cached.length() == stream.contentLength) return cached.absolutePath
            return if (allowDownload) ownCopy(spotifyId, videoId) else null
        }
        if (!allowDownload) return null
        val fresh = cacheFresh(spotifyId) ?: throw IOException(COULD_NOT_FETCH)
        if (!isStream(fresh, streamFor(spotifyId, videoId))) throw IOException(CHANGED)
        return fresh.absolutePath
    }

    /** The job prepared its upload from [source]: a copy made for it alone is deleted (anything else stays). */
    fun release(source: String) {
        val file = File(source)
        if (file.parentFile?.absolutePath == workDir.absolutePath) file.delete()
    }

    private suspend fun streamFor(spotifyId: String, videoId: String): StreamSource {
        streamSource(spotifyId, videoId)?.let { return it }
        if (currentMatch(spotifyId) != videoId) throw CloudWrongAudioException(MATCH_CHANGED)
        throw IOException(COULD_NOT_REACH)
    }

    /** A file fetched through the proxy for the video is its stream when the lengths agree (or the URL names none). */
    private fun isStream(file: File, stream: StreamSource): Boolean =
        file.isFile && (stream.contentLength == null || file.length() == stream.contentLength)

    private suspend fun ownCopy(spotifyId: String, videoId: String): String {
        workDir.mkdirs()
        clearLeftovers()
        // Two profiles of one song may be prepared at once: each job has a copy of its own.
        val target = File(workDir, "$spotifyId-${UUID.randomUUID()}$SUFFIX")
        try {
            download(spotifyId, target)
            // Checked against the stream as the proxy holds it now (a URL refreshed before the first byte may be another
            // format of the same video); a match that changed meanwhile stops the job.
            if (!isStream(target, streamFor(spotifyId, videoId))) throw IOException(CHANGED)
            return target.absolutePath
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    /** Copies a stopped process left behind (each job lets go of its own as soon as it is prepared). */
    private fun clearLeftovers() {
        val cutoff = nowMs() - LEFTOVER_AFTER_MS
        workDir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    companion object {
        const val MATCH_CHANGED = "This song now plays a different YouTube video than the one the render was asked for."
        private const val COULD_NOT_REACH = "Couldn't reach this song's stream to check its audio. It will try again."
        private const val COULD_NOT_FETCH = "Couldn't download this song for the cloud. Check your connection; it will try again."
        private const val CHANGED = "This song's stream changed while it was fetched. It will try again."
        private const val SUFFIX = ".audio"
        private const val LEFTOVER_AFTER_MS = 60L * 60_000
    }
}
