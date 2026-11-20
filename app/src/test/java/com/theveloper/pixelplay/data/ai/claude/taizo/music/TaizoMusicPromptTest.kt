package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoContext
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoTools
import java.io.File
import org.junit.jupiter.api.Test

/** The prompt additions of spec 9.4 and the words the assistant's user-visible strings must not use. */
class TaizoMusicPromptTest {
    @Test
    fun `the music rules come after the existing guidance and say what the spec says`() {
        val text = TaizoContext.INSTRUCTIONS
        assertThat(text.endsWith(TaizoContext.MUSIC_RULES)).isTrue()
        assertThat(text.indexOf("If a tool fails")).isLessThan(text.indexOf("Music picks come from the listener's own engine"))
        for (line in listOf(
            "Never name a track, album or artist as a recommendation unless a tool returned it in this conversation.",
            "If you think of a song yourself, pass it to resolve_tracks first and drop anything not found.",
            "To recommend, call recommend or build_mix with structured filters. Do not rank or reorder what they return.",
            "Genre and mood filters are ids. Get them from lookup_genre; do not guess ids.",
            "To explain a pick or the listener's taste, call explain_track or explain_taste and report only what they return.",
            "If a field is listed as unknown, say it is unknown.",
            "Numbers about the listener come only from tool results.",
        )) {
            assertThat(text).contains(line)
        }
        // Every tool the rules name exists.
        for (name in listOf("resolve_tracks", "recommend", "build_mix", "lookup_genre", "explain_track", "explain_taste")) {
            assertThat(TaizoTools.clientToolNames).contains(name)
        }
    }

    @Test
    fun `the assistant's new strings never name the vendor or the model`() {
        val file = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml")).first { it.isFile }
        val strings = Regex("""<string name="(taizo_[a-z_]+)">([^<]*)</string>""").findAll(file.readText()).map { it.groupValues[1] to it.groupValues[2] }
        val forbidden = listOf("lossless", "hi-res", "dolby", "atmos", "360 reality", "claude", "anthropic", "binilyrics", "enhanced")
        for ((name, value) in strings) {
            // The names of the strings may say what they are about; the words the listener reads may not carry the forbidden ones.
            if (name == "taizo_unverified_name" || name.startsWith("taizo_activity_")) {
                for (word in forbidden) assertThat(value.lowercase()).doesNotContain(word)
            }
        }
        assertThat(strings.any { it.first == "taizo_unverified_name" }).isTrue()
    }
}
