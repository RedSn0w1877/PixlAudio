package com.theveloper.pixelplay.brain

import java.io.File
import java.security.MessageDigest

/**
 * Generates the stage 1 golden vectors (docs/brain-spec.md, 10.4). Run with
 * `./gradlew :brain:generateGolden`; `BrainGoldenTest` asserts that a fresh run equals the files that
 * are checked in. The Swift port must reproduce every `out` from its `in`, byte for byte.
 *
 * Everything here is deterministic: inputs come from SplitMix64 streams with fixed seeds and from the
 * two corpus files next to the golden folder. Format: JSON Lines, `{"fn", "in", "out"}`, doubles as
 * 16-hex-digit bit patterns, Int64 as decimal strings, non-ASCII escaped (see [BrainJson]). The
 * README.md written beside the files says how each `fn` is replayed.
 */
object GoldenGenerator {

    const val GOLDEN_DIR = "src/test/resources/brain-golden"
    const val CORPUS_DIR = "src/test/resources/brain-corpus"
    const val MANIFEST = "MANIFEST.sha256"
    const val SPEC_VERSION_FILE = "SPEC_VERSION"

    @JvmStatic
    fun main(args: Array<String>) {
        val project = File(if (args.isNotEmpty()) args[0] else ".")
        val out = File(project, GOLDEN_DIR)
        out.mkdirs()
        val files = generate(File(project, CORPUS_DIR))
        for (old in out.listFiles().orEmpty()) if (old.isFile && old.name !in files) old.delete()
        for ((name, content) in files) File(out, name).writeBytes(content.toByteArray(Charsets.UTF_8))
        println("brain golden: ${files.size} files written to ${out.absolutePath}")
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** File name to content, in a fixed order; the manifest and the version file come last. */
    fun generate(corpusDir: File): LinkedHashMap<String, String> {
        val names = readCorpus(File(corpusDir, "names.txt"))
        val titles = readCorpus(File(corpusDir, "titles.txt"))
        val files = LinkedHashMap<String, String>()
        files["math.jsonl"] = math()
        files["hash.jsonl"] = hash(names)
        files["fold.jsonl"] = fold(names)
        files["title.jsonl"] = title(titles)
        files["ids.jsonl"] = ids(names, titles)
        files["reward.jsonl"] = reward()
        files["affinity.jsonl"] = affinity()
        files["retrieval.jsonl"] = retrieval()
        files["session.jsonl"] = session()
        files["lists.jsonl"] = lists()
        files["README.md"] = README
        val manifest = StringBuilder()
        for ((name, content) in files) {
            manifest.append(sha256(content.toByteArray(Charsets.UTF_8))).append("  ").append(name).append('\n')
        }
        files[MANIFEST] = manifest.toString()
        files[SPEC_VERSION_FILE] = Brain.SPEC_VERSION.toString() + "\n"
        return files
    }

    private fun readCorpus(file: File): List<String> {
        val text = String(file.readBytes(), Charsets.UTF_8).replace("\r\n", "\n")
        val lines = text.split('\n')
        return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    private fun line(fn: String, input: BrainJson.Writer.() -> Unit, output: BrainJson.Writer.() -> Unit): String {
        val w = BrainJson.Writer().beginObject()
        w.string("fn", fn)
        w.beginObject("in"); w.input(); w.endObject()
        w.beginObject("out"); w.output(); w.endObject()
        return w.endObject().toString() + "\n"
    }

    private fun BrainHash.Rng.int(n: Int): Int {
        val v = (uniform() * n).toInt()
        return if (v >= n) n - 1 else v
    }

    private fun BrainHash.Rng.chance(p: Double): Boolean = uniform() < p

    // ── math.jsonl ───────────────────────────────────────────────────────────

    private fun randomDouble(rng: BrainHash.Rng, minExp: Int, maxExp: Int): Double {
        val exp = (1023 + minExp + rng.int(maxExp - minExp + 1)).toLong()
        return Double.fromBits((exp shl 52) or (rng.nextLong() ushr 12))
    }

    private fun math(): String {
        val sb = StringBuilder()
        val edges = doubleArrayOf(
            0.0, -0.0, 1.0, -1.0, 2.0, 0.5, 4.0, 1e-300, 1e300, Double.MIN_VALUE, Double.MAX_VALUE,
            java.lang.Double.MIN_NORMAL, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
            BrainMath.SQRT2, BrainMath.LN2, 0.9999999999999999, 1.0000000000000002, 700.0, -700.0, 709.0, -745.0,
            1e-17, -1e-17, 0.34657359027997264, -0.34657359027997264, 36.0, -36.0, 3.0, 10.0, 0.1,
        )
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:math"))
        // ln and log2: every exponent the engine can meet, plus the edges.
        for (fn in arrayOf("ln", "log2")) {
            for (i in 0 until 2000) {
                val x = if (i < edges.size) edges[i] else when (i % 4) {
                    0 -> randomDouble(rng, -60, 60)
                    1 -> randomDouble(rng, -2, 2)
                    2 -> 1.0 + (rng.uniform() - 0.5) * 1e-3
                    else -> randomDouble(rng, -1022, 1023)
                }
                val y = if (fn == "ln") BrainMath.ln(x) else BrainMath.log2(x)
                sb.append(line(fn, { double("x", x) }, { double("y", y) }))
            }
        }
        for (i in 0 until 2000) {
            val x = if (i < edges.size) edges[i] else when (i % 4) {
                0 -> (rng.uniform() * 2.0 - 1.0) * 710.0
                1 -> (rng.uniform() * 2.0 - 1.0) * 5.0
                2 -> (rng.uniform() * 2.0 - 1.0) * 1e-6
                else -> (rng.uniform() * 2.0 - 1.0) * 50.0
            }
            sb.append(line("exp", { double("x", x) }, { double("y", BrainMath.exp(x)) }))
        }
        for (i in 0 until 2000) {
            val x = if (i < edges.size) edges[i] else when (i % 3) {
                0 -> (rng.uniform() * 2.0 - 1.0) * 40.0
                1 -> (rng.uniform() * 2.0 - 1.0) * 4.0
                else -> (rng.uniform() * 2.0 - 1.0) * 800.0
            }
            sb.append(line("sigmoid", { double("x", x) }, { double("y", BrainMath.sigmoid(x)) }))
        }
        val ds = doubleArrayOf(0.3, 0.5, 0.7, 0.9, 1.2)
        for (i in 0 until 2000) {
            val x = if (i < edges.size) edges[i]
            else if (i % 2 == 0) 1.0 + rng.uniform() * 9000.0
            else randomDouble(rng, -10, 20)
            val d = if (i % 3 == 0) rng.uniform() * 2.0 else ds[i % ds.size]
            sb.append(line("powNeg", { double("x", x); double("d", d) }, { double("y", BrainMath.powNeg(x, d)) }))
        }
        // Counters and ordering keys ride on the same file: they are maths too.
        for (i in 0 until 200) {
            val x = if (i < edges.size) edges[i] else (rng.uniform() - 0.2) * (if (i % 2 == 0) 50.0 else 2e12)
            sb.append(line("toQ", { double("x", x) }, { long("q", BrainMath.toQ(x)) }))
            sb.append(line("orderKey", { double("x", x) }, { long("k", BrainMath.orderKey(x)) }))
        }
        for (i in 0 until 100) {
            val ms = (rng.nextLong() shr 20) // both signs
            val tz = rng.int(1681) - 840
            sb.append(
                line("localDay", { long("ms", ms); int("tzOffsetMin", tz) }, {
                    val day = BrainMath.localDay(ms, tz)
                    long("day", day)
                    int("weekday", BrainMath.weekday(day))
                    bool("weekend", BrainMath.isWeekend(ms, tz))
                })
            )
        }
        return sb.toString()
    }

    // ── hash.jsonl, fold.jsonl, title.jsonl ──────────────────────────────────

    private fun hash(names: List<String>): String {
        val sb = StringBuilder()
        for (i in 0 until 300) {
            val text = names[i]
            sb.append(line("fnv1a64", { string("text", text) }, { long("h", BrainHash.fnv1a64(text)) }))
        }
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:hash"))
        for (i in 0 until 100) {
            val bytes = ByteArray(rng.int(40)) { rng.nextLong().toByte() }
            val hex = bytes.joinToString("") { "%02x".format(it) }
            sb.append(line("fnv1a64Bytes", { string("hex", hex) }, { long("h", BrainHash.fnv1a64(bytes)) }))
        }
        for (i in 0 until 300) {
            val state = if (i < 4) longArrayOf(0L, -1L, 1L, Long.MIN_VALUE)[i] else rng.nextLong()
            sb.append(line("splitmix64", { long("state", state) }, { long("z", BrainHash.splitmix64(state)) }))
        }
        for (i in 0 until 100) {
            val seed = if (i == 0) 0L else rng.nextLong()
            val u = BrainHash.Rng(seed)
            val g = BrainHash.Rng(seed)
            sb.append(
                line("rng", { long("seed", seed); int("n", 8) }, {
                    beginArray("uniform"); repeat(8) { doubleItem(u.uniform()) }; endArray()
                    beginArray("gaussian12"); repeat(8) { doubleItem(g.gaussian12()) }; endArray()
                })
            )
        }
        // Unsigned use of a hash (10.3, rule 10): the remainders the engine takes.
        for (i in 0 until 100) {
            val h = rng.nextLong()
            sb.append(
                line("unsigned", { long("h", h) }, {
                    int("mod6", java.lang.Long.remainderUnsigned(h, 6L).toInt())
                    int("mod10", java.lang.Long.remainderUnsigned(h, 10L).toInt())
                    bool("lessThanMixed", BrainMath.compareUnsigned(h, BrainHash.splitmix64(h)) < 0)
                })
            )
        }
        return sb.toString()
    }

    private fun fold(names: List<String>): String {
        val sb = StringBuilder()
        for (name in names) sb.append(line("fold", { string("s", name) }, { string("s", BrainText.fold(name)) }))
        return sb.toString()
    }

    private fun title(titles: List<String>): String {
        val sb = StringBuilder()
        for (t in titles) {
            val c = BrainText.cleanTitle(t)
            sb.append(
                line("cleanTitle", { string("s", t) }, {
                    string("title", c.title)
                    int("versionFlags", c.versionFlags)
                    int("identityFlags", c.identityFlags)
                })
            )
        }
        return sb.toString()
    }

    // ── ids.jsonl ────────────────────────────────────────────────────────────

    private class IdStep(
        val s: String?,
        val i: String?,
        val y: String?,
        val artist: String?,
        val title: String?,
        val durationMs: Int,
    )

    private fun BrainJson.Writer.optional(name: String, value: String?) {
        if (value == null) nullValue(name) else string(name, value)
    }

    private fun ids(names: List<String>, titles: List<String>): String {
        val sb = StringBuilder()
        for (k in 0 until 200) {
            val artist = names[(k * 7) % names.size]
            val t = titles[(k * 3) % titles.size]
            val key = Ids.nameKey(artist, t)
            sb.append(line("nameKey", { string("artist", artist); string("title", t) }, { optional("key", key) }))
        }
        fun case(name: String, steps: List<IdStep>) {
            val table = Ids.MemoryAliasTable()
            for ((n, step) in steps.withIndex()) {
                val keys = Ids.TrackKeys(
                    spotifyId = step.s,
                    isrc = step.i,
                    videoId = step.y,
                    nameKey = if (step.artist != null && step.title != null) Ids.nameKey(step.artist, step.title) else null,
                )
                val res = Ids.resolveTrack(keys, step.durationMs, table)
                if (res != null) table.apply(res)
                sb.append(
                    line("resolveTrack", {
                        string("case", name); int("step", n)
                        optional("s", step.s); optional("i", step.i); optional("y", step.y)
                        optional("artist", step.artist); optional("title", step.title)
                        int("durationMs", step.durationMs)
                    }, {
                        if (res == null) {
                            nullValue("rid")
                        } else {
                            long("rid", res.id); bool("minted", res.minted)
                            beginArray("aliases")
                            for (a in res.aliases) {
                                beginObject(); string("key", a.key); long("id", a.id); int("durationMs", a.durationMs); endObject()
                            }
                            endArray()
                            beginArray("merges")
                            for (m in res.merges) { beginObject(); long("from", m.from); long("into", m.into); endObject() }
                            endArray()
                        }
                    })
                )
            }
        }
        case(
            "step1-strong-key-decides",
            listOf(
                IdStep("4uLU6hMCjMI75M1A2tKUQC", null, null, "Fleetwood Mac", "Dreams", 257_000),
                IdStep("4uLU6hMCjMI75M1A2tKUQC", null, "mrZRURcb1cM", "Fleetwood Mac", "Dreams (2004 Remaster)", 258_000),
                IdStep(null, null, "mrZRURcb1cM", null, null, 0),
            )
        )
        case(
            "step2-name-bridge",
            listOf(
                IdStep("2Fxmhks0bxGSBdJ92vM42m", null, null, "Billie Eilish", "bad guy", 194_000),
                IdStep(null, null, "DyDfgMOUjCI", "Billie Eilish", "bad guy", 195_500),
                IdStep(null, null, null, "billie eilish", "Bad Guy", 0),
                IdStep(null, null, null, "Billie Eilish", "bad guy (Live)", 0),
            )
        )
        case(
            "step2-duration-rejection",
            listOf(
                IdStep("aaaaaaaaaaaaaaaaaaaaaa", null, null, "Taylor Swift", "All Too Well", 329_000),
                IdStep(null, null, "tollGa3S0o8", "Taylor Swift", "All Too Well", 613_000),
                IdStep(null, null, "tollGa3S0o8", "Taylor Swift", "All Too Well", 613_000),
                IdStep(null, null, null, "Taylor Swift", "All Too Well", 0),
                IdStep(null, null, "zzzzzzzzzzz", "Taylor Swift", "All Too Well", 331_500),
            )
        )
        case(
            "step3-mint-order",
            listOf(
                IdStep(null, "usum71703861", "y1", "A", "B", 1000),
                IdStep(null, null, "y2", "C", "D", 0),
                IdStep(null, null, null, "E", "F", 0),
                IdStep(null, null, null, null, null, 0),
                IdStep("", " ", "", "", "", 0),
            )
        )
        case(
            "merge-isrc-joins-two",
            listOf(
                IdStep("sp1", null, null, "Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại", 302_000),
                IdStep(null, null, "yt1", "Son Tung M-TP", "Chung Ta Cua Hien Tai (Official Audio Edit)", 250_000),
                IdStep("sp1", "VNA0D2000001", "yt1", null, null, 302_000),
                IdStep(null, "vna0d2000001", null, null, null, 0),
                IdStep(null, null, "yt1", null, null, 0),
                IdStep(null, null, "yt2", "Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại", 303_000),
                IdStep("sp2", null, "yt2", null, null, 0),
                IdStep("sp2", null, "yt1", null, null, 0),
            )
        )
        // Vietnamese, Korean and Japanese names through the name key.
        case(
            "names-from-the-corpus",
            (0 until 40).map { k ->
                IdStep(
                    null, null, if (k % 3 == 0) "vid$k" else null,
                    names[(k * 11) % 120], titles[(k * 5) % 120], if (k % 2 == 0) 200_000 + k * 500 else 0,
                )
            }
        )
        // A forced hash collision: the value fnv1a64 gives is already someone else's.
        for (k in 0 until 20) {
            val key = "y:collide$k"
            val first = BrainHash.fnv1a64(key)
            val taken = if (k % 2 == 0) longArrayOf(first) else longArrayOf(first, BrainHash.fnv1a64("$key#1"))
            val id = Ids.mint(key) { it in taken }
            sb.append(line("mint", { string("key", key); longs("taken", taken) }, { long("id", id) }))
        }
        val artists = Ids.MemoryAliasTable()
        for (k in 0 until 60) {
            val name = names[(k * 13) % 200]
            val sa = if (k % 5 == 0) "sa$k" else null
            val ya = if (k % 3 == 0) "UC$k" else null
            val res = Ids.resolveArtist(Ids.ArtistKeys(spotifyId = sa, youTubeChannelId = ya, name = name), artists)
            if (res != null) artists.apply(res)
            sb.append(
                line("resolveArtist", { int("step", k); optional("sa", sa); optional("ya", ya); string("name", name) }, {
                    if (res == null) {
                        nullValue("aid")
                    } else {
                        long("aid", res.id); bool("minted", res.minted)
                        beginArray("aliases"); for (a in res.aliases) stringItem(a.key); endArray()
                    }
                })
            )
        }
        return sb.toString()
    }

    // ── Events ───────────────────────────────────────────────────────────────

    /** 2025-10-09: a fixed "now" for the vectors. */
    private const val T0 = 1_760_000_000_000L

    private fun randomEvent(
        rng: BrainHash.Rng,
        rid: Long,
        aid: Long,
        otherAids: LongArray,
        startedAtMs: Long,
        sessionId: Long,
        pos: Int,
    ): PlayEvent {
        val originRoll = rng.int(10)
        val origin = when {
            originRoll < 6 -> Origin.LIVE
            originRoll < 8 -> Origin.MIGRATED_HISTORY
            originRoll < 9 -> Origin.IMPORT_SPOTIFY
            else -> Origin.IMPORT_YTM
        }
        val live = origin == Origin.LIVE
        val durationMs = if (Origin.isImport(origin) || rng.chance(0.1)) 0 else 60_000 + rng.int(360_000)
        val playedMs = when {
            Origin.isImport(origin) && rng.chance(0.4) -> -1
            rng.chance(0.15) -> rng.int(2_500)
            rng.chance(0.2) -> rng.int(30_000)
            durationMs > 0 && rng.chance(0.6) -> durationMs - rng.int(2_000)
            durationMs > 0 -> rng.int(durationMs)
            else -> rng.int(300_000)
        }
        val endReason = when {
            !live -> if (rng.chance(0.5)) EndReason.TRACK_DONE else if (rng.chance(0.5)) EndReason.NEXT else EndReason.UNKNOWN
            durationMs > 0 && playedMs >= durationMs - 2_000 -> EndReason.TRACK_DONE
            else -> rng.int(9)
        }
        var flags = 0
        if (live) {
            if (rng.chance(0.2)) flags = flags or EventFlags.SHUFFLE
            if (rng.chance(0.05)) flags = flags or EventFlags.LIKED_DURING
            if (rng.chance(0.03)) flags = flags or EventFlags.SAVED_DURING
            if (rng.chance(0.03)) flags = flags or EventFlags.ADDED_TO_PLAYLIST
            if (rng.chance(0.04)) flags = flags or EventFlags.SEEK_BACK
            if (rng.chance(0.03)) flags = flags or EventFlags.REPLAYED
            if (rng.chance(0.03)) flags = flags or EventFlags.NOT_FOR_ME
            if (rng.chance(0.03)) flags = flags or EventFlags.EXPLORE_SLOT
            if (rng.chance(0.02)) flags = flags or EventFlags.NEVER_AUDIBLE
            if (rng.chance(0.02)) flags = flags or EventFlags.LEARN_EXCLUDED
            if (rng.chance(0.02)) flags = flags or EventFlags.REPEAT_ONE
        }
        val surface = if (live) rng.int(15) else Surface.UNKNOWN
        val engine = live && Reward.isEngineSurface(surface)
        val arm = if (engine) rng.int(9) else Arm.NONE
        val key = "n:track|$rid"
        return PlayEvent(
            eventId = PlayEvent.mintId(startedAtMs, origin, key) { null },
            origin = origin,
            rid = rid,
            primaryAid = aid,
            otherAids = otherAids,
            keyAtWrite = key,
            startedAtMs = startedAtMs,
            tzOffsetMin = if (live) 420 else PlayEvent.TZ_UNKNOWN,
            playedMs = playedMs,
            durationMs = durationMs,
            startReason = if (live) rng.int(8) else StartReason.UNKNOWN,
            endReason = endReason,
            flags = flags,
            surface = surface,
            surfaceId = if (engine) "s$surface" else "",
            recId = if (engine) startedAtMs shl 8 else 0L,
            slot = if (engine) rng.int(5) else -1,
            arm = arm,
            sessionId = if (live) sessionId else 0L,
            posInSession = if (live) pos else 0,
            passiveRun = if (live) rng.int(15) else 0,
            route = if (live) rng.int(7) else Route.UNKNOWN,
            retrQ = if (engine) rng.int(1001) else -1,
            radioRank = if (engine) 1 + rng.int(50) else -1,
        )
    }

    private fun BrainJson.Writer.events(name: String, events: List<PlayEvent>) {
        beginArray(name)
        for (e in events) rawItem(e.encode())
        endArray()
    }

    private fun BrainJson.Writer.graded(g: Reward.Graded) {
        double("r", g.r); double("c", g.c); double("cOther", g.cOther); int("rule", g.rule)
        bool("isSkip", g.isSkip); double("h", g.h)
    }

    // ── reward.jsonl ─────────────────────────────────────────────────────────

    private fun reward(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:reward"))
        for (i in 0 until 300) {
            val e = randomEvent(rng, 1000L + rng.int(20), 50L + rng.int(5), PlayEvent.NO_AIDS, T0 - rng.int(1_000_000_000), 7L, i)
            val past = if (rng.chance(0.3)) 3 + rng.int(4) else rng.int(3)
            val streak = if (rng.chance(0.3)) 1 + rng.int(4) else 0
            // The decoded event must grade the same: the codec is part of the contract.
            val decoded = PlayEvent.decode(e.encode())
            val g = Reward.grade(decoded, Reward.Context(past, streak))
            sb.append(
                line("grade", { raw("event", e.encode()); int("pastCompletions", past); int("skipStreak", streak) }, { graded(g) })
            )
        }
        return sb.toString()
    }

    // ── affinity.jsonl ───────────────────────────────────────────────────────

    private fun BrainJson.Writer.counter(k: Affinity.Counter) {
        long("posQ", k.posQ); long("negQ", k.negQ); long("lastMs", k.lastMs)
        long("posRQ", k.posRQ); long("negRQ", k.negRQ); long("lastRMs", k.lastRMs)
    }

    /** Every row of the state, in canonical order: `[storedFamily, key, posQ, negQ, lastMs, posRQ, negRQ, lastRMs]`. */
    fun dump(state: Affinity.State): String {
        val w = BrainJson.Writer().beginArray()
        fun row(family: Int, key: Long, k: Affinity.Counter) {
            w.beginArray().intItem(family).longItem(key).longItem(k.posQ).longItem(k.negQ).longItem(k.lastMs)
                .longItem(k.posRQ).longItem(k.negRQ).longItem(k.lastRMs).endArray()
        }
        for (f in 0 until Family.COUNT) for (key in state.sortedKeys(f)) row(f, key, state.counter(f, key)!!)
        for (f in 0 until Family.COUNT) {
            val s = state.sum(f)
            if (s.posQ != 0L || s.negQ != 0L || s.lastMs != 0L) row(Family.SUM_BASE + f, Family.ALL_KEY, s)
        }
        for (f in 0 until Family.COUNT) for (key in state.sortedPriorKeys(f)) {
            val p = state.prior(f, key)!!
            row(Family.PRIOR_BASE + f, key, Affinity.Counter(p.posQ, p.negQ))
        }
        for (rid in state.completions.keys.sorted()) {
            row(Family.COMPLETIONS, rid, Affinity.Counter(posQ = state.completions[rid]!!.toLong()))
        }
        return w.endArray().toString()
    }

    private fun BrainJson.Writer.facts(f: LibraryFacts) {
        beginObject("facts")
        beginArray("liked")
        for (x in f.liked) beginArray().longItem(x.rid).longItem(x.primaryAid).endArray()
        endArray()
        beginArray("topArtists")
        for (x in f.topArtists) beginArray().longItem(x.aid).intItem(x.range).intItem(x.position).intItem(x.listSize).endArray()
        endArray()
        beginArray("topTracks")
        for (x in f.topTracks) {
            beginArray().longItem(x.rid).longItem(x.primaryAid).intItem(x.range).intItem(x.position).intItem(x.listSize).endArray()
        }
        endArray()
        longs("followed", f.followed)
        beginArray("adjustments")
        for (x in f.adjustments) beginArray().intItem(x.family).longItem(x.key).intItem(if (x.more) 1 else 0).intItem(x.strength).endArray()
        endArray()
        endObject()
    }

    private fun affinity(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:affinity"))
        val day = 86_400_000L
        // Counter traces. Times go forward, stand still, and jump back (late events).
        for (c in 0 until 12) {
            val state = Affinity.State()
            var t = T0 - 400L * day
            val keys = longArrayOf(11L, -12L, BrainHash.fnv1a64("k$c"))
            for (step in 0 until 40) {
                val family = if (step % 5 == 4) Family.ARTIST else Family.TRACK
                val key = keys[rng.int(keys.size)]
                val r = when (rng.int(4)) { 0 -> 0.0; 1 -> 1.0; 2 -> 0.1; else -> rng.uniform() }
                val cc = when (rng.int(5)) { 0 -> 0.0; 1 -> 4.5; 2 -> 0.6; else -> rng.uniform() * 3.0 }
                val part = if (family == Family.ARTIST && rng.chance(0.4)) 0.5 else 1.0
                t += when (c % 4) {
                    0 -> rng.int(3 * 24) * 3_600_000L // forward, hours to days
                    1 -> if (rng.chance(0.3)) -rng.int(30) * day else rng.int(10) * day // late events
                    2 -> rng.int(200) * day // long gaps
                    else -> 0L // the same instant
                }
                Affinity.apply(state, family, key, r, cc, part, t)
                val tt = t
                sb.append(
                    line("apply", {
                        int("case", c); int("step", step); int("family", family); long("key", key)
                        double("r", r); double("c", cc); double("part", part); long("tMs", tt)
                    }, {
                        counter(state.counter(family, key)!!)
                        long("sumPosQ", state.sum(family).posQ); long("sumNegQ", state.sum(family).negQ)
                        long("newestMs", state.newestMs)
                    })
                )
            }
            Affinity.apply(state, Family.ALL, Family.ALL_KEY, 0.7, 3.0, 1.0, t)
            state.addPrior(Family.TRACK, 11L, BrainMath.toQ(2.0), 0L)
            for (key in keys) for (family in intArrayOf(Family.TRACK, Family.ARTIST)) {
                val now = t + rng.int(100) * day - 20 * day // reads before and after the last write
                val p0 = Affinity.p0(state, now)
                sb.append(
                    line("read", { int("case", c); int("family", family); long("key", key); long("nowMs", now) }, {
                        double("p0", p0)
                        double("aff", Affinity.aff(state, family, key, now, p0))
                        double("affRecent", Affinity.affRecent(state, family, key, now, p0))
                        double("share", Affinity.share(state, family, key, now))
                        double("weight", Affinity.weight(state, family, key, now))
                        bool("clockJumpedBack", Affinity.clockJumpedBack(state, now))
                    })
                )
            }
        }
        // Rebuilds: the whole fold, with facts; the last one with a "now" behind half the events (a clock
        // that was once set ahead).
        for (c in 0 until 6) {
            val world = World(BrainHash.fnv1a64("golden:rebuild:$c"), historySize = 120, withFacts = c != 0)
            val now = if (c == 5) world.history[world.history.size / 2].startedAtMs else world.nowMs
            val state = Replay.rebuild(world.history.iterator(), world.facts, now)
            sb.append(
                line("rebuild", { events("events", world.history); facts(world.facts); long("nowMs", now) }, { raw("state", dump(state)) })
            )
        }
        return sb.toString()
    }

    // ── A synthetic listener ─────────────────────────────────────────────────

    class Track(val rid: Long, val aid: Long, val albumKey: Long, val inLibrary: Boolean, val liked: Boolean)

    /**
     * A small made-up listener: 90 tracks by 24 artists, about a quarter of them in the library, some
     * history, some facts, and one to three seeds with their radio lists. Ids are full-range hashes,
     * negative ones included.
     */
    class World(seed: Long, historySize: Int = 30, withFacts: Boolean = true, seedCount: Int = -1) {
        val rng = BrainHash.Rng(seed)
        val nowMs = T0 + (rng.int(1000) * 3_600_000L)
        val tz = 420
        val artists = LongArray(24) { BrainHash.fnv1a64("na:artist $seed $it") }
        val tracks: List<Track> = (0 until 90).map { i ->
            val aid = artists[if (rng.chance(0.5)) rng.int(6) else rng.int(artists.size)]
            val inLibrary = i < 4 || rng.chance(0.28)
            Track(
                rid = BrainHash.fnv1a64("n:track $seed $i"),
                aid = if (i >= 4 && rng.chance(0.03)) 0L else aid,
                albumKey = if (rng.chance(0.3)) 0L else BrainHash.fnv1a64("album $seed ${i / 3}"),
                inLibrary = inLibrary,
                liked = inLibrary && rng.chance(0.3),
            )
        }
        val history: List<PlayEvent>
        val facts: LibraryFacts
        val seeds: List<Retrieval.SeedList>

        init {
            val events = ArrayList<PlayEvent>()
            var t = nowMs - 200L * 86_400_000L
            val stepSeconds = ((190L * 86_400L) / (if (historySize > 0) historySize else 1)).toInt()
            for (i in 0 until historySize) {
                t += 1_000L + rng.int(stepSeconds) * 1_000L
                val track = tracks[if (rng.chance(0.6)) rng.int(20) else rng.int(tracks.size)]
                val others = if (rng.chance(0.15)) longArrayOf(artists[rng.int(artists.size)]) else PlayEvent.NO_AIDS
                events.add(randomEvent(rng, track.rid, track.aid, others, t, 1L + i / 12, i % 12))
            }
            events.sortWith { a, b -> a.eventId.compareTo(b.eventId) }
            history = events
            facts = if (!withFacts) LibraryFacts.EMPTY else LibraryFacts(
                liked = tracks.filter { it.liked }.map { LibraryFacts.TrackFact(it.rid, it.aid) },
                topArtists = (0 until 8).map { LibraryFacts.TopArtist(artists[rng.int(10)], it % 3, it, 8) },
                topTracks = (0 until 6).map {
                    val tr = tracks[rng.int(30)]
                    LibraryFacts.TopTrack(tr.rid, tr.aid, it % 3, it, 6)
                },
                followed = longArrayOf(artists[1], artists[7], artists[1]),
                adjustments = listOf(
                    LibraryFacts.Adjustment(Family.ARTIST, artists[2], true, 1 + rng.int(3)),
                    LibraryFacts.Adjustment(Family.ARTIST, artists[9], false, 1 + rng.int(3)),
                    LibraryFacts.Adjustment(Family.TRACK, tracks[3].rid, false, 2),
                ),
            )
            val n = if (seedCount >= 0) seedCount else 1 + rng.int(3)
            val library = tracks.filter { it.inLibrary }
            seeds = (0 until n).map { s ->
                val seedTrack = library[s]
                val size = 20 + rng.int(31)
                val picked = LinkedHashSet<Long>()
                while (picked.size < size) {
                    val tr = tracks[rng.int(tracks.size)]
                    if (tr.rid != seedTrack.rid) picked.add(tr.rid)
                }
                Retrieval.SeedList(seedTrack.rid, seedTrack.aid, picked.toLongArray(), if (s == 2) Graph.RELATED else Graph.RADIO)
            }
        }

        val byRid: Map<Long, Track> = tracks.associateBy { it.rid }

        /** Everything in a seed's list or by a seed's artist, as candidates. */
        fun candidates(): List<Candidate> {
            val seedAids = seeds.map { it.seedAid }.toSet()
            val inLists = HashSet<Long>()
            for (s in seeds) for (r in s.rids) inLists.add(r)
            return tracks.filter { it.rid in inLists || (it.inLibrary && it.aid in seedAids) }.mapIndexed { i, t ->
                Candidate(
                    rid = t.rid,
                    aid = t.aid,
                    albumKey = t.albumKey,
                    inLibrary = t.inLibrary,
                    liked = t.liked,
                    fromArtistRadio = !t.inLibrary && i % 17 == 0,
                    boostLn = if (i % 13 == 0) BrainMath.ln(1.6) else if (i % 19 == 0) -BrainMath.ln(1.25) else 0.0,
                )
            }
        }

        /** A live play of [track] in session 9, as the recorder would write it. */
        fun play(
            track: Track,
            startedAtMs: Long,
            stamp: Session.Stamp,
            playedMs: Int,
            durationMs: Int,
            endReason: Int,
            engine: Boolean,
            flags: Int = 0,
        ): PlayEvent = PlayEvent(
            eventId = PlayEvent.mintId(startedAtMs, Origin.LIVE, "n:track|${track.rid}") { null },
            origin = Origin.LIVE,
            rid = track.rid,
            primaryAid = track.aid,
            keyAtWrite = "n:track|${track.rid}",
            startedAtMs = startedAtMs,
            tzOffsetMin = tz,
            playedMs = playedMs,
            durationMs = durationMs,
            startReason = if (engine) StartReason.AUTOPLAY else StartReason.TAP,
            endReason = endReason,
            flags = flags,
            surface = if (engine) Surface.AUTOPLAY else Surface.PLAYLIST,
            recId = if (engine) 77L else 0L,
            slot = if (engine) stamp.posInSession else -1,
            arm = if (engine) (if (track.inLibrary) Arm.FAV else Arm.RADIO) else Arm.NONE,
            sessionId = stamp.sessionId,
            posInSession = stamp.posInSession,
            passiveRun = stamp.passiveRun,
            route = Route.BLUETOOTH,
            retrQ = if (engine) 500 else -1,
            radioRank = if (engine) 3 else -1,
        )
    }

    private fun BrainJson.Writer.seeds(seeds: List<Retrieval.SeedList>) {
        beginArray("seeds")
        for (s in seeds) {
            beginObject(); long("seedRid", s.seedRid); long("seedAid", s.seedAid); longs("rids", s.rids)
            double("edgeFactor", s.edgeFactor); endObject()
        }
        endArray()
    }

    private fun BrainJson.Writer.candidates(list: List<Candidate>) {
        beginArray("candidates")
        for (c in list) {
            beginObject(); long("rid", c.rid); long("aid", c.aid); long("albumKey", c.albumKey)
            bool("inLibrary", c.inLibrary); bool("liked", c.liked); bool("fromArtistRadio", c.fromArtistRadio)
            double("boostLn", c.boostLn); endObject()
        }
        endArray()
    }

    private fun BrainJson.Writer.request(r: StationPlanner.Request) {
        beginObject("request")
        int("mode", r.mode); int("count", r.count)
        beginArray("prior")
        for (p in r.prior) beginArray().longItem(p.rid).longItem(p.aid).longItem(p.albumKey).intItem(if (p.novel) 1 else 0).endArray()
        endArray()
        long("nowMs", r.nowMs); int("tzOffsetMin", r.tzOffsetMin); double("nuMultiplier", r.nuMultiplier)
        long("tieSeed", r.tieSeed); longs("excludedRids", r.excludedRids.toLongArray().also { it.sort() })
        bool("bridge", r.bridge); bool("finite", r.finite)
        endObject()
    }

    private fun BrainJson.Writer.plan(name: String, plan: StationPlanner.Plan?) {
        if (plan == null) {
            nullValue(name)
            return
        }
        beginObject(name)
        beginArray("picks")
        for (p in plan.picks) {
            beginObject(); long("rid", p.rid); int("arm", p.arm); int("slot", p.slot); int("slotKind", p.slotKind)
            string("why", WhyLine.nameOf(p.whyKey)); long("whyArg", p.whyArg); int("retrQ", p.retrQ)
            int("radioRank", p.radioRank); long("key", p.key); int("pQ", p.pQ); bool("novel", p.novel); endObject()
        }
        endArray()
        bool("usedLiked", plan.usedLiked); bool("usedFamiliarNext", plan.usedFamiliarNext); int("poolSize", plan.poolSize)
        endObject()
    }

    // ── retrieval.jsonl ──────────────────────────────────────────────────────

    private fun retrieval(): String {
        val sb = StringBuilder()
        for (c in 0 until 12) {
            val world = World(BrainHash.fnv1a64("golden:retrieval:$c"), historySize = 40, seedCount = 1 + c % 3)
            val state = Replay.rebuild(world.history.iterator(), world.facts, world.nowMs)
            val p0 = Affinity.p0(state, world.nowMs)
            val table = Retrieval.cooc(world.seeds, state, world.nowMs, p0)
            sb.append(
                line("cooc", {
                    events("events", world.history); facts(world.facts); long("nowMs", world.nowMs); seeds(world.seeds)
                }, {
                    double("p0", p0)
                    beginArray("entries")
                    for (rid in table.keys.sorted()) {
                        val e = table[rid]!!
                        beginObject(); long("rid", rid); double("cooc", e.cooc); int("bestRank", e.bestRank)
                        int("bestSeed", e.bestSeed); endObject()
                    }
                    endArray()
                })
            )
        }
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:percentiles"))
        for (c in 0 until 30) {
            val n = 1 + rng.int(40)
            val arms = IntArray(n) { intArrayOf(Arm.RADIO, Arm.RADIO, Arm.ARTIST, Arm.FAV, Arm.DEEP)[rng.int(5)] }
            val scores = DoubleArray(n) { if (rng.chance(0.2)) 0.0 else if (rng.chance(0.2)) 0.25 else rng.uniform() }
            val pct = Retrieval.percentiles(arms, scores)
            sb.append(
                line("percentiles", {
                    ints("arms", arms)
                    beginArray("scores"); for (s in scores) doubleItem(s); endArray()
                }, { beginArray("pct"); for (p in pct) doubleItem(p); endArray() })
            )
        }
        return sb.toString()
    }

    // ── session.jsonl ────────────────────────────────────────────────────────

    private fun session(): String {
        val sb = StringBuilder()
        for (c in 0 until 16) {
            val world = World(BrainHash.fnv1a64("golden:session:$c"), historySize = 30)
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:session:steps:$c"))
            val affinity = Replay.rebuild(world.history.iterator(), world.facts, world.nowMs)
            val tracker = GradeTracker(affinity.completions)
            val state = SessionState(Session.NU_DEFAULT)
            val station = c % 4 == 3
            if (station) {
                state.seed = world.seeds[0].seedRid
                state.seedAid = world.seeds[0].seedAid
                state.seedKind = Session.SEED_STATION_SONG
            }
            val candidates = world.candidates()
            var t = world.nowMs
            // The listener starts with the seeds themselves; then the engine's picks play.
            val out = BrainJson.Writer().beginArray()
            val inSteps = BrainJson.Writer().beginArray()
            var tail = LongArray(0)
            val played = HashSet<Long>()
            for (step in 0 until 14) {
                val fromTail = tail.isNotEmpty() && step >= world.seeds.size
                val track = if (fromTail) world.byRid[tail[0]]!! else world.byRid[world.seeds[step % world.seeds.size].seedRid]!!
                val duration = 180_000 + rng.int(60_000)
                // Scripted moods per case, so every rule of 7.3 fires somewhere.
                val mood = when (c % 4) {
                    0 -> rng.int(6)
                    1 -> if (step in 3..7) 0 else 5
                    2 -> if (step % 3 == 2) 5 else 1
                    else -> if (step in 4..6) 0 else rng.int(6)
                }
                val playedMs = when (mood) {
                    0 -> 1_000 + rng.int(8_000)
                    1 -> 12_000 + rng.int(15_000)
                    2 -> 40_000 + rng.int(30_000)
                    else -> duration
                }
                val end = if (playedMs >= duration) EndReason.TRACK_DONE else if (rng.chance(0.85)) EndReason.NEXT else EndReason.STOPPED
                val flags = if (mood == 5 && rng.chance(0.4)) EventFlags.LIKED_DURING
                else if (mood == 0 && rng.chance(0.15)) EventFlags.NOT_FOR_ME
                else 0
                check(state.sessionId == 0L || !Session.isExpired(state, t)) { "the golden session must not expire" }
                val stamp = Session.begin(state, t, if (fromTail) StartReason.AUTOPLAY else StartReason.TAP)
                val e = world.play(track, t, stamp, playedMs, duration, end, engine = fromTail, flags = flags)
                val g = Brain.onEvent(affinity, tracker, state, e)
                played.add(track.rid)
                t = e.endedAtMs + 500
                if (fromTail) tail = tail.copyOfRange(1, tail.size)

                // The tail is re-scored: its own tracks and the pool compete for its slots. An empty
                // tail is a top-up of five.
                val graph = StationPlanner.buildGraph(world.seeds, candidates, state)
                val topUp = tail.isEmpty()
                val request = StationPlanner.Request(
                    mode = if (station) StationPlanner.MODE_STATION else StationPlanner.MODE_AUTOPLAY,
                    count = if (topUp) 5 else tail.size,
                    candidates = candidates,
                    seeds = world.seeds,
                    nowMs = t,
                    tzOffsetMin = world.tz,
                    tieSeed = state.sessionId xor step.toLong(),
                    excludedRids = played.toSet(),
                    bridge = topUp && step < world.seeds.size + 1,
                )
                val plan = if (topUp) StationPlanner.plan(request, affinity, state, graph)
                else Session.patchQueue(state, tail, request, affinity, graph)
                if (plan != null) {
                    StationPlanner.consume(state, plan)
                    tail = LongArray(plan.picks.size) { plan.picks[it].rid }
                }
                inSteps.beginObject().raw("event", e.encode()).bool("topUp", topUp).endObject()
                out.beginObject()
                out.beginObject("graded")
                out.graded(g)
                out.endObject()
                out.raw("state", state.encode())
                out.plan("plan", plan)
                out.longs("tail", tail)
                out.endObject()
            }
            val steps = inSteps.endArray().toString()
            val result = out.endArray().toString()
            sb.append(
                line("session", {
                    events("history", world.history); facts(world.facts); long("nowMs", world.nowMs); int("tzOffsetMin", world.tz)
                    bool("station", station); seeds(world.seeds); candidates(candidates); raw("steps", steps)
                }, { raw("steps", result) })
            )
        }
        return sb.toString()
    }

    // ── lists.jsonl ──────────────────────────────────────────────────────────

    private fun lists(): String {
        val sb = StringBuilder()
        for (c in 0 until 36) {
            val mode = c % 3 // autoplay, station, home row
            val world = World(
                BrainHash.fnv1a64("golden:lists:$c"),
                historySize = if (c < 3) 0 else 30,
                withFacts = c >= 3,
                seedCount = if (mode == 0) -1 else 1,
            )
            val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:lists:req:$c"))
            val affinity = Replay.rebuild(world.history.iterator(), world.facts, world.nowMs)
            val tracker = GradeTracker(affinity.completions)
            val state = SessionState(Session.NU_DEFAULT)
            if (mode == 1) {
                state.seed = world.seeds[0].seedRid
                state.seedAid = world.seeds[0].seedAid
                state.seedKind = Session.SEED_STATION_SONG
            }
            // A few plays in the session first, so the session features are not all zero.
            val sessionEvents = ArrayList<PlayEvent>()
            var t = world.nowMs
            val plays = if (c < 3) 0 else rng.int(6)
            for (k in 0 until plays) {
                val track = world.tracks[rng.int(world.tracks.size)]
                val duration = 200_000
                val playedMs = if (rng.chance(0.5)) duration else rng.int(40_000)
                val stamp = Session.begin(state, t, StartReason.TAP)
                val e = world.play(
                    track, t, stamp, playedMs, duration,
                    if (playedMs >= duration) EndReason.TRACK_DONE else EndReason.NEXT,
                    engine = rng.chance(0.4),
                    flags = if (rng.chance(0.2)) EventFlags.LIKED_DURING else 0,
                )
                sessionEvents.add(e)
                Brain.onEvent(affinity, tracker, state, e)
                t = e.endedAtMs + 500
            }
            val candidates = world.candidates()
            val graph = StationPlanner.buildGraph(world.seeds, candidates, state)
            val priorCount = if (mode == 0 && c % 2 == 1) 3 + rng.int(8) else 0
            val prior = (0 until priorCount).map {
                val tr = world.tracks[rng.int(world.tracks.size)]
                ListBuilder.Prior(tr.rid, tr.aid, tr.albumKey, !tr.inLibrary)
            }
            val request = StationPlanner.Request(
                mode = mode,
                count = when (mode) { 0 -> 5; 1 -> 20; else -> 12 },
                candidates = candidates,
                seeds = world.seeds,
                prior = prior,
                nowMs = t,
                tzOffsetMin = world.tz,
                nuMultiplier = when (c % 5) { 0 -> 0.8; 1 -> 1.2; 2 -> 1.0; 3 -> 0.4; else -> 2.0 },
                tieSeed = BrainHash.fnv1a64("list $c"),
                excludedRids = prior.map { it.rid }.toSet(),
                bridge = mode == 0 && priorCount == 0,
                finite = mode == 2,
            )
            val plan = StationPlanner.plan(request, affinity, state, graph)
            sb.append(
                line("plan", {
                    events("history", world.history); facts(world.facts); long("nowMs", world.nowMs)
                    bool("station", mode == 1); events("session", sessionEvents); seeds(world.seeds)
                    candidates(candidates); request(request)
                }, { raw("sessionState", state.encode()); plan("plan", plan) })
            )
        }
        return sb.toString()
    }

    private val README = """
        # Brain golden vectors (stage 1)

        GENERATED by `./gradlew :brain:generateGolden` from the Kotlin in `:brain`. Never edit by hand.
        `BrainGoldenTest` fails when a fresh run differs from these files. The Swift port copies this
        folder and must reproduce every `out` from its `in`, byte for byte (docs/brain-spec.md, 10.4).

        One JSON object per line: `{"fn", "in", "out"}`. Doubles are 16-hex-digit IEEE bit patterns,
        Int64 values are decimal strings, everything outside printable ASCII is escaped as `\uXXXX`
        (UTF-16 units). `MANIFEST.sha256` lists every file's hash; `SPEC_VERSION` is `Brain.SPEC_VERSION`.

        How each `fn` is replayed:

        - `math.jsonl`: `ln`, `log2`, `exp`, `sigmoid`, `powNeg`, `toQ`, `orderKey`, `localDay` on `in`.
        - `hash.jsonl`: `fnv1a64` (UTF-8 of `text`), `fnv1a64Bytes`, `splitmix64` (the stateless form),
          `rng` (a fresh generator: `n` uniforms; another fresh one: `n` gaussian12), `unsigned`
          (unsigned remainders, and one unsigned comparison of `h` with `splitmix64(h)`).
        - `fold.jsonl`, `title.jsonl`: `BrainText.fold`, `BrainText.cleanTitle`.
        - `ids.jsonl`: `nameKey`; `resolveTrack` steps run in order against one in-memory alias table
          per `case` (a new table at step 0; each resolution is applied before the next step; the name
          key is `nameKey(artist, title)` when both are given); `mint` with the listed ids already
          taken; `resolveArtist` steps in order against one table.
        - `reward.jsonl`: `Reward.grade(event, Context(pastCompletions, skipStreak))`.
        - `affinity.jsonl`: `apply` steps run in order on one empty state per `case`; `out` is the
          key's counter and its family's sum after the step. `read` lines follow their case's last
          step, after `apply(ALL, 0, r = 0.7, c = 3.0, part = 1, tMs = the last step's tMs)` and a
          prior of `toQ(2.0)` pos on track 11. `rebuild` is `Replay.rebuild(events, facts, nowMs)`;
          `state` is every row in canonical order as
          `[storedFamily, key, posQ, negQ, lastMs, posRQ, negRQ, lastRMs]` (families 0-7 by key, then
          sums 16+, priors 32+, completions 48).
        - `retrieval.jsonl`: `cooc` over `seeds` with the state rebuilt from `events` and `facts`;
          `percentiles(arms, scores)`.
        - `session.jsonl`: rebuild the affinity state from `history` and `facts`; make a
          `GradeTracker(state.completions)` and a fresh `SessionState` (for `station`, with the seed
          of `seeds[0]` and `SEED_STATION_SONG`). For each step: `Brain.onEvent` with the step's event
          (it is already stamped by `Session.begin`, which must also be called: start reason
          `autoplay` when the track came from the tail, else `tap`); the track that played leaves the
          front of the tail when it came from it; then, with `nowMs = event.endedAtMs + 500`,
          `tieSeed = sessionId xor stepIndex`, `excludedRids` = every rid played so far, and the graph
          of `StationPlanner.buildGraph(seeds, candidates, session)`: a top-up (`topUp`: the tail is
          empty) is `StationPlanner.plan` with `count = 5` and `bridge = stepIndex < seeds.size + 1`;
          otherwise `Session.patchQueue(tail)` with `count = tail.size`. A non-null plan is consumed
          (`StationPlanner.consume`) and becomes the tail.
        - `lists.jsonl`: rebuild from `history` and `facts`; replay `session` (`Session.begin` with
          start reason `tap`, then `Brain.onEvent`, for each event); build the graph;
          `StationPlanner.plan(request)`.
    """.trimIndent() + "\n"
}
