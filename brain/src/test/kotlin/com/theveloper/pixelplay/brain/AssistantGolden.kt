package com.theveloper.pixelplay.brain

/**
 * `assistant.jsonl` (docs/brain-spec.md, 9.3, 10.4; stage 4): the pure rules of Ask Taizo's tools. Refs, the request a tool call becomes,
 * the filters, the chips, the factor names and the grounding check. Text goes in as it is (UTF-8, escaped by the writer); numbers go in as
 * the hex bit patterns the other files use.
 */
object AssistantGolden {

    fun generate(): String {
        val sb = StringBuilder()
        refs(sb)
        normalize(sb)
        filters(sb)
        chips(sb)
        factors(sb)
        grounding(sb)
        return sb.toString()
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

    private fun BrainJson.Writer.intList(name: String, v: List<Int>) {
        beginArray(name)
        for (i in v) intItem(i)
        endArray()
    }

    private fun refs(sb: StringBuilder) {
        val cases = listOf(
            "sp:4uLU6hMCjMI75M1A2tKUQC", "sp:4uLU6hMCjMI75M1A2tKUQ", "sp:4uLU6hMCjMI75M1A2tKUQ!", "yt:dQw4w9WgXcQ", "yt:dQw4w9WgXc", "yt:dQw4w9Wg-cQ",
            "yt:dQw4w9Wg_cQ", "yt:dQw4w9Wg cQ", "SP:4uLU6hMCjMI75M1A2tKUQC", "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "", "Espresso", "yt:",
        )
        for (c in cases) {
            val r = AssistantRules.parseRef(c)
            sb.append(line("parseRef", { string("ref", c) }, {
                if (r == null) nullValue("kind") else int("kind", r.kind)
                if (r == null) nullValue("id") else string("id", r.id)
            }))
        }
    }

    private fun queryOut(w: BrainJson.Writer, q: AssistantRules.Query) {
        w.string("title", q.title)
        w.strings("seedRefs", q.seedRefs)
        w.intList("genreIds", q.genreIds)
        w.intList("moodIds", q.moodIds)
        w.int("yearFrom", q.yearFrom); w.int("yearTo", q.yearTo)
        w.string("energy", q.energy); w.string("tempo", q.tempo); w.string("novelty", q.novelty)
        w.int("noveltySharePct", q.noveltySharePct); w.int("count", q.count)
        w.strings("excludeArtistRefs", q.excludeArtistRefs)
        w.intList("excludeGenreIds", q.excludeGenreIds)
    }

    private fun normalize(sb: StringBuilder) {
        val sp = "sp:4uLU6hMCjMI75M1A2tKUQC"
        val yt = "yt:dQw4w9WgXcQ"
        class Case(
            val title: String?, val seeds: List<String>?, val genres: List<Int>?, val moods: List<Int>?, val from: Int, val to: Int,
            val energy: String?, val tempo: String?, val novelty: String?, val share: Int, val count: Int, val exclude: List<String>?,
            val min: Int, val max: Int, val def: Int,
        )
        val cases = listOf(
            Case("  Late   drive\n ", listOf(sp, yt, "Espresso", sp), listOf(12, 12, 0, -3, 40), listOf(5), 2015, 2005, "Lower", "SLOW", "new", 30, 30, listOf(yt, "junk"), 10, 60, 30),
            Case(null, null, null, null, 0, 0, null, null, null, -1, 0, null, 10, 60, 30),
            Case("x".repeat(90), listOf(sp, sp, sp, sp, sp, sp, yt, yt), List(20) { it + 1 }, emptyList(), -5, 1999, "sideways", "fast", "familiar", 250, 99, emptyList(), 10, 60, 30),
            Case("A mix", listOf(yt), listOf(3), emptyList(), 1990, 0, "higher", "mid", "any", 0, 7, listOf(sp), 10, 60, 30),
            Case("", emptyList(), emptyList(), emptyList(), 0, 2001, "any", "any", "any", -40, 80, emptyList(), 1, 50, 25),
            Case("Recs", listOf(sp), listOf(9), emptyList(), 0, 0, "any", "any", "any", -1, 0, emptyList(), 1, 50, 25),
        )
        for (c in cases) {
            val q = AssistantRules.normalize(
                c.title, c.seeds, c.genres, c.moods, c.from, c.to, c.energy, c.tempo, c.novelty, c.share, c.count, c.exclude, c.min, c.max, c.def,
            )
            sb.append(line("normalize", {
                if (c.title == null) nullValue("title") else string("title", c.title)
                if (c.seeds == null) nullValue("seeds") else strings("seeds", c.seeds)
                if (c.genres == null) nullValue("genres") else intList("genres", c.genres)
                if (c.moods == null) nullValue("moods") else intList("moods", c.moods)
                int("from", c.from); int("to", c.to)
                if (c.energy == null) nullValue("energy") else string("energy", c.energy)
                if (c.tempo == null) nullValue("tempo") else string("tempo", c.tempo)
                if (c.novelty == null) nullValue("novelty") else string("novelty", c.novelty)
                int("share", c.share); int("count", c.count)
                if (c.exclude == null) nullValue("exclude") else strings("exclude", c.exclude)
                int("min", c.min); int("max", c.max); int("def", c.def)
            }, { queryOut(this, q) }))
        }
    }

    private fun filters(sb: StringBuilder) {
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:assistant:filters"))
        fun pick(n: Int) = BrainMath.floorToLong(rng.uniform() * n.toDouble()).toInt().coerceIn(0, n - 1)
        val ytA = "yt:aaaaaaaaaaa"
        for (k in 0 until 48) {
            val q = AssistantRules.Query(
                title = "t", seedRefs = emptyList(),
                genreIds = if (pick(3) == 0) emptyList() else listOf(1 + pick(6), 8 + pick(3)),
                moodIds = if (pick(3) == 0) listOf(pick(5)) else emptyList(),
                yearFrom = if (pick(2) == 0) 0 else 1990 + pick(30), yearTo = if (pick(2) == 0) 0 else 2010 + pick(20),
                energy = AssistantRules.ENERGY[pick(3)], tempo = AssistantRules.TEMPO[pick(4)], novelty = AssistantRules.NOVELTY[pick(3)],
                noveltySharePct = if (pick(3) == 0) -1 else pick(101), count = 30,
                excludeArtistRefs = if (pick(2) == 0) emptyList() else listOf(ytA),
                excludeGenreIds = if (pick(4) == 0) listOf(1 + pick(6)) else emptyList(),
            )
            val gl = IntArray(pick(4)) { 1 + pick(10) }
            val moodHave = IntArray(pick(3)) { pick(5) }
            val facts = AssistantRules.Facts(
                if (pick(4) == 0) 0 else 1985 + pick(40), gl, pick(2) == 0, if (pick(3) == 0) 0 else 60 + pick(100),
                if (pick(2) == 0) "artist a" else "artist b", if (pick(4) == 0) -1 else pick(256), moodHave,
            )
            val ctx = AssistantRules.FilterContext(
                tempoKnown = pick(4) != 0, energyKnown = pick(4) != 0, moodKnown = pick(3) != 0, energyRef = if (pick(2) == 0) AssistantRules.ENERGY_MIDDLE else 60 + pick(140),
                excludedArtists = if (pick(3) == 0) setOf("artist a") else emptySet(),
            )
            val base = pick(40).toDouble() / 100.0
            sb.append(line("passes", {
                queryIn(this, q)
                intList("have", gl.toList()); int("year", facts.year); bool("novel", facts.novel); int("bpm", facts.bpm); string("artist", facts.artistKey)
                int("energy", facts.energy); intList("moodHave", moodHave.toList())
                bool("tempoKnown", ctx.tempoKnown); bool("energyKnown", ctx.energyKnown); bool("moodKnown", ctx.moodKnown); int("energyRef", ctx.energyRef)
                strings("excluded", ctx.excludedArtists.toList().sorted()); double("base", base)
            }, {
                bool("passes", AssistantRules.passes(q, facts, ctx))
                double("share", AssistantRules.noveltyShare(q, base))
                strings("unsupported", AssistantRules.unsupportedFilters(q, ctx))
            }))
        }
    }

    private fun queryIn(w: BrainJson.Writer, q: AssistantRules.Query) = queryOut(w, q)

    private fun chips(sb: StringBuilder) {
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:assistant:chips"))
        val ytA = "yt:aaaaaaaaaaa"
        val ytB = "yt:bbbbbbbbbbb"
        for (c in 0 until 10) {
            var q = AssistantRules.Query(
                "Late drive", listOf(ytA), listOf(4), emptyList(), 0, 0, AssistantRules.ANY, AssistantRules.ANY, AssistantRules.ANY,
                if (c % 2 == 0) -1 else 40, 30, emptyList(),
            )
            val start = q
            val steps = ArrayList<AssistantRules.Chip>()
            for (k in 0 until 8) {
                val kind = BrainMath.floorToLong(rng.uniform() * 11.0).toInt().coerceIn(0, 10)
                val chip = AssistantRules.Chip(
                    kind,
                    ref = if (BrainMath.floorToLong(rng.uniform() * 2.0) == 0L) ytA else ytB,
                    genreId = 1 + BrainMath.floorToLong(rng.uniform() * 6.0).toInt().coerceIn(0, 5),
                )
                steps.add(chip)
                q = AssistantRules.applyChip(q, chip)
            }
            val offered = AssistantRules.chipsFor(q, ytA, 5)
            sb.append(line("chips", {
                queryIn(this, start)
                beginArray("steps")
                for (s in steps) beginArray().intItem(s.kind).stringItem(s.ref).intItem(s.genreId).endArray()
                endArray()
            }, {
                queryOut(this, q)
                beginArray("offered")
                for (o in offered) beginArray().intItem(o.kind).stringItem(o.ref).intItem(o.genreId).endArray()
                endArray()
            }))
        }
    }

    private fun factors(sb: StringBuilder) {
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:assistant:factors"))
        for (c in 0 until 8) {
            val x = DoubleArray(Ranker.D)
            x[0] = 1.0
            for (i in 1 until Ranker.D) if (BrainMath.floorToLong(rng.uniform() * 3.0) == 0L) x[i] = (BrainMath.floorToLong(rng.uniform() * 33.0) - 16).toDouble() / 8.0
            val w = Ranker.weights(0.6)
            val out = AssistantRules.topFactors(x, w)
            sb.append(line("topFactors", {
                beginArray("x"); for (v in x) doubleItem(v); endArray()
                double("p0", 0.6)
            }, {
                beginArray("factors")
                for (f in out) beginArray().stringItem(f.name).intItem(if (f.up) 1 else 0).endArray()
                endArray()
            }))
        }
    }

    private fun grounding(sb: StringBuilder) {
        class Case(val names: List<String>, val texts: List<String>, val user: String, val reply: String, val drop: Boolean)
        val cases = listOf(
            Case(
                listOf("Espresso", "Sabrina Carpenter", "Short n' Sweet", "Chappell Roan"),
                listOf("{\"plays\":212}"), "make me something like Espresso",
                "[[Espresso]] by [[Sabrina Carpenter]] fits, and so does [[Good Luck, Babe!]] from the same side. You played Espresso 212 times.", true,
            ),
            Case(
                listOf("Espresso", "Sabrina Carpenter"), emptyList(), "",
                "Try [[Espresso by Sabrina Carpenter]] or [[Espresso - Sabrina Carpenter]] or [[Espresso & Billie Eilish]].", false,
            ),
            Case(
                listOf("Blinding Lights - Radio Edit", "The Weeknd"), listOf("Late night, the city lights"), "play the weeknd",
                "I queued \"Blinding Lights\" by [[The Weeknd]]. The line \"the city lights\" is in the lyrics. \"Starboy\" is another.", false,
            ),
            Case(
                emptyList(), listOf("Top artists: Radiohead 212 plays, 9% of your listening"), "how much do i play radiohead",
                "Radiohead: 212 plays, 9% of what you play. That is 15 hours in total. Not bad. About 3 times a week.", true,
            ),
            Case(
                listOf("Sơn Tùng M-TP", "Chúng Ta Của Hiện Tại"), listOf("1,240 plays"), "",
                "[[Son Tung M-TP]] has [[Chung Ta Cua Hien Tai]], and you have 1,240 plays in total. Version 2.5 of nothing. 3rd place.", true,
            ),
            Case(emptyList(), emptyList(), "", "No names here, and 30 songs is all I need to say. 2024 was a year.", false),
            Case(listOf("Espresso"), emptyList(), "something like Espresso or Please Please Please", "[[Espresso]] and [[Please Please Please]] you named, but not [[Please Please]] and not [[A]].", false),
            Case(emptyList(), emptyList(), "30 songs please", "I made 30 songs for you. [[ ]] [[unclosed and \"also unclosed", false),
        )
        for (c in cases) {
            val reg = Grounding.Registry()
            for (n in c.names) reg.addName(n)
            for (t in c.texts) reg.addText(t)
            val v = Grounding.check(c.reply, reg, c.user, "(unverified)", c.drop)
            sb.append(line("grounding", {
                strings("names", c.names); strings("texts", c.texts); string("user", c.user); string("reply", c.reply); bool("drop", c.drop)
            }, {
                string("cleaned", v.cleaned); strings("ungroundedNames", v.ungroundedNames); strings("ungroundedNumbers", v.ungroundedNumbers)
            }))
        }
        // The second pass (stage 4 review): names with no mark and no quote, single quotes and guillemets, whole-word text lookups.
        val scanCases = listOf(
            Case(
                listOf("Espresso", "Sabrina Carpenter", "Tyler, The Creator"), listOf("{\"genre\":\"asia\"}"), "something like Espresso",
                "You might also try Radiohead or OK Computer. Sabrina Carpenter is a good start, and so is Tyler.", false,
            ),
            Case(
                emptyList(), listOf("{\"genre\":\"asia\"}"), "",
                "Try \"Sia\" or 'Chappell Roan' or " + 0x00AB.toChar() + "Lorde" + 0x00BB.toChar() + ". It's Monday and I'm here for Spotify and Taizo.", false,
            ),
            Case(listOf("Good Luck, Babe!", "Espresso"), emptyList(), "", "Great pick. Espresso and Good Luck fit; so does Pink Pony Club.", false),
        )
        for (c in scanCases) {
            val reg = Grounding.Registry()
            for (n in c.names) reg.addName(n)
            for (t in c.texts) reg.addText(t)
            val v = Grounding.check(c.reply, reg, c.user, "(unverified)", c.drop, scanUnmarked = true)
            sb.append(line("grounding2", {
                strings("names", c.names); strings("texts", c.texts); string("user", c.user); string("reply", c.reply); bool("drop", c.drop)
            }, {
                string("cleaned", v.cleaned); strings("ungroundedNames", v.ungroundedNames); strings("ungroundedNumbers", v.ungroundedNumbers)
            }))
        }
        for (t in listOf("1,240 plays and 9.50% and 007 times, 2pac 3rd, R3 day 12 days; 45 minutes", "no numbers", "0.0 skips 100% 5 hours.")) {
            sb.append(line("numbers", { string("text", t) }, {
                strings("all", Grounding.numbersIn(t))
                beginArray("stat"); for (s in Grounding.listenerNumbers(t)) stringItem(s.token); endArray()
            }))
        }
    }

    const val README_NOTES = """
        - `assistant.jsonl` (stage 4, milestone 1): the pure rules of Ask Taizo's tools, `AssistantRules` and `Grounding`.
          `parseRef`: `AssistantRules.parseRef` (kind 0 `sp:` with 22 letters or digits, 1 `yt:` with 11 letters, digits, `_` or `-`; else null).
          `normalize`: `AssistantRules.normalize(title, seeds, genres, moods, from, to, energy, tempo, novelty, share, count, exclude, min, max,
          def)` is the `Query` in `out` (a null list is empty; `share` below 0 is -1, unset). `passes`: `AssistantRules.passes(Query, Facts(year,
          have, novel, bpm, artist, energy, moodHave), FilterContext(tempoKnown, energyKnown, moodKnown, energyRef, excluded))`,
          `noveltyShare(Query, base)` and `unsupportedFilters(Query, FilterContext)`. `chips`: start from the
          `Query` in `in`, `applyChip(q, Chip(kind, ref, genreId))` for each step, then `chipsFor(q, "yt:aaaaaaaaaaa", 5)` is `offered`.
          `topFactors`: `AssistantRules.topFactors(x, Ranker.weights(p0))`, each factor `[name, 1 up | 0 down]`.
          `grounding`: a `Grounding.Registry` with `addName` for each of `names` and `addText` for each of `texts`, then `Grounding.check(reply,
          registry, user, "(unverified)", drop)`. `numbers`: `Grounding.numbersIn(text)` and the tokens of `Grounding.listenerNumbers(text)`.
          `grounding2` is the same call with the fifth argument `scanUnmarked = true` (the second pass of the review): runs of capitalised words outside every
          mark and quote are checked too (`Grounding.unmarkedNames`), quotes include single quotes at a word start and guillemets, and a registered free text
          matches whole words only. A skipped sentence opener, `NOT_NAMES` and `NAME_CONNECTORS` are in `Grounding.kt`.
          A marked name that is a whole-word run of the user's own words (two letters or more) is grounded too. No regular expressions anywhere: a Swift port scans UTF-16 units, as the Kotlin does.
    """
}
