package com.theveloper.pixelplay.songtap

import com.theveloper.pixelplay.songtap.SessionEvent.*
import java.io.File
import java.security.MessageDigest

/**
 * Generates Song Tap's golden vectors. Run with `./gradlew :songtap:generateGolden`;
 * `SongTapGoldenTest` asserts that a fresh run equals the files that are checked in. The Swift port
 * (Packages/PixlCore/Sources/PixlLibrary/SongTap) must reproduce every `out` from its `in`, byte for byte.
 *
 * Everything here is deterministic: inputs are fixed lists and SplitMix64 streams with fixed seeds.
 * Format: JSON Lines `{"fn", "in", "out"}`, Int64 as decimal strings, bytes as lower-case hex, non-ASCII
 * escaped (see [GoldenJson]). The README.md written beside the files says how each `fn` is replayed.
 * Signatures are never part of a golden: only the bytes that are signed.
 */
object GoldenGenerator {

    const val GOLDEN_DIR = "src/test/resources/songtap-golden"
    const val MANIFEST = "MANIFEST.sha256"
    const val SPEC_VERSION_FILE = "SPEC_VERSION"
    const val SPEC_VERSION = 4

    @JvmStatic
    fun main(args: Array<String>) {
        val project = File(if (args.isNotEmpty()) args[0] else ".")
        val out = File(project, GOLDEN_DIR)
        out.mkdirs()
        val files = generate()
        for (old in out.listFiles().orEmpty()) if (old.isFile && old.name !in files) old.delete()
        for ((name, content) in files) File(out, name).writeBytes(content.toByteArray(Charsets.UTF_8))
        println("songtap golden: ${files.size} files written to ${out.absolutePath}")
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["payload.jsonl"] = payload()
        files["link.jsonl"] = link()
        files["signing.jsonl"] = signing()
        files["session.jsonl"] = session()
        files["detector.jsonl"] = detector()
        files["impulse.jsonl"] = impulse()
        files["ratelimit.jsonl"] = rateLimits()
        files["timeline.jsonl"] = timeline()
        files["haptics.jsonl"] = haptics()
        files["handshake.jsonl"] = handshake()
        files["wire.jsonl"] = wire()
        files.putAll(com.theveloper.pixelplay.songtap.ring.RingGolden.generate())
        files["README.md"] = README + com.theveloper.pixelplay.songtap.ring.RingGolden.README
        val manifest = StringBuilder()
        for ((name, content) in files) {
            manifest.append(sha256(content.toByteArray(Charsets.UTF_8))).append("  ").append(name).append('\n')
        }
        files[MANIFEST] = manifest.toString()
        files[SPEC_VERSION_FILE] = SPEC_VERSION.toString() + "\n"
        return files
    }

    private fun line(fn: String, input: GoldenJson.() -> Unit, output: GoldenJson.() -> Unit): String {
        val w = GoldenJson().obj()
        w.str("fn", fn)
        w.obj("in"); w.input(); w.end()
        w.obj("out"); w.output(); w.end()
        return w.end().toString() + "\n"
    }

    // ── payload.jsonl ────────────────────────────────────────────────────────────────

    private val BASE = SongTapPayload(
        v = 1, ts = 1_763_600_000_000L, ytVideoId = "dQw4w9WgXcQ", spotifyId = "4uLU6hMCjMI75M1A2tKUQC", isrc = "GBARL9300135",
        title = "Never Gonna Give You Up", artist = "Rick Astley", durationMs = 213_000,
        artUrl = "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg",
    )

    private fun GoldenJson.fields(p: SongTapPayload) {
        int("v", p.v); long("ts", p.ts); strOrNull("yt", p.ytVideoId); strOrNull("sp", p.spotifyId); strOrNull("isrc", p.isrc)
        str("t", p.title); str("a", p.artist); int("d", p.durationMs); strOrNull("art", p.artUrl)
    }

    private fun GoldenJson.decoded(r: PayloadDecode) {
        when (r) {
            is PayloadDecode.Ok -> { bool("ok", true); fields(r.payload) }
            is PayloadDecode.Rejected -> { bool("ok", false); str("reason", r.reason.code) }
        }
    }

    private fun payloadCases(): List<SongTapPayload> {
        val cases = ArrayList<SongTapPayload>()
        cases += BASE
        cases += BASE.copy(spotifyId = null)
        cases += BASE.copy(isrc = null)
        cases += BASE.copy(artUrl = null)
        cases += BASE.copy(spotifyId = null, isrc = null, artUrl = null)
        cases += BASE.copy(ts = 0L, durationMs = 0, title = "a", artist = "b")
        cases += BASE.copy(durationMs = SongTapPayloads.MAX_DURATION_MS)
        cases += BASE.copy(title = "\u6771\u4EAC\u30A8\u30EC\u30AF\u30C8\u30ED\u30F3 \u2014 \u591C\u306E\u6B4C", artist = "\u5E73\u91CE\u7DBE")
        cases += BASE.copy(title = "Caf\u00E9 del Mar \uD83C\uDFB5", artist = "Jos\u00E9 Padilla")
        cases += BASE.copy(title = "Say \"hello\" \\ goodbye", artist = "A/B")
        cases += BASE.copy(title = "x".repeat(100), artist = "y".repeat(80))
        cases += BASE.copy(title = "\u00E9".repeat(50), artist = "\u00E9".repeat(40))
        cases += BASE.copy(title = "\u6771".repeat(33), artist = "\u6771".repeat(26))
        cases += BASE.copy(ytVideoId = "a-b_c-d_e-f")
        cases += BASE.copy(ytVideoId = null)
        cases += BASE.copy(ytVideoId = null, isrc = null, artUrl = null)
        cases += BASE.copy(artUrl = "https://i.scdn.co/image/ab67616d0000b273")
        cases += BASE.copy(artUrl = "https://lh3.googleusercontent.com/abc=w544-h544-l90-rj")
        cases += BASE.copy(artUrl = "https://yt3.ggpht.com/a/xyz")
        // Rejected on purpose.
        cases += BASE.copy(v = 2)
        cases += BASE.copy(ts = -1L)
        cases += BASE.copy(ytVideoId = null, spotifyId = null)
        cases += BASE.copy(ytVideoId = "dQw4w9WgXc")
        cases += BASE.copy(ytVideoId = "dQw4w9WgXcQQ")
        cases += BASE.copy(ytVideoId = "dQw4w9WgX.Q")
        cases += BASE.copy(spotifyId = "short")
        cases += BASE.copy(spotifyId = "4uLU6hMCjMI75M1A2tKUQ-")
        cases += BASE.copy(isrc = "gbarl9300135")
        cases += BASE.copy(isrc = "GBARL930013")
        cases += BASE.copy(title = "")
        cases += BASE.copy(title = " leading")
        cases += BASE.copy(title = "trailing ")
        cases += BASE.copy(title = "two  spaces")
        cases += BASE.copy(title = "bell\u0007")
        cases += BASE.copy(title = "new\nline")
        cases += BASE.copy(title = "\u202Eevil")
        cases += BASE.copy(title = "zero\u200Bwidth")
        cases += BASE.copy(title = "x".repeat(101))
        cases += BASE.copy(artist = "")
        cases += BASE.copy(artist = "y".repeat(81))
        cases += BASE.copy(durationMs = -1)
        cases += BASE.copy(durationMs = SongTapPayloads.MAX_DURATION_MS + 1)
        cases += BASE.copy(artUrl = "http://i.ytimg.com/vi/x.jpg")
        cases += BASE.copy(artUrl = "https://evil.example/x.jpg")
        cases += BASE.copy(artUrl = "https://i.ytimg.com.evil.example/x.jpg")
        cases += BASE.copy(artUrl = "https://i.ytimg.com@evil.example/x.jpg")
        cases += BASE.copy(artUrl = "https://i.ytimg.com:443/x.jpg")
        cases += BASE.copy(artUrl = "https://I.YTIMG.COM/x.jpg")
        cases += BASE.copy(artUrl = "https://i.ytimg.com")
        cases += BASE.copy(artUrl = "https://i.ytimg.com/")
        cases += BASE.copy(artUrl = "https://i.ytimg.com/a b")
        cases += BASE.copy(artUrl = "https://i.ytimg.com/" + "p".repeat(100))
        cases += BASE.copy(title = "\u6771".repeat(33), artist = "\u6771".repeat(26), artUrl = "https://i.ytimg.com/" + "p".repeat(80), spotifyId = "4uLU6hMCjMI75M1A2tKUQC", isrc = "GBARL9300135")
        return cases
    }

