package com.theveloper.pixelplay.data.cache

import android.content.Context
import androidx.work.WorkManager
import com.theveloper.pixelplay.data.database.SongCacheDao
import com.theveloper.pixelplay.data.database.SongCacheEntity
import com.theveloper.pixelplay.data.spotify.SpotifyStreamProxy
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class OfflineAudioRetentionTest {
    @TempDir lateinit var directory: Path

    private fun fixture(): Pair<AudioCacheManager, ConcurrentHashMap<String, SongCacheEntity>> {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns directory.resolve("files").toFile().apply { mkdirs() }
        every { context.cacheDir } returns directory.resolve("cache").toFile().apply { mkdirs() }
        val rows = ConcurrentHashMap<String, SongCacheEntity>()
        val dao = mockk<SongCacheDao>(relaxed = true)
        coEvery { dao.getAllEntries() } returns emptyList()
        every { dao.getAllCacheStatesFlow() } returns emptyFlow()
        coEvery { dao.get(any()) } answers { rows[firstArg()] }
        coEvery { dao.upsert(any()) } answers { firstArg<SongCacheEntity>().let { rows[it.songId] = it }; Unit }
        coEvery { dao.delete(any()) } answers { rows.remove(firstArg<String>()); Unit }
        return AudioCacheManager(context, dao, mockk<WorkManager>(relaxed = true)) to rows
    }

    @Test fun `promoting an automatic copy really moves it to persistent storage without network`() = runBlocking {
        val (manager, rows) = fixture()
        val source = File(manager.cacheDir, "track.m4a").apply { writeBytes(ByteArray(4096) { 21 }) }
        rows["track"] = SongCacheEntity("track", source.path, source.length(), false, true, 1L, 1L)
        val proxy = mockk<SpotifyStreamProxy>() // No proxy methods may be called on this offline path.
        val result = manager.downloadAndCache(proxy, "track", true)!!
        assertEquals(manager.downloadsDir.canonicalFile, result.parentFile.canonicalFile)
        assertArrayEquals(ByteArray(4096) { 21 }, result.readBytes())
        assertFalse(source.exists())
        assertTrue(rows.getValue("track").isPermanent)
    }

    @Test fun `legacy permanent cache row migrates on offline playback`() = runBlocking {
        val (manager, rows) = fixture()
        val source = File(manager.cacheDir, "legacy.webm").apply { writeBytes(ByteArray(1024) { 9 }) }
        rows["legacy"] = SongCacheEntity("legacy", source.path, source.length(), true, true, 1L, 1L)
        val playable = manager.getPlayableFile("legacy")!!
        assertEquals(manager.downloadsDir.canonicalFile, playable.parentFile.canonicalFile)
        assertEquals(1024L, playable.length())
    }

    @Test fun `automatic cache job never downgrades a retained download`() = runBlocking {
        val (manager, rows) = fixture()
        val source = File(manager.downloadsDir, "track.m4a").apply { writeBytes(ByteArray(1024) { 3 }) }
        rows["track"] = SongCacheEntity("track", source.path, source.length(), true, true, 1L, 1L)
        assertEquals(source.canonicalFile, manager.downloadAndCache(mockk(), "track", false)?.canonicalFile)
        assertTrue(rows.getValue("track").isPermanent)
    }

    @Test fun `missing retained file preserves download intent but is never reported playable`() = runBlocking {
        val (manager, rows) = fixture()
        rows["track"] = SongCacheEntity("track", directory.resolve("missing.m4a").toString(), 100L, true, true, 1L, 1L)
        assertNull(manager.getPlayableFile("track"))
        assertTrue(rows.getValue("track").isPermanent)
        assertFalse(rows.getValue("track").isComplete)
    }

    @Test fun `failed replacement preserves a previously complete retained file`() {
        val destination = directory.resolve("instrumental.wav").toFile().apply { writeText("previous audio") }
        val emptySource = directory.resolve("new.part").toFile().apply { writeBytes(byteArrayOf()) }
        assertThrows(IllegalArgumentException::class.java) { RetainedAudioFiles.copyAtomically(emptySource, destination) }
        assertEquals("previous audio", destination.readText())
    }
}
