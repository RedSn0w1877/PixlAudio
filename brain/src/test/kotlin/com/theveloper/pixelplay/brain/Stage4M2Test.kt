package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Stage 4 milestone 2: the chips, set notes, the weekly note and the batch checks (docs/brain-spec.md, 9.3, 9.4). */
class Stage4M2Test {

    private val ytA = "yt:aaaaaaaaaaa"

    private fun query(share: Int = -1, tempo: String = AssistantRules.ANY, energy: String = AssistantRules.ANY, count: Int = 30) = AssistantRules.Query(
        "Late drive", listOf(ytA), listOf(4), emptyList(), 0, 0, energy, tempo, AssistantRules.ANY, share, count, emptyList(),
    )

    // ── Chips ────────────────────────────────────────────────────────────────

    @Test
    fun `a mix card offers the three chips of the spec and the pace and novelty chips after them`() {
        val chips = AssistantCards.allChips(query(), ytA, 7, emptyList())
        val labels = chips.map { AssistantCards.chipLabel(it, "Sabrina Carpenter", "R&B") }
        assertEquals(listOf("Less Sabrina Carpenter", "More R&B", "Shorter", "Slower", "More new", "More familiar"), labels)
    }

    @Test
    fun `a chip is never offered that cannot change the request`() {
        // Already slow and already at the top of the new-music share: neither is offered.
        val chips = AssistantCards.allChips(query(share = 60, tempo = AssistantRules.SLOW, count = AssistantRules.MIN_MIX), "", 0, emptyList())
        val kinds = chips.map { it.kind }
        assertEquals(listOf(AssistantRules.CHIP_LONGER, AssistantRules.CHIP_LOWER_ENERGY, AssistantRules.CHIP_MORE_FAMILIAR), kinds)
        // No data for tempo or energy: no pace chip at all.
        val none = AssistantCards.allChips(query(), ytA, 7, listOf(AssistantRules.FILTER_TEMPO, AssistantRules.FILTER_ENERGY)).map { it.kind }
        assertFalse(AssistantRules.CHIP_SLOWER in none || AssistantRules.CHIP_LOWER_ENERGY in none)
        // Never more than six.
        assertTrue(AssistantCards.allChips(query(), ytA, 7, emptyList()).size <= AssistantCards.MAX_CHIPS)
    }

    @Test
    fun `a chip about a name has no label without one`() {
        assertEquals("", AssistantCards.chipLabel(AssistantRules.Chip(AssistantRules.CHIP_LESS_ARTIST, ytA), "", "x"))
        assertEquals("", AssistantCards.chipLabel(AssistantRules.Chip(AssistantRules.CHIP_MORE_GENRE, "", 3), "x", "  "))
        val long = AssistantCards.chipLabel(AssistantRules.Chip(AssistantRules.CHIP_LESS_ARTIST, ytA), "The Hierophant Green Guardians of Everything", "")
        assertTrue(long.length <= 5 + AssistantCards.MAX_NAME, long)
    }

