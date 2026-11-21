package com.theveloper.pixelplay.songtap

/**
 * The thing a beam carries: which song, in the fewest bytes (ids, title, artist, art link). No audio, no
 * account, no device identity: each phone streams the song itself.
 *
 * Wire form, one canonical JSON object in UTF-8, keys in this order, no whitespace, optional keys left
 * out when absent, only `"` and `\` ever escaped (text with control or bidirectional characters is
 * rejected, so nothing else needs escaping):
 *
 * `{"v":1,"ts":<ms>,"yt":"<11>","sp":"<22>","isrc":"<12>","t":"<title>","a":"<artist>","d":<ms>,"art":"<url>"}`
 *
 * [decode] accepts exactly what [encode] writes and nothing else, so the bytes a phone signs are the bytes
 * the other phone parses. Hard cap [MAX_BYTES] bytes.
 */
data class SongTapPayload(
    val v: Int,
    /** When the sender made it, Unix milliseconds on the sender's clock. */
    val ts: Long,
    /**
     * The YouTube Music video the song plays from: 11 characters of `A-Z a-z 0-9 _ -`. Null for a song the
     * sender only knows from Spotify (not matched to a video yet): the receiver then finds the video itself.
     * At least one of [ytVideoId] and [spotifyId] is always present.
     */
    val ytVideoId: String?,
    /** The Spotify track id (22 base62 characters), when the sender knows it. */
    val spotifyId: String?,
    /** The recording code (12 characters of `A-Z 0-9`), when the sender knows it. */
    val isrc: String?,
    val title: String,
    val artist: String,
    val durationMs: Int,
    /** `https` only, on an allow-listed host (see [SongTapPayloads.ART_HOSTS]); null when there is no art. */
    val artUrl: String?,
) {
    /** What identifies the song in a session: the video id, or the Spotify id when there is no video yet. */
    val songKey: String get() = ytVideoId ?: spotifyId ?: ""
}

enum class PayloadReject(val code: String) {
    TOO_LARGE("too_large"),
    NOT_UTF8("not_utf8"),
    SYNTAX("syntax"),
    VERSION("version"),
    BAD_TIMESTAMP("bad_timestamp"),
    NO_SONG_ID("no_song_id"),
    BAD_VIDEO_ID("bad_video_id"),
    BAD_SPOTIFY_ID("bad_spotify_id"),
    BAD_ISRC("bad_isrc"),
    BAD_TITLE("bad_title"),
    BAD_ARTIST("bad_artist"),
    BAD_DURATION("bad_duration"),
    BAD_ART_URL("bad_art_url"),
}

sealed interface PayloadDecode {
    data class Ok(val payload: SongTapPayload) : PayloadDecode
    data class Rejected(val reason: PayloadReject) : PayloadDecode
}

object SongTapPayloads {

    const val VERSION = 1
    const val MAX_BYTES = 400
    const val MAX_TITLE_BYTES = 100
    const val MAX_ARTIST_BYTES = 80
    const val MAX_ART_URL_BYTES = 110
    const val MAX_DURATION_MS = 86_400_000
    const val UNKNOWN_ARTIST = "Unknown artist"

    /** The only hosts a beam's art link may point at, matched exactly. */
    val ART_HOSTS: List<String> = listOf("i.scdn.co", "i.ytimg.com", "lh3.googleusercontent.com", "yt3.ggpht.com")

    // ── Field validators ─────────────────────────────────────────────────────────────

