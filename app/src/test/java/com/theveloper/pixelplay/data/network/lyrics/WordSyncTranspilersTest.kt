package com.theveloper.pixelplay.data.network.lyrics

import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.model.*
import com.theveloper.pixelplay.utils.LyricsUtils
import org.junit.Assert.*
import org.junit.Test

class WordSyncTranspilersTest {
    private val yrc = "[12000,2500](12000,400,0)Hel(12400,600,0)lo (13500,1000,0)there"

    @Test fun yrcUsesAbsoluteTimesAndPreservesSyllableJoinsAndGaps() {
        val doc = WordSyncTranspilers.yrc(yrc)!!
        val line = doc.lines.single()
        assertEquals("Hello there", line.text)
        assertEquals(listOf(12000L,12400L,13500L), line.syllables.map { it.startMs })
        assertNull(line.currentSyllable(13000))
        assertNull(doc.findActiveLine(11999))
        assertNull(doc.findActiveLine(14500))
        val legacy = doc.toLyrics().synced!!.single()
        assertEquals(listOf(true,false,true), legacy.words!!.map { it.startsNewWord })
        assertEquals(12400, legacy.words!!.first().endTime)
    }

    @Test fun richsyncOffsetsBecomeAbsoluteAndLastFragmentEndsAtLineEnd() {
        val doc = WordSyncTranspilers.richSync("""[{"ts":12.5,"te":14.0,"x":"Hi there","l":[{"c":"Hi ","o":0.0},{"c":"there","o":0.5}]}]""")!!
        assertEquals(listOf(12500L,13000L),doc.lines.single().syllables.map { it.startMs })
        assertEquals(1000L,doc.lines.single().syllables.last().durationMs)
    }

    @Test fun richsyncAllowsLineOnlyAndRejectsInvalidOffsetsAndTextLoss() {
        assertTrue(WordSyncTranspilers.richSync("""[{"ts":1,"te":2,"x":"hello"}]""")!!.lines.single().syllables.isEmpty())
        assertNull(WordSyncTranspilers.richSync("""[{"ts":1,"te":2,"x":"hello","l":[{"c":"hello","o":-1}]}]"""))
        assertNull(WordSyncTranspilers.richSync("""[{"ts":1,"te":2,"x":"full line","l":[{"c":"half","o":0}]}]"""))
    }

    @Test fun invalidYrcNeverBecomesGuessedTiming() {
        listOf("[100,300](50,100,0)early", "[100,300](100,999,0)long",
            "[100,300](100,-1,0)bad", "[100,300](<100,200,0>)wrong").forEach {
            assertNull(it,WordSyncTranspilers.yrc(it))
        }
    }

    @Test fun documentRoundTripRetainsRolesOverlapAndExclusiveEnds() {
        val doc = LyricsDoc(voices = listOf(Voice(),Voice("back","background"),Voice("guest","duet")),
            lines = listOf(TimedLine(1000,3000,"Lead"),TimedLine(1500,2200,"Echo","back"),TimedLine(2000,3500,"Guest","guest")))
        val restored = LyricsDocCodec.decode(LyricsDocCodec.encode(doc))!!
        assertEquals(doc,restored)
        assertEquals(3, restored.activeLines(2100).size)
        assertEquals("Lead",restored.findActiveLine(2100)?.text)
        assertEquals(2,restored.activeLines(2200).size)
        assertNull(restored.findActiveLine(3500))
        assertEquals("background",LyricsUtils.parseLyrics(LyricsDocCodec.encode(doc)).synced!![1].voiceRole)
    }

    @Test fun jsonRejectsUnsupportedVersionUnknownVoiceAndExcessiveDepth() {
        val doc = LyricsDoc(lines = listOf(TimedLine(1,2,"a")))
        assertNull(LyricsDocCodec.decode(LyricsDocCodec.encode(doc.copy(version=2))))
        assertFalse(LyricsDocCodec.isValid(doc.copy(lines=listOf(TimedLine(1,2,"a","missing")))))
        assertNull(LyricsDocCodec.decode("[".repeat(40)+"0"+"]".repeat(40)))
    }

