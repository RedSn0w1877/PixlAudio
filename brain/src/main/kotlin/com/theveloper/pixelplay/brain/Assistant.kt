package com.theveloper.pixelplay.brain

/**
 * The pure parts of Ask Taizo's ten music tools (docs/brain-spec.md, 9.3, stage 4): the refs a tool call can use, the structured request a
 * sentence becomes and how a request is cleaned and filtered, the chips under a mix card (deltas on the stored request, applied on the
 * device with no model call), and the fixed list of factor names `explain_track` may use.
 *
 * The assistant never ranks and never invents a track: it fills this structure, and the engine does the rest. Everything here is a
 * plain function of its inputs (no I/O, no clock, no regular expressions), so the Swift port reproduces `assistant.jsonl` bit for bit.
 */
object AssistantRules {

    // ── Refs ─────────────────────────────────────────────────────────────────

    /** A track a tool returned, in the form the next call takes: `sp:<spotifyId>` (22 letters and digits) or `yt:<videoId>` (11). */
    class Ref(val kind: Int, val id: String) {
        override fun toString(): String = (if (kind == REF_SPOTIFY) "sp:" else "yt:") + id
    }

    const val REF_SPOTIFY = 0
    const val REF_YOUTUBE = 1
    const val SPOTIFY_ID_LENGTH = 22
    const val YOUTUBE_ID_LENGTH = 11

    fun spotifyRef(id: String): String = "sp:$id"
    fun youTubeRef(videoId: String): String = "yt:$videoId"

    /** The ref [text] is, or null for anything else (a name, a half id, an id with a character a real id never has). */
    fun parseRef(text: String?): Ref? {
        if (text == null) return null
        if (text.length == 3 + SPOTIFY_ID_LENGTH && text.startsWith("sp:")) {
            for (i in 3 until text.length) if (!isAlnum(text[i])) return null
            return Ref(REF_SPOTIFY, text.substring(3))
        }
        if (text.length == 3 + YOUTUBE_ID_LENGTH && text.startsWith("yt:")) {
            for (i in 3 until text.length) if (!isAlnum(text[i]) && text[i] != '_' && text[i] != '-') return null
            return Ref(REF_YOUTUBE, text.substring(3))
        }
        return null
    }

    private fun isAlnum(c: Char): Boolean = (c in '0'..'9') || (c in 'a'..'z') || (c in 'A'..'Z')

    // ── Sentinels and enums (9.3: every parameter is required, "no value" is explicit) ──

    const val ANY = "any"
    const val LOWER = "lower"
    const val HIGHER = "higher"
    const val FAMILIAR = "familiar"
    const val NEW = "new"
    const val SLOW = "slow"
    const val MID = "mid"
    const val FAST = "fast"

    val ENERGY: List<String> = listOf(ANY, LOWER, HIGHER)
    val NOVELTY: List<String> = listOf(ANY, FAMILIAR, NEW)
    val TEMPO: List<String> = listOf(ANY, SLOW, MID, FAST)
    val SEED_KINDS: List<String> = listOf("song", "artist", "album", "playlist", "genre")
    val STATION_MODES: List<String> = listOf("familiar", "blend", "discover")
    val STEER_CHANGES: List<String> = listOf(
        "more_artist", "less_artist", "more_genre", "less_genre", "energy_up", "energy_down", "more_familiar", "more_new",
    )
    val STEER_SCOPES: List<String> = listOf("session", "always")
    val EXPLAIN_SCOPES: List<String> = listOf("summary", "islands", "genres", "dayparts", "recent_moves", "model")
    val RELEASE_SCOPES: List<String> = listOf("my_artists", "adjacent", "country")

    /** `novelty_share_pct` when the sentence said nothing about it (0 is a real answer: no new music). */
    const val SHARE_UNSET = -1

    const val DEFAULT_RESULTS = 25
    const val MAX_RECOMMEND = 50
    const val MIN_MIX = 10
    const val MAX_MIX = 60
    const val DEFAULT_MIX = 30
    const val MAX_RESOLVE_ITEMS = 25
    const val MAX_SEED_REFS = 5
    const val MAX_GENRES = 8
    const val MAX_EXCLUDED_ARTISTS = 25
    const val MAX_TITLE = 60

    fun clampInt(v: Int, lo: Int, hi: Int): Int = if (v < lo) lo else if (v > hi) hi else v

    /** The one of [allowed] that [value] is (folded), or [fallback]. */
    fun oneOf(value: String?, allowed: List<String>, fallback: String): String {
        if (value == null) return fallback
        val v = BrainText.fold(value).trim()
        for (a in allowed) if (a == v) return a
        return fallback
    }

