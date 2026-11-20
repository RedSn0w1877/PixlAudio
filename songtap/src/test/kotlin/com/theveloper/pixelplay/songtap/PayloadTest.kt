package com.theveloper.pixelplay.songtap

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PayloadTest {

    private val base = SongTapPayload(
        v = 1, ts = 1_763_600_000_000L, ytVideoId = "dQw4w9WgXcQ", spotifyId = "4uLU6hMCjMI75M1A2tKUQC", isrc = "GBARL9300135",
        title = "Never Gonna Give You Up", artist = "Rick Astley", durationMs = 213_000,
        artUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    )

    private fun text(bytes: ByteArray) = SongTapText.fromCodePoints(SongTapText.decodeUtf8(bytes)!!)

    private fun reject(bytes: ByteArray): PayloadReject = (SongTapPayloads.decode(bytes) as PayloadDecode.Rejected).reason

    private fun reject(text: String): PayloadReject = reject(SongTapText.encodeUtf8(SongTapText.toCodePoints(text)))

    @Test
    fun `canonical form is exactly the documented JSON`() {
        val bytes = SongTapPayloads.encode(base)!!
        assertEquals(
            "{\"v\":1,\"ts\":1763600000000,\"yt\":\"dQw4w9WgXcQ\",\"sp\":\"4uLU6hMCjMI75M1A2tKUQC\",\"isrc\":\"GBARL9300135\"," +
                "\"t\":\"Never Gonna Give You Up\",\"a\":\"Rick Astley\",\"d\":213000,\"art\":\"https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg\"}",
            text(bytes),
        )
        assertTrue(bytes.size <= SongTapPayloads.MAX_BYTES)
    }

    @Test
    fun `a payload round trips`() {
        val variants = listOf(
            base, base.copy(spotifyId = null), base.copy(isrc = null, artUrl = null),
            base.copy(title = "Say \"hi\" \\ bye"), base.copy(title = "\u6771\u4EAC \uD83C\uDFB5", artist = "\u5E73\u91CE"),
            base.copy(ts = 0L, durationMs = 0), base.copy(durationMs = SongTapPayloads.MAX_DURATION_MS),
        )
        for (p in variants) {
            val decoded = SongTapPayloads.decode(SongTapPayloads.encode(p)!!)
            assertEquals(PayloadDecode.Ok(p), decoded, p.title)
        }
    }

    @Test
    fun `the size cap is hard`() {
        val big = base.copy(
            title = "\u6771".repeat(33), artist = "\u6771".repeat(26),
            artUrl = "https://i.ytimg.com/" + "p".repeat(90),
        )
        assertNull(SongTapPayloads.encode(big), "over 400 bytes is refused by encode")
        assertEquals(PayloadReject.TOO_LARGE, reject(ByteArray(401) { 0x61 }))
        assertEquals(PayloadReject.SYNTAX, reject(ByteArray(400) { 0x61 }))
    }

    @Test
    fun `make drops the optional parts until it fits`() {
        val made = SongTapPayloads.make(
            1L, "dQw4w9WgXcQ", "4uLU6hMCjMI75M1A2tKUQC", "GBARL9300135", "\u6771".repeat(60), "\u6771".repeat(60), 1000L,
            "https://i.ytimg.com/" + "p".repeat(90),
        )
        assertNotNull(made)
        assertNull(made!!.artUrl)
        assertTrue(SongTapPayloads.encode(made)!!.size <= SongTapPayloads.MAX_BYTES)
        assertTrue(SongTapText.utf8Length(SongTapText.toCodePoints(made.title)) <= SongTapPayloads.MAX_TITLE_BYTES)
    }

    @Test
    fun `make cleans text and ignores bad optional ids`() {
        val made = SongTapPayloads.make(5L, "dQw4w9WgXcQ", "bad", "bad", "  T\u202Ee\u200Bs\tt  ", "  ", -9L, "http://i.scdn.co/x")!!
        assertEquals("Tes t", made.title)
        assertEquals(SongTapPayloads.UNKNOWN_ARTIST, made.artist)
        assertNull(made.spotifyId); assertNull(made.isrc); assertNull(made.artUrl)
        assertEquals(0, made.durationMs)
        assertNull(SongTapPayloads.make(5L, "short", null, null, "t", "a", 0L, null))
        assertNull(SongTapPayloads.make(5L, "dQw4w9WgXcQ", null, null, "\u0007\u202E", "a", 0L, null))
    }

    @Test
    fun `art links are https on the four hosts only`() {
        for (ok in listOf("https://i.scdn.co/image/x", "https://i.ytimg.com/vi/x/y.jpg", "https://lh3.googleusercontent.com/a=w1", "https://yt3.ggpht.com/a")) {
            assertTrue(SongTapPayloads.isAllowedArtUrl(ok), ok)
        }
        for (bad in listOf(
            "http://i.scdn.co/x", "https://evil.example/x", "https://i.scdn.co.evil.example/x", "https://i.scdn.co@evil.example/x",
            "https://i.scdn.co:443/x", "https://I.SCDN.CO/x", "https://i.scdn.co", "https://i.scdn.co/", "https://i.scdn.co/a b",
            "https://i.scdn.co/\"x", "https://i.scdn.co/\\x", "https://i.scdn.co/<x>", "https://i.scdn.co/\u00E9", "javascript:alert(1)", "",
        )) {
            assertTrue(!SongTapPayloads.isAllowedArtUrl(bad), bad)
        }
    }

    @Test
    fun `ids follow their alphabets`() {
        assertTrue(SongTapPayloads.isVideoId("a-b_c-d_e-f"))
        assertTrue(!SongTapPayloads.isVideoId("a.b_c-d_e-f"))
        assertTrue(!SongTapPayloads.isVideoId("short"))
        assertTrue(SongTapPayloads.isSpotifyId("4uLU6hMCjMI75M1A2tKUQC"))
        assertTrue(!SongTapPayloads.isSpotifyId("4uLU6hMCjMI75M1A2tKUQ_"))
        assertTrue(SongTapPayloads.isIsrc("GBARL9300135"))
        assertTrue(!SongTapPayloads.isIsrc("gbarl9300135"))
    }

    @Test
    fun `hostile text is refused`() {
        assertEquals(PayloadReject.BAD_TITLE, reject(text(SongTapPayloads.encode(base)!!).replace("Never Gonna Give You Up", "a\u202Eb")))
        assertEquals(PayloadReject.BAD_TITLE, reject(text(SongTapPayloads.encode(base)!!).replace("Never Gonna Give You Up", "a\u0007b")))
        assertEquals(PayloadReject.BAD_TITLE, reject(text(SongTapPayloads.encode(base)!!).replace("Never Gonna Give You Up", " lead")))
        assertEquals(PayloadReject.BAD_ARTIST, reject(text(SongTapPayloads.encode(base)!!).replace("Rick Astley", "")))
        assertEquals(PayloadReject.BAD_ART_URL, reject(text(SongTapPayloads.encode(base)!!).replace("i.ytimg.com", "evil.example")))
    }

    @Test
    fun `anything that is not exactly the canonical form is refused`() {
        val good = text(SongTapPayloads.encode(base.copy(spotifyId = null, isrc = null, artUrl = null))!!)
        assertTrue(SongTapPayloads.decode(SongTapText.encodeUtf8(SongTapText.toCodePoints(good))) is PayloadDecode.Ok)
        val variants = listOf(
            good.replace("{\"v\":1,", "{ \"v\":1,"),
            good + "\n",
            good.replace("\"ts\":", "\"ts\":0"),
            good.replace("\"v\":1,\"ts\"", "\"ts\""),
            good.replace("}", ",\"x\":1}"),
            good.replace("\"t\":\"Never Gonna Give You Up\"", "\"t\":\"N\\u0065ver Gonna Give You Up\""),
            good.replace("\"d\":213000", "\"d\":213000.0"),
            good.replace("\"d\":213000", "\"d\":-1"),
            "",
            "{}",
        )
        for (v in variants) assertTrue(SongTapPayloads.decode(SongTapText.encodeUtf8(SongTapText.toCodePoints(v))) is PayloadDecode.Rejected, v)
    }

    @Test
    fun `invalid utf8 is refused`() {
        val good = SongTapPayloads.encode(base)!!
        val at = good.indexOf('N'.code.toByte())
        for (bad in listOf(intArrayOf(0xC0, 0x80), intArrayOf(0x80), intArrayOf(0xE6, 0x9D), intArrayOf(0xED, 0xA0, 0x80), intArrayOf(0xF4, 0x90, 0x80, 0x80))) {
            val bytes = good.copyOfRange(0, at) + ByteArray(bad.size) { bad[it].toByte() } + good.copyOfRange(at + 1, good.size)
            assertEquals(PayloadReject.NOT_UTF8, reject(bytes), bad.joinToString())
        }
    }

    @Test
    fun `random bytes and mutations never crash and never get through unless exact`() {
        val rng = SplitMix64(7)
        val source = SongTapPayloads.encode(base)!!
        var accepted = 0
        for (i in 0 until 5000) {
            val bytes = when (i % 3) {
                0 -> ByteArray(rng.nextInt(450)) { rng.nextInt(256).toByte() }
                1 -> source.copyOf().also { it[rng.nextInt(it.size)] = rng.nextInt(256).toByte() }
                else -> source.copyOf(rng.nextInt(source.size + 1))
            }
            val r = SongTapPayloads.decode(bytes)
            if (r is PayloadDecode.Ok) {
                accepted++
                assertTrue(SongTapPayloads.encode(r.payload)!!.contentEquals(bytes), "an accepted payload is exactly its canonical bytes")
            }
        }
        assertTrue(accepted < 2500)
    }

    @Test
    fun `text rules`() {
        assertEquals("a b", SongTapText.sanitize("  a \t\u00A0 b  ", 100))
        assertEquals("ab", SongTapText.sanitize("a\u0000b", 100))
        assertEquals("abc", SongTapText.sanitize("abcdef", 3))
        assertEquals("\u6771", SongTapText.sanitize("\u6771\u4EAC", 5))
        assertEquals("a", SongTapText.sanitize("a \u6771", 3))
        val s = SongTapText.sanitize("\uD83C\uDFB5 x \u200D y", 100)
        assertEquals(s, SongTapText.sanitize(s, 100), "idempotent")
        assertNull(SongTapText.decodeUtf8(byteArrayOf(0xF0.toByte(), 0x9F.toByte())))
    }

    @Test
    fun `links round trip and are unique`() {
        val bytes = SongTapPayloads.encode(base)!!
        val link = SongTapLink.encode(bytes)
        assertTrue(link.startsWith("pixlsong1:"))
        assertTrue(SongTapLink.decode(link)!!.contentEquals(bytes))
        assertNull(SongTapLink.decode(link + "="))
        assertNull(SongTapLink.decode(link.replace("pixlsong1:", "pixlsong2:")))
        assertNull(SongTapLink.decode(link + "A"))
        assertNull(SongTapLink.decode("pixlsong1:"))
        for (n in 0..40) {
            val b = ByteArray(n) { (it * 31 + n).toByte() }
            val l = SongTapLink.encode(b)
            if (n > 0) assertTrue(SongTapLink.decode(l)!!.contentEquals(b), "n=$n")
        }
    }
}
