package com.theveloper.pixelplay.brain

import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/**
 * The golden vectors of stage 2b, milestone 1 (docs/brain-spec.md, 10.4): the genre atlas lookups, the tag vector and the
 * track DNA functions. Three new files; no existing golden file changes.
 *
 * The atlas is the checked-in asset (`app/src/main/assets/genre-atlas-v1.json.gz`), so a case names atlas ids and
 * names that only exist in that exact file: the iOS port must read the same bytes (their SHA-256 is pinned by
 * `AtlasTest`). Every input is drawn from SplitMix64 streams with fixed seeds.
 */
object Stage2bGolden {

    const val ATLAS_ASSET = "../app/src/main/assets/genre-atlas-v1.json.gz"

    fun atlasText(project: File): String {
        val gz = File(project, ATLAS_ASSET).readBytes()
        return String(GZIPInputStream(ByteArrayInputStream(gz)).readBytes(), Charsets.UTF_8)
    }

    fun generate(project: File, names: List<String>, titles: List<String>): LinkedHashMap<String, String> {
        val atlas = GenreAtlas.parse(atlasText(project))
        val files = LinkedHashMap<String, String>()
        files["atlas.jsonl"] = atlasLines(atlas, names)
        val vectors = ArrayList<Tags.Vector>()
        files["tags.jsonl"] = tagLines(atlas, vectors)
        files["dna.jsonl"] = dnaLines(atlas, vectors, titles)
        files["tagfeatures.jsonl"] = tagFeatureLines(vectors)
        return files
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

    private fun BrainJson.Writer.intArray(name: String, values: IntArray) = ints(name, values)

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder()
        for (b in bytes) sb.append("0123456789abcdef"[(b.toInt() ushr 4) and 15]).append("0123456789abcdef"[b.toInt() and 15])
        return sb.toString()
    }

    private fun BrainJson.Writer.vector(name: String, v: Tags.Vector) {
        beginArray(name)
        for (t in v.tags) stringItem("${t.atlasId}:${t.q}")
        endArray()
    }

    // ── atlas.jsonl ──────────────────────────────────────────────────────────

