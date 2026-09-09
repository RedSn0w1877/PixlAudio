package com.theveloper.pixelplay.data.network.lyrics

import com.theveloper.pixelplay.data.model.Song
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class NeteaseLyricsSourceTest {
    private val song = Song.emptySong().copy(title="Example",artist="Singer",album="Album",duration=180000)
    private fun client(replies: (Request) -> Pair<Int,String>) = OkHttpClient.Builder().addInterceptor { chain ->
        val (code,body)=replies(chain.request())
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Fixture")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun skipsWrongFirstSearchHitAndPersistsRealWordEnds() = runBlocking {
        val requested = mutableListOf<String?>()
        val http=client { request ->
            if (request.url.encodedPath.endsWith("search/get")) 200 to """{"code":200,"result":{"songs":[
                {"id":1,"name":"Example (Live)","artists":[{"name":"Singer"}],"album":{"name":"Album"},"duration":180000},
                {"id":2,"name":"Example","artists":[{"name":"Singer"}],"album":{"name":"Album"},"duration":180000}]}}"""
            else {
                requested += request.url.queryParameter("id")
                200 to """{"code":200,"yrc":{"lyric":"[12000,1500](12000,500,0)Hi (13000,500,0)there"}}"""
            }
        }
        val lyrics=NeteaseLyricsSource(http).find(song)!!
        assertEquals(listOf("2"),requested)
        assertEquals(12500,lyrics.synced!!.first().words!!.first().endTime)
        assertEquals("NetEase",lyrics.document!!.metadata.source)
        http.dispatcher.executorService.shutdown()
    }

    @Test fun opaqueRegionalSearchAndHttpFailuresAreCleanMisses() = runBlocking {
        for (reply in listOf(200 to """{"code":200,"result":"opaque"}""",403 to "blocked",200 to "not json")) {
            val http=client { reply }
            assertNull(NeteaseLyricsSource(http).find(song))
            http.dispatcher.executorService.shutdown()
        }
    }
    @Test fun featuredCreditsMatchAcrossCatalogFieldsButVersionsStillDoNot() {
        val featured=song.copy(title="Example (feat. Guest)",artist="Singer, Guest")
        fun track(title:String="Example",artists:String="""{"name":"Singer"},{"name":"Guest"}""") =
            com.google.gson.JsonParser.parseString("""{"name":"$title","artists":[$artists],"album":{"name":"Album"},"duration":180000}""").asJsonObject
        assertTrue(NeteaseLyricsSource.matchesRecording(featured,track()))
        assertFalse(NeteaseLyricsSource.matchesRecording(featured,track(title="Example (Live)")))
        assertFalse(NeteaseLyricsSource.matchesRecording(featured,track(artists="""{"name":"Singer"}""")))
        assertTrue(NeteaseLyricsSource.matchesRecording(song.copy(artist="Earth, Wind & Fire"),
            track(artists="""{"name":"Earth, Wind & Fire"}""")))
    }

}