    // ── The structured request ───────────────────────────────────────────────

    /**
     * What `recommend` and `build_mix` ask the engine for, with the sentinels already read: 0 is "no year bound", `any` is "no preference".
     * [excludeGenreIds] is not a tool parameter: it exists for the chips ("Less trap"), which are deltas on this stored request.
     */
    class Query(
        val title: String,
        val seedRefs: List<String>,
        val genreIds: List<Int>,
        val moodIds: List<Int>,
        val yearFrom: Int,
        val yearTo: Int,
        val energy: String,
        val tempo: String,
        val novelty: String,
        /** 0..100, or [SHARE_UNSET]. */
        val noveltySharePct: Int,
        /** Tracks wanted: 1..50 for `recommend`, 10..60 for `build_mix`. */
        val count: Int,
        val excludeArtistRefs: List<String>,
        val excludeGenreIds: List<Int> = emptyList(),
    ) {
        fun copy(
            title: String = this.title, seedRefs: List<String> = this.seedRefs, genreIds: List<Int> = this.genreIds,
            moodIds: List<Int> = this.moodIds, yearFrom: Int = this.yearFrom, yearTo: Int = this.yearTo, energy: String = this.energy,
            tempo: String = this.tempo, novelty: String = this.novelty, noveltySharePct: Int = this.noveltySharePct,
            count: Int = this.count, excludeArtistRefs: List<String> = this.excludeArtistRefs,
            excludeGenreIds: List<Int> = this.excludeGenreIds,
        ): Query = Query(
            title, seedRefs, genreIds, moodIds, yearFrom, yearTo, energy, tempo, novelty, noveltySharePct, count, excludeArtistRefs, excludeGenreIds,
        )
    }

    /** Cleans what a tool call carried: bad refs dropped, ids de-duplicated, bounds ordered, every number clamped. Never throws. */
    fun normalize(
        title: String?, seedRefs: List<String>?, genreIds: List<Int>?, moodIds: List<Int>?, yearFrom: Int, yearTo: Int,
        energy: String?, tempo: String?, novelty: String?, noveltySharePct: Int, count: Int, excludeArtistRefs: List<String>?,
        minCount: Int, maxCount: Int, defaultCount: Int,
    ): Query {
        var from = if (yearFrom > 0) yearFrom else 0
        var to = if (yearTo > 0) yearTo else 0
        if (from > 0 && to > 0 && from > to) { val t = from; from = to; to = t }
        val share = if (noveltySharePct < 0) SHARE_UNSET else clampInt(noveltySharePct, 0, 100)
        return Query(
            title = cleanTitle(title),
            seedRefs = refsOf(seedRefs, MAX_SEED_REFS),
            genreIds = idsOf(genreIds, MAX_GENRES),
            moodIds = idsOf(moodIds, MAX_GENRES),
            yearFrom = from, yearTo = to,
            energy = oneOf(energy, ENERGY, ANY),
            tempo = oneOf(tempo, TEMPO, ANY),
            novelty = oneOf(novelty, NOVELTY, ANY),
            noveltySharePct = share,
            count = if (count <= 0) defaultCount else clampInt(count, minCount, maxCount),
            excludeArtistRefs = refsOf(excludeArtistRefs, MAX_EXCLUDED_ARTISTS),
        )
    }

    /** A title for a card: trimmed, one line, at most [MAX_TITLE] characters. */
    fun cleanTitle(raw: String?): String {
        if (raw == null) return ""
        val sb = StringBuilder()
        var lastSpace = true
        for (c in raw) {
            val space = c == ' ' || c == '\n' || c == '\r' || c == '\t'
            if (space) {
                if (!lastSpace) sb.append(' ')
                lastSpace = true
            } else {
                sb.append(c)
                lastSpace = false
            }
        }
        var s = sb.toString().trim()
        if (s.length > MAX_TITLE) s = s.substring(0, MAX_TITLE).trim()
        return s
    }

    private fun refsOf(list: List<String>?, max: Int): List<String> {
        if (list == null) return emptyList()
        val out = ArrayList<String>()
        for (raw in list) {
            val ref = parseRef(raw.trim()) ?: continue
            val text = ref.toString()
            if (text !in out) out.add(text)
            if (out.size >= max) break
        }
        return out
    }

    private fun idsOf(list: List<Int>?, max: Int): List<Int> {
        if (list == null) return emptyList()
        val out = ArrayList<Int>()
        for (id in list) {
            if (id <= 0) continue
            if (id !in out) out.add(id)
            if (out.size >= max) break
        }
        return out
    }

