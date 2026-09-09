package com.theveloper.pixelplay.data.tais.dj

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TaisIntentParserTest {
    private val parser = TaisIntentParser()

    @Test fun `questions containing music genres are conversational`() {
        assertFalse(parser.isMediaRequest("Why is jazz different from blues?"))
        assertFalse(parser.isMediaRequest("Tell me about rock music"))
        assertFalse(parser.isMediaRequest("What should I know about relaxing music?"))
    }

    @Test fun `polite queue command keeps its action`() {
        val prompt = "Could you please queue some acoustic songs"
        assertTrue(parser.isMediaRequest(prompt))
        val intent = parser.parse(prompt)
        assertEquals(DjAction.QUEUE, intent.action)
        assertEquals(listOf("acoustic"), intent.genres)
    }

    @Test fun `exact titles keep mood and genre words`() {
        assertEquals("the night we met", parser.parse("play The Night We Met").searchQuery)
        assertEquals("love story taylor swift", parser.parse("play Love Story by Taylor Swift").searchQuery)
        assertEquals("house of cards", parser.parse("play House of Cards").searchQuery)
    }

    @Test fun `genre request also keeps named artist`() {
        val intent = parser.parse("find rock by Muse")
        assertEquals(DjAction.FIND, intent.action)
        assertEquals(listOf("rock"), intent.genres)
        assertEquals("muse", intent.searchQuery)
    }

    @Test fun `play prefix inside a different word is not a command`() {
        assertFalse(parser.isMediaRequest("playlist history"))
    }
}