    private fun handmade(): List<ByteArray> {
        val ok = "{\"v\":1,\"ts\":5,\"yt\":\"dQw4w9WgXcQ\",\"t\":\"a\",\"a\":\"b\",\"d\":0}"
        fun s(text: String) = text.toByteArray(Charsets.UTF_8)
        val out = ArrayList<ByteArray>()
        out += s(ok)
        out += s(ok.replace("{\"v\":1,", "{ \"v\":1,"))
        out += s(ok.replace(",\"ts\"", ", \"ts\""))
        out += s(ok + "\n")
        out += s(ok + ok)
        out += s("")
        out += s("{}")
        out += s("null")
        out += s(ok.replace("\"v\":1,\"ts\":5", "\"ts\":5,\"v\":1"))
        out += s(ok.replace("\"ts\":5", "\"ts\":05"))
        out += s(ok.replace("\"ts\":5", "\"ts\":-5"))
        out += s(ok.replace("\"ts\":5", "\"ts\":5.0"))
        out += s(ok.replace("\"ts\":5", "\"ts\":5e1"))
        out += s(ok.replace("\"v\":1", "\"v\":01"))
        out += s(ok.replace("\"d\":0", "\"d\":\"0\""))
        out += s(ok.replace("}", ",\"x\":1}"))
        out += s(ok.replace("\"t\":\"a\",", ""))
        out += s(ok.replace("\"yt\":\"dQw4w9WgXcQ\",", "\"sp\":\"4uLU6hMCjMI75M1A2tKUQC\","))
        out += s(ok.replace("\"yt\":\"dQw4w9WgXcQ\",", ""))
        out += s(ok.replace("\"t\":\"a\"", "\"t\":\"\\u0061\""))
        out += s(ok.replace("\"t\":\"a\"", "\"t\":\"\\/\""))
        out += s(ok.replace("\"t\":\"a\"", "\"t\":\"\\n\""))
        out += s(ok.replace("\"t\":\"a\"", "\"t\":\"a\\\"b\\\\c\""))
        out += s(ok.replace("\"t\":\"a\"", "\"t\":\"a"))
        out += s(ok.replace("\"yt\":\"dQw4w9WgXcQ\"", "\"yt\":\"dQw4w9WgXcQ\",\"sp\":\"4uLU6hMCjMI75M1A2tKUQC\""))
        out += s(ok.replace("\"yt\":\"dQw4w9WgXcQ\"", "\"yt\":\"dQw4w9WgXcQ\",\"sp\":\"4uLU6hMCjMI75M1A2tKUQC\",\"sp\":\"4uLU6hMCjMI75M1A2tKUQC\""))
        out += s(ok.replace("\"d\":0", "\"d\":0,\"art\":\"https://i.scdn.co/image/x\""))
        out += s(ok.replace("\"d\":0", "\"d\":0,\"art\":\"https://i.scdn.co@evil.example/x\""))
        out += s(ok.replace("\"d\":0", "\"d\":0,\"art\":null"))
        out += byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s(ok)
        val raw = s(ok)
        // Raw invalid UTF-8 inside the title: overlong NUL, lone continuation, truncated sequence, surrogate, too large.
        val at = String(raw, Charsets.ISO_8859_1).indexOf("\"t\":\"a\"") + 5
        fun withTitle(vararg b: Int): ByteArray {
            val head = raw.copyOfRange(0, at)
            val tail = raw.copyOfRange(at + 1, raw.size)
            return head + ByteArray(b.size) { b[it].toByte() } + tail
        }
        out += withTitle(0xC0, 0x80)
        out += withTitle(0x80)
        out += withTitle(0xE6, 0x9D)
        out += withTitle(0xED, 0xA0, 0x80)
        out += withTitle(0xF4, 0x90, 0x80, 0x80)
        out += withTitle(0xE2, 0x80, 0xAE)
        out += withTitle(0xF0, 0x9F, 0x8E, 0xB5)
        out += withTitle(0xC2, 0x85)
        out += ByteArray(401) { 0x61 }
        out += ByteArray(400) { 0x61 }
        return out
    }

    private fun mutate(rng: SplitMix64, source: ByteArray): ByteArray {
        val b = source.toMutableList()
        when (rng.nextInt(6)) {
            0 -> b[rng.nextInt(b.size)] = rng.nextInt(256).toByte()
            1 -> b.removeAt(rng.nextInt(b.size))
            2 -> b.add(rng.nextInt(b.size + 1), rng.nextInt(256).toByte())
            3 -> { val keep = rng.nextInt(b.size); while (b.size > keep) b.removeAt(b.size - 1) }
            4 -> { val i = rng.nextInt(b.size); b.add(i, b[i]) }
            else -> { val i = rng.nextInt(b.size); val j = rng.nextInt(b.size); val t = b[i]; b[i] = b[j]; b[j] = t }
        }
        return b.toByteArray()
    }

