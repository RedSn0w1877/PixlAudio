package com.theveloper.pixelplay.presentation.components

import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import com.theveloper.pixelplay.presentation.lyrics.model.clusterSyncedWords
import com.theveloper.pixelplay.presentation.lyrics.model.resolveLineEndTimeMs
import com.theveloper.pixelplay.presentation.lyrics.model.sanitizeLyricLineText
import com.theveloper.pixelplay.presentation.lyrics.model.sanitizeSyncedWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsSheetLogicTest {

    @Test
    fun sanitizeSyncedWords_removesLeadingTags_preventsOverlap() {
        val words = listOf(
            SyncedWord(time = 0, word = "v1:"),
            SyncedWord(time = 120, word = "Hello"),
            SyncedWord(time = 240, word = "world")
        )

        val sanitized = sanitizeSyncedWords(words)

        assertEquals(listOf("Hello", "world"), sanitized.map { it.word })
        assertEquals(listOf(120, 240), sanitized.map { it.time })
        assertTrue(sanitized.all { it.startsNewWord })
    }

    @Test
    fun sanitizeLyricLineText_stripsLrcTimestampTags() {
        val raw = "[00:26.42][01:12.34] Three in the morning, I ain't slept all weekend"

        val sanitized = sanitizeLyricLineText(raw)

        assertEquals("Three in the morning, I ain't slept all weekend", sanitized)
    }

    @Test
    fun resolveLineEndTimeMs_extendsPastNextLineWhenLastWordStartsLater() {
        val line = SyncedLine(
            time = 1_000,
            line = "abc",
            words = listOf(
                SyncedWord(1_000, "a"),
                SyncedWord(1_600, "b"),
                SyncedWord(2_000, "c")
            )
        )

        val lineEnd = resolveLineEndTimeMs(line, nextLineStartMs = 2_000)

        assertEquals(2_001L, lineEnd)
    }

    @Test
    fun sanitizeSyncedWords_promotesFirstVisibleWordAfterLeadingMarker() {
        val words = listOf(
            SyncedWord(time = 1000, word = "v1:"),
            SyncedWord(time = 1200, word = "fall", startsNewWord = false)
        )

        val sanitized = sanitizeSyncedWords(words)

        assertEquals(1, sanitized.size)
        assertEquals("fall", sanitized[0].word)
        assertTrue(sanitized[0].startsNewWord)
    }

    @Test
    fun clusterSyncedWords_keepsSyllablesInsideSameWord() {
        val clusters = clusterSyncedWords(
            listOf(
                SyncedWord(time = 1000, word = "to", startsNewWord = true),
                SyncedWord(time = 1100, word = "geth", startsNewWord = false),
                SyncedWord(time = 1200, word = "er", startsNewWord = false),
                SyncedWord(time = 1500, word = "now", startsNewWord = true)
            )
        )

        assertEquals(2, clusters.size)
        assertEquals(listOf("to", "geth", "er"), clusters[0].words.map { it.word })
        assertEquals(listOf("now"), clusters[1].words.map { it.word })
        assertEquals(0, clusters[0].startIndex)
        assertEquals(3, clusters[1].startIndex)
    }

    @Test
    fun resolveSeekPositionMs_subtractsPositiveLyricsOffset() {
        val seekPosition = resolveSeekPositionMs(
            lineTimeMs = 12_000L,
            lyricsSyncOffsetMs = 750
        )

        assertEquals(11_250L, seekPosition)
    }

    @Test
    fun resolveSeekPositionMs_addsNegativeLyricsOffset() {
        val seekPosition = resolveSeekPositionMs(
            lineTimeMs = 12_000L,
            lyricsSyncOffsetMs = -750
        )

        assertEquals(12_750L, seekPosition)
    }

    @Test
    fun resolveSeekPositionMs_clampsToZeroWhenOffsetWouldGoNegative() {
        val seekPosition = resolveSeekPositionMs(
            lineTimeMs = 300L,
            lyricsSyncOffsetMs = 750
        )

        assertEquals(0L, seekPosition)
    }
    @Test fun explicitLineEndWinsOverNextLineStart() {
        val lines=listOf(SyncedLine(1000,"Lead",endTime=3000),
            SyncedLine(1500,"Echo",endTime=2200,voiceRole="background"),
            SyncedLine(4000,"Next",endTime=5000))
        assertEquals(3000L,resolveLineEndTimeMs(lines[0],1500))
    }

    @Test fun syllablesStayTogetherWhileCjkFragmentsCanWrap() {
        val latin=listOf(SyncedWord(0,"Hel",true),SyncedWord(100,"lo",false),SyncedWord(200,"there",true))
        assertEquals(listOf(2,1),clusterSyncedWords(latin).map { it.words.size })
        val cjk=listOf(SyncedWord(0,"你",true),SyncedWord(100,"好",false),SyncedWord(200,"世",false),SyncedWord(300,"界",false))
        assertEquals(listOf(1,1,1,1),clusterSyncedWords(cjk).map { it.words.size })
        assertTrue(clusterSyncedWords(cjk).drop(1).none { it.words.first().startsNewWord })
    }

    @Test
    fun chromeColors_material_usesFixedRoles_soLightAndDarkSchemesMatch() {
        val light = lyricsChromeColors(androidx.compose.material3.lightColorScheme())
        val dark = lyricsChromeColors(androidx.compose.material3.darkColorScheme())
        val lightScheme = androidx.compose.material3.lightColorScheme()
        assertEquals(lightScheme.primaryFixed, light.content)
        assertEquals(lightScheme.tertiaryFixedDim, light.playPause)
        // Fixed roles are theme-independent, so the chrome over the dark art does not flip.
        assertEquals(light.content, dark.content)
        assertEquals(light.container, dark.container)
    }

    @Test
    fun chromeColors_highContrast_makesFillsHeavier() {
        val scheme = androidx.compose.material3.darkColorScheme()
        assertTrue(lyricsChromeColors(scheme, highContrast = true).container.alpha >
            lyricsChromeColors(scheme).container.alpha)
    }
}
