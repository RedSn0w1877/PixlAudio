package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The generated tables must say exactly what the reviewed .tsv files say (10.3 rule 15).
 * After editing a .tsv, run `python tools/brain/gen_tables.py kotlin`.
 */
class TablesInSyncTest {

    private fun rows(name: String): List<Pair<String, String>> {
        val file = File("tables/$name")
        assertTrue(file.isFile, "missing ${file.absolutePath}")
        return file.readLines()
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val tab = line.indexOf('\t')
                if (tab < 0) line to "" else line.substring(0, tab) to line.substring(tab + 1)
            }
    }

    @Test
    fun `FoldTable matches fold-v1 tsv`() {
        val expected = rows("fold-v1.tsv")
            .map { (key, value) ->
                key.toInt(16) to value.split(' ').filter { it.isNotEmpty() }
                    .joinToString("") { String(Character.toChars(it.toInt(16))) }
            }
            .sortedWith { a, b -> a.first.compareTo(b.first) }
        assertEquals(expected.size, FoldTable.ROWS)
        assertEquals(expected.size, FoldTable.KEYS.length)
        val values = FoldTable.VALUES.split('\u0001')
        assertEquals(expected.size, values.size)
        for (i in expected.indices) {
            assertEquals(expected[i].first, FoldTable.KEYS[i].code, "key of row $i")
            assertEquals(expected[i].second, values[i], "value of U+%04X".format(expected[i].first))
        }
    }

    @Test
    fun `every fold row is reachable through fold`() {
        val values = FoldTable.VALUES.split('\u0001')
        for (i in 0 until FoldTable.ROWS) {
            val key = FoldTable.KEYS[i].toString()
            val expected = values[i].trim()
            assertEquals(expected, BrainText.fold(key), "fold of U+%04X".format(FoldTable.KEYS[i].code))
        }
    }

    @Test
    fun `TitleRules matches title-rules-v1 tsv`() {
        val flagNames = listOf(
            "live", "remix", "acoustic", "instrumental", "remaster", "cover", "karaoke", "sped-up", "slowed", "re-recorded"
        )
        val rules = rows("title-rules-v1.tsv")
        val flags = rules.filter { it.first in flagNames }
        assertEquals(flags.map { it.second }, TitleRules.FLAG_TOKENS.toList())
        assertEquals(flags.map { flagNames.indexOf(it.first) }, TitleRules.FLAG_BITS.toList())
        assertEquals(rules.filter { it.first == "neutral-prefix" }.map { it.second }, TitleRules.NEUTRAL_PREFIX.toList())
        assertEquals(rules.filter { it.first == "neutral-equals" }.map { it.second }, TitleRules.NEUTRAL_EQUALS.toList())
        assertEquals(rules.size, flags.size + TitleRules.NEUTRAL_PREFIX.size + TitleRules.NEUTRAL_EQUALS.size)
        // Rule sequences must already be folded, or they could never match a folded segment.
        for ((_, tokens) in rules) assertEquals(tokens, BrainText.fold(tokens))
    }
}
