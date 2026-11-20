package com.theveloper.pixelplay.data.tais.dj

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** "Start a station from a sentence" with no network (docs/brain-spec.md, 14 stage 4): read by rules, so it needs no model and no connection. */
class StationSentenceTest {

    @Test
    fun `a station request gives the name asked for`() {
        assertThat(StationSentence.parse("Start a station from Radiohead")).isEqualTo("radiohead")
        assertThat(StationSentence.parse("start station based on Espresso please")).isEqualTo("espresso")
        assertThat(StationSentence.parse("play a radio of bedroom pop")).isEqualTo("bedroom pop")
        assertThat(StationSentence.parse("Put on a station for the weeknd!")).isEqualTo("weeknd")
        assertThat(StationSentence.parse("start me a station like \"Good Luck, Babe!\"")).isEqualTo("good luck, babe!")
        assertThat(StationSentence.parse("station from jazz")).isEqualTo("jazz")
        assertThat(StationSentence.parse("play some jazz radio")).isEqualTo("jazz")
        assertThat(StationSentence.parse("start Sơn Tùng M-TP radio")).isEqualTo("sơn tùng m-tp")
    }

    @Test
    fun `anything that is not a station request is left to the old path`() {
        assertThat(StationSentence.parse("play Radiohead")).isNull()
        assertThat(StationSentence.parse("what is a radio station?")).isNull()
        assertThat(StationSentence.parse("make me a playlist for the gym")).isNull()
        assertThat(StationSentence.parse("")).isNull()
        assertThat(StationSentence.parse("play the radio")).isNull()
        assertThat(StationSentence.parse("start a station from this")).isNull()
        assertThat(StationSentence.parse("my radio is broken, start fixing it")).isNull()
        assertThat(StationSentence.parse("start a station from " + "x".repeat(80))).isNull()
    }
}
