package com.theveloper.pixelplay.data.lyrics.sync

import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class LyricsSyncDraftStoreTest {

    private fun draft(songId: String = "content://media/42"): SyncDraft {
        var d = LyricsTapSync.buildDraft(songId, "Title", "Artist", "Album", 200_000L, null, "Hello world\n君が好き").draft!!
        d = LyricsTapSync.tap(d, 1_000L, 0.75f, 100).draft
        d = LyricsTapSync.tap(d, 1_400L, 1f, 100).draft
        d = LyricsTapSync.release(d, 1, 2_600L, 1f, 100)
        return LyricsTapSync.setNudge(d, -20)
    }

    @Test
    fun saveThenLoadRoundTrips(@TempDir dir: File) = runBlocking<Unit> {
        val store = LyricsSyncDraftStore(dir)
        val original = draft()
        assertTrue(store.save(original))
        assertTrue(store.exists(original.songId))
        assertEquals(original, store.load(original.songId))
        assertNull(store.load("another song"))
        // Only the draft itself is left behind: no temp files.
        assertEquals(listOf(store.fileFor(original.songId).name), dir.listFiles()!!.map { it.name })
    }

    @Test
    fun fileNameIsAHashOfTheSongId(@TempDir dir: File) = runBlocking<Unit> {
        val store = LyricsSyncDraftStore(dir)
        val name = store.fileFor("content://media/42").name
        assertTrue(Regex("^[0-9a-f]{40}\\.json$").matches(name), name)
        assertEquals("a9993e364706816aba3e25717850c26c9cd0d89d", LyricsSyncDraftStore.sha1("abc"))
    }

    @Test
    fun overwritingKeepsOnlyTheLatestDraft(@TempDir dir: File) = runBlocking<Unit> {
        val store = LyricsSyncDraftStore(dir)
        val first = draft()
        store.save(first)
        val second = LyricsTapSync.tap(first, 5_000L, 1f, 100).draft
        store.save(second)
        assertEquals(second, store.load(first.songId))
    }

    @Test
    fun corruptOrForeignFilesAreDiscarded(@TempDir dir: File) = runBlocking<Unit> {
        val store = LyricsSyncDraftStore(dir)
        val original = draft()
        store.fileFor(original.songId).writeText("{ not json")
        assertNull(store.load(original.songId))
        assertFalse(store.fileFor(original.songId).exists())

        // Structurally broken: the line text no longer matches its tokens.
        val broken = LyricsSyncDraftStore.encode(original).replace("\"Hello world\"", "\"Goodbye world\"")
        store.fileFor(original.songId).writeText(broken)
        assertNull(store.load(original.songId))

        // A draft stored under another song's name is not handed out.
        store.fileFor("other").writeText(LyricsSyncDraftStore.encode(original))
        assertNull(store.load("other"))
    }

    @Test
    fun deleteAndPrune(@TempDir dir: File) = runBlocking<Unit> {
        val store = LyricsSyncDraftStore(dir)
        val keep = draft("keep")
        val old = draft("old")
        store.save(keep)
        store.save(old)
        val now = System.currentTimeMillis()
        assertTrue(store.fileFor("old").setLastModified(now - LyricsSyncDraftStore.MAX_AGE_MS - 60_000L))
        assertEquals(1, store.pruneOlderThan(nowMs = now))
        assertNotNull(store.load("keep"))
        assertNull(store.load("old"))

        store.delete("keep")
        assertNull(store.load("keep"))
        assertEquals(0, store.pruneOlderThan(nowMs = now))
    }
}