    // ---- Filtering what the engine returned -------------------------------------------------------

    /**
     * What the filters read about one candidate. Unknown is 0 / -1 / empty: a filter with a bound never passes an unknown value. Energy and
     * tempo are the tag-weighted means of the genres' descriptors (3.5), NOT measured on the track: the result says so.
     */
    class Facts(
        /** 0 when unknown. */
        val year: Int,
        /** The atlas ids of the track's genres, ancestors included; empty when unknown. */
        val genreIds: IntArray,
        /** True when the listener has never played it. */
        val novel: Boolean,
        /** Beats per minute of its genres' usual range; 0 when unknown. */
        val bpm: Int,
        /** The primary artist, folded ([BrainText.fold]); "" when unknown. */
        val artistKey: String = "",
        /** 0..255 from its genres' descriptors; -1 when unknown. */
        val energy: Int = -1,
        /** Indexes into the atlas's mood list from its genres; empty when unknown. */
        val moodIds: IntArray = IntArray(0),
    )

    /** What a filter pass needs besides the request: what the data knows, and what the energy words are relative to. */
    class FilterContext(
        /** At least one candidate has a bpm: the tempo filter can be applied. */
        val tempoKnown: Boolean,
        val energyKnown: Boolean,
        val moodKnown: Boolean,
        /** The energy "lower" and "higher" are relative to (the seeds' mean), or [ENERGY_MIDDLE]. */
        val energyRef: Int = ENERGY_MIDDLE,
        /** Folded names of the artists the request excludes. */
        val excludedArtists: Set<String> = emptySet(),
    )

    const val ENERGY_MIDDLE = 128
    const val ENERGY_MARGIN = 12

    const val FILTER_ENERGY = "energy"
    const val FILTER_TEMPO = "tempo"
    const val FILTER_MOOD = "mood"

    /** The filters [q] asks for that no data answers: reported in the result instead of being quietly ignored. */
    fun unsupportedFilters(q: Query, ctx: FilterContext): List<String> {
        val out = ArrayList<String>()
        if (q.energy != ANY && !ctx.energyKnown) out.add(FILTER_ENERGY)
        if (q.tempo != ANY && !ctx.tempoKnown) out.add(FILTER_TEMPO)
        if (q.moodIds.isNotEmpty() && !ctx.moodKnown) out.add(FILTER_MOOD)
        return out
    }

    fun yearOk(year: Int, from: Int, to: Int): Boolean {
        if (from <= 0 && to <= 0) return true
        if (year <= 0) return false
        if (from > 0 && year < from) return false
        if (to > 0 && year > to) return false
        return true
    }

    /** The bpm band of a tempo word: slow under 90, mid 90 to 120, fast over 120 (the three buckets a listener means). */
    fun tempoOk(bpm: Int, tempo: String): Boolean {
        if (tempo == ANY) return true
        if (bpm <= 0) return false
        return when (tempo) {
            SLOW -> bpm < 90
            MID -> bpm in 90..120
            FAST -> bpm > 120
            else -> true
        }
    }

    /** `lower` is under the reference by at least [ENERGY_MARGIN], `higher` over it by as much; an unknown energy never passes. */
    fun energyOk(energy: Int, word: String, ref: Int): Boolean {
        if (word == ANY) return true
        if (energy < 0) return false
        return when (word) {
            LOWER -> energy <= ref - ENERGY_MARGIN
            HIGHER -> energy >= ref + ENERGY_MARGIN
            else -> true
        }
    }

    fun noveltyOk(novel: Boolean, novelty: String): Boolean = when (novelty) {
        NEW -> novel
        FAMILIAR -> !novel
        else -> true
    }

    fun genreOk(wanted: List<Int>, have: IntArray): Boolean {
        if (wanted.isEmpty()) return true
        for (g in have) if (g in wanted) return true
        return false
    }

    fun moodOk(wanted: List<Int>, have: IntArray): Boolean = genreOk(wanted, have)

    /**
     * Whether [facts] passes [q]. A genre filter passes when ANY of the wanted ids is among the track's (ancestors included); an excluded
     * genre or artist never passes. A filter the data cannot answer at all (see [unsupportedFilters]) is not applied.
     */
    fun passes(q: Query, facts: Facts, ctx: FilterContext): Boolean {
        if (!yearOk(facts.year, q.yearFrom, q.yearTo)) return false
        if (!noveltyOk(facts.novel, q.novelty)) return false
        if (!genreOk(q.genreIds, facts.genreIds)) return false
        if (q.excludeGenreIds.isNotEmpty() && genreOk(q.excludeGenreIds, facts.genreIds)) return false
        if (ctx.tempoKnown && !tempoOk(facts.bpm, q.tempo)) return false
        if (ctx.energyKnown && !energyOk(facts.energy, q.energy, ctx.energyRef)) return false
        if (ctx.moodKnown && q.moodIds.isNotEmpty() && !moodOk(q.moodIds, facts.moodIds)) return false
        if (facts.artistKey.isNotEmpty() && facts.artistKey in ctx.excludedArtists) return false
        return true
    }

