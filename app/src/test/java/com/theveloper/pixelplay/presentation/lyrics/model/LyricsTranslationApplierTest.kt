package com.theveloper.pixelplay.presentation.lyrics.model

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.LyricsDoc
import com.theveloper.pixelplay.data.model.SyncedLine
import com.theveloper.pixelplay.data.model.TimedLine
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LyricsTranslationApplierTest {

    private val synced = Lyrics(
        synced = listOf(
            SyncedLine(1_000, "Hola amigo"),
            SyncedLine(2_000, "Buenos días", romanization = null),
            SyncedLine(3_000, "Ya traducida", translation = "Already translated"),
            SyncedLine(4_000, "Hola amigo"),
        ),
        plain = listOf("Hola amigo", "Buenos días", "Ya traducida\nAlready translated", "Hola amigo"),
    )

    @Test
    fun `withTranslations sets the translation on every matching line only`() {
        val result = synced.withTranslations(mapOf("Hola amigo" to "Hello friend"))

        val lines = result.synced!!
        assertEquals("Hello friend", lines[0].translation)
        assertNull(lines[1].translation)
        assertEquals("Hello friend", lines[3].translation)
    }

    @Test
    fun `withTranslations never replaces an existing translation`() {
        val result = synced.withTranslations(mapOf("Ya traducida" to "Something else", "Hola amigo" to "Hi"))

        assertEquals("Already translated", result.synced!![2].translation)
    }

    @Test
    fun `withTranslations rebuilds plain as line, romanization, translation`() {
        val withRoman = Lyrics(
            synced = listOf(SyncedLine(0, "こんにちは", romanization = "Konnichiwa"), SyncedLine(1_000, "ありがとう")),
            plain = listOf("こんにちは\nKonnichiwa", "ありがとう"),
        )

        val result = withRoman.withTranslations(mapOf("こんにちは" to "Hello", "ありがとう" to "Thank you"))

        assertEquals(listOf("こんにちは\nKonnichiwa\nHello", "ありがとう\nThank you"), result.plain)
    }

    @Test
    fun `withTranslations keeps the document`() {
        val doc = LyricsDoc(lines = listOf(TimedLine(1_000, 2_000, "Hola amigo")))
        val lyrics = doc.toLyrics()

        val result = lyrics.withTranslations(mapOf("Hola amigo" to "Hello friend"))

        assertSame(doc, result.document)
    }

    @Test
    fun `withTranslations returns the same lyrics when nothing matches`() {
        assertSame(synced, synced.withTranslations(mapOf("Nope" to "No")))
        assertSame(synced, synced.withTranslations(emptyMap()))
    }

    @Test
    fun `the karaoke model carries the translation on the LRC path`() {
        val prepared = PreparedLyricsBuilder.build(synced.withTranslations(mapOf("Buenos días" to "Good morning")))!!

        assertEquals("Good morning", prepared.lines.first { it.text == "Buenos días" }.translation)
    }

    @Test
    fun `the karaoke model carries the translation on the LyricsDoc path`() {
        val doc = LyricsDoc(
            lines = listOf(
                TimedLine(1_000, 2_000, "Hola amigo"),
                TimedLine(2_000, 3_000, "Buenos días"),
            )
        )

        val prepared = PreparedLyricsBuilder.build(doc.toLyrics().withTranslations(mapOf("Buenos días" to "Good morning")))!!

        assertEquals("Good morning", prepared.lines.first { it.text == "Buenos días" }.translation)
        assertNull(prepared.lines.first { it.text == "Hola amigo" }.translation)
    }

    @Test
    fun `untranslatedLines skips translated and blank lines and repeats`() {
        val pending = Lyrics(
            synced = synced.synced!! + SyncedLine(5_000, "   ")
        ).untranslatedLines()

        assertEquals(listOf("Hola amigo", "Buenos días"), pending.keys.toList())
        assertEquals("Buenos días", pending["Buenos días"])
    }

    @Test
    fun `lacksTranslationsFrom is true only while a cached translation is missing`() {
        val cached = mapOf("Hola amigo" to "Hello friend")

        assertTrue(synced.lacksTranslationsFrom(cached))
        assertFalse(synced.withTranslations(cached).lacksTranslationsFrom(cached))
    }

    @Test
    fun `display is synced when there are synced lines, else plain, else none`() {
        assertEquals(LyricsDisplay.SYNCED, resolveLyricsDisplay(synced, plainOverride = false))
        assertEquals(LyricsDisplay.PLAIN, resolveLyricsDisplay(Lyrics(plain = listOf("a")), plainOverride = false))
        assertEquals(LyricsDisplay.NONE, resolveLyricsDisplay(Lyrics(), plainOverride = false))
        assertEquals(LyricsDisplay.NONE, resolveLyricsDisplay(null, plainOverride = true))
    }

    @Test
    fun `show as plain text turns synced lyrics plain, falling back to the synced texts`() {
        assertEquals(LyricsDisplay.PLAIN, resolveLyricsDisplay(synced, plainOverride = true))

        val syncedOnly = Lyrics(synced = listOf(SyncedLine(0, "only synced")))
        assertEquals(LyricsDisplay.PLAIN, resolveLyricsDisplay(syncedOnly, plainOverride = true))
        assertEquals(listOf("only synced"), plainLinesFor(syncedOnly))
        assertEquals(synced.plain, plainLinesFor(synced))
    }
}
