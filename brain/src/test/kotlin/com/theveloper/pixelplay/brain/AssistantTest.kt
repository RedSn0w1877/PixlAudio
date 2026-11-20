package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The pure rules of Ask Taizo's tools (docs/brain-spec.md, 9.3) and the grounding rule (1, 9.4). */
class AssistantTest {

    private val sp = "sp:4uLU6hMCjMI75M1A2tKUQC"
    private val yt = "yt:dQw4w9WgXcQ"
    private val yt2 = "yt:aaaaaaaaaaa"

    private fun query(
        genres: List<Int> = emptyList(), from: Int = 0, to: Int = 0, energy: String = AssistantRules.ANY, tempo: String = AssistantRules.ANY,
        novelty: String = AssistantRules.ANY, share: Int = -1, count: Int = 30, exclude: List<String> = emptyList(), moods: List<Int> = emptyList(),
        seeds: List<String> = emptyList(),
    ) = AssistantRules.Query("t", seeds, genres, moods, from, to, energy, tempo, novelty, share, count, exclude)

    private fun ctx(tempo: Boolean = true, energy: Boolean = true, mood: Boolean = true, ref: Int = 128, excluded: Set<String> = emptySet()) =
        AssistantRules.FilterContext(tempo, energy, mood, ref, excluded)

    private fun facts(year: Int = 2020, genres: IntArray = intArrayOf(5), novel: Boolean = true, bpm: Int = 100, artist: String = "a", energy: Int = 100, moods: IntArray = intArrayOf(1)) =
        AssistantRules.Facts(year, genres, novel, bpm, artist, energy, moods)

    // ── Refs ─────────────────────────────────────────────────────────────────

    @Test
    fun `a ref is sp or yt with the right id and nothing else`() {
        assertEquals(AssistantRules.REF_SPOTIFY, AssistantRules.parseRef(sp)!!.kind)
        assertEquals("4uLU6hMCjMI75M1A2tKUQC", AssistantRules.parseRef(sp)!!.id)
        assertEquals(sp, AssistantRules.parseRef(sp).toString())
        assertEquals(AssistantRules.REF_YOUTUBE, AssistantRules.parseRef(yt)!!.kind)
        assertNotNull(AssistantRules.parseRef("yt:dQw4w9Wg-c_"))
        for (bad in listOf("", "sp:", "sp:short", "yt:short", "yt:dQw4w9WgXc!", "spotify:track:4uLU6hMCjMI75M1A2tKUQC", "Espresso", "SP:4uLU6hMCjMI75M1A2tKUQC")) {
            assertNull(AssistantRules.parseRef(bad), bad)
        }
        assertNull(AssistantRules.parseRef(null))
    }

    // ── The request ──────────────────────────────────────────────────────────

    @Test
    fun `a request is cleaned and clamped and the sentinels are read`() {
        val q = AssistantRules.normalize(
            "  Late   drive\n", listOf(sp, "junk", sp, yt), listOf(3, 3, 0, -1, 7), listOf(2), 2015, 2005, "Lower", "FAST", "new", 250, 99,
            listOf(yt, "x"), 10, 60, 30,
        )
        assertEquals("Late drive", q.title)
        assertEquals(listOf(sp, yt), q.seedRefs)
        assertEquals(listOf(3, 7), q.genreIds)
        assertEquals(2005, q.yearFrom)
        assertEquals(2015, q.yearTo)
        assertEquals("lower", q.energy)
        assertEquals("fast", q.tempo)
        assertEquals(100, q.noveltySharePct)
        assertEquals(60, q.count)
        assertEquals(listOf(yt), q.excludeArtistRefs)
        // 0 is "no bound" and -1 is "no share"; a word that is not one of ours is "any".
        val empty = AssistantRules.normalize(null, null, null, null, 0, -4, "sideways", null, "", -1, 0, null, 1, 50, 25)
        assertEquals("", empty.title)
        assertEquals(0, empty.yearFrom)
        assertEquals(0, empty.yearTo)
        assertEquals("any", empty.energy)
        assertEquals(AssistantRules.SHARE_UNSET, empty.noveltySharePct)
        assertEquals(25, empty.count)
        assertEquals(1, AssistantRules.normalize("x", null, null, null, 0, 0, null, null, null, 0, -3, null, 1, 50, 25).let { if (it.count == 25) 1 else 0 })
        assertEquals(0, AssistantRules.normalize("x", null, null, null, 0, 0, null, null, null, 0, 5, null, 10, 60, 30).noveltySharePct)
        assertEquals(10, AssistantRules.normalize("x", null, null, null, 0, 0, null, null, null, 0, 5, null, 10, 60, 30).count)
    }

