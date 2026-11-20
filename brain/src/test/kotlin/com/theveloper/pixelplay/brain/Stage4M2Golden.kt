package com.theveloper.pixelplay.brain

/**
 * The golden vectors of stage 4 milestone 2 (docs/brain-spec.md, 9.3, 9.4, 10.4): the chips under a mix card and their words
 * (`assistantcards.jsonl`), set notes (`setnotes.jsonl`), the weekly note (`weeklynote.jsonl`) and the checks on the optional batch jobs
 * (`assistbatch.jsonl`). New files only: no earlier golden file changes.
 */
object Stage4M2Golden {

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["assistantcards.jsonl"] = cards()
        files["setnotes.jsonl"] = setNotes()
        files["weeklynote.jsonl"] = weeklyNote()
        files["assistbatch.jsonl"] = batch()
        return files
    }

    private fun line(fn: String, input: BrainJson.Writer.() -> Unit, output: BrainJson.Writer.() -> Unit): String {
        val w = BrainJson.Writer().beginObject()
        w.string("fn", fn)
        w.beginObject("in"); w.input(); w.endObject()
        w.beginObject("out"); w.output(); w.endObject()
        return w.endObject().toString() + "\n"
    }

    private fun BrainJson.Writer.strings(name: String, v: List<String>) {
        beginArray(name)
        for (s in v) stringItem(s)
        endArray()
    }

    private fun BrainJson.Writer.chips(name: String, v: List<AssistantRules.Chip>) {
        beginArray(name)
        for (c in v) beginArray().intItem(c.kind).stringItem(c.ref).intItem(c.genreId).endArray()
        endArray()
    }

    private fun BrainHash.Rng.int(n: Int): Int = BrainMath.floorToLong(uniform() * n.toDouble()).toInt().coerceIn(0, n - 1)

    // ── assistantcards.jsonl ─────────────────────────────────────────────────

    private fun cards(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:assistantcards"))
        val ytA = "yt:aaaaaaaaaaa"
        for (c in 0 until 40) {
            val q = AssistantRules.Query(
                title = "Late drive", seedRefs = listOf(ytA), genreIds = if (rng.int(2) == 0) listOf(4) else emptyList(), moodIds = emptyList(),
                yearFrom = 0, yearTo = 0, energy = AssistantRules.ENERGY[rng.int(3)], tempo = AssistantRules.TEMPO[rng.int(4)],
                novelty = AssistantRules.NOVELTY[rng.int(3)], noveltySharePct = if (rng.int(3) == 0) -1 else rng.int(101),
                count = AssistantRules.MIN_MIX + rng.int(51), excludeArtistRefs = if (rng.int(4) == 0) listOf(ytA) else emptyList(),
            )
            val genre = if (rng.int(3) == 0) 0 else 1 + rng.int(8)
            val unsupported = ArrayList<String>()
            if (rng.int(3) == 0) unsupported.add(AssistantRules.FILTER_TEMPO)
            if (rng.int(3) == 0) unsupported.add(AssistantRules.FILTER_ENERGY)
            val topRef = if (rng.int(5) == 0) "" else ytA
            val out = AssistantCards.allChips(q, topRef, genre, unsupported)
            sb.append(line("allChips", {
                string("title", q.title); string("energy", q.energy); string("tempo", q.tempo); string("novelty", q.novelty)
                int("share", q.noveltySharePct); int("count", q.count); strings("excludeArtistRefs", q.excludeArtistRefs)
                strings("seedRefs", q.seedRefs); beginArray("genreIds"); for (g in q.genreIds) intItem(g); endArray()
                string("topArtistRef", topRef); int("topGenreId", genre)
                strings("unsupported", unsupported)
            }, { chips("chips", out) }))
        }
        val names = listOf("Sabrina Carpenter", "R&B", "Hip Hop", "A", "", "  Chappell   Roan ", "The Hierophant Green Guardians of Everything", "Sơn Tùng M-TP")
        for (kind in 0..11) {
            for (n in names) {
                val chip = AssistantRules.Chip(kind, ref = ytA, genreId = 5)
                sb.append(line("chipLabel", { int("kind", kind); string("artist", n); string("genre", n) }, { string("label", AssistantCards.chipLabel(chip, n, n)) }))
            }
        }
        class Case(val user: String, val named: List<AssistantCards.Named>)
        val cases = listOf(
            Case("Why do I keep getting trap?", listOf(AssistantCards.Named(0, "Trap"), AssistantCards.Named(0, "Pop"), AssistantCards.Named(1, "Drake"))),
            Case("why do i keep getting trap music", listOf(AssistantCards.Named(0, "Trap"), AssistantCards.Named(0, "Latin Trap"))),
            Case("why do i keep getting latin trap", listOf(AssistantCards.Named(0, "Trap"), AssistantCards.Named(0, "Latin Trap"))),
            Case("why so much entrapment", listOf(AssistantCards.Named(0, "Trap"))),
            Case("Why is Drake everywhere?", listOf(AssistantCards.Named(0, "Hip Hop"), AssistantCards.Named(1, "Drake"))),
            Case("why so much of it", listOf(AssistantCards.Named(0, "Pop"))),
            Case("Why is so much V-pop in my mixes", listOf(AssistantCards.Named(0, "V-pop"), AssistantCards.Named(0, "Pop"))),
            Case("tại sao Sơn Tùng M-TP", listOf(AssistantCards.Named(1, "Sơn Tùng M-TP"))),
            Case("a b c", listOf(AssistantCards.Named(0, "A"), AssistantCards.Named(0, ""))),
            Case("why do I keep getting pop? I love rock though", listOf(AssistantCards.Named(0, "Pop"), AssistantCards.Named(0, "Rock"))),
            Case("why do I keep getting pop and rock", listOf(AssistantCards.Named(0, "Pop"), AssistantCards.Named(0, "Rock"))),
            Case("i love trap, why so much trap", listOf(AssistantCards.Named(0, "Trap"))),
        )
        for (c in cases) {
            val t = AssistantCards.lessTarget(c.user, c.named)
            sb.append(line("lessTarget", {
                string("user", c.user)
                beginArray("named"); for (n in c.named) beginArray().intItem(n.kind).stringItem(n.name).endArray(); endArray()
            }, {
                if (t == null) nullValue("target") else string("target", t.name)
                string("label", if (t == null) "" else AssistantCards.lessLabel(t.name))
            }))
        }
        return sb.toString()
    }

    // ── setnotes.jsonl ───────────────────────────────────────────────────────

    private fun setNotes(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:setnotes"))
        val genres = listOf("V-pop", "Bedroom pop", "Hip Hop", "", "v-pop")
        val artists = listOf("Hoang Dung", "Mono", "Chappell Roan", "Sabrina Carpenter", "", "HIEUTHUHAI")
        for (c in 0 until 60) {
            val n = 1 + rng.int(18)
            val slots = ArrayList<SetNotes.Slot>(n)
            val mode = c % 4
            for (i in 0 until n) {
                val known = if (mode == 0) rng.int(5) != 0 else rng.int(3) != 0
                // Mode 1 makes long runs of one genre, mode 2 of one artist.
                val genre = if (mode == 1) genres[0] else genres[rng.int(genres.size)]
                val artist = if (mode == 2) artists[1] else artists[rng.int(artists.size)]
                slots.add(SetNotes.Slot(artist, genre, known))
            }
            val notes = SetNotes.notes(slots)
            sb.append(line("notes", {
                beginArray("slots")
                for (s in slots) beginArray().stringItem(s.artist).stringItem(s.genre).intItem(if (s.known) 1 else 0).endArray()
                endArray()
            }, {
                beginArray("notes")
                for (x in notes) beginArray().intItem(x.index).intItem(x.kind).intItem(x.count).stringItem(x.genre).stringItem(x.artist).stringItem(x.newArtist).stringItem(SetNotes.text(x)).endArray()
                endArray()
            }))
        }
        for (raw in listOf("V-pop", "Pop, Rock", "pop; rock", "hip hop/rap", "", "  indie pop | folk", ",pop")) {
            sb.append(line("firstGenre", { string("raw", raw) }, { string("genre", SetNotes.firstGenre(raw)) }))
        }
        return sb.toString()
    }

    // ── weeklynote.jsonl ─────────────────────────────────────────────────────

    private fun weeklyNote(): String {
        val sb = StringBuilder()
        val full = WeeklyNote.Evidence(25, 18, listOf("Mono", "HIEUTHUHAI", "Mono", "Chappell Roan", "Extra"), "Bedroom pop", "Soft pop & R&B", 22)
        val evidence = listOf(
            full,
            WeeklyNote.Evidence(1, 1, listOf("Mono"), "", "", -1),
            WeeklyNote.Evidence(0, -1, emptyList(), "", "", -1),
            WeeklyNote.Evidence(30, 0, emptyList(), "Dream pop", "", 0),
            WeeklyNote.Evidence(12, -1, listOf("A", "B"), "", "", 41),
            WeeklyNote.Evidence(25, 99, listOf("A"), "Trap", "Hip hop", 7),
        )
        for (e in evidence) {
            sb.append(line("template", { evidenceIn(this, e) }, {
                string("text", WeeklyNote.template(e)); string("lines", WeeklyNote.evidenceLines(e)); long("hash", WeeklyNote.evidenceHash(e))
            }))
        }
        val cloud = listOf(
            "This week's list has 25 songs, and 18 of them are from artists you have not played. It opens with [[Mono]] and [[HIEUTHUHAI]]. Last week 22% of what you played was new.",
            "This week's list has 25 songs. It leans on [[Bedroom pop]], which sits next to [[Soft pop & R&B]].",
            "This week's list has 26 songs. Last week 22% of what you played was new.",
            "This week's list has 25 songs. It opens with [[Radiohead]].",
            "Just one sentence about 25 songs.",
            "One. Two. Three. Four.",
            "This week's list has 25 songs.\nIt opens with Mono. I learned you like it.",
            "The Claude pick has 25 songs. It opens with Mono.",
            "This week's list has 25 songs. It opens with [[Mono]] and Mono is first.",
            "  This week's   list has 25 songs.  It opens with [[Mono]].  ",
            "This week's list has 25 songs, with 18 from new artists. Last week 22% was new to you. It opens with [[Mono]] and [[Chappell Roan]], then [[Mono]] again.",
        )
        for (c in cloud) {
            val r = WeeklyNote.check(c, full)
            sb.append(line("check", { evidenceIn(this, full); string("cloud", c) }, {
                if (r == null) nullValue("text") else string("text", r)
                int("sentences", WeeklyNote.sentenceCount(WeeklyNote.oneLine(c)))
            }))
        }
        return sb.toString()
    }

    private fun evidenceIn(w: BrainJson.Writer, e: WeeklyNote.Evidence) {
        w.int("tracks", e.tracks); w.int("newSongs", e.newSongs); w.strings("artists", e.artists)
        w.string("genre", e.genre); w.string("island", e.island); w.int("newSharePct", e.newSharePct)
    }

    // ── assistbatch.jsonl ────────────────────────────────────────────────────

    private fun batch(): String {
        val sb = StringBuilder()
        for (m in listOf("model-a", "model-b")) {
            for (k in listOf("islands", "tags")) {
                sb.append(line("cacheKey", { string("model", m); string("kind", k); string("input", "x|y|$k") }, { long("key", AssistBatch.cacheKey(m, k, "x|y|$k")) }))
            }
        }
        val ins = listOf(
            AssistBatch.IslandIn(1, listOf("Bedroom pop", "Dream pop"), listOf("Clairo", "Mono")),
            AssistBatch.IslandIn(2, listOf("Vietnamese Hip Hop", "Trap"), listOf("Hoang Dung", "Wxrdie")),
            AssistBatch.IslandIn(3, listOf("Soft pop & R&B"), listOf("Mono")),
        )
        val outs = listOf(
            AssistBatch.IslandName(1, "Dreamy bedroom pop", "Soft, close-up songs for late evenings"),
            AssistBatch.IslandName(1, "Another name for one", "x"),
            AssistBatch.IslandName(2, "Hip hop from Vietnam", "Bars and 808s, 2024 style"),
            AssistBatch.IslandName(2, "Trap nights", "Heavy low end and quick hi-hats"),
            AssistBatch.IslandName(4, "Dream pop", ""),
            AssistBatch.IslandName(3, "Mono", "An artist is not an island"),
            AssistBatch.IslandName(3, "Soft pop", "A \"quoted\" line"),
            AssistBatch.IslandName(1, "Claude's favourites", ""),
            AssistBatch.IslandName(2, "Sad boys", "Not about its own genres"),
            AssistBatch.IslandName(3, "Soft pop and learned R&B", ""),
        )
        val kept = AssistBatch.validateIslandNames(ins, outs)
        sb.append(line("islandNames", {
            beginArray("ins"); for (i in ins) beginArray().intItem(i.id).stringItem(i.genres.joinToString("|")).stringItem(i.artists.joinToString("|")).endArray(); endArray()
            beginArray("outs"); for (o in outs) beginArray().intItem(o.id).stringItem(o.name).stringItem(o.line).endArray(); endArray()
        }, {
            beginArray("kept"); for (k in kept) beginArray().intItem(k.id).stringItem(k.name).stringItem(k.line).endArray(); endArray()
        }))
        val shortlists = listOf(intArrayOf(10, 11, 12), intArrayOf(20, 21), IntArray(0))
        val tags = listOf(
            AssistBatch.TagOut(0, 10, 0.9), AssistBatch.TagOut(0, 11, 0.7), AssistBatch.TagOut(0, 12, 0.7), AssistBatch.TagOut(0, 13, 0.9),
            AssistBatch.TagOut(0, 10, 0.95), AssistBatch.TagOut(1, 20, 0.49), AssistBatch.TagOut(1, 21, 0.5), AssistBatch.TagOut(1, 10, 0.9),
            AssistBatch.TagOut(2, 10, 0.9), AssistBatch.TagOut(3, 10, 0.9), AssistBatch.TagOut(-1, 10, 0.9), AssistBatch.TagOut(1, 20, 1.5),
        )
        val keptTags = AssistBatch.validateTags(shortlists, tags)
        sb.append(line("tags", {
            beginArray("shortlists"); for (s in shortlists) { beginArray(); for (v in s) intItem(v); endArray() }; endArray()
            beginArray("outs"); for (t in tags) beginArray().intItem(t.index).intItem(t.atlasId).doubleItem(t.confidence).endArray(); endArray()
        }, {
            beginArray("kept"); for (t in keptTags) beginArray().intItem(t.index).intItem(t.atlasId).doubleItem(t.confidence).endArray(); endArray()
        }))
        val raw = listOf("Clairo", "  Beabadoobee ", "mono", "2024", "A", "Beabadoobee", "Rex Orange County", "x".repeat(70)) + List(30) { "Artist $it" }
        val names = AssistBatch.validateArtistNames(raw, setOf("clairo"))
        sb.append(line("artistNames", { strings("raw", raw); strings("known", listOf("clairo")) }, { strings("kept", names) }))
        return sb.toString()
    }

    const val README_NOTES = """
        - `assistantcards.jsonl` (stage 4, milestone 2): `AssistantCards`. `allChips`: `AssistantRules.Query` from the fields of `in` (the
          genre ids are `genreIds`, no moods, no years, no excluded genres), then `allChips(q, topArtistRef, topGenreId, unsupported)` is
          `chips` (`[kind, ref, genreId]` each). `chipLabel`: `chipLabel(Chip(kind, "yt:aaaaaaaaaaa", 5), artist, genre)`. `lessTarget`:
          `lessTarget(user, [Named(kind, name)])`, then `lessLabel(target.name)`. Names are compared folded, whole words, two characters or more; exactly one name may remain (a name inside a longer matching name is the same mention; a name with `love`, `like`, `enjoy`, `adore`, `favourite`, `into` or `want` in the three words before it is dropped).
        - `setnotes.jsonl`: `SetNotes.notes(slots)` for `slots` = `[artist, genre, known 1|0]`, each note `[index, kind, count, genre,
          artist, newArtist, text]` with `text = SetNotes.text(note)`. `firstGenre`: `SetNotes.firstGenre(raw)`.
        - `weeklynote.jsonl`: `WeeklyNote.Evidence(tracks, newSongs, artists, genre, island, newSharePct)` (-1 and "" are unknown).
          `template`: `template(e)`, `evidenceLines(e)` and `evidenceHash(e)`. `check`: `check(cloud, e)` is `text` (null: the template is
          shown instead) and `sentenceCount(oneLine(cloud))` is `sentences`.
        - `assistbatch.jsonl`: `cacheKey(model, kind, input)`; `islandNames`: `validateIslandNames(ins, outs)` (a row of `ins` is `[id,
          genres joined by |, artists joined by |]`); `tags`: `validateTags(shortlists, outs)`; `artistNames`: `validateArtistNames(raw,
          known)`. No regular expressions anywhere, as in the other files.
    """
}