    @Test fun parserDispatchesYrcWithoutTreatingItAsKugouRelativeOffsets() {
        assertEquals(12000,LyricsUtils.parseLyrics(yrc).synced!!.first().words!!.first().time)
        assertNotNull(LyricsUtils.parseLyrics(yrc).document)
    }

    @Test fun neteaseRejectsWrongRecordingEvenWhenSearchReturnsItFirst() {
        val song=Song.emptySong().copy(title="Example",artist="Singer",album="Album",duration=180000)
        fun track(title:String="Example",artist:String="Singer",album:String="Album",duration:Int=180000)=
            JsonParser.parseString("""{"name":"$title","artists":[{"name":"$artist"}],"album":{"name":"$album"},"duration":$duration}""").asJsonObject
        assertTrue(NeteaseLyricsSource.matchesRecording(song,track()))
        assertFalse(NeteaseLyricsSource.matchesRecording(song,track(title="Example (Live)")))
        assertFalse(NeteaseLyricsSource.matchesRecording(song,track(artist="Cover Artist")))
        assertFalse(NeteaseLyricsSource.matchesRecording(song,track(album="Other Recording")))
        assertFalse(NeteaseLyricsSource.matchesRecording(song,track(duration=195000)))
    }
    @Test fun realYrcStructureRetainsEveryTimedFragment() {
        val raw=javaClass.getResourceAsStream("/lyrics/netease-yrc-structure.yrc")!!.bufferedReader().use { it.readText() }
        val doc=WordSyncTranspilers.yrc(raw)!!
        assertEquals(458,doc.lines.sumOf { it.syllables.size })
    }
    @Test fun documentImportUsesTheExistingValidatedImportPath() {
        val raw=LyricsDocCodec.encode(WordSyncTranspilers.yrc(yrc)!!)
        val result=com.theveloper.pixelplay.utils.LyricsImportSecurity.validateImportedLyricsFile(
            "song.json","application/json",raw.byteInputStream())
        assertTrue(result is com.theveloper.pixelplay.utils.LyricsImportValidationResult.Valid)
        val parsed=(result as com.theveloper.pixelplay.utils.LyricsImportValidationResult.Valid).value.parsedLyrics
        assertEquals(14500,parsed.synced!!.first().endTime)
    }

    @Test fun realCatalogPunctuationPreservesTextAndPositiveOnsets() {
        for (name in listOf("netease-featured-punctuation.yrc", "netease-punctuation.yrc")) {
            val raw=javaClass.getResourceAsStream("/lyrics/$name")!!.bufferedReader().use { it.readText() }
            val doc=WordSyncTranspilers.yrc(raw)!!
            val sourceLines=raw.lines().filter { it.isNotBlank() }
            assertEquals(sourceLines.size,doc.lines.size)
            sourceLines.zip(doc.lines).forEach { (source,line) ->
                assertEquals(source.substringAfter(']').replace(Regex("""\(\d+,\d+,0\)"""),""),line.text)
                val onsets=Regex("""\((\d+),(\d+),0\)""").findAll(source)
                    .filter { it.groupValues[2].toLong()>0 }.map { it.groupValues[1].toLong() }.toList()
                assertEquals(onsets,line.syllables.map { it.startMs })
            }
        }
    }
    @Test fun zeroDurationSpeechIsNotInventedAndPunctuationRetainsNeighborTime() {
        assertNull(WordSyncTranspilers.yrc("[100,300](100,0,0)missing(100,300,0)word"))
        val line=WordSyncTranspilers.yrc("[100,300](100,0,0), (100,300,0)word")!!.lines.single()
        assertEquals(", word",line.text)
        assertEquals(TimedSyllable(100,300,", word"),line.syllables.single())
    }

}