    @Test
    fun `a title is one line and at most sixty characters`() {
        assertEquals(AssistantRules.MAX_TITLE, AssistantRules.cleanTitle("x".repeat(200)).length)
        assertEquals("a b", AssistantRules.cleanTitle(" a \t\n b "))
        assertEquals("", AssistantRules.cleanTitle(null))
    }

    // ── Filters ──────────────────────────────────────────────────────────────

    @Test
    fun `years genres novelty tempo energy mood and artists filter, and an unknown value never passes a bound`() {
        assertTrue(AssistantRules.passes(query(), facts(year = 0, genres = IntArray(0), bpm = 0, energy = -1, moods = IntArray(0)), ctx()))
        assertFalse(AssistantRules.passes(query(from = 2010), facts(year = 0), ctx()))
        assertFalse(AssistantRules.passes(query(from = 2010, to = 2019), facts(year = 2020), ctx()))
        assertTrue(AssistantRules.passes(query(from = 2010, to = 2019), facts(year = 2015), ctx()))
        assertTrue(AssistantRules.passes(query(genres = listOf(5, 9)), facts(genres = intArrayOf(9)), ctx()))
        assertFalse(AssistantRules.passes(query(genres = listOf(5, 9)), facts(genres = intArrayOf(2)), ctx()))
        assertFalse(AssistantRules.passes(query(genres = listOf(5)), facts(genres = IntArray(0)), ctx()))
        assertFalse(AssistantRules.passes(query(novelty = "new"), facts(novel = false), ctx()))
        assertFalse(AssistantRules.passes(query(novelty = "familiar"), facts(novel = true), ctx()))
        assertTrue(AssistantRules.passes(query(tempo = "slow"), facts(bpm = 80), ctx()))
        assertFalse(AssistantRules.passes(query(tempo = "slow"), facts(bpm = 95), ctx()))
        assertTrue(AssistantRules.passes(query(tempo = "mid"), facts(bpm = 120), ctx()))
        assertFalse(AssistantRules.passes(query(tempo = "fast"), facts(bpm = 0), ctx()))
        // Energy is relative to the reference and needs a margin.
        assertTrue(AssistantRules.passes(query(energy = "lower"), facts(energy = 100), ctx(ref = 128)))
        assertFalse(AssistantRules.passes(query(energy = "lower"), facts(energy = 120), ctx(ref = 128)))
        assertTrue(AssistantRules.passes(query(energy = "higher"), facts(energy = 200), ctx(ref = 128)))
        assertFalse(AssistantRules.passes(query(energy = "higher"), facts(energy = -1), ctx(ref = 128)))
        assertFalse(AssistantRules.passes(query(moods = listOf(4)), facts(moods = intArrayOf(1)), ctx()))
        assertTrue(AssistantRules.passes(query(moods = listOf(1)), facts(moods = intArrayOf(1)), ctx()))
        assertFalse(AssistantRules.passes(query(), facts(artist = "sabrina carpenter"), ctx(excluded = setOf("sabrina carpenter"))))
        assertFalse(AssistantRules.passes(query().copy(excludeGenreIds = listOf(5)), facts(genres = intArrayOf(5, 6)), ctx()))
    }

    @Test
    fun `a filter the data cannot answer is reported and not applied`() {
        val q = query(energy = "lower", tempo = "slow", moods = listOf(3))
        val none = ctx(tempo = false, energy = false, mood = false)
        assertEquals(listOf("energy", "tempo", "mood"), AssistantRules.unsupportedFilters(q, none))
        assertTrue(AssistantRules.passes(q, facts(bpm = 0, energy = -1, moods = IntArray(0)), none))
        assertEquals(emptyList<String>(), AssistantRules.unsupportedFilters(q, ctx()))
        assertEquals(emptyList<String>(), AssistantRules.unsupportedFilters(query(), none))
    }

    @Test
    fun `the share of new music is the sentence's, then the word's, then the listener's, and never above the planner's cap`() {
        assertEquals(0.4, AssistantRules.noveltyShare(query(share = 40), 0.2))
        assertEquals(0.0, AssistantRules.noveltyShare(query(share = 0, novelty = "new"), 0.2))
        assertEquals(MixPlanner.NU_MAX, AssistantRules.noveltyShare(query(share = 100), 0.2))
        assertEquals(0.6, AssistantRules.noveltyShare(query(novelty = "new"), 0.2))
        assertEquals(0.1, AssistantRules.noveltyShare(query(novelty = "familiar"), 0.2))
        assertEquals(0.2, AssistantRules.noveltyShare(query(), 0.2))
    }

    // ── Chips ────────────────────────────────────────────────────────────────

