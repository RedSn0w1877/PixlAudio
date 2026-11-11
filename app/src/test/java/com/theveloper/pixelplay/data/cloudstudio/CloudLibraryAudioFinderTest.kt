package com.theveloper.pixelplay.data.cloudstudio

import com.theveloper.pixelplay.data.spotify.StreamSource
import java.io.File
import java.io.IOException
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A Spatial audio job uploads its video's audio, whatever the listening cache (keyed by Spotify id) holds: a song matched
 * to another video after its file was cached must not put the old video's audio into the shared library under the new
 * video's key (cloud-audio-spec §6.2 step 3).
 */
class CloudLibraryAudioFinderTest {
    @TempDir lateinit var dir: Path

    private val spotifyId = "4uLU6hMCjMI75M1A2tKUQC"
    private val oldVideo = "AAAAAAAAAAA"
    private val video = "J7p4bzqLvCw"
    /** The song's match now. */
    private var match: String? = video
    /** What the proxy resolves per video: the stream's length (null: its URL names none). */
    private var streams: Map<String, Long?> = mapOf(video to 5_000L)
    /** The proxy matches an unplayable video again while resolving it. */
    private var rematchTo: String? = null
    private var cached: File? = null
    private var fresh: File? = null
    private var freshCalls = 0
    private var downloadBytes = 5_000
    private var downloads = 0

    private val workDir: File get() = dir.resolve("work").toFile()

    private fun file(name: String, bytes: Int): File = dir.resolve(name).toFile().apply { writeBytes(ByteArray(bytes) { 1 }) }

    private fun finder() = CloudLibraryAudioFinder(
        currentMatch = { match },
        streamSource = { _, wanted ->
            rematchTo?.let { match = it }
            if (match == wanted && streams.containsKey(wanted)) StreamSource(wanted, 251, streams[wanted]) else null
        },
        cachedFile = { cached },
        cacheFresh = {
            freshCalls++
            fresh
        },
        download = { _, destination ->
            downloads++
            destination.writeBytes(ByteArray(downloadBytes) { 2 })
        },
        workDir = workDir,
    )

    private suspend fun failure(block: suspend () -> Unit): Throwable? = try {
        block()
        null
    } catch (error: Throwable) {
        error
    }

    @Test fun `a cached file exactly the length of the video's stream is used as it is`() = runBlocking {
        cached = file("cache.webm", 5_000)
        assertEquals(cached!!.absolutePath, finder().source(spotifyId, video, allowDownload = true))
        assertEquals(0, downloads)
        assertEquals(0, freshCalls)
    }

    @Test fun `the old video's cached file is left alone and the job gets a copy of its own video's stream`() = runBlocking {
        // Cached while the song was still matched to another video: another stream, another length.
        cached = file("cache.webm", 4_200)
        val finder = finder()
        val source = finder.source(spotifyId, video, allowDownload = true)!!
        assertEquals(workDir.absolutePath, File(source).parentFile!!.absolutePath)
        assertEquals(5_000L, File(source).length())
        assertEquals(4_200L, cached!!.length(), "the cached file the person may be playing stays as it is")
        assertEquals(0, freshCalls)
        finder.release(source)
        assertFalse(File(source).exists())
        // A release never deletes anything but a copy of its own.
        finder.release(cached!!.absolutePath)
        assertTrue(cached!!.exists())
        // Without downloads allowed there is nothing provable to send.
        assertNull(finder.source(spotifyId, video, allowDownload = false))
    }

    @Test fun `with nothing cached a fresh copy goes into the listening cache, checked the same way`() = runBlocking {
        fresh = file("fresh.webm", 5_000)
        assertEquals(fresh!!.absolutePath, finder().source(spotifyId, video, allowDownload = true))
        assertEquals(1, freshCalls)
        // A fresh copy that isn't the stream (the proxy served something else meanwhile) is never sent.
        fresh = file("other.webm", 4_999)
        assertTrue(failure { finder().source(spotifyId, video, allowDownload = true) } is IOException)
        // Nor is there one to send without downloads.
        fresh = null
        assertNull(finder().source(spotifyId, video, allowDownload = false))
    }

    @Test fun `a copy that came down at another length is thrown away and tried again later`() = runBlocking {
        cached = file("cache.webm", 4_200)
        downloadBytes = 4_800
        val error = failure { finder().source(spotifyId, video, allowDownload = true) }
        assertTrue(error is IOException, "got $error") // worth another try (a CloudWrongAudioException is not one)
        assertTrue(workDir.listFiles().isNullOrEmpty())
    }

    @Test fun `a stream whose URL names no length is taken as the proxy resolved it for the video`() = runBlocking {
        streams = mapOf(video to null)
        cached = file("cache.webm", 5_000)
        // Nothing proves the cached file is that stream: a copy of its own.
        val source = finder().source(spotifyId, video, allowDownload = true)!!
        assertEquals(workDir.absolutePath, File(source).parentFile!!.absolutePath)
        assertEquals(1, downloads)
    }

    @Test fun `a song that plays another video now stops the job, whatever is cached`() = runBlocking {
        cached = file("cache.webm", 5_000)
        match = oldVideo
        assertTrue(failure { finder().source(spotifyId, video, allowDownload = true) } is CloudWrongAudioException)
        // Matched to the job's video, but resolving it re-matched the song to another (the video turned unplayable).
        match = video
        rematchTo = oldVideo
        assertTrue(failure { finder().source(spotifyId, video, allowDownload = true) } is CloudWrongAudioException)
        // The stream can't be reached right now: that is worth another try.
        match = video
        rematchTo = null
        streams = emptyMap()
        val offline = failure { finder().source(spotifyId, video, allowDownload = true) }
        assertTrue(offline is IOException, "got $offline")
        assertEquals(0, downloads)
    }
}