    private fun atlasLines(atlas: GenreAtlas, names: List<String>): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:atlas"))
        val ids = atlas.allIds
        sb.append(line("meta", {}, {
            int("version", atlas.version); int("genres", atlas.size); int("families", atlas.families.size); int("moods", atlas.moods.size)
            beginArray("familyNames"); for (f in atlas.families) stringItem(f); endArray()
            beginArray("moodNames"); for (m in atlas.moods) stringItem(m); endArray()
        }))
        // Names and aliases: what a listener or a source would type.
        val texts = ArrayList<String>()
        for (k in 0 until 260) {
            val g = atlas.genre(ids[rng.int(ids.size)])!!
            val pick = rng.int(1 + g.aliases.size)
            val base = if (pick == 0) g.name else g.aliases[pick - 1]
            texts.add(when (k % 5) { 1 -> base.uppercase(); 2 -> " $base "; 3 -> base.replace(' ', '-'); else -> base })
        }
        for (hand in listOf("K-Pop", "k pop", "V-pop", "Vietnamese Hip Hop", "vietnamese indie", "Shoegaze", "R&B", "Rhythm and Blues", "Hip-Hop/Rap",
            "rap", "dance pop", "Lo-Fi Beats", "bossa nova", "Nhạc Vàng", "nhac vang", "Trap", "EDM", "Vinahouse", "singer/songwriter", "city pop",
            "not a genre at all", "", "   ", "chill", "2010s", "love", "workout", "popular")) texts.add(hand)
        for (i in 0 until 40) texts.add(names[rng.int(names.size)])
        for (t in texts) sb.append(line("name", { string("text", t) }, { int("id", atlas.idForName(t)) }))
        for (k in 0 until 80) {
            val g = atlas.genre(ids[rng.int(ids.size)])!!
            if (g.mbid.isEmpty()) continue
            sb.append(line("mbid", { string("text", g.mbid) }, { int("id", atlas.idForMbid(g.mbid)) }))
        }
        sb.append(line("mbid", { string("text", "00000000-0000-0000-0000-000000000000") }, { int("id", atlas.idForMbid("00000000-0000-0000-0000-000000000000")) }))
        val sources = listOf(
            "deezer" to listOf("All", "Pop", "Rap/Hip Hop", "Soul & Funk", "Films/Games", "Asian Music", "Latin Music", "Kids", "Cumbia", "rock"),
            "itunes" to listOf("Hip-Hop/Rap", "K-Pop", "Cantopop/HK-Pop", "Jungle/Drum'n'bass", "Death Metal/Black Metal", "Holiday", "Singer/Songwriter", "Pop Latino", "R&B/Soul", "Bollywood", "Alternative", "Fantasy"),
            "chip" to listOf("Pop", "Hip-Hop", "V-Pop", "Vietnamese Hip Hop", "Adult Pop", "Romance", "2010s", "Popular"),
            "nothing" to listOf("Pop"),
        )
        for ((source, raws) in sources) for (raw in raws) {
            val ids2 = atlas.idsForSource(source, raw)
            sb.append(line("source", { string("source", source); string("raw", raw) }, {
                if (ids2 == null) nullValue("ids") else intArray("ids", ids2)
                intArray("resolved", atlas.resolve(source, raw))
            }))
        }
        for (k in 0 until 120) {
            val id = ids[rng.int(ids.size)]
            sb.append(line("ancestors", { int("id", id) }, { intArray("ids", atlas.ancestors(id)) }))
        }
        for (k in 0 until 160) {
            val id = ids[rng.int(ids.size)]
            val g = atlas.genre(id)!!
            val d = g.descriptor
            sb.append(line("genre", { int("id", id) }, {
                int("family", g.family); int("depth", g.depth); int("eraStart", g.eraStart)
                if (d == null) nullValue("descriptor") else {
                    beginObject("descriptor")
                    int("energy", d.energy); int("valence", d.valence); int("danceability", d.danceability); int("acousticness", d.acousticness)
                    int("tempoLo", d.tempoLo); int("tempoHi", d.tempoHi); intArray("moods", d.moods)
                    endObject()
                }
                intArray("parents", g.parents); intArray("related", g.related)
            }))
        }
        return sb.toString()
    }

    // ── tags.jsonl ───────────────────────────────────────────────────────────

    private val CONFS = doubleArrayOf(1.0, 1.0, 0.5, 0.25, 0.8, 0.1, 0.0, 1.5, -0.2, 0.333)

    private fun tagLines(atlas: GenreAtlas, keep: ArrayList<Tags.Vector>): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:tags"))
        val ids = atlas.allIds
        // Described and rooted genres make better cases than a draw over all 2,000 (most of which are obscure).
        val common = ids.filter { atlas.genre(it)!!.descriptor != null && atlas.genre(it)!!.family >= 0 && atlas.genre(it)!!.description.isNotEmpty() }
        for (k in 0 until 360) {
            val inputs = ArrayList<Tags.Input>()
            val sourceCount = 1 + rng.int(5)
            val pool = if (k % 3 == 0) ids.toList() else common
            val anchor = pool[rng.int(pool.size)]
            for (s in 0 until sourceCount) {
                val source = if (k % 4 == 3 && s == 0) intArrayOf(7, 9, 10)[rng.int(3)] else 1 + rng.int(10)
                val per = 1 + rng.int(4)
                for (j in 0 until per) {
                    val id = if (j == 0 && rng.int(2) == 0) anchor else pool[rng.int(pool.size)]
                    val conf = CONFS[rng.int(CONFS.size)]
                    inputs.add(Tags.Input(source, id, conf))
                }
            }
            if (k % 17 == 0) inputs.add(Tags.Input(3, 30000, 1.0)) // an id the atlas does not have
            if (k % 23 == 0) inputs.add(Tags.Input(11, anchor, 1.0)) // a source that does not exist
            val v = Tags.merge(atlas, inputs)
            keep.add(v)
            sb.append(line("merge", {
                beginArray("inputs")
                for (i in inputs) rawItem(BrainJson.Writer().beginObject().int("source", i.source).int("id", i.atlasId).double("conf", i.conf).endObject().toString())
                endArray()
            }, { vector("tags", v); string("blob", hex(Tags.encode(v))) }))
        }
        for (k in 0 until 80) {
            val a = keep[rng.int(keep.size)]
            val b = keep[rng.int(keep.size)]
            sb.append(line("tagdot", { vector("a", a); vector("b", b) }, { double("dot", Tags.tagdot(a, b)) }))
        }
        for (k in 0 until 40) {
            val a = keep[rng.int(keep.size)]
            sb.append(line("fromArtist", { vector("artist", a) }, { vector("tags", Tags.fromArtist(a)) }))
        }
        // From raw genre strings: the placement step in front of merge.
        val raws = listOf(
            Triple(Tags.SRC_RECORDING, null as String?, listOf(Tags.Raw("shoegaze", atlas.genre(atlas.idForName("shoegaze"))?.mbid ?: "", 12), Tags.Raw("dream pop", "", 4), Tags.Raw("seen live", "", 2))),
            Triple(Tags.SRC_ITUNES, "itunes", listOf(Tags.Raw("Hip-Hop/Rap"), Tags.Raw("Holiday"))),
            Triple(Tags.SRC_DEEZER_ALBUM, "deezer", listOf(Tags.Raw("Pop"), Tags.Raw("All"), Tags.Raw("Kids"))),
            Triple(Tags.SRC_CHIP, "chip", listOf(Tags.Raw("Vietnamese Hip Hop"), Tags.Raw("Popular"), Tags.Raw("2010s"))),
            Triple(Tags.SRC_SPOTIFY, null, listOf(Tags.Raw("k-pop girl group"), Tags.Raw("pop dance"), Tags.Raw("zzz unknown genre"))),
            Triple(Tags.SRC_ARTIST, null, listOf(Tags.Raw("v-pop", "", 3), Tags.Raw("vietnamese bolero", "", 0), Tags.Raw("", "", 1))),
        )
        for ((source, table, list) in raws) {
            val ins = Tags.inputs(atlas, source, table, list)
            val v = Tags.merge(atlas, ins)
            sb.append(line("inputs", {
                int("source", source)
                if (table == null) nullValue("table") else string("table", table)
                beginArray("raws")
                for (r in list) rawItem(BrainJson.Writer().beginObject().string("label", r.label).string("mbid", r.mbid).int("votes", r.votes).endObject().toString())
                endArray()
            }, {
                beginArray("inputs")
                for (i in ins) rawItem(BrainJson.Writer().beginObject().int("source", i.source).int("id", i.atlasId).double("conf", i.conf).endObject().toString())
                endArray()
                vector("tags", v)
            }))
        }
        return sb.toString()
    }

    // ── dna.jsonl ────────────────────────────────────────────────────────────

    private fun dnaLines(atlas: GenreAtlas, vectors: List<Tags.Vector>, titles: List<String>): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:dna"))
        for (k in 0 until 120) {
            val claims = ArrayList<TrackDna.YearClaim>()
            for (j in 0 until 1 + rng.int(4)) {
                val year = when (rng.int(8)) { 0 -> 1850; 1 -> 2300; 2 -> 0; else -> 1960 + rng.int(70) }
                claims.add(TrackDna.YearClaim(1 + rng.int(5), year))
            }
            val y = TrackDna.mergeYear(claims, 2027)
            sb.append(line("mergeYear", {
                int("maxYear", 2027)
                beginArray("claims")
                for (c in claims) rawItem(BrainJson.Writer().beginObject().int("source", c.source).int("year", c.year).endObject().toString())
                endArray()
            }, { int("year", y.year); int("source", y.source) }))
        }
        for (t in listOf("2019", "2019-03", "2019-03-29", "20190329", "1899", "2201", "abcd", "", "19", "1994-", "2022-1")) {
            sb.append(line("parseYear", { string("text", t) }, { int("year", TrackDna.parseYear(t)) }))
        }
        for (r in longArrayOf(-5, 0, 1, 999, 1000, 541811, 984833, 999999, 5_000_000)) {
            sb.append(line("popDeezer", { long("rank", r) }, { int("pop", TrackDna.popFromDeezerRank(r)) }))
        }
        for (p in longArrayOf(-1, 0, 1, 9, 1_000, 47_000_000, 1_200_000_000, 9_999_999_999L)) {
            sb.append(line("popPlays", { long("plays", p) }, { int("pop", TrackDna.popFromPlays(p)) }))
            sb.append(line("popListeners", { long("users", p) }, { int("pop", TrackDna.popFromListeners(p)) }))
        }
        for (k in 0 until 24) {
            val a = if (rng.int(3) == 0) -1 else rng.int(1001)
            val b = if (rng.int(3) == 0) -1 else rng.int(1001)
            val c = if (rng.int(3) == 0) -1 else rng.int(1001)
            sb.append(line("mergePopularity", { int("deezer", a); int("youtube", b); int("listenbrainz", c) }, { int("pop", TrackDna.mergePopularity(a, b, c)) }))
        }
        for (t in listOf("47M plays", "1.2B views", "950K plays", "12,345 plays", "3.4K", "1.25M", "87 plays", "", "plays", "0", "2.5 million", "12.3456K", "1,2M"))
            sb.append(line("playCount", { string("text", t) }, { long("plays", TrackDna.parsePlayCount(t)) }))
        val gains = intArrayOf(TrackDna.GAIN_UNKNOWN, -82, -96, -70, -140, 0, 30, -90, -200)
        for (k in 0 until 140) {
            val v = vectors[rng.int(vectors.size)]
            val gain = gains[rng.int(gains.size)]
            val f = TrackDna.feel(atlas, v, gain)
            sb.append(line("feel", { vector("tags", v); int("gainDbX10", gain) }, { int("energy", f.energy); int("valence", f.valence); intArray("moods", TrackDna.moods(atlas, v)) }))
        }
        val countries = listOf("", "VN", "KR", "JP", "CN", "US")
        val extra = listOf("Chúng Ta Của Hiện Tại", "Ditto", "ひまわりの約束", "사랑해", "夜曲", "Мой друг", "ไม่รู้", "أنا", "तू", "", "1234", "Sơn Tùng M-TP", "Hà Nội 2020")
        for (t in extra + titles.take(120)) {
            val c = countries[rng.int(countries.size)]
            sb.append(line("scriptLang", { string("title", t); string("country", c) }, { int("lang", TrackDna.scriptLang(t, c)) }))
        }
        return sb.toString()
    }

    // ── tagfeatures.jsonl ────────────────────────────────────────────────────

    private fun tagFeatureLines(vectors: List<Tags.Vector>): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:tagfeatures"))
        val usable = vectors.filter { !it.isEmpty }
        // Feature 4: tags' affinities after some plays, read with a vector.
        for (k in 0 until 60) {
            val state = Affinity.State()
            val steps = ArrayList<DoubleArray>() // id, r, c, part, tMs
            var t = 1_700_000_000_000L
            for (s in 0 until 2 + rng.int(12)) {
                val v = usable[rng.int(usable.size)]
                val tag = v.tags[rng.int(v.tags.size)]
                t += (1 + rng.int(40)) * 3_600_000L
                val r = rng.int(11) / 10.0
                val c = 0.5 + rng.int(6) / 2.0
                val part = tag.q / 255.0
                Affinity.apply(state, Family.TAG, tag.atlasId.toLong(), r, c, part, t)
                steps.add(doubleArrayOf(tag.atlasId.toDouble(), r, c, part, t.toDouble()))
            }
            val read = usable[rng.int(usable.size)]
            val now = t + rng.int(60) * 86_400_000L
            val p0 = 0.5 + (rng.int(5) - 2) / 10.0
            sb.append(line("tagAff", {
                beginArray("steps")
                for (st in steps) rawItem(BrainJson.Writer().beginObject().int("id", st[0].toInt()).double("r", st[1]).double("c", st[2]).double("part", st[3]).long("tMs", st[4].toLong()).endObject().toString())
                endArray()
                vector("tags", read); long("nowMs", now); double("p0", p0)
            }, { double("feature", Features.tagAff(read, state, now, p0)) }))
        }
        // Feature 24 and the penalties of 5.5.
        for (k in 0 until 60) {
            val session = SessionState()
            val steps = ArrayList<Triple<Tags.Vector, Double, Boolean>>()
            for (s in 0 until 1 + rng.int(8)) {
                val v = usable[rng.int(usable.size)]
                val completed = rng.int(4) == 0
                val h = if (completed) 0.0 else doubleArrayOf(0.0, 0.2, 0.6, 1.0)[rng.int(4)]
                Session.applyTags(session, v, h, completed)
                steps.add(Triple(v, h, completed))
            }
            val read = usable[rng.int(usable.size)]
            sb.append(line("sessTagPen", {
                beginArray("steps")
                for ((v, h, done) in steps) rawItem(BrainJson.Writer().beginObject().raw("tags", BrainJson.Writer().also { w -> w.beginArray(); for (tg in v.tags) w.stringItem("${tg.atlasId}:${tg.q}"); w.endArray() }.toString()).double("h", h).bool("completed", done).endObject().toString())
                endArray()
                vector("tags", read)
            }, {
                beginArray("penalties")
                for ((id, pen) in session.tagPen) rawItem(BrainJson.Writer().beginObject().int("id", id).double("pen", pen).endObject().toString())
                endArray()
                double("feature", Features.sessTagPen(read, session))
            }))
        }
        return sb.toString()
    }

    const val README_NOTES = """
        - `atlas.jsonl`, `tags.jsonl`, `dna.jsonl` (stage 2b, milestone 1; new files, nothing before them changed): they need the
          genre atlas, the bytes of `app/src/main/assets/genre-atlas-v1.json.gz` (SHA-256 in `atlas/genre-atlas-v1.sha256`),
          gunzipped, as UTF-8 text, then `GenreAtlas.parse`. `meta` is the atlas's counts, family names and mood names. `name` is
          `idForName(text)`; `mbid` is `idForMbid`; `source` is `idsForSource(source, raw)` (null when the table has no entry)
          and `resolve(source, raw)`; `ancestors`; `genre` reads family, depth, era start, descriptor, parents and related.
          `merge` is `Tags.merge(atlas, inputs)` (`conf` is a double bit pattern; `tags` are `"atlasId:q"`; `blob` is
          `Tags.encode` in hex); `tagdot`; `fromArtist`; `inputs` is `Tags.inputs(atlas, source, table, raws)` then `merge`.
          `mergeYear(claims, maxYear)`, `parseYear`, `popDeezer`, `popPlays`, `popListeners`, `mergePopularity`, `playCount`
          (`TrackDna.parsePlayCount`), `feel` is `TrackDna.feel(atlas, tags, gainDbX10)` and `TrackDna.moods`, `scriptLang`.
        - `tagfeatures.jsonl` (stage 2b): `tagAff` runs `Affinity.apply(state, TAG, id, r, c, part, tMs)` for each step on one empty
          state and reads `Features.tagAff(tags, state, nowMs, p0)` (feature 4). `sessTagPen` runs `Session.applyTags(session,
          tags, h, completed)` for each step on one fresh `SessionState`; `penalties` are the session's `tagPen` entries in ascending
          atlas id, `feature` is `Features.sessTagPen(tags, session)` (feature 24).
    """
}