    private fun payload(): String {
        val sb = StringBuilder()
        val cases = payloadCases()
        val encoded = ArrayList<ByteArray>()
        for (c in cases) {
            val bytes = SongTapPayloads.encode(c)
            if (bytes != null) encoded += bytes
            sb.append(line("encode", { fields(c) }, { hex("hex", bytes) }))
        }
        val inputs = ArrayList<ByteArray>()
        inputs += encoded
        inputs += handmade()
        val rng = SplitMix64(0x50494C58L)
        for (i in 0 until 800) inputs += mutate(rng, encoded[rng.nextInt(encoded.size)])
        for (bytes in inputs) {
            sb.append(line("decode", { hex("hex", bytes) }, { decoded(SongTapPayloads.decode(bytes)) }))
        }
        // make: what the sender's builder does with raw text.
        class Raw(val yt: String?, val sp: String?, val isrc: String?, val t: String, val a: String, val d: Long, val art: String?)
        val raws = listOf(
            Raw("dQw4w9WgXcQ", "4uLU6hMCjMI75M1A2tKUQC", "GBARL9300135", "Never Gonna Give You Up", "Rick Astley", 213_000L, "https://i.ytimg.com/vi/dQw4w9WgXcQ/hqdefault.jpg"),
            Raw("dQw4w9WgXcQ", null, null, "  Spaced \t out \u00A0 title  ", "  ", 1L, null),
            Raw("dQw4w9WgXcQ", "bad", "bad", "T\u202Ee\u200Bs\u0000t", "Ar\u200Ftist", -5L, "http://i.scdn.co/x"),
            Raw("dQw4w9WgXcQ", null, null, "\u0007\u0008", "x", 5L, null),
            Raw("short", null, null, "t", "a", 5L, null),
            Raw("short", "4uLU6hMCjMI75M1A2tKUQC", null, "t", "a", 5L, null),
            Raw(null, "4uLU6hMCjMI75M1A2tKUQC", "GBARL9300135", "t", "a", 5L, null),
            Raw("dQw4w9WgXcQ", null, null, "x".repeat(300), "y".repeat(300), 99_999_999_999L, null),
            Raw("dQw4w9WgXcQ", "4uLU6hMCjMI75M1A2tKUQC", "GBARL9300135", "\u6771".repeat(60), "\u6771".repeat(60), 1000L, "https://i.ytimg.com/" + "p".repeat(90)),
            Raw("dQw4w9WgXcQ", "4uLU6hMCjMI75M1A2tKUQC", "GBARL9300135", "\uD83C\uDFB5".repeat(40), "\uD83C\uDFB5".repeat(30), 1000L, "https://i.ytimg.com/" + "p".repeat(90)),
            Raw("dQw4w9WgXcQ", null, null, "á combined", "a", 1L, null),
        )
        for (r in raws) {
            val made = SongTapPayloads.make(1_763_600_000_000L, r.yt, r.sp, r.isrc, r.t, r.a, r.d, r.art)
            sb.append(line("make", {
                strOrNull("yt", r.yt); strOrNull("sp", r.sp); strOrNull("isrc", r.isrc); str("t", r.t); str("a", r.a)
                long("d", r.d); strOrNull("art", r.art); long("now", 1_763_600_000_000L)
            }, {
                if (made == null) { bool("ok", false) } else { bool("ok", true); fields(made); hex("hex", SongTapPayloads.encode(made)) }
            }))
        }
        // sanitize: the text rules on their own.
        val texts = listOf(
            "Hello", "  a  b  ", "\u202Eevil\u202C", "a\u0000b", "tab\there", "\uD83C\uDFB5 song", "\u6771\u4EAC".repeat(40),
            "e\u0301", "zero\u200Dwidth\u200Djoiner", "\u00ADsoft", "\uFEFFbom", "line\u2028sep", "nbsp\u00A0here", "\uFFFD", "\uDBFF\uDFFF",
        )
        for (t in texts) for (cap in intArrayOf(100, 80, 5, 1)) {
            sb.append(line("sanitize", { str("text", t); int("max", cap) }, { str("text", SongTapText.sanitize(t, cap)) }))
        }
        return sb.toString()
    }

    // ── link.jsonl ───────────────────────────────────────────────────────────────────

    private fun link(): String {
        val sb = StringBuilder()
        val rng = SplitMix64(0x4C494E4BL)
        val sources = ArrayList<ByteArray>()
        for (c in payloadCases()) SongTapPayloads.encode(c)?.let { sources += it }
        for (n in 0..9) sources += ByteArray(n) { (rng.nextInt(256)).toByte() }
        for (bytes in sources) {
            val text = SongTapLink.encode(bytes)
            sb.append(line("linkEncode", { hex("hex", bytes) }, { str("link", text) }))
        }
        val links = ArrayList<String>()
        for (bytes in sources.take(6)) links += SongTapLink.encode(bytes)
        links += listOf(
            "", "pixlsong1:", "pixlsong2:AAAA", "pixlsong1:A", "pixlsong1:AA", "pixlsong1:AAA", "pixlsong1:AAAA", "pixlsong1:AAAAA",
            "pixlsong1:AB", "pixlsong1:AAB", "pixlsong1:AA==", "pixlsong1:AAA=", "pixlsong1:A+A", "pixlsong1:A/A", "pixlsong1:AA A",
            "PIXLSONG1:AAAA", " pixlsong1:AAAA", "pixlsong1:AAAA ", "pixlsong1:_-_-", "pixlsong1:\u00E9AAA",
        )
        for (l in links) sb.append(line("linkDecode", { str("link", l) }, { hex("hex", SongTapLink.decode(l)) }))
        return sb.toString()
    }

    // ── signing.jsonl ────────────────────────────────────────────────────────────────

    private fun pattern(n: Int, seed: Int): ByteArray = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    private fun signing(): String {
        val sb = StringBuilder()
        val payload = SongTapPayloads.encode(BASE)!!
        val challenges = listOf(pattern(16, 1), pattern(16, 99), pattern(15, 1), pattern(17, 1), pattern(0, 0))
        val stamps = longArrayOf(1_763_600_000_000L, 0L, Long.MAX_VALUE, 255L, 65_536L)
        for (c in challenges) for (t in stamps) {
            sb.append(line("signingBytes", { hex("payload", payload); hex("challenge", c); long("ts", t) }, { hex("hex", SongTapSigning.signingBytes(payload, c, t)) }))
        }
        val offers = ArrayList<SignedOffer>()
        offers += SignedOffer(payload, 1_763_600_000_000L, pattern(65, 3), pattern(64, 5))
        offers += SignedOffer(SongTapPayloads.encode(BASE.copy(spotifyId = null, isrc = null, artUrl = null))!!, 42L, pattern(65, 8), pattern(64, 9))
        offers += SignedOffer(payload, 0L, pattern(64, 3), pattern(64, 5))
        offers += SignedOffer(payload, 0L, pattern(65, 3), pattern(63, 5))
        offers += SignedOffer(ByteArray(0), 0L, pattern(65, 3), pattern(64, 5))
        offers += SignedOffer(ByteArray(401) { 0x61 }, 0L, pattern(65, 3), pattern(64, 5))
        val frames = ArrayList<ByteArray>()
        for (o in offers) {
            val frame = SongTapSigning.encodeFrame(o)
            if (frame != null) frames += frame
            sb.append(line("frameEncode", {
                hex("payload", o.payload); long("ts", o.timestampMs); hex("key", o.publicKey); hex("sig", o.signature)
            }, { hex("hex", frame) }))
        }
        val decodes = ArrayList<ByteArray>()
        decodes += frames
        val good = frames[0]
        decodes += good.copyOfRange(0, good.size - 1)
        decodes += good + byteArrayOf(0)
        decodes += byteArrayOf(2) + good.copyOfRange(1, good.size)
        decodes += ByteArray(0)
        decodes += byteArrayOf(1, 0, 0)
        decodes += ByteArray(513)
        for (f in decodes) {
            val o = SongTapSigning.decodeFrame(f)
            sb.append(line("frameDecode", { hex("hex", f) }, {
                if (o == null) bool("ok", false) else { bool("ok", true); hex("payload", o.payload); long("ts", o.timestampMs); hex("key", o.publicKey); hex("sig", o.signature) }
            }))
        }
        for (k in listOf(pattern(65, 3), pattern(65, 200), pattern(3, 1), pattern(4, 1))) {
            sb.append(line("senderKey", { hex("key", k) }, { int("key", SongTapSigning.senderKey(k)) }))
        }
        return sb.toString()
    }

