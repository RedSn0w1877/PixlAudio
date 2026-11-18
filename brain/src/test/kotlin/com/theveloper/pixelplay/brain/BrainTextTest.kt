package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class BrainTextTest {

    private fun clean(raw: String) = BrainText.cleanTitle(raw)

    @Test
    fun `fold lower-cases ascii and turns punctuation into single spaces`() {
        assertEquals("ac dc", BrainText.fold("AC/DC"))
        assertEquals("tyler the creator", BrainText.fold("Tyler, The Creator"))
        assertEquals("p nk", BrainText.fold("  P!nk  "))
        assertEquals("", BrainText.fold(" -- "))
        assertEquals("", BrainText.fold(""))
        assertEquals("blink 182", BrainText.fold("blink-182"))
    }

    @Test
    fun `fold removes vietnamese diacritics, composed or decomposed`() {
        assertEquals("son tung m tp", BrainText.fold("Sơn Tùng M-TP"))
        assertEquals("den vau", BrainText.fold("Đen Vâu"))
        assertEquals("nguoi la oi", BrainText.fold("Người Lạ Ơi"))
        // The same name written with combining marks (NFD).
        assertEquals("son tung", BrainText.fold("Sơn Tùng"))
        assertEquals(BrainText.fold("Hoàng Thùy Linh"), BrainText.fold("Hoàng Thùy Linh"))
    }

    @Test
    fun `fold maps letters unicode does not decompose`() {
        assertEquals("strasse", BrainText.fold("Straße"))
        assertEquals("aero", BrainText.fold("Ærø"))
        assertEquals("lodz", BrainText.fold("Łódź"))
        assertEquals("bjork", BrainText.fold("Björk"))
        assertEquals("sigur ros", BrainText.fold("Sigur Rós"))
        assertEquals("beyonce", BrainText.fold("Beyoncé"))
        assertEquals("motley crue", BrainText.fold("Mötley Crüe"))
    }

    @Test
    fun `fold lower-cases greek and cyrillic and leaves other scripts alone`() {
        assertEquals("кино", BrainText.fold("КИНО"))
        assertEquals("αβγ", BrainText.fold("ΑΒΓ"))
        assertEquals("아이유", BrainText.fold("아이유"))
        assertEquals("米津玄師", BrainText.fold("米津玄師"))
        assertEquals("ヨルシカ", BrainText.fold("ヨルシカ"))
    }

    @Test
    fun `fold handles full-width forms, cjk and general punctuation, surrogates`() {
        assertEquals("abc 123", BrainText.fold("ＡＢＣ　１２３"))
        assertEquals("a b", BrainText.fold("a—b"))
        assertEquals("don t", BrainText.fold("Don’t"))
        assertEquals("a b", BrainText.fold("a b"))
        assertEquals("a b", BrainText.fold("a\uD800b"))
        assertEquals("a b", BrainText.fold("a�b"))
        // A valid supplementary scalar passes through.
        assertEquals("a🎵b", BrainText.fold("a🎵b"))
    }

    @Test
    fun `cleanTitle drops version segments and sets their flags`() {
        clean("Dreams (2004 Remaster)").let {
            assertEquals("dreams", it.title)
            assertEquals(BrainText.FLAG_REMASTER, it.versionFlags)
            assertEquals(0, it.identityFlags)
        }
        clean("Hotel California - Live at the Forum").let {
            assertEquals("hotel california", it.title)
            assertEquals(BrainText.FLAG_LIVE, it.versionFlags)
        }
        clean("Love Story (Taylor's Version)").let {
            assertEquals("love story", it.title)
            assertEquals(BrainText.FLAG_RE_RECORDED, it.versionFlags)
        }
        clean("Blinding Lights [Chromatics Remix]").let {
            assertEquals("blinding lights", it.title)
            assertEquals(BrainText.FLAG_REMIX, it.versionFlags)
        }
        clean("Heather (slowed + reverb)").let {
            assertEquals("heather", it.title)
            assertEquals(BrainText.FLAG_SLOWED, it.versionFlags)
        }
        clean("Bad Habits (Acoustic Cover)").let {
            assertEquals("bad habits", it.title)
            assertEquals(BrainText.FLAG_ACOUSTIC or BrainText.FLAG_COVER, it.versionFlags)
        }
        clean("Numb（Instrumental）").let {
            assertEquals("numb", it.title)
            assertEquals(BrainText.FLAG_INSTRUMENTAL, it.versionFlags)
        }
    }

    @Test
    fun `cleanTitle drops packaging without a flag`() {
        assertEquals("espresso", clean("Espresso (feat. Someone)").title)
        assertEquals(0, clean("Espresso (feat. Someone)").versionFlags)
        assertEquals("stay", clean("STAY (with Justin Bieber)").title)
        assertEquals("what was i made for", clean("What Was I Made For? [From The Motion Picture \"Barbie\"]").title)
        assertEquals("song", clean("Song - Radio Edit").title)
        assertEquals("song", clean("Song (Deluxe Edition)").title)
        assertEquals(0, clean("Song - Radio Edit").versionFlags)
    }

    @Test
    fun `cleanTitle keeps segments that are part of the name`() {
        assertEquals("satisfaction i can t get no", clean("(I Can't Get No) Satisfaction").title)
        assertEquals("heroes part 2", clean("Heroes (Part 2)").title)
        assertEquals("up tempo", clean("Up - Tempo").title)
        // Words in the head never set a flag.
        clean("Live Forever").let {
            assertEquals("live forever", it.title)
            assertEquals(0, it.versionFlags)
        }
        clean("Cover Me Up").let { assertEquals(0, it.versionFlags) }
        // "inst" must be a whole token.
        assertEquals(0, clean("Song (Instant Crush)").versionFlags)
    }

    @Test
    fun `cleanTitle survives odd input`() {
        assertEquals("live", clean("(Live)").title)
        assertEquals(BrainText.FLAG_LIVE, clean("(Live)").versionFlags)
        assertEquals("open bracket never closes", clean("Open (bracket never closes").title)
        assertEquals("", clean("").title)
        assertEquals("a b nested", clean("A (B (nested) ) ").title)
        assertEquals("em dash", clean("Em Dash — Remastered").title)
        assertEquals(BrainText.FLAG_REMASTER, clean("Em Dash — Remastered").versionFlags)
    }

    @Test
    fun `spotify and youtube spellings of one song clean to the same title`() {
        val spotify = clean("Chạy Ngay Đi")
        val youTube = clean("CHẠY NGAY ĐI (Official Audio)")
        // "(Official Audio)" is not a rule: it stays, so the two differ. Radio rows are official-audio
        // entries whose titles carry no such suffix; this pins the behaviour so a rule change is deliberate.
        assertEquals("chay ngay di", spotify.title)
        assertEquals("chay ngay di official audio", youTube.title)
    }
}