    private fun isAlnum(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9'

    fun isVideoId(s: String): Boolean {
        if (s.length != 11) return false
        for (c in s) if (!isAlnum(c) && c != '_' && c != '-') return false
        return true
    }

    fun isSpotifyId(s: String): Boolean {
        if (s.length != 22) return false
        for (c in s) if (!isAlnum(c)) return false
        return true
    }

    fun isIsrc(s: String): Boolean {
        if (s.length != 12) return false
        for (c in s) if (!(c in 'A'..'Z' || c in '0'..'9')) return false
        return true
    }

    private fun isUrlChar(c: Char): Boolean {
        if (isAlnum(c)) return true
        return when (c) {
            '-', '.', '_', '~', '/', '?', '&', '=', '%', '+', ',', ':', ';', '@', '!', '$', '*', '\'', '(', ')' -> true
            else -> false
        }
    }

    /** `https://<allow-listed host>/<path>`, printable ASCII only, at most [MAX_ART_URL_BYTES] bytes. */
    fun isAllowedArtUrl(s: String): Boolean {
        if (s.length > MAX_ART_URL_BYTES) return false
        val prefix = "https://"
        if (!s.startsWith(prefix)) return false
        for (c in s) if (!isUrlChar(c)) return false
        val hostEnd = s.indexOf('/', prefix.length)
        if (hostEnd < 0 || hostEnd == s.length - 1) return false
        val host = s.substring(prefix.length, hostEnd)
        return host in ART_HOSTS
    }

    /** The first rule [p] breaks, or null when it is a payload a phone may send and accept. */
    fun validate(p: SongTapPayload): PayloadReject? {
        if (p.v != VERSION) return PayloadReject.VERSION
        if (p.ts < 0L) return PayloadReject.BAD_TIMESTAMP
        if (p.ytVideoId == null && p.spotifyId == null) return PayloadReject.NO_SONG_ID
        if (p.ytVideoId != null && !isVideoId(p.ytVideoId)) return PayloadReject.BAD_VIDEO_ID
        if (p.spotifyId != null && !isSpotifyId(p.spotifyId)) return PayloadReject.BAD_SPOTIFY_ID
        if (p.isrc != null && !isIsrc(p.isrc)) return PayloadReject.BAD_ISRC
        if (!SongTapText.isClean(SongTapText.toCodePoints(p.title), MAX_TITLE_BYTES)) return PayloadReject.BAD_TITLE
        if (!SongTapText.isClean(SongTapText.toCodePoints(p.artist), MAX_ARTIST_BYTES)) return PayloadReject.BAD_ARTIST
        if (p.durationMs < 0 || p.durationMs > MAX_DURATION_MS) return PayloadReject.BAD_DURATION
        if (p.artUrl != null && !isAllowedArtUrl(p.artUrl)) return PayloadReject.BAD_ART_URL
        return null
    }

    // ── Encode ───────────────────────────────────────────────────────────────────────

    private fun quoted(sb: StringBuilder, text: String) {
        sb.append('"')
        for (c in text) {
            if (c == '"' || c == '\\') sb.append('\\')
            sb.append(c)
        }
        sb.append('"')
    }

    /** The canonical bytes of [p], or null when [p] breaks a rule or is over [MAX_BYTES]. */
    fun encode(p: SongTapPayload): ByteArray? {
        if (validate(p) != null) return null
        val sb = StringBuilder(256)
        sb.append("{\"v\":").append(p.v)
        sb.append(",\"ts\":").append(p.ts)
        if (p.ytVideoId != null) { sb.append(",\"yt\":"); quoted(sb, p.ytVideoId) }
        if (p.spotifyId != null) { sb.append(",\"sp\":"); quoted(sb, p.spotifyId) }
        if (p.isrc != null) { sb.append(",\"isrc\":"); quoted(sb, p.isrc) }
        sb.append(",\"t\":"); quoted(sb, p.title)
        sb.append(",\"a\":"); quoted(sb, p.artist)
        sb.append(",\"d\":").append(p.durationMs)
        if (p.artUrl != null) { sb.append(",\"art\":"); quoted(sb, p.artUrl) }
        sb.append('}')
        val bytes = SongTapText.encodeUtf8(SongTapText.toCodePoints(sb.toString()))
        return if (bytes.size > MAX_BYTES) null else bytes
    }

    // ── Decode ───────────────────────────────────────────────────────────────────────

    private class Cursor(val b: ByteArray) {
        var i = 0

        fun lit(text: String): Boolean {
            if (i + text.length > b.size) return false
            for (k in text.indices) if (b[i + k].toInt() != text[k].code) return false
            i += text.length
            return true
        }

        /** A bare non-negative integer without sign, leading zeros or more than [maxDigits] digits; -1 on error. */
        fun number(maxDigits: Int): Long {
            val start = i
            var v = 0L
            while (i < b.size && b[i] in 0x30..0x39) {
                v = v * 10 + (b[i] - 0x30)
                i++
                if (i - start > maxDigits) return -1L
            }
            val digits = i - start
            if (digits == 0) return -1L
            if (digits > 1 && b[start].toInt() == 0x30) return -1L
            return v
        }

        /** A quoted string: `\"` and `\\` are the only escapes; the raw bytes between the quotes are returned as text. */
        fun string(): String? {
            if (i >= b.size || b[i].toInt() != 0x22) return null
            i++
            val raw = ByteArray(b.size - i)
            var n = 0
            while (true) {
                if (i >= b.size) return null
                val c = b[i].toInt()
                i++
                if (c == 0x22) break
                if (c == 0x5C) {
                    if (i >= b.size) return null
                    val e = b[i].toInt()
                    i++
                    if (e != 0x22 && e != 0x5C) return null
                    raw[n++] = e.toByte()
                } else {
                    raw[n++] = c.toByte()
                }
            }
            val cps = SongTapText.decodeUtf8(raw, 0, n) ?: return null
            return SongTapText.fromCodePoints(cps)
        }
    }

    fun decode(bytes: ByteArray): PayloadDecode {
        if (bytes.size > MAX_BYTES) return PayloadDecode.Rejected(PayloadReject.TOO_LARGE)
        if (SongTapText.decodeUtf8(bytes) == null) return PayloadDecode.Rejected(PayloadReject.NOT_UTF8)
        val c = Cursor(bytes)
        fun bad(): PayloadDecode = PayloadDecode.Rejected(PayloadReject.SYNTAX)

        if (!c.lit("{\"v\":")) return bad()
        val v = c.number(3)
        if (v < 0) return bad()
        if (!c.lit(",\"ts\":")) return bad()
        val ts = c.number(15)
        if (ts < 0) return bad()
        var yt: String? = null
        if (c.lit(",\"yt\":")) yt = c.string() ?: return bad()
        var sp: String? = null
        if (c.lit(",\"sp\":")) sp = c.string() ?: return bad()
        var isrc: String? = null
        if (c.lit(",\"isrc\":")) isrc = c.string() ?: return bad()
        if (!c.lit(",\"t\":")) return bad()
        val title = c.string() ?: return bad()
        if (!c.lit(",\"a\":")) return bad()
        val artist = c.string() ?: return bad()
        if (!c.lit(",\"d\":")) return bad()
        val d = c.number(9)
        if (d < 0) return bad()
        var art: String? = null
        if (c.lit(",\"art\":")) art = c.string() ?: return bad()
        if (!c.lit("}")) return bad()
        if (c.i != bytes.size) return bad()
        val payload = SongTapPayload(
            v = v.toInt(), ts = ts, ytVideoId = yt, spotifyId = sp, isrc = isrc,
            title = title, artist = artist, durationMs = d.toInt(), artUrl = art,
        )
        validate(payload)?.let { return PayloadDecode.Rejected(it) }
        // The byte-for-byte check that makes "accepted" mean "exactly what encode writes".
        val again = encode(payload)
        if (again == null || !again.contentEquals(bytes)) return bad()
        return PayloadDecode.Ok(payload)
    }

    // ── Building a payload from what the phone knows ────────────────────────────────

    /**
     * A payload for the song that is playing: text cleaned and cut to fit, ids kept only when they are
     * well formed, and the optional parts dropped (art link first, then the Spotify id, then the
     * recording code) until it is at most [MAX_BYTES]. Null when there is neither a valid video id nor a valid
     * Spotify id, or no title.
     */
    fun make(
        nowMs: Long,
        ytVideoId: String?,
        spotifyId: String?,
        isrc: String?,
        title: String,
        artist: String,
        durationMs: Long,
        artUrl: String?,
    ): SongTapPayload? {
        val video = ytVideoId?.takeIf { isVideoId(it) }
        val spotify = spotifyId?.takeIf { isSpotifyId(it) }
        if (video == null && spotify == null) return null
        val cleanTitle = SongTapText.sanitize(title, MAX_TITLE_BYTES)
        if (cleanTitle.isEmpty()) return null
        var cleanArtist = SongTapText.sanitize(artist, MAX_ARTIST_BYTES)
        if (cleanArtist.isEmpty()) cleanArtist = UNKNOWN_ARTIST
        val clampedDuration = if (durationMs < 0L) 0 else if (durationMs > MAX_DURATION_MS.toLong()) MAX_DURATION_MS else durationMs.toInt()
        var full = SongTapPayload(
            v = VERSION, ts = if (nowMs < 0L) 0L else nowMs, ytVideoId = video,
            spotifyId = spotify,
            isrc = isrc?.takeIf { isIsrc(it) },
            title = cleanTitle, artist = cleanArtist, durationMs = clampedDuration,
            artUrl = artUrl?.takeIf { isAllowedArtUrl(it) },
        )
        if (encode(full) != null) return full
        full = full.copy(artUrl = null)
        if (encode(full) != null) return full
        // The Spotify id goes only when a video id stays, so the song can still be named.
        if (full.ytVideoId != null) {
            full = full.copy(spotifyId = null)
            if (encode(full) != null) return full
        }
        full = full.copy(isrc = null)
        return if (encode(full) != null) full else null
    }
}
