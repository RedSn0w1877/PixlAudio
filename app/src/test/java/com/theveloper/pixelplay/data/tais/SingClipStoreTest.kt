package com.theveloper.pixelplay.data.tais

import java.io.File
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SingClipStoreTest {
    @TempDir lateinit var directory: Path

    private fun dir(): File = directory.resolve("sing_clips").toFile().apply { mkdirs() }

    private fun clip(dir: File, songId: String, startMs: Long, flac: Boolean = true): File =
        File(dir, SingClipStore.fileName(songId, startMs, flac)).apply { writeBytes(ByteArray(10) { 1 }) }

    @Test
    fun `clip files are named by song and start, and read back`() {
        assertEquals("42_clip_57000.flac", SingClipStore.fileName("42", 57_000, flac = true))
        assertEquals("42_clip_57000.m4a", SingClipStore.fileName("42", 57_000, flac = false))
        assertEquals("42" to 57_000L, SingClipStore.parse("42_clip_57000.flac"))
        assertEquals("-3000000000123" to 0L, SingClipStore.parse("-3000000000123_clip_0.m4a"))
        // Anything else in the folder is not a clip.
        assertNull(SingClipStore.parse("42_cloud_inst.m4a"))
        assertNull(SingClipStore.parse("42_clip_x.flac"))
        assertNull(SingClipStore.parse("_clip_5.flac"))
        assertNull(SingClipStore.parse("42_clip_5.wav"))
    }

    @Test
    fun `a clip is found by its song and start, never as an instrumental`() {
        val dir = dir()
        clip(dir, "42", 57_000)
        assertEquals("42_clip_57000.flac", SingClipStore.find(dir, "42", 57_000)?.name)
        assertNull(SingClipStore.find(dir, "42", 58_000))
        assertNull(SingClipStore.find(dir, "43", 57_000))
    }

    @Test
    fun `installing moves the staged file into place`() {
        val dir = dir()
        val staged = File(directory.toFile(), "staged.flac").apply { writeBytes(ByteArray(20) { 2 }) }
        val installed = SingClipStore.install(dir, staged, "42", 57_000, flac = true)
        assertEquals(File(dir, "42_clip_57000.flac"), installed)
        assertEquals(20, installed.length())
        assertFalse(staged.exists())
    }

    @Test
    fun `discarding a song's clips spares other songs, and waits for a clip the player is reading`() {
        val dir = dir()
        val playing = clip(dir, "42", 57_000)
        val older = clip(dir, "42", 10_000, flac = false)
        val other = clip(dir, "43", 57_000)
        SingClipStore.markInUse(playing)
        try {
            SingClipStore.discard(dir, "42")
            assertFalse(older.exists())
            assertTrue(other.exists())
            assertTrue(playing.exists(), "still being read")
        } finally {
            SingClipStore.release(playing)
        }
        assertFalse(playing.exists(), "goes as soon as the player lets go")
        // Releasing a clip nobody asked to delete keeps it.
        SingClipStore.markInUse(other)
        SingClipStore.release(other)
        assertTrue(other.exists())
    }

    @Test
    fun `app start clears every clip`() {
        val dir = dir()
        val a = clip(dir, "42", 57_000)
        val b = clip(dir, "43", 0)
        SingClipStore.clearAll(dir)
        assertFalse(a.exists())
        assertFalse(b.exists())
    }
}
