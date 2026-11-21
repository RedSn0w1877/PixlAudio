package com.theveloper.pixelplay.brain

/**
 * The golden vectors of Endless Listening (`endless.jsonl`): the island seed picker ([EndlessSeeds]) and the host notes ([HostNotes]). A new
 * file only: no earlier golden file changes.
 */
object EndlessGolden {

    fun generate(): LinkedHashMap<String, String> {
        val files = LinkedHashMap<String, String>()
        files["endless.jsonl"] = seeds() + hostNotes() + facts()
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

    // ── seeds ────────────────────────────────────────────────────────────────

    private fun BrainJson.Writer.tracks(v: List<EndlessSeeds.Track>) {
        beginArray()
        for (t in v) beginArray().longItem(t.rid).longItem(t.aid).doubleItem(t.weight).endArray()
        endArray()
    }

    private fun seeds(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:endless:seeds"))
        for (c in 0 until 60) {
            // Mode 0: many islands; 1: one island; 2: no island, artists only; 3: islands that share one artist.
            val mode = c % 4
            val islandCount = when (mode) { 0 -> 2 + rng.int(7); 1 -> 1; 2 -> 0; else -> 2 + rng.int(3) }
            val islands = ArrayList<EndlessSeeds.IslandIn>()
            var nextRid = 1000L
            for (k in 0 until islandCount) {
                val n = rng.int(7)
                val tracks = ArrayList<EndlessSeeds.Track>()
                for (t in 0 until n) {
                    val aid = if (mode == 3) 7L + rng.int(2) else if (rng.int(6) == 0) 0L else 100L + rng.int(30)
                    // Weights repeat on purpose, so ties are decided by the recording id.
                    tracks.add(EndlessSeeds.Track(nextRid++, aid, (rng.int(5) * 2).toDouble() + if (rng.int(4) == 0) 0.5 else 0.0))
                }
                islands.add(EndlessSeeds.IslandIn(k + 1 + rng.int(3) * 10, (rng.int(6) + 1).toDouble(), tracks))
            }
            val artists = ArrayList<EndlessSeeds.ArtistIn>()
            for (k in 0 until rng.int(9)) {
                val aid = 500L + k
                val tracks = ArrayList<EndlessSeeds.Track>()
                for (t in 0 until rng.int(4)) tracks.add(EndlessSeeds.Track(nextRid++, aid, rng.int(4).toDouble()))
                artists.add(EndlessSeeds.ArtistIn(aid, rng.int(5).toDouble(), tracks))
            }
            val seeds = EndlessSeeds.pick(islands, artists)
            sb.append(line("seeds", {
                beginArray("islands")
                for (i in islands) { beginArray().intItem(i.id).doubleItem(i.weight); tracks(i.tracks); endArray() }
                endArray()
                beginArray("artists")
                for (a in artists) { beginArray().longItem(a.aid).doubleItem(a.weight); tracks(a.tracks); endArray() }
                endArray()
            }, {
                beginArray("seeds")
                for (s in seeds) beginArray().longItem(s.rid).longItem(s.aid).intItem(s.islandId).endArray()
                endArray()
            }))
        }
        return sb.toString()
    }

    // ── host notes ───────────────────────────────────────────────────────────

    private fun hostNotes(): String {
        val sb = StringBuilder()
        val rng = BrainHash.Rng(BrainHash.fnv1a64("golden:endless:notes"))
        val genres = listOf("V-pop", "Bedroom pop", "Hip hop", "", "v-pop", "Shoegaze")
        val facts = listOf(
            "", "Hushed, hazy pop made in bedrooms.", "A guitar wall of sound. It began in the late 1980s.", "Slow, drifting songs about almost nothing at all, sung quietly over a drone",
            "Rap built on drum machines and turntables.", "Dance music with a lossless feel.", "Bright guitars.",
        )
        val islands = listOf("", "Bedroom pop", "V-pop & Hip hop", "Shoegaze")
        val artists = listOf("Hoang Dung", "Mono", "", "HIEUTHUHAI", "Dr. Dre")
        for (c in 0 until 70) {
            val n = 1 + rng.int(20)
            val slots = ArrayList<HostNotes.Slot>(n)
            val sparse = c % 3 == 0
            for (i in 0 until n) {
                val has = { rng.int(if (sparse) 6 else 3) == 0 }
                slots.add(
                    HostNotes.Slot(
                        artist = artists[rng.int(artists.size)],
                        genre = genres[rng.int(genres.size)],
                        genreFact = facts[rng.int(facts.size)],
                        year = if (has()) 1895 + rng.int(240) else 0,
                        island = if (has()) islands[rng.int(islands.size)] else "",
                        newFromPlayedArtist = has(),
                        sideways = has(),
                    )
                )
            }
            val taken = ArrayList<HostNotes.Taken>()
            if (c % 2 == 1) {
                var at = rng.int(4)
                while (at < n) {
                    taken.add(HostNotes.Taken(at, 2 + rng.int(5)))
                    at += 5 + rng.int(8)
                }
            }
            val notes = HostNotes.notes(slots, taken)
            sb.append(line("notes", {
                beginArray("slots")
                for (s in slots) {
                    beginArray().stringItem(s.artist).stringItem(s.genre).stringItem(s.genreFact).intItem(s.year).stringItem(s.island)
                        .intItem(if (s.newFromPlayedArtist) 1 else 0).intItem(if (s.sideways) 1 else 0).endArray()
                }
                endArray()
                beginArray("taken")
                for (t in taken) beginArray().intItem(t.index).intItem(t.span).endArray()
                endArray()
            }, {
                beginArray("notes")
                for (x in notes) beginArray().intItem(x.index).intItem(x.kind).stringItem(x.a).stringItem(x.b).stringItem(HostNotes.text(x)).endArray()
                endArray()
            }))
        }
        return sb.toString()
    }

    private fun facts(): String {
        val sb = StringBuilder()
        val raws = listOf(
            "", "  ", "Hushed pop.", "A slow genre. It began in 1985.", "No full stop at all", "Wait... what", "One two three four five six seven eight nine ten.",
            "Short.\nSecond line.", "Dream pop built from echo and reverb, with a hush!",
        )
        for (raw in raws) sb.append(line("fact", { string("raw", raw) }, { string("fact", HostNotes.factOf(raw)) }))
        val texts = listOf(
            "From your Bedroom pop side.", "One. Two. Three.", "A very very very very very very very very very very long sentence here.",
            "Listen to the lossless sound.", "Learn the genre.", "", "Hello there.", "No stop but short words only",
        )
        for (t in texts) sb.append(line("speakable", { string("text", t) }, { bool("ok", HostNotes.isSpeakable(t)) }))
        return sb.toString()
    }

    const val README_NOTES = """
        - `endless.jsonl` (Endless Listening): `seeds`: `EndlessSeeds.pick(islands, artists)` for `islands` = `[id, weight, [[rid, aid, weight] ...]]`
          and `artists` = `[aid, weight, [[rid, aid, weight] ...]]`, the seeds `[rid, aid, islandId]` (-1 for the artist fallback). `notes`:
          `HostNotes.notes(slots, taken)` for `slots` = `[artist, genre, genreFact, year, island, newFromPlayedArtist 1|0, sideways 1|0]` and
          `taken` = `[index, span]` (the set notes already in the queue), each note `[index, kind, a, b, text]` with `text = HostNotes.text(note)`.
          `fact`: `HostNotes.factOf(raw)`. `speakable`: `HostNotes.isSpeakable(text)`. Word and sentence counting splits on a space and on `.`, `!`, `?`; no regular expressions.
    """
}