    // ── session.jsonl ────────────────────────────────────────────────────────────────

    private class Trace(val name: String, val askFirst: Boolean, val events: List<SessionEvent>)

    private const val V1 = "dQw4w9WgXcQ"
    private const val V2 = "aaaaaaaaaaa"

    private fun traces(): List<Trace> {
        val t = ArrayList<Trace>()
        fun add(name: String, ask: Boolean, vararg e: SessionEvent) { t += Trace(name, ask, e.toList()) }
        run {
            add("send happy path", true, Arm(Role.SEND, true), PeerFound(1), PeerRole(1, Role.RECEIVE), PeerNear(1), TapDetected(1, V1),
                AckReceived(1000, 80), BeamFinished, Dismiss)
            add("send: nothing playing", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, ""), Dismiss)
            add("send: the receiver declines", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, V1), PeerDeclined(1), Dismiss)
            add("send: no acknowledgement", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, V1), Timeout(TimeoutTag.ACK), Dismiss)
            add("send: late acknowledgement after cancel", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, V1), Cancel, AckReceived(900, 50), Dismiss)
            add("send: duplicate acknowledgement", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, V1), AckReceived(900, 50), AckReceived(950, 50), BeamFinished, BeamFinished)
            add("send: peer drops mid transfer", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, V1), AckReceived(900, 50), PeerLost(1), Dismiss)
            add("send: a stranger's signal is ignored", true, Arm(Role.SEND, true), PeerFound(1), PeerFound(2), PeerLost(2), PeerNear(2), PeerNear(1), TapDetected(2, V1), TapDetected(1, V1))
            add("send: qr", true, Arm(Role.SEND, true), QrShown(V1), QrShown(V2), Cancel, Dismiss)
            add("send: qr with nothing playing", true, Arm(Role.SEND, true), QrShown(""), Dismiss)
            add("both phones send", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), PeerRole(1, Role.SEND), Dismiss)
            add("both phones send: offer arrives at the sender", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, V1), OfferReceived(1, V2, 5, 1000), Dismiss)
            add("both phones receive", true, Arm(Role.RECEIVE, true), PeerFound(1), PeerNear(1), PeerRole(1, Role.RECEIVE), TapDetected(1, ""), Timeout(TimeoutTag.READ), Dismiss)
            add("switch role while searching", true, Arm(Role.SEND, true), SetRole(Role.RECEIVE), PeerFound(1), PeerNear(1), SetRole(Role.SEND), SetRole(Role.SEND), TapDetected(1, V1))
            add("switch role is ignored mid offer", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), TapDetected(1, V1), SetRole(Role.RECEIVE))
            add("receive: first beam asks, then saves", true, Arm(Role.RECEIVE, true), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), Accept(1500), SaveResult(SaveOutcome.SAVED, 1800), GoReceived(1900, 60), Dismiss)
            add("receive: first beam declined", true, Arm(Role.RECEIVE, true), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, V1, 7, 1000), Decline, Dismiss)
            add("receive: consent card times out", true, Arm(Role.RECEIVE, true), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, V1, 7, 1000), Timeout(TimeoutTag.CONSENT), Dismiss)
            add("receive: later beams save at once and can be undone", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), SaveResult(SaveOutcome.SAVED, 1400), GoReceived(1500, 40), Undo(3000), Undo(3100))
            add("receive: save finishes before go", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), SaveResult(SaveOutcome.SAVED, 1100), GoReceived(1300, 40), Dismiss)
            add("receive: already in the library", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), SaveResult(SaveOutcome.ALREADY_HAD, 1100), Undo(1200), Dismiss)
            add("receive: not found", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), SaveResult(SaveOutcome.NOT_FOUND, 1100), Undo(1200), Dismiss)
            add("receive: save failed", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), SaveResult(SaveOutcome.FAILED, 1100), Dismiss)
            add("receive: save times out", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), Timeout(TimeoutTag.SAVE), SaveResult(SaveOutcome.SAVED, 5000), Dismiss)
            add("receive: duplicate offer is ignored", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), OfferReceived(1, V2, 7, 1010), SaveResult(SaveOutcome.SAVED, 1100))
            add("receive: nothing arrives", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), Timeout(TimeoutTag.READ), Timeout(TimeoutTag.READ), Dismiss)
            add("receive: cancel while saving undoes", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), Cancel, SaveResult(SaveOutcome.SAVED, 1100), Dismiss)
            add("receive: peer drops while saving", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), PeerLost(1), SaveResult(SaveOutcome.SAVED, 1100), Dismiss)
            add("receive: first beam, phones come apart before Add", true, Arm(Role.RECEIVE, true), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), PeerLost(1), Accept(9000), SaveResult(SaveOutcome.SAVED, 9200), Dismiss)
            add("receive: phones come apart while the beam is still being read", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), PeerLost(1), Dismiss)
            add("receive: not found tells the sender", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 10_000), SaveResult(SaveOutcome.NOT_FOUND, 10_100), Dismiss, TapDetected(1, ""), OfferReceived(1, V2, 7, 11_000))
            add("receive: disarm while saving stops only the transfer", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""),
                OfferReceived(1, V1, 7, 1000), Disarm)
            run {
                val events = ArrayList<SessionEvent>()
                events += Arm(Role.RECEIVE, false)
                var at = 10_000L
                for (v in listOf(V1, V2, "bbbbbbbbbbb", "ccccccccccc", "ddddddddddd")) {
                    events += OfferReceived(0, v, 0, at); events += SaveResult(SaveOutcome.SAVED, at + 100); events += Dismiss
                    at += 10_000L
                }
                events += OfferReceived(0, "eeeeeeeeeee", 0, at)
                t += Trace("receive: the fifth beam saved without asking makes the next one ask", false, events)
            }
            add("receive: disarm mid transfer", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferReceived(1, V1, 7, 1000), Disarm, Disarm)
            add("receive: qr, strangers and then a song", true, Arm(Role.RECEIVE, true), OfferRejected, OfferRejected, OfferReceived(0, V1, 0, 5000), Accept(6000), SaveResult(SaveOutcome.SAVED, 6100))
            add("receive: qr without asking", false, Arm(Role.RECEIVE, false), OfferReceived(0, V1, 0, 5000), SaveResult(SaveOutcome.SAVED, 5100), Dismiss)
            add("receive: bad offer after a tap", false, Arm(Role.RECEIVE, false), PeerFound(1), PeerNear(1), TapDetected(1, ""), OfferRejected, Dismiss)
            add("limits: two beams within three seconds", false, Arm(Role.RECEIVE, false), OfferReceived(0, V1, 3, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Dismiss,
                OfferReceived(0, V2, 3, 11_000), Dismiss, OfferReceived(0, V2, 3, 14_000), SaveResult(SaveOutcome.SAVED, 14_100), Dismiss)
            add("limits: the same song within a minute", false, Arm(Role.RECEIVE, false), OfferReceived(0, V1, 3, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Dismiss,
                OfferReceived(0, V1, 3, 20_000), Dismiss, OfferReceived(0, V1, 3, 80_000), SaveResult(SaveOutcome.SAVED, 80_100))
            add("limits: two undos mute the sender", false, Arm(Role.RECEIVE, false),
                OfferReceived(0, V1, 9, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Undo(11_000),
                OfferReceived(0, V2, 9, 20_000), SaveResult(SaveOutcome.SAVED, 20_100), Undo(21_000),
                OfferReceived(0, "bbbbbbbbbbb", 9, 30_000), Dismiss, OfferReceived(0, "bbbbbbbbbbb", 8, 31_000), SaveResult(SaveOutcome.SAVED, 31_100))
            add("limits: qr beams are never muted", false, Arm(Role.RECEIVE, false),
                OfferReceived(0, V1, 0, 10_000), SaveResult(SaveOutcome.SAVED, 10_100), Undo(11_000),
                OfferReceived(0, V2, 0, 20_000), SaveResult(SaveOutcome.SAVED, 20_100), Undo(21_000),
                OfferReceived(0, "bbbbbbbbbbb", 0, 30_000), SaveResult(SaveOutcome.SAVED, 30_100))
            add("idle ignores everything", true, PeerFound(1), TapDetected(1, V1), OfferReceived(0, V1, 1, 0), Accept(0), Undo(0), Dismiss, Cancel, Disarm, Timeout(TimeoutTag.ACK))
            add("arm twice", true, Arm(Role.SEND, true), Arm(Role.RECEIVE, false), PeerFound(1), Arm(Role.SEND, true))
            add("a peer that leaves while found", true, Arm(Role.SEND, true), PeerFound(1), PeerNear(1), PeerFar(1), PeerLost(1), PeerNear(1))
        }
        // Seeded random event streams.
        val rng = SplitMix64(0x53455353L)
        val vids = listOf(V1, V2, "bbbbbbbbbbb", "")
        for (n in 0 until 150) {
            val events = ArrayList<SessionEvent>()
            var now = 1_000L
            val ask = rng.nextInt(2) == 0
            events += SessionEvent.Arm(if (rng.nextInt(2) == 0) Role.SEND else Role.RECEIVE, ask)
            for (i in 0 until 28) {
                now += rng.nextInt(4000).toLong()
                val peer = 1 + rng.nextInt(2)
                val role = if (rng.nextInt(2) == 0) Role.SEND else Role.RECEIVE
                events += when (rng.nextInt(24)) {
                    0 -> SessionEvent.Disarm
                    1 -> SessionEvent.SetRole(role)
                    2, 3 -> SessionEvent.PeerFound(peer)
                    4 -> SessionEvent.PeerLost(peer)
                    5, 6 -> SessionEvent.PeerNear(peer)
                    7 -> SessionEvent.PeerFar(peer)
                    8 -> SessionEvent.PeerRole(peer, role)
                    9 -> SessionEvent.PeerDeclined(peer)
                    10, 11 -> SessionEvent.TapDetected(peer, vids[rng.nextInt(vids.size)])
                    12 -> SessionEvent.QrShown(vids[rng.nextInt(vids.size)])
                    13, 14 -> SessionEvent.OfferReceived(if (rng.nextInt(3) == 0) 0 else peer, vids[rng.nextInt(3)], 1 + rng.nextInt(2), now)
                    15 -> SessionEvent.OfferRejected
                    16 -> SessionEvent.AckReceived(now, rng.nextInt(300).toLong())
                    17 -> SessionEvent.GoReceived(now, rng.nextInt(300).toLong())
                    18 -> SessionEvent.BeamFinished
                    19 -> SessionEvent.Accept(now)
                    20 -> if (rng.nextInt(2) == 0) SessionEvent.Decline else SessionEvent.Cancel
                    21 -> SessionEvent.SaveResult(SaveOutcome.entries[rng.nextInt(4)], now)
                    22 -> if (rng.nextInt(2) == 0) SessionEvent.Undo(now) else SessionEvent.Dismiss
                    else -> SessionEvent.Timeout(TimeoutTag.entries[rng.nextInt(4)])
                }
                if (rng.nextInt(10) == 0) events += SessionEvent.Arm(role, ask)
            }
            t += Trace("random $n", ask, events)
        }
        return t
    }

    private fun session(): String {
        val sb = StringBuilder()
        for (trace in traces()) {
            val steps = SongTapSession.run(SessionState.initial(trace.askFirst), trace.events)
            val w = GoldenJson().obj()
            w.str("fn", "trace")
            w.obj("in"); w.str("name", trace.name); w.bool("askFirst", trace.askFirst)
            w.arr("events"); for (e in trace.events) w.strItem(e.describe()); w.endArr(); w.end()
            w.obj("out")
            w.arr("steps")
            for (s in steps) {
                w.obj(); w.str("state", s.state.describe())
                w.arr("effects"); for (x in s.effects) w.strItem(x.describe()); w.endArr()
                w.end()
            }
            w.endArr(); w.end()
            sb.append(w.end().toString()).append('\n')
        }
        return sb.toString()
    }

    // ── detector.jsonl, impulse.jsonl ────────────────────────────────────────────────

    private class Sig(val t: Long, val rssi: Int, val impulse: Long)

    private fun detectorTraces(): List<Pair<String, List<Sig>>> {
        val out = ArrayList<Pair<String, List<Sig>>>()
        fun series(name: String, segments: List<Triple<Int, Int, Int>>, step: Long = 100L, impulseAt: Long = -1L, startT: Long = 0L) {
            val list = ArrayList<Sig>()
            var t = startT
            for ((count, rssi, _) in segments) repeat(count) {
                list += Sig(t, rssi, if (impulseAt in 0..t) impulseAt else -1L)
                t += step
            }
            out += name to list
        }
        series("steady", listOf(Triple(40, -60, 0)))
        series("tap with a jerk", listOf(Triple(20, -62, 0), Triple(10, -42, 0)), impulseAt = 2_050L)
        series("tap without a jerk", listOf(Triple(20, -62, 0), Triple(10, -42, 0)))
        series("tap with a jerk long before", listOf(Triple(20, -62, 0), Triple(10, -42, 0)), impulseAt = 100L)
        series("a short spike", listOf(Triple(20, -62, 0), Triple(1, -40, 0), Triple(20, -62, 0)))
        series("a slow approach", listOf(Triple(10, -85, 0), Triple(10, -75, 0), Triple(10, -65, 0), Triple(10, -55, 0), Triple(10, -45, 0)))
        series("jump too far away", listOf(Triple(20, -95, 0), Triple(10, -78, 0)), impulseAt = 2_000L)
        series("jump just inside near", listOf(Triple(20, -90, 0), Triple(10, -69, 0)), impulseAt = 2_000L)
        series("two taps close together", listOf(Triple(20, -62, 0), Triple(10, -42, 0), Triple(5, -62, 0), Triple(10, -42, 0)), impulseAt = 2_050L)
        series("a gap in the signal", listOf(Triple(10, -62, 0), Triple(5, -42, 0)), startT = 0L)
        out += "a gap resets the peer" to listOf(
            Sig(0, -62, -1), Sig(100, -62, -1), Sig(200, -62, -1), Sig(300, -42, -1), Sig(400, -42, -1), Sig(5_000, -42, -1), Sig(5_100, -42, -1), Sig(5_200, -42, -1),
        )
        series("phones stay together", listOf(Triple(20, -62, 0), Triple(80, -42, 0)), impulseAt = 2_050L)
        series("phones stay together, no jerk", listOf(Triple(20, -62, 0), Triple(80, -42, 0)))
        series("a slow approach that stops short", (0 until 30).map { Triple(1, -90 + it, 0) } + listOf(Triple(40, -61, 0)))
        series("a slow approach to contact", (0 until 45).map { Triple(1, -90 + it, 0) } + listOf(Triple(20, -45, 0)))
        series("tap, apart for a second, tap again", listOf(Triple(20, -62, 0), Triple(5, -42, 0), Triple(12, -62, 0), Triple(14, -42, 0)), impulseAt = 2_050L)
        series("signal fades away", listOf(Triple(10, -50, 0), Triple(10, -65, 0), Triple(10, -80, 0), Triple(10, -95, 0)))
        val rng = SplitMix64(0x44455443L)
        for (n in 0 until 60) {
            val list = ArrayList<Sig>()
            var t = 0L
            var level = -90 + rng.nextInt(40)
            var impulse = -1L
            for (i in 0 until 80) {
                t += 50L + rng.nextInt(100)
                if (rng.nextInt(12) == 0) level = -95 + rng.nextInt(60)
                if (rng.nextInt(20) == 0) impulse = t - rng.nextInt(300)
                list += Sig(t, level + rng.nextInt(7) - 3, impulse)
            }
            out += "random $n" to list
        }
        return out
    }

    private fun detector(): String {
        val sb = StringBuilder()
        for ((name, samples) in detectorTraces()) {
            val w = GoldenJson().obj()
            w.str("fn", "detector")
            w.obj("in"); w.str("name", name)
            w.arr("samples"); for (s in samples) w.strItem("${s.t},${s.rssi},${s.impulse}"); w.endArr(); w.end()
            w.obj("out").arr("steps")
            var state = DetectorState.INITIAL
            for (s in samples) {
                val step = SongTapDetector.step(state, RssiSample(s.t, s.rssi), s.impulse)
                state = step.state
                w.strItem("${step.fire.code},${step.glowPermille},${state.baselineQ},${state.fastQ},${if (state.near) 1 else 0}")
            }
            w.endArr(); w.end()
            sb.append(w.end().toString()).append('\n')
        }
        for (q in intArrayOf(-100 * 256, -80 * 256, -79 * 256 - 1, -62 * 256, -45 * 256, -44 * 256, 0)) {
            sb.append(line("glow", { int("fastQ", q) }, { int("permille", SongTapDetector.glowPermille(q)) }))
        }
        for (a in intArrayOf(-70 * 256, -70 * 256 + 1, -1, 0, 1, 255, 256, -255, -256, -257)) {
            sb.append(line("floorDiv", { int("a", a) }, { int("q", SongTapDetector.floorDiv(a, 256)) }))
        }
        return sb.toString()
    }

    private fun impulse(): String {
        val sb = StringBuilder()
        val traces = ArrayList<Pair<String, List<AccelSample>>>()
        fun mk(name: String, vararg rows: IntArray) {
            var t = 0L
            traces += name to rows.map { r -> AccelSample(t, r[0], r[1], r[2]).also { t += 20L } }
        }
        mk("still", intArrayOf(0, 0, 1000), intArrayOf(5, -3, 998), intArrayOf(-4, 6, 1003), intArrayOf(2, 2, 999))
        mk("one bump", intArrayOf(0, 0, 1000), intArrayOf(0, 0, 1000), intArrayOf(300, -200, 4200), intArrayOf(100, 0, 700), intArrayOf(0, 0, 1000))
        mk("two bumps inside the debounce", intArrayOf(0, 0, 1000), intArrayOf(0, 0, 4000), intArrayOf(0, 0, 1000), intArrayOf(0, 0, 4000), intArrayOf(0, 0, 1000))
        mk("a sideways knock", intArrayOf(0, 0, 1000), intArrayOf(3000, 0, 1000), intArrayOf(0, 0, 1000))
        mk("a slow tilt", intArrayOf(0, 0, 1000), intArrayOf(300, 0, 950), intArrayOf(600, 0, 800), intArrayOf(900, 0, 450), intArrayOf(1000, 0, 0))
        val rng = SplitMix64(0x4A45524BL)
        for (n in 0 until 40) {
            val rows = ArrayList<AccelSample>()
            var t = 0L
            for (i in 0 until 60) {
                t += 10L + rng.nextInt(40)
                val spike = if (rng.nextInt(15) == 0) 2000 + rng.nextInt(4000) else 0
                rows += AccelSample(t, rng.nextInt(60) - 30 + spike / 3, rng.nextInt(60) - 30, 1000 + rng.nextInt(60) - 30 + spike)
            }
            traces += "random $n" to rows
        }
        for ((name, rows) in traces) {
            val w = GoldenJson().obj()
            w.str("fn", "impulse")
            w.obj("in"); w.str("name", name)
            w.arr("samples"); for (r in rows) w.strItem("${r.tMs},${r.xMg},${r.yMg},${r.zMg}"); w.endArr(); w.end()
            w.obj("out").arr("hits")
            var state = ImpulseState.INITIAL
            for (r in rows) {
                val step = SongTapImpulse.step(state, r)
                state = step.state
                w.intItem(if (step.impulse) 1 else 0)
            }
            w.endArr(); w.end()
            sb.append(w.end().toString()).append('\n')
        }
        return sb.toString()
    }

    // ── ratelimit.jsonl ──────────────────────────────────────────────────────────────

    private fun rateLimits(): String {
        val sb = StringBuilder()
        val scripts = ArrayList<Pair<String, List<String>>>()
        scripts += "gap" to listOf("admit,0,a,1", "accepted,0,a", "admit,2999,b,1", "admit,3000,b,1")
        scripts += "same song" to listOf("accepted,0,a", "admit,10000,a,1", "admit,59999,a,1", "admit,60000,a,1", "admit,60000,b,1")
        val minute = ArrayList<String>()
        for (i in 0 until 11) { minute += "admit,${i * 5000},v$i,1"; minute += "accepted,${i * 5000},v$i" }
        minute += "admit,56000,vx,1"; minute += "admit,61000,vx,1"
        scripts += "ten a minute" to minute
        val hour = ArrayList<String>()
        for (i in 0 until 31) { hour += "admit,${i * 100_000},h$i,1"; hour += "accepted,${i * 100_000},h$i" }
        hour += "admit,3100001,hx,1"; hour += "admit,3700000,hx,1"
        scripts += "thirty an hour" to hour
        scripts += "an unknown sender is never muted" to listOf("undone,0,0", "undone,1000,0", "admit,2000,a,0")
        scripts += "attempts count" to listOf("attempted,0", "admit,2999,a,1", "admit,3000,a,1", "attempted,3000", "admit,4000,a,1")
        val attempts = ArrayList<String>()
        for (i in 0 until 11) { attempts += "admit,${i * 5000},v$i,1"; attempts += "attempted,${i * 5000}" }
        attempts += "admit,56000,vx,1"; attempts += "admit,61000,vx,1"
        scripts += "ten attempts a minute" to attempts
        scripts += "mute" to listOf("undone,0,5", "admit,100,a,5", "undone,1000,5", "admit,1001,a,5", "admit,1001,a,6", "admit,3601000,a,5", "admit,3601001,a,5")
        val rng = SplitMix64(0x52415445L)
        for (n in 0 until 40) {
            val ops = ArrayList<String>()
            var now = 0L
            for (i in 0 until 40) {
                now += rng.nextInt(20_000).toLong()
                val v = "v${rng.nextInt(4)}"
                val key = 1 + rng.nextInt(2)
                ops += when (rng.nextInt(5)) {
                    0, 1 -> "admit,$now,$v,$key"
                    2 -> "accepted,$now,$v"
                    3 -> "attempted,$now"
                    else -> "undone,$now,$key"
                }
            }
            scripts += "random $n" to ops
        }
        for ((name, ops) in scripts) {
            var limits = RateLimits()
            val verdicts = ArrayList<String>()
            for (op in ops) {
                val p = op.split(',')
                when (p[0]) {
                    "admit" -> verdicts += limits.admit(p[1].toLong(), p[2], p[3].toInt()).code
                    "accepted" -> { limits = limits.accepted(p[1].toLong(), p[2]); verdicts += "-" }
                    "attempted" -> { limits = limits.attempted(p[1].toLong()); verdicts += "-" }
                    else -> { limits = limits.undone(p[1].toLong(), p[2].toInt()); verdicts += "-" }
                }
            }
            val w = GoldenJson().obj()
            w.str("fn", "limits")
            w.obj("in"); w.str("name", name); w.arr("ops"); for (o in ops) w.strItem(o); w.endArr(); w.end()
            w.obj("out").arr("verdicts"); for (v in verdicts) w.strItem(v); w.endArr(); w.end()
            sb.append(w.end().toString()).append('\n')
        }
        return sb.toString()
    }

    // ── timeline.jsonl, haptics.jsonl, handshake.jsonl ───────────────────────────────

    private fun timeline(): String {
        val sb = StringBuilder()
        for (x in 0..1000 step 25) {
            sb.append(line("ease", { int("x", x) }, {
                int("outCubic", SongTapTimeline.easeOutCubic(x)); int("inCubic", SongTapTimeline.easeInCubic(x))
                int("smooth", SongTapTimeline.smooth(x)); int("outBack", SongTapTimeline.easeOutBack(x))
            }))
        }
        val times = ArrayList<Long>()
        times += -100L
        for (t in 0L..2000L step 100L) times += t
        times += listOf(2500L, 6000L, 6900L, 7000L, 7050L, 7100L, 7150L, 7200L, 7250L, 7300L, 7400L, 8000L)
        for (role in Role.entries) for (reduced in listOf(false, true)) for (t in times) {
            val f = SongTapTimeline.frame(role, t, reduced)
            sb.append(line("frame", { str("role", role.code); long("t", t); bool("reduced", reduced) }, { str("frame", f.describe()) }))
        }
        for (role in Role.entries) for (reduced in listOf(false, true)) {
            sb.append(line("total", { str("role", role.code); bool("reduced", reduced) }, { long("ms", SongTapTimeline.totalMs(role, reduced)) }))
        }
        return sb.toString()
    }

    private fun haptics(): String {
        val sb = StringBuilder()
        for (role in Role.entries) for (reduced in listOf(false, true)) {
            sb.append(line("cues", { str("role", role.code); bool("reduced", reduced) }, {
                arr("cues"); for (c in SongTapHaptics.cues(role, reduced)) strItem(c.describe()); endArr()
            }))
        }
        return sb.toString()
    }

    private fun handshake(): String {
        val sb = StringBuilder()
        for (now in longArrayOf(0L, 1000L, 1_763_600_000_000L)) {
            sb.append(line("senderStart", { long("now", now) }, { long("ms", SongTapHandshake.senderStartMs(now)) }))
        }
        for (rtt in longArrayOf(-5L, 0L, 1L, 40L, 81L, 999L, 1000L, 1001L, 50_000L)) {
            sb.append(line("receiverStart", { long("receipt", 10_000L); long("rtt", rtt) }, { long("ms", SongTapHandshake.receiverStartMs(10_000L, rtt)) }))
        }
        for ((now, at) in listOf(1000L to 900L, 1000L to 1000L, 1000L to 1001L, 1000L to -1L, 1000L to 0L)) {
            sb.append(line("impulseAge", { long("now", now); long("impulse", at) }, { long("ms", SongTapHandshake.impulseAgeMs(now, at)) }))
        }
        for ((age, rtt) in listOf(100L to 40L, 0L to 0L, -1L to 40L, 500L to 5000L, 250L to 81L)) {
            sb.append(line("peerImpulse", { long("receipt", 10_000L); long("age", age); long("rtt", rtt) }, { long("ms", SongTapHandshake.peerImpulseLocalMs(10_000L, age, rtt)) }))
        }
        return sb.toString()
    }

    // ── wire.jsonl ───────────────────────────────────────────────────────────────────

    private fun wire(): String {
        val sb = StringBuilder()
        sb.append(line("constants", { }, {
            str("sendService", SongTapWire.SERVICE_SEND); str("receiveService", SongTapWire.SERVICE_RECEIVE)
            str("info", SongTapWire.CHAR_INFO); str("offer", SongTapWire.CHAR_OFFER)
            str("control", SongTapWire.CHAR_CONTROL); str("event", SongTapWire.CHAR_EVENT)
            int("notReady", SongTapWire.ERROR_NOT_READY); int("mtu", SongTapWire.WANTED_MTU)
        }))
        for (age in longArrayOf(-1L, 0L, 1L, 150L, 4_999L, 5_000L, 5_001L, 1_000_000L)) {
            sb.append(line("infoEncode", { long("age", age) }, { hex("hex", SongTapWire.encodeInfo(age)) }))
        }
        val infos = listOf(
            "0100000000", "0100001388", "0100001389", "01ffffffff", "0200000000", "00", "", "010000", "010000000000", "01000000ff",
        )
        for (h in infos) {
            sb.append(line("infoDecode", { str("hex", h) }, { val v = SongTapWire.decodeInfo(fromHex(h)); if (v == null) bool("ok", false) else { bool("ok", true); long("age", v) } }))
        }
        val challenge = ByteArray(16) { (it * 17 + 3).toByte() }
        sb.append(line("helloEncode", { hex("challenge", challenge) }, { hex("hex", SongTapWire.encodeHello(challenge)) }))
        sb.append(line("helloEncode", { hex("challenge", ByteArray(15)) }, { hex("hex", SongTapWire.encodeHello(ByteArray(15))) }))
        sb.append(line("ackEncode", { }, { hex("hex", SongTapWire.encodeAck()) }))
        sb.append(line("declineEncode", { }, { hex("hex", SongTapWire.encodeDecline()) }))
        sb.append(line("goEncode", { }, { hex("hex", SongTapWire.encodeGo()) }))
        for (age in longArrayOf(-1L, 0L, 250L, 5_000L, 5_001L)) {
            sb.append(line("tapEncode", { long("age", age) }, { hex("hex", SongTapWire.encodeTap(age)) }))
        }
        val controls = listOf(
            "01" + toHex(challenge), "01" + toHex(challenge) + "00", "0100", "02", "0200", "03", "0300", "0400000000fa", "0400000000", "04ffffffff",
            "0400001389", "05", "00", "", "ff", "04000000", "0400000000fa00",
        )
        for (h in controls) {
            sb.append(line("controlDecode", { str("hex", h) }, {
                when (val m = SongTapWire.decodeControl(fromHex(h))) {
                    null -> str("kind", "none")
                    is SongTapWire.ControlMessage.Hello -> { str("kind", "hello"); hex("challenge", m.challenge) }
                    SongTapWire.ControlMessage.Ack -> str("kind", "ack")
                    SongTapWire.ControlMessage.Decline -> str("kind", "decline")
                    is SongTapWire.ControlMessage.Tap -> { str("kind", "tap"); long("age", m.ageMs) }
                }
            }))
        }
        for (h in listOf("01", "00", "02", "0101", "")) {
            sb.append(line("isGo", { str("hex", h) }, { bool("go", SongTapWire.isGo(fromHex(h))) }))
        }
        for (u in listOf(SongTapWire.SERVICE_SEND, SongTapWire.SERVICE_RECEIVE, SongTapWire.CHAR_INFO, "B7F3A1C0-5D2E-4C9A-8E61-3F4D7A2B9C01")) {
            sb.append(line("roleOfService", { str("uuid", u) }, { strOrNull("role", SongTapWire.roleOfService(u)?.code) }))
        }
        return sb.toString()
    }

    private fun toHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    private fun fromHex(h: String): ByteArray = ByteArray(h.length / 2) { h.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    private val README = """
        # Song Tap golden vectors

        Written by `./gradlew :songtap:generateGolden`. Do not edit by hand. The iOS repo copies this
        folder byte for byte to `Fixtures/songtap`; `MANIFEST.sha256` lists every file's SHA-256 and
        `SPEC_VERSION` is the version of the pure core they describe.

        Format: JSON Lines, one `{"fn", "in", "out"}` object per line. Int64 values are decimal strings,
        bytes are lower-case hex strings, every character outside printable ASCII is escaped as `\uXXXX`
        (UTF-16 units). A port reads each line, runs the function named by `fn` on `in` and must produce
        exactly `out`. Signatures are never part of a golden, only the bytes that are signed.

        - payload.jsonl
          - `encode` in: the fields (`v`, `ts`, `yt`, `sp`, `isrc`, `t`, `a`, `d`, `art`; null for absent);
            out: `hex` of the canonical bytes, or null when `encode` returns nothing.
          - `decode` in: `hex`; out: `ok` and the fields, or `ok:false` and a `reason` code. Includes
            hand-made hostile inputs and 800 seeded mutations of valid payloads.
          - `make` in: raw text and ids and `now`; out: `ok` and the cleaned fields and `hex`.
          - `sanitize` in: `text` and `max` (bytes); out: `text`. The text rules on their own.
        - link.jsonl: `linkEncode` (hex to `pixlsong1:` text), `linkDecode` (text to hex or null).
        - signing.jsonl: `signingBytes` (payload, challenge, ts to hex or null), `frameEncode`,
          `frameDecode`, `senderKey`.
        - session.jsonl: `trace`: start from `SessionState.initial(askFirst)`, apply each event; `out.steps`
          has the state description and the effect descriptions after each. Event grammar:
          `name(arg,arg,...)` with the arguments exactly as `SessionEvent.describe()` prints them (roles
          `send`/`receive`, `-` for an empty video id, outcomes `saved|already_had|not_found|failed`, timeout
          tags `ack|read|consent|save`).
        - detector.jsonl: `detector`: samples `"t,rssi,impulseMs"` from `DetectorState.INITIAL`; each step is
          `"fire,glow,baselineQ,fastQ,near"`. Also `glow` and `floorDiv`.
        - impulse.jsonl: `impulse`: samples `"t,x,y,z"` from `ImpulseState.INITIAL`; out `hits` 0/1.
        - ratelimit.jsonl: `limits`: ops `admit,now,videoId,key`, `accepted,now,videoId`, `attempted,now`, `undone,now,key`
          from an empty `RateLimits`; out `verdicts` (`-` for the two that return no verdict).
        - timeline.jsonl: `ease`, `frame` (role, t, reduced), `total`.
        - wire.jsonl: the Bluetooth wire format (`SongTapWire`): `constants` (the UUIDs), `infoEncode`, `infoDecode`,
          `helloEncode`, `ackEncode`, `declineEncode`, `goEncode`, `tapEncode`, `controlDecode` (`kind` hello, ack, decline,
          tap or none), `isGo`, `roleOfService` (case sensitive).
        - haptics.jsonl: `cues`. handshake.jsonl: `senderStart`, `receiverStart`, `impulseAge`, `peerImpulse`.
    """.trimIndent() + "\n"
}
