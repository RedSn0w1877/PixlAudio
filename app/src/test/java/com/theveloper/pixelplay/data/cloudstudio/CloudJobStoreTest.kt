package com.theveloper.pixelplay.data.cloudstudio

import java.io.File
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
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
}