    /**
     * The share of new tracks a `build_mix` asks the planner for: the sentence's own share when it gave one, else the novelty word
     * (`new` 0.6, `familiar` 0.1) or the listener's own appetite [base]. Always within 0..0.6 (the planner's cap).
     */
    fun noveltyShare(q: Query, base: Double): Double {
        val v = if (q.noveltySharePct >= 0) q.noveltySharePct.toDouble() / 100.0
        else when (q.novelty) {
            NEW -> 0.6
            FAMILIAR -> 0.1
            else -> base
        }
        return BrainMath.clip(v, 0.0, MixPlanner.NU_MAX)
    }

    // ── Chips: deltas on the stored request (9.3, "What the listener gets") ───

    const val CHIP_LESS_ARTIST = 0
    const val CHIP_MORE_GENRE = 1
    const val CHIP_LESS_GENRE = 2
    const val CHIP_SHORTER = 3
    const val CHIP_LONGER = 4
    const val CHIP_MORE_NEW = 5
    const val CHIP_MORE_FAMILIAR = 6
    const val CHIP_SLOWER = 7
    const val CHIP_FASTER = 8
    const val CHIP_LOWER_ENERGY = 9
    const val CHIP_HIGHER_ENERGY = 10

    /** One chip under a card: [kind] and, for the artist and genre chips, what it is about ([ref] for an artist, [genreId] for a genre). */
    class Chip(val kind: Int, val ref: String = "", val genreId: Int = 0)

    const val LENGTH_STEP = 10
    const val SHARE_STEP = 15

    /** `new` share a request with no share counts from when "more new" or "more familiar" is tapped. */
    const val SHARE_BASE = 25

    /** The request after [chip]. A chip that changes nothing (already at its limit) returns an equal request. */
    fun applyChip(q: Query, chip: Chip): Query = when (chip.kind) {
        CHIP_LESS_ARTIST -> {
            val ref = parseRef(chip.ref)?.toString()
            if (ref == null || ref in q.excludeArtistRefs || q.excludeArtistRefs.size >= MAX_EXCLUDED_ARTISTS) q
            else q.copy(excludeArtistRefs = q.excludeArtistRefs + ref, seedRefs = q.seedRefs.filter { it != ref })
        }
        CHIP_MORE_GENRE ->
            if (chip.genreId <= 0 || chip.genreId in q.genreIds || q.genreIds.size >= MAX_GENRES) q
            else q.copy(genreIds = q.genreIds + chip.genreId, excludeGenreIds = q.excludeGenreIds.filter { it != chip.genreId })
        CHIP_LESS_GENRE ->
            if (chip.genreId <= 0) q
            else q.copy(
                genreIds = q.genreIds.filter { it != chip.genreId },
                excludeGenreIds = if (chip.genreId in q.excludeGenreIds || q.excludeGenreIds.size >= MAX_GENRES) q.excludeGenreIds
                else q.excludeGenreIds + chip.genreId,
            )
        CHIP_SHORTER -> q.copy(count = clampInt(q.count - LENGTH_STEP, MIN_MIX, MAX_MIX))
        CHIP_LONGER -> q.copy(count = clampInt(q.count + LENGTH_STEP, MIN_MIX, MAX_MIX))
        CHIP_MORE_NEW -> q.copy(novelty = if (q.novelty == FAMILIAR) ANY else q.novelty, noveltySharePct = clampInt(shareOf(q) + SHARE_STEP, 0, 100))
        CHIP_MORE_FAMILIAR -> q.copy(novelty = if (q.novelty == NEW) ANY else q.novelty, noveltySharePct = clampInt(shareOf(q) - SHARE_STEP, 0, 100))
        CHIP_SLOWER -> q.copy(tempo = when (q.tempo) { FAST -> MID; MID -> SLOW; SLOW -> SLOW; else -> SLOW })
        CHIP_FASTER -> q.copy(tempo = when (q.tempo) { SLOW -> MID; MID -> FAST; FAST -> FAST; else -> FAST })
        CHIP_LOWER_ENERGY -> q.copy(energy = if (q.energy == HIGHER) ANY else LOWER)
        CHIP_HIGHER_ENERGY -> q.copy(energy = if (q.energy == LOWER) ANY else HIGHER)
        else -> q
    }

