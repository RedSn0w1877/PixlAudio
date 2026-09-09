package com.theveloper.pixelplay

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.theveloper.pixelplay.data.model.*
import com.theveloper.pixelplay.data.network.lyrics.LyricsfileParser
import com.theveloper.pixelplay.data.network.lyrics.WordSyncTranspilers
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Device compatibility checks only: no library edits, playback, downloads or account access. */
@RunWith(AndroidJUnit4::class)
class LyricsFormatDeviceTest {
    @Test fun lyricsfilePreservesWordTimesAndTextOnAndroid() {
        val lyrics = LyricsfileParser.parse("""
            version: '1.0'
            lines:
              - text: No surprises
                start_ms: 12000
                words:
                  - text: 'No '
                    start_ms: 12000
                  - text: surprises
                    start_ms: 13500
        """.trimIndent())!!
        assertEquals(listOf(12000,13500),lyrics.synced!!.single().words!!.map { it.time })
        assertEquals("No",lyrics.synced!!.single().words!!.first().word)
    }

    @Test fun yrcAndJsonKeepGapsAndWordEndsOnAndroid() {
        val doc=WordSyncTranspilers.yrc("[12000,1500](12000,500,0)Hi (13000,500,0)there")!!
        val restored=LyricsDocCodec.decode(LyricsDocCodec.encode(doc))!!
        assertEquals(doc,restored)
        assertNull(restored.findActiveLine(11999))
        assertNull(restored.lines.single().currentSyllable(12700))
        assertEquals(12500,restored.toLyrics().synced!!.single().words!!.first().endTime)
    }
    @Test fun liveNeteaseCatalogReturnsWordsForTheReportedRecordings() = kotlinx.coroutines.runBlocking {
        val client=okhttp3.OkHttpClient()
        try {
            val source=com.theveloper.pixelplay.data.network.lyrics.NeteaseLyricsSource(client)
            for (song in listOf(
                Song.emptySong().copy(title="Notion",artist="The Rare Occasions",album="Notion",duration=195120),
                Song.emptySong().copy(title="Like That (feat. Gucci Mane)",artist="Doja Cat, Gucci Mane",album="Hot Pink",duration=163167)
            )) {
                val lyrics=source.find(song)
                assertNotNull("Live catalog miss for ${song.title}",lyrics)
                val doc=lyrics!!.document!!
                assertEquals("NetEase",doc.metadata.source)
                assertTrue("Missing real word times for ${song.title}",doc.lines.sumOf { it.syllables.size }>200)
                assertTrue(doc.lines.flatMap { it.syllables }.all { it.durationMs>0 })
                val restored=LyricsDocCodec.decode(LyricsDocCodec.encode(doc))!!
                assertEquals(doc,restored)
                android.util.Log.i("LyricsFormatDeviceTest", "${song.title}: ${doc.lines.size} lines, ${doc.lines.sumOf { it.syllables.size }} timed fragments, first ${doc.lines.first().startMs}ms")
            }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

    @Test fun liveLrcLibLyricsfileContainsRealWordsOnAndroid() {
        val client=okhttp3.OkHttpClient.Builder().callTimeout(15,java.util.concurrent.TimeUnit.SECONDS).build()
        try {
            client.newCall(okhttp3.Request.Builder().url("https://lrclib.net/api/get/36497565")
                .header("User-Agent","PixelPlay/0.7.6").build()).execute().use { response ->
                assertTrue(response.isSuccessful)
                val data=com.google.gson.JsonParser.parseString(response.body!!.string()).asJsonObject
                val lyrics=LyricsfileParser.parse(data.get("lyricsfile").asString)!!
                assertEquals(190,lyrics.synced!!.sumOf { it.words.orEmpty().size })
                assertEquals(16742,lyrics.synced!!.first { !it.words.isNullOrEmpty() }.words!!.first().time)
                android.util.Log.i("LyricsFormatDeviceTest","LRCLIB 36497565: 190 timed fragments verified on Android")
            }
        } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
    }

}
