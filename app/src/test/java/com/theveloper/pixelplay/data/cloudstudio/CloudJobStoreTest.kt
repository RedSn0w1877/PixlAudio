package com.theveloper.pixelplay.data.cloudstudio

import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CloudJobStoreTest {
    @TempDir lateinit var directory: Path

    @Test fun `jobs round-trip through the file, replacing it in one step`() = runBlocking {
        val store = CloudJobStore(directory.resolve("cloud_studio").toFile())
        assertEquals(emptyList<CloudJobRecord>(), store.load())
        val record = CloudJobRecord(
            jobKey = CloudFixtures.JOB_KEY, songId = "42", title = "Song", tasks = listOf(CloudTask.INSTRUMENTAL, CloudTask.LYRICS),
            lyricsMode = CloudLyricsMode.ALIGN, state = CloudJobState.RESULTS_READY, runpodJobId = "rp-1",
            outputs = mapOf("instrumental" to CloudOutputFile("out/x/instrumental.m4a", 10, "ab", samples = 5)),
            warnings = listOf("w"), createdAtMs = 1,
        )
        store.save(listOf(record))
        store.save(listOf(record, record.copy(jobKey = "00000000-0000-4000-8000-000000000000", songId = "43")))
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals(record, loaded[0])
        val text = File(directory.resolve("cloud_studio").toFile(), CloudJobStore.FILE_NAME).readText()
        assertTrue("\"state\":\"resultsReady\"" in text, text)
        assertTrue("\"tasks\":[\"instrumental\",\"lyrics\"]" in text, text)
    }

    @Test fun `an older or newer file still loads, and an unreadable one is kept aside`() = runBlocking {
        val dir = directory.resolve("cloud_studio").toFile().apply { mkdirs() }
        File(dir, CloudJobStore.FILE_NAME).writeText(
            """[{"jobKey":"${CloudFixtures.JOB_KEY}","songId":"1","state":"submitted","fieldFromTheFuture":true}]"""
        )
        val store = CloudJobStore(dir)
        val loaded = store.load().single()
        assertEquals(CloudJobState.SUBMITTED, loaded.state)
        assertEquals(CloudOutputCodec.AAC, loaded.outputCodec)
        File(dir, CloudJobStore.FILE_NAME).writeText("{not json")
        assertEquals(emptyList<CloudJobRecord>(), store.load())
        assertTrue(dir.listFiles()!!.any { it.name.startsWith("${CloudJobStore.FILE_NAME}.unreadable-") })
        // A second unreadable file is kept too, under its own name (never one over the other).
        Thread.sleep(2)
        File(dir, CloudJobStore.FILE_NAME).writeText("[{]")
        assertEquals(emptyList<CloudJobRecord>(), store.load())
        assertEquals(2, dir.listFiles()!!.count { it.name.startsWith("${CloudJobStore.FILE_NAME}.unreadable-") })
    }

    @Test fun `a file from before the priority lane still loads, with every lane field empty`() = runBlocking {
        val dir = directory.resolve("cloud_studio").toFile().apply { mkdirs() }
        File(dir, CloudJobStore.FILE_NAME).writeText(
            """[{"jobKey":"${CloudFixtures.JOB_KEY}","songId":"1","state":"submitted","runpodJobId":"rp-1","submittedAtMs":5}]"""
        )
        val loaded = CloudJobStore(dir).load().single()
        assertEquals(CloudJobState.SUBMITTED, loaded.state)
        assertEquals(CloudJobOrigin.MANUAL, loaded.origin, "a job without an origin is a batch")
        assertNull(loaded.priorityAtMs)
        assertNull(loaded.pausedByJobKey)
        assertNull(loaded.pausedAtMs)
        assertNull(loaded.pausedNeverStarted)
        assertNull(loaded.pauseIntentRunpodJobId)
        assertFalse(loaded.isPriority)
        assertFalse(loaded.isPaused)
        assertFalse(loaded.hasPauseIntent)
    }

    @Test fun `the lane fields are saved under fixed names and read back`() = runBlocking {
        val store = CloudJobStore(directory.resolve("cloud_studio").toFile())
        val sing = CloudJobRecord(
            jobKey = CloudFixtures.JOB_KEY, songId = "1", origin = CloudJobOrigin.SING, priorityAtMs = 5,
            state = CloudJobState.UPLOADED, createdAtMs = 1,
        )
        val paused = CloudJobRecord(
            jobKey = "00000000-0000-4000-8000-000000000001", songId = "2", origin = CloudJobOrigin.AUTO,
            state = CloudJobState.UPLOADED, submittedAtMs = 3, pausedByJobKey = CloudFixtures.JOB_KEY, pausedAtMs = 7,
            pausedNeverStarted = true, createdAtMs = 2,
        )
        val intent = CloudJobRecord(
            jobKey = "00000000-0000-4000-8000-000000000002", songId = "3", state = CloudJobState.SUBMITTED,
            runpodJobId = "rp-3", pauseIntentRunpodJobId = "rp-3", createdAtMs = 3,
        )
        store.save(listOf(sing, paused, intent))
        assertEquals(listOf(sing, paused, intent), store.load())
        val text = File(directory.resolve("cloud_studio").toFile(), CloudJobStore.FILE_NAME).readText()
        assertTrue("\"origin\":\"sing\"" in text, text)
        assertTrue("\"origin\":\"auto\"" in text, text)
        assertTrue("\"pauseIntentRunpodJobId\":\"rp-3\"" in text, text)
    }

    @Test fun `a file from before preview clips still loads, and no job in it is a clip`() = runBlocking {
        val dir = directory.resolve("cloud_studio").toFile().apply { mkdirs() }
        File(dir, CloudJobStore.FILE_NAME).writeText(
            """[{"jobKey":"${CloudFixtures.JOB_KEY}","songId":"1","state":"uploaded","origin":"sing","priorityAtMs":5}]"""
        )
        val loaded = CloudJobStore(dir).load().single()
        assertNull(loaded.clipStartMs)
        assertNull(loaded.clipEndMs)
        assertNull(loaded.clipOfJobKey)
        assertFalse(loaded.isClip)
        assertTrue(loaded.isPriority)
    }

    @Test fun `the clip fields are saved under fixed names and read back`() = runBlocking {
        val store = CloudJobStore(directory.resolve("cloud_studio").toFile())
        val clip = CloudJobRecord(
            jobKey = CloudFixtures.JOB_KEY, songId = "1", origin = CloudJobOrigin.SING, priorityAtMs = 5,
            tasks = listOf(CloudTask.INSTRUMENTAL), outputCodec = CloudOutputCodec.FLAC,
            clipStartMs = 57_000, clipEndMs = 120_000, clipOfJobKey = "00000000-0000-4000-8000-000000000001", createdAtMs = 1,
        )
        store.save(listOf(clip))
        val loaded = store.load().single()
        assertEquals(clip, loaded)
        assertTrue(loaded.isClip)
        val text = File(directory.resolve("cloud_studio").toFile(), CloudJobStore.FILE_NAME).readText()
        assertTrue("\"clipStartMs\":57000" in text, text)
        assertTrue("\"clipOfJobKey\":\"00000000-0000-4000-8000-000000000001\"" in text, text)
    }
}