    private fun shareOf(q: Query): Int = if (q.noveltySharePct >= 0) q.noveltySharePct else SHARE_BASE

    /**
     * The chips offered under a mix card: "Less [top artist]", "More [top genre]" and a length chip, in that order, each only when it
     * can change something. [topArtistRef] and [topGenreId] come from the mix's own tracks (what it has most of); "" and 0 mean unknown.
     */
    fun chipsFor(q: Query, topArtistRef: String, topGenreId: Int): List<Chip> {
        val out = ArrayList<Chip>(3)
        val ref = parseRef(topArtistRef)?.toString()
        if (ref != null && ref !in q.excludeArtistRefs) out.add(Chip(CHIP_LESS_ARTIST, ref = ref))
        if (topGenreId > 0 && topGenreId !in q.genreIds) out.add(Chip(CHIP_MORE_GENRE, genreId = topGenreId))
        if (q.count > MIN_MIX) out.add(Chip(CHIP_SHORTER)) else out.add(Chip(CHIP_LONGER))
        return out
    }

    // ── Factors: what explain_track may say (9.3) ────────────────────────────

    /** One reason a pick scored the way it did: a name from [FACTOR_NAMES], and whether it pushed the pick up or down. */
    class Factor(val name: String, val up: Boolean)

    /**
     * The fixed names `explain_track` uses for the features of 5.8 (index = feature). A feature with no name here is never reported:
     * it is either not computed yet or says nothing a listener could use. No raw weight and no raw value leaves the device.
     */
    val FACTOR_NAMES: Array<String?> = arrayOfNulls<String>(Ranker.D).also {
        it[1] = "you have played this track before"
        it[2] = "you play this artist"
        it[3] = "you have been playing this artist lately"
        it[4] = "its genres match what you play"
        it[5] = "it fits one of your taste islands"
        it[6] = "it fits a large part of what you play"
        it[8] = "it is one of your liked songs"
        it[10] = "it is about due for a repeat"
        it[11] = "you have played it a lot lately"
        it[12] = "you played it in the last two hours"
        it[13] = "you played it in the last day"
        it[14] = "you played it in the last week"
        it[15] = "you played it in the last month"
        it[16] = "you have not played it for a while"
        it[17] = "you have never played this track"
        it[18] = "you have never played this artist"
        it[19] = "it is far from what you usually play"
        it[20] = "it is popular"
        it[22] = "it is close to tracks you just finished"
        it[23] = "it is close to tracks you just skipped"
        it[24] = "its genres are ones you just skipped"
        it[25] = "you finished the track before it"
        it[26] = "you have been skipping"
        it[36] = "this kind of pick has worked for you"
        it[37] = "it ranks high in the related lists"
        it[38] = "it is near the top of the radio list"
        it[39] = "it is a new release"
    }

    /** A factor is reported when its contribution is at least this far from 0. */
    const val FACTOR_MIN = 0.05

    const val FACTORS_SHOWN = 3

    /**
     * The factors that moved [x] the most under the weights [w]: contribution `w_i * x_i` for every named feature, largest absolute value
     * first, ties by the lower index; at most [FACTORS_SHOWN], none below [FACTOR_MIN]. `up` is the sign of the contribution.
     */
    fun topFactors(x: DoubleArray, w: DoubleArray): List<Factor> {
        val idx = ArrayList<Int>()
        val contrib = DoubleArray(Ranker.D)
        for (i in 1 until Ranker.D) {
            if (FACTOR_NAMES[i] == null) continue
            val c = w[i] * x[i]
            contrib[i] = c
            val a = if (c < 0.0) -c else c
            if (a >= FACTOR_MIN) idx.add(i)
        }
        // Selection of the largest |c|; indexes are visited in ascending order, so a tie keeps the lower index.
        val out = ArrayList<Factor>(FACTORS_SHOWN)
        val taken = BooleanArray(Ranker.D)
        while (out.size < FACTORS_SHOWN) {
            var best = -1
            var bestAbs = 0.0
            for (i in idx) {
                if (taken[i]) continue
                val a = if (contrib[i] < 0.0) -contrib[i] else contrib[i]
                if (best < 0 || a > bestAbs) {
                    best = i
                    bestAbs = a
                }
            }
            if (best < 0) break
            taken[best] = true
            out.add(Factor(FACTOR_NAMES[best]!!, contrib[best] > 0.0))
        }
        return out
    }
}