    @Test
    fun `a tapped chip is a delta on the stored request and takes no model`() {
        var q = query()
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_SLOWER))
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_MORE_NEW))
        q = AssistantRules.applyChip(q, AssistantRules.Chip(AssistantRules.CHIP_SHORTER))
        assertEquals(AssistantRules.SLOW, q.tempo)
        assertEquals(AssistantRules.SHARE_BASE + AssistantRules.SHARE_STEP, q.noveltySharePct)
        assertEquals(20, q.count)
        // After the tap the same chips are not all offered again.
        val kinds = AssistantCards.allChips(q, ytA, 7, emptyList()).map { it.kind }
        assertFalse(AssistantRules.CHIP_SLOWER in kinds)
    }

    @Test
    fun `less X is offered only for what the listener asked about and a tool returned`() {
        val named = listOf(AssistantCards.Named(AssistantCards.NAMED_GENRE, "Trap"), AssistantCards.Named(AssistantCards.NAMED_GENRE, "Pop"))
        assertEquals("Trap", AssistantCards.lessTarget("Why do I keep getting trap?", named)?.name)
        assertEquals("Less Trap", AssistantCards.lessLabel("Trap"))
        // A word inside another word is not a match; nothing named means no chip.
        assertNull(AssistantCards.lessTarget("why so much entrapment", named))
        assertNull(AssistantCards.lessTarget("why so much of it", named))
        // The question is about one thing: a name the listener praised, or a second name, gives no chip.
        val pair = listOf(AssistantCards.Named(0, "Pop"), AssistantCards.Named(0, "Rock"))
        assertEquals("Pop", AssistantCards.lessTarget("why do I keep getting pop? I love rock though", pair)?.name)
        assertNull(AssistantCards.lessTarget("why do I keep getting pop and rock", pair))
        // The longest name wins.
        val both = listOf(AssistantCards.Named(0, "Trap"), AssistantCards.Named(0, "Latin Trap"))
        assertEquals("Latin Trap", AssistantCards.lessTarget("why latin trap", both)?.name)
    }

    @Test
    fun `the names explain_track may use contain no word the app never says`() {
        for (n in AssistantRules.FACTOR_NAMES.toList() + AssistantRules.NEGATIVE_NAMES.toList()) {
            if (n == null) continue
            val f = BrainText.fold(n)
            for (w in WeeklyNote.FORBIDDEN) {
                // "learn" is a word of the copy rule; the factor names must not use it either.
                assertFalse(f.contains(w), "factor name \"$n\" says \"$w\"")
            }
        }
    }

    // ── Set notes ────────────────────────────────────────────────────────────

    private fun known(artist: String, genre: String) = SetNotes.Slot(artist, genre, true)
    private fun fresh(artist: String) = SetNotes.Slot(artist, "", false)

    @Test
    fun `three from a genre then something new reads as the spec says`() {
        val slots = listOf(known("A", "V-pop"), known("B", "V-pop"), known("C", "V-pop"), fresh("HIEUTHUHAI"))
        val notes = SetNotes.notes(slots)
        assertEquals(1, notes.size)
        assertEquals(0, notes[0].index)
        assertEquals("Three from your V-pop side, then something new from HIEUTHUHAI.", SetNotes.text(notes[0]))
    }

    @Test
    fun `a note is true of its tracks or there is none`() {
        // A single known track before a new one: nothing to say.
        assertTrue(SetNotes.notes(listOf(known("A", "x"), fresh("B"))).isEmpty())
        // Genres that differ: the group is described by what is true of it, not by a genre.
        val mixed = SetNotes.notes(listOf(known("A", "pop"), known("B", "rock"), fresh("C")))
        assertEquals("Two you know, then something new from C.", SetNotes.text(mixed.single()))
        // The same artist three times.
        val same = SetNotes.notes(listOf(known("Mono", ""), known("mono", ""), known("Mono", ""), fresh("D")))
        assertEquals("Three from Mono, then something new from D.", SetNotes.text(same.single()))
        // A new track with no artist name cannot be named.
        assertTrue(SetNotes.notes(listOf(known("A", "x"), known("B", "x"), fresh(""))).isEmpty())
        // Three of a genre and no new track after: said without the second half.
        assertEquals("Three from your x side.", SetNotes.text(SetNotes.notes(listOf(known("A", "x"), known("B", "x"), known("C", "x"))).single()))
        // The empty queue has no notes.
        assertTrue(SetNotes.notes(emptyList()).isEmpty())
    }

    @Test
    fun `at most one note for every five tracks`() {
        val slots = ArrayList<SetNotes.Slot>()
        repeat(12) {
            slots.add(known("A", "g")); slots.add(known("B", "g")); slots.add(fresh("N$it"))
        }
        val notes = SetNotes.notes(slots)
        assertTrue(notes.size >= 2)
        for (i in 1 until notes.size) assertTrue(notes[i].index - notes[i - 1].index >= SetNotes.MIN_GAP, "notes ${notes[i - 1].index} and ${notes[i].index}")
        assertTrue(notes.size <= (slots.size + SetNotes.MIN_GAP - 1) / SetNotes.MIN_GAP)
    }

    @Test
    fun `a genre field gives its first genre`() {
        assertEquals("pop", SetNotes.firstGenre("pop, rock"))
        assertEquals("V-pop", SetNotes.firstGenre(" V-pop ; ballad"))
        assertEquals("", SetNotes.firstGenre(null))
        assertEquals("", SetNotes.firstGenre(",pop"))
    }

    // ── The weekly note ──────────────────────────────────────────────────────

    private val evidence = WeeklyNote.Evidence(25, 18, listOf("Mono", "HIEUTHUHAI"), "Bedroom pop", "Soft pop & R&B", 22)

    @Test
    fun `the template says only what the evidence holds`() {
        val t = WeeklyNote.template(evidence)
        assertEquals(
            "This week's First Listen has 25 songs, and 18 of them are not in your library yet. Some of it is Bedroom pop, which sits next to Soft pop & R&B. In the last 7 days, 22% of what you played was new to you.",
            t,
        )
        assertEquals(3, WeeklyNote.sentenceCount(t))
        assertEquals("", WeeklyNote.template(WeeklyNote.Evidence(0, -1, emptyList(), "", "", -1)))
        // Unknown rows are left out, not guessed.
        val thin = WeeklyNote.template(WeeklyNote.Evidence(10, -1, emptyList(), "", "", -1))
        assertEquals("This week's First Listen has 10 songs.", thin)
    }

    @Test
    fun `a model note is used only when its names and numbers are the evidence's`() {
        val ok = "This week's list has 25 songs, and 18 of them are from artists you have not played. It opens with [[Mono]] and [[HIEUTHUHAI]]."
        assertEquals("This week's list has 25 songs, and 18 of them are from artists you have not played. It opens with Mono and HIEUTHUHAI.", WeeklyNote.check(ok, evidence))
        assertNull(WeeklyNote.check("This week's list has 26 songs. It opens with [[Mono]].", evidence))
        assertNull(WeeklyNote.check("This week's list has 25 songs. It opens with [[Radiohead]].", evidence))
        assertNull(WeeklyNote.check("One sentence only.", evidence))
        assertNull(WeeklyNote.check("One. Two. Three. Four.", evidence))
        assertNull(WeeklyNote.check("This week's list has 25 songs. Claude thinks you will like it.", evidence))
        assertNull(WeeklyNote.check("This week's list has 25 songs. I learned what you like.", evidence))
        assertNull(WeeklyNote.check("x".repeat(500), evidence))
        assertNotNull(WeeklyNote.check("  This week's   list has 25 songs.  It opens with [[Mono]].  ", evidence))
    }

    @Test
    fun `the same evidence has the same hash and a changed one does not`() {
        assertEquals(WeeklyNote.evidenceHash(evidence), WeeklyNote.evidenceHash(evidence))
        val other = WeeklyNote.Evidence(25, 17, evidence.artists, evidence.genre, evidence.island, 22)
        assertTrue(WeeklyNote.evidenceHash(evidence) != WeeklyNote.evidenceHash(other))
    }

    @Test
    fun `the words the templates write are words the app may say`() {
        val texts = ArrayList<String>()
        for (kind in 0..3) texts.add(SetNotes.text(SetNotes.Note(0, kind, 3, "Pop", "Mono", "Clairo")))
        texts.add(WeeklyNote.template(evidence))
        texts.add(WeeklyNote.template(WeeklyNote.Evidence(10, -1, listOf("A", "B", "C"), "", "", -1)))
        for (c in 0..11) texts.add(AssistantCards.chipLabel(AssistantRules.Chip(c, "yt:aaaaaaaaaaa", 3), "Mono", "Pop"))
        texts.add(AssistantCards.lessLabel("Trap"))
        for (t in texts) {
            val folded = BrainText.fold(t)
            for (w in WeeklyNote.FORBIDDEN) assertFalse(folded.contains(w), "\"$t\" says \"$w\"")
        }
    }

    // ── The batch checks ─────────────────────────────────────────────────────

    @Test
    fun `an island name must be about the island's own genres`() {
        val ins = listOf(AssistBatch.IslandIn(1, listOf("Bedroom pop", "Dream pop"), listOf("Clairo")))
        fun ok(name: String, line: String = "") = AssistBatch.validateIslandNames(ins, listOf(AssistBatch.IslandName(1, name, line))).size == 1
        assertTrue(ok("Dreamy bedroom pop"))
        assertFalse(ok("Sad boys"))
        assertFalse(ok("Clairo"))
        assertFalse(ok("Bedroom pop, loud"))
        assertFalse(ok("Claude's pop"))
        assertFalse(ok("Pop " + "x".repeat(30)))
        assertFalse(AssistBatch.validateIslandNames(ins, listOf(AssistBatch.IslandName(9, "Bedroom pop", ""))).isNotEmpty())
        // A bad description drops alone.
        val kept = AssistBatch.validateIslandNames(ins, listOf(AssistBatch.IslandName(1, "Bedroom pop nights", "Songs from 2024")))
        assertEquals("Bedroom pop nights", kept.single().name)
        assertEquals("", kept.single().line)
    }

    @Test
    fun `a tag the model gives must be on that track's own shortlist`() {
        val lists = listOf(intArrayOf(10, 11, 12), intArrayOf(20))
        val kept = AssistBatch.validateTags(
            lists,
            listOf(
                AssistBatch.TagOut(0, 10, 0.9), AssistBatch.TagOut(0, 20, 0.99), AssistBatch.TagOut(1, 20, 0.4), AssistBatch.TagOut(1, 20, 0.6),
                AssistBatch.TagOut(0, 11, 0.8), AssistBatch.TagOut(0, 12, 0.7), AssistBatch.TagOut(0, 10, 1.0), AssistBatch.TagOut(5, 10, 1.0),
            ),
        )
        assertEquals(listOf(0 to 10, 0 to 11, 0 to 12, 1 to 20), kept.map { it.index to it.atlasId })
        assertEquals(0.9, kept[0].confidence, 0.0)
    }

    @Test
    fun `the cache key changes with the model, the prompt version and the input`() {
        val a = AssistBatch.cacheKey("m1", "islands", "x")
        assertTrue(a != AssistBatch.cacheKey("m2", "islands", "x"))
        assertTrue(a != AssistBatch.cacheKey("m1", "tags", "x"))
        assertTrue(a != AssistBatch.cacheKey("m1", "islands", "y"))
        assertEquals(a, AssistBatch.cacheKey("m1", "islands", "x"))
    }

    @Test
    fun `suggested artists are checked before they are looked up`() {
        val kept = AssistBatch.validateArtistNames(listOf("Clairo", " New Artist ", "new artist", "2024", "A", "Mono"), setOf("clairo"))
        assertEquals(listOf("New Artist", "Mono"), kept)
        assertEquals(AssistBatch.ASK_PER_ISLAND, AssistBatch.validateArtistNames(List(50) { "Artist $it" }, emptySet()).size)
    }
}