    @Test
    fun `the chips are deltas on the stored request and stay inside the limits`() {
        var q = query(count = 30, genres = listOf(4), seeds = listOf(yt))
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_LESS_ARTIST, ref = yt))
        assertEquals(listOf(yt), q.excludeArtistRefs)
        assertEquals(emptyList<String>(), q.seedRefs)
        // The same chip twice changes nothing; a ref that is not a ref changes nothing.
        assertEquals(q.excludeArtistRefs, AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_LESS_ARTIST, ref = yt)).excludeArtistRefs)
        assertEquals(q.excludeArtistRefs, AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_LESS_ARTIST, ref = "Sabrina")).excludeArtistRefs)
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_MORE_GENRE, genreId = 9))
        assertEquals(listOf(4, 9), q.genreIds)
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_LESS_GENRE, genreId = 4))
        assertEquals(listOf(9), q.genreIds)
        assertEquals(listOf(4), q.excludeGenreIds)
        // Taking a genre back in removes it from the excluded ones.
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_MORE_GENRE, genreId = 4))
        assertEquals(emptyList<Int>(), q.excludeGenreIds)
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_SHORTER))
        assertEquals(20, q.count)
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_SHORTER))
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_SHORTER))
        assertEquals(10, q.count)
        repeat(8) { q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_LONGER)) }
        assertEquals(60, q.count)
        // New and familiar move the share by 15 from 25 when it was not set, within 0..100.
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_MORE_NEW))
        assertEquals(40, q.noveltySharePct)
        repeat(6) { q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_MORE_FAMILIAR)) }
        assertEquals(0, q.noveltySharePct)
        repeat(8) { q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_MORE_NEW)) }
        assertEquals(100, q.noveltySharePct)
        // Tempo and energy step and come back to "any" instead of overshooting.
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_SLOWER))
        assertEquals("slow", q.tempo)
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_FASTER))
        assertEquals("mid", q.tempo)
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_LOWER_ENERGY))
        assertEquals("lower", q.energy)
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_HIGHER_ENERGY))
        assertEquals("any", q.energy)
        // No chip ever changes what it was not about.
        assertEquals(listOf(9, 4).sorted(), q.genreIds.sorted())
        assertEquals("t", q.title)
    }

    @Test
    fun `the chips offered are about the mix itself and only when they can change something`() {
        val q = query(count = 30)
        val chips = AssistantRules.chipsFor(q, yt, 5)
        assertEquals(listOf(AssistantRules.CHIP_LESS_ARTIST, AssistantRules.CHIP_MORE_GENRE, AssistantRules.CHIP_SHORTER), chips.map { it.kind })
        // An artist already excluded, a genre already asked for and a mix at its shortest offer other things.
        val done = query(count = 10, genres = listOf(5), exclude = listOf(yt))
        assertEquals(listOf(AssistantRules.CHIP_LONGER), AssistantRules.chipsFor(done, yt, 5).map { it.kind })
        assertEquals(listOf(AssistantRules.CHIP_LONGER), AssistantRules.chipsFor(done, "", 0).map { it.kind })
    }

    // ── Factors ──────────────────────────────────────────────────────────────

    @Test
    fun `the factors are the three biggest contributions, named from the fixed list, with a direction and no raw numbers`() {
        val x = DoubleArray(Ranker.D)
        x[0] = 1.0
        x[1] = 0.9      // you have played this track before: +
        x[2] = 0.8      // you play this artist: +
        x[17] = 1.0     // never played this track: -0.3
        x[23] = 0.9     // close to tracks you just skipped: -
        x[27] = 1.0     // a feature with no name is never reported
        val w = Ranker.weights(0.6)
        val factors = AssistantRules.topFactors(x, w)
        assertEquals(3, factors.size)
        for (f in factors) assertTrue(f.name in AssistantRules.FACTOR_NAMES.filterNotNull(), f.name)
        assertEquals("you have played this track before", factors[0].name)
        assertTrue(factors[0].up)
        assertTrue(factors.any { it.name == "it is close to tracks you just skipped" && !it.up })
        // Nothing big enough: nothing reported, and the bias is never a factor.
        val flat = DoubleArray(Ranker.D)
        flat[0] = 1.0
        assertEquals(0, AssistantRules.topFactors(flat, w).size)
    }

    // ── Grounding ────────────────────────────────────────────────────────────

    private fun registry(names: List<String> = emptyList(), texts: List<String> = emptyList()): Grounding.Registry {
        val r = Grounding.Registry()
        for (n in names) r.addName(n)
        for (t in texts) r.addText(t)
        return r
    }

    private fun check(reply: String, reg: Grounding.Registry, user: String = "", drop: Boolean = true) =
        Grounding.check(reply, reg, user, "(unverified)", drop)

    @Test
    fun `a name a tool returned stays and loses its marks, a name no tool returned is replaced and flagged`() {
        val reg = registry(listOf("Espresso", "Sabrina Carpenter"))
        val v = check("Try [[Espresso]] by [[Sabrina Carpenter]], and [[Good Luck, Babe!]] too.", reg)
        assertEquals("Try Espresso by Sabrina Carpenter, and (unverified) too.", v.cleaned)
        assertEquals(listOf("Good Luck, Babe!"), v.ungroundedNames)
        assertFalse(v.ok)
        assertTrue(check("Try [[Espresso]].", reg).ok)
    }

    @Test
    fun `names are compared folded and the clean title counts`() {
        val reg = registry(listOf("Blinding Lights - Radio Edit", "Sơn Tùng M-TP"))
        assertTrue(check("[[blinding lights]]", reg).ok)
        assertTrue(check("[[Blinding Lights (Radio Edit)]]", reg).ok)
        assertTrue(check("[[Son Tung M TP]]", reg).ok)
        assertFalse(check("[[Blinding Lights Remix]]", reg).ok)
    }

    @Test
    fun `several names in one mark are fine when every one of them was returned`() {
        val reg = registry(listOf("Espresso", "Sabrina Carpenter", "Billie Eilish"))
        assertTrue(check("[[Espresso by Sabrina Carpenter]]", reg).ok)
        assertTrue(check("[[Espresso - Sabrina Carpenter]]", reg).ok)
        assertTrue(check("[[Sabrina Carpenter & Billie Eilish]]", reg).ok)
        assertTrue(check("[[Sabrina Carpenter, Billie Eilish]]", reg).ok)
        assertFalse(check("[[Espresso by Taylor Swift]]", reg).ok)
    }

    @Test
    fun `a quoted phrase is checked too, unless it is something the conversation held`() {
        val reg = registry(listOf("Starboy"), listOf("Late night, the city lights are low"))
        assertTrue(check("\"Starboy\" is a good one.", reg).ok)
        assertTrue(check("The line \"the city lights\" is in the lyrics.", reg).ok)
        assertTrue(check("You asked for \"late drive\".", reg, user = "something for a late drive").ok)
        val bad = check("Also \"Blinding Lights\" and “Save Your Tears”.", reg)
        assertEquals(listOf("Blinding Lights", "Save Your Tears"), bad.ungroundedNames)
        assertEquals("Also (unverified) and (unverified).", bad.cleaned)
    }

    @Test
    fun `a number about the listener has to come from a tool, the user, or the app`() {
        val reg = registry(emptyList(), listOf("{\"plays\":212,\"share\":9.5}"))
        reg.addNumber(25)
        assertTrue(check("Radiohead: 212 plays.", reg).ok)
        assertTrue(check("That is 9.5% of what you play, and I picked 25 songs.", reg).ok)
        val bad = check("You played it 300 times. Nice pick. It is 15 hours.", reg)
        assertEquals(listOf("300", "15"), bad.ungroundedNumbers)
        // The sentences that state them are dropped; the rest stays.
        assertEquals("Nice pick.", bad.cleaned)
        assertEquals("You played it 300 times. Nice pick. It is 15 hours.", check("You played it 300 times. Nice pick. It is 15 hours.", reg, drop = false).cleaned)
        // The user's own number is theirs; years and numbers with no unit are not statistics.
        assertTrue(check("I made 30 songs.", reg, user = "make me 30 songs").ok)
        assertTrue(check("It came out in 2024, track 3 of the album.", reg).ok)
        assertEquals(listOf("1240", "9.5", "7"), Grounding.numbersIn("1,240 plays, 9.50 and 007 but not 2pac or R3"))
    }

    @Test
    fun `a marked name the listener typed is theirs, as whole words only`() {
        val reg = registry(listOf("Espresso"))
        assertTrue(check("[[Please Please Please]] is the one you named.", reg, user = "something like Please Please Please").ok)
        assertTrue(check("[[Espresso]] and [[sabrina carpenter]].", reg, user = "Sabrina Carpenter, please").ok)
        // A name that is only part of a longer word of the user's, or a single letter, is not theirs.
        assertFalse(check("[[Sabrina]].", reg, user = "Sabrinas Mix").ok)
        assertFalse(check("[[A]].", reg, user = "a song").ok)
        assertFalse(check("[[Taylor Swift]].", reg, user = "something new").ok)
    }

    @Test
    fun `an unclosed mark and an empty mark are not names`() {
        val reg = registry()
        assertTrue(check("[[ ]] and [[unclosed", reg).ok)
        assertEquals(emptyList<Grounding.Span>().size, Grounding.markedSpans("no marks [x] here").size)
        assertEquals(1, Grounding.markedSpans("a [[b]] c [[").size)
    }
}
