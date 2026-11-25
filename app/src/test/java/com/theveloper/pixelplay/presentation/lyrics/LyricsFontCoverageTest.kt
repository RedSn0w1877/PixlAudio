package com.theveloper.pixelplay.presentation.lyrics

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.SyncedWord
import org.junit.jupiter.api.Test

class LyricsFontCoverageTest {

    @Test
    fun latinAccentsAndTypographicMarksAreCovered() {
        // These used to push a whole song onto the system font (the old check knew only ASCII).
        listOf("señorita", "Beyoncé", "Ðóttir þú æ", "Tiếng Việt", "“quoted” — it’s… ok", "naïve façade", "ß ü ö ä")
            .forEach { assertWithMessage(it).that(LyricsFontCoverage.isCovered(it)).isTrue() }
        assertThat(LyricsFontCoverage.isCovered("a b c​d")).isTrue() // nbsp, four-per-em, zero-width
        assertThat(LyricsFontCoverage.isCovered("line\r\n\tnext")).isTrue()
    }

    @Test
    fun scriptsTheFaceLacksAreNotCovered() {
        listOf("사랑해", "こんにちは", "你好", "Привет", "مرحبا", "Γειά", "♪").forEach {
            assertWithMessage(it).that(LyricsFontCoverage.isCovered(it)).isFalse()
        }
    }

    @Test
    fun rangeEdges() {
        assertThat(LyricsFontCoverage.isCovered('ć')).isTrue()
        assertThat(LyricsFontCoverage.isCovered('Ĉ')).isFalse()
        assertThat(LyricsFontCoverage.isCovered('Ċ')).isTrue()
        assertThat(LyricsFontCoverage.isCovered('◌')).isTrue()
        assertThat(LyricsFontCoverage.isCovered('◍')).isFalse()
        assertThat(LyricsFontCoverage.isCovered('\u0000')).isFalse()
    }

    @Test
    fun aSongIsDecidedAsAWhole_includingTranslationsAndWords() {
        val duet = Lyrics(
            synced = listOf(
                SyncedLine(0, "Hola, señorita", voiceRole = "lead"),
                SyncedLine(2_000, "I love it when you call me", voiceRole = "duet",
                    words = listOf(SyncedWord(2_000, "I"), SyncedWord(2_300, "love"))),
            ),
        )
        assertThat(LyricsFontCoverage.isCovered(duet)).isTrue()
        val withHangulTranslation = duet.copy(synced = duet.synced!! + SyncedLine(4_000, "Hi", translation = "안녕"))
        assertThat(LyricsFontCoverage.isCovered(withHangulTranslation)).isFalse()
        assertThat(LyricsFontCoverage.isCovered(Lyrics(plain = listOf("ok", "Привет")))).isFalse()
    }
}
