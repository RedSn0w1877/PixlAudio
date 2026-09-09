package com.theveloper.pixelplay.data.tais

import android.content.Context
import com.theveloper.pixelplay.data.spotify.SpotifyRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Path

class TaisInstrumentalRetentionTest {
    @TempDir lateinit var directory: Path

    private fun context(): Context = mockk<Context>().also { context ->
        every { context.filesDir } returns directory.resolve("files").toFile().apply { mkdirs() }
        every { context.cacheDir } returns directory.resolve("cache").toFile().apply { mkdirs() }
    }

    private fun wav(file: File, declaredSize: Int = 164): File = file.apply {
        parentFile.mkdirs()
        val bytes = ByteBuffer.allocate(172).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(declaredSize).put("WAVE".toByteArray())
        writeBytes(bytes.array())
    }

    @Test fun `persistent render is found without any legacy cache directory`() {
        val context = context()
        val stem = wav(File(TaisInstrumentalIndex.stemsDirectory(context), "song_instrumental.wav"))
        assertEquals(stem, TaisInstrumentalIndex.bestAvailableFile(context, "song"))
    }

    @Test fun `truncated higher quality render does not hide complete on device render`() {
        val context = context()
        val stem = wav(File(TaisInstrumentalIndex.stemsDirectory(context), "song_instrumental.wav"))
        wav(File(TaisInstrumentalIndex.stemsDirectory(context), "song_hq_roformer_inst.wav"), 4096)
        assertEquals(stem, TaisInstrumentalIndex.bestAvailableFile(context, "song"))
    }

    @Test fun `failed migration keeps original cached render`() = runBlocking {
        val context = context()
        val source = wav(File(context.cacheDir, "tais_stems/song_instrumental.wav"))
        File(TaisInstrumentalIndex.stemsDirectory(context), "song_instrumental.wav").mkdirs()
        val index = TaisInstrumentalIndex(context)
        index.refresh()
        assertTrue(source.isFile)
        assertTrue(TaisInstrumentalIndex.isCompleteStem(source))
        assertTrue("song" in index.instrumentalizedSongIds.value)
    }

    @Test fun `legacy alias render is playable and indexed under unified numeric id`() = runBlocking {
        val context = context()
        val alias = "spotify_retained-track"
        val canonical = SpotifyRepository.unifiedSongId("retained-track").toString()
        wav(File(context.cacheDir, "tais_stems/${alias}_instrumental.wav"))
        val index = TaisInstrumentalIndex(context)
        index.refresh()
        assertTrue(alias in index.instrumentalizedSongIds.value)
        assertTrue(canonical in index.instrumentalizedSongIds.value)
        val playable = TaisInstrumentalIndex.bestAvailableFile(context, canonical)
        assertNotNull(playable)
        assertEquals(TaisInstrumentalIndex.stemsDirectory(context), playable!!.parentFile)
    }

    @Test fun `numeric render remains playable from a saved alias playlist`() = runBlocking {
        val context = context()
        val alias = "spotify_yt_saved-video"
        val canonical = SpotifyRepository.unifiedSongId("yt_saved-video").toString()
        val stem = wav(File(TaisInstrumentalIndex.stemsDirectory(context), "${canonical}_instrumental.wav"))
        val index = TaisInstrumentalIndex(context)
        index.refresh()
        assertEquals(stem, TaisInstrumentalIndex.bestAvailableFile(context, alias))
        assertTrue(TaisInstrumentalIndex.canonicalSongId(alias) in index.instrumentalizedSongIds.value)
        assertEquals("42", TaisInstrumentalIndex.canonicalSongId("42"))
    }

    @Test fun `numeric playback can find legacy alias before first library refresh`() {
        val context = context()
        val canonical = SpotifyRepository.unifiedSongId("early-playback").toString()
        val stem = wav(File(TaisInstrumentalIndex.stemsDirectory(context), "spotify_early-playback_instrumental.wav"))
        assertEquals(stem, TaisInstrumentalIndex.bestAvailableFile(context, canonical))
    }
}
