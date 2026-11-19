package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.store.MusicBrainMigrations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The version 1 to 2 migration of `brain.db`, held against the schema files Room exported
 * (`app/schemas/.../MusicBrainDatabase/1.json` and `2.json`). Room checks a migrated database against
 * its schema when it opens, so a statement that differs from what Room itself wrote would fail on the
 * first query of a phone that has real data. This runs the same comparison without a device. The
 * device-side test that opens a version 1 file and migrates it is `MusicBrainMigrationInstrumentedTest`.
 */
class MusicBrainMigrationsTest {

    private fun schema(version: Int): JsonObject {
        val relative = "schemas/com.theveloper.pixelplay.data.brain.store.MusicBrainDatabase/$version.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject
    }

    private fun entities(version: Int): List<JsonObject> =
        schema(version)["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }

    private fun tableName(e: JsonObject) = e["tableName"]!!.jsonPrimitive.content

    private fun createSql(e: JsonObject) = e["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", tableName(e))

    private fun indexSql(e: JsonObject, table: String): List<String> =
        (e["indices"] as? JsonArray).orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table) }

    @Test
    fun `the schema files are versions 1 and 2`() {
        assertEquals(1, schema(1)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        assertEquals(2, schema(2)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun `version 2 adds exactly graph_edge, served_list and feedback and changes nothing that was there`() {
        val v1 = entities(1).associateBy(::tableName)
        val v2 = entities(2).associateBy(::tableName)
        assertEquals(setOf("graph_edge", "served_list", "feedback"), v2.keys - v1.keys)
        assertTrue((v1.keys - v2.keys).isEmpty(), "no table is removed")
        for ((name, old) in v1) {
            assertEquals(createSql(old), createSql(v2[name]!!), "table $name is untouched")
            assertEquals(indexSql(old, name), indexSql(v2[name]!!, name), "indices of $name are untouched")
        }
    }

    @Test
    fun `the migration statements are exactly what Room wrote for the new tables`() {
        val added = entities(2).filter { tableName(it) in setOf("graph_edge", "served_list", "feedback") }
            .sortedBy { MusicBrainMigrations.V1_TO_V2.indexOf(createSql(it)) }
        val expected = ArrayList<String>()
        for (e in added) {
            expected.add(createSql(e))
            expected.addAll(indexSql(e, tableName(e)))
        }
        assertEquals(expected, MusicBrainMigrations.V1_TO_V2)
    }

    @Test
    fun `there is one migration for every version step and no destructive path`() {
        assertEquals(5, MusicBrainMigrations.ALL.size)
        for ((i, m) in MusicBrainMigrations.ALL.withIndex()) {
            assertEquals(i + 1, m.startVersion)
            assertEquals(i + 2, m.endVersion)
        }
    }

    @Test
    fun `version 3 adds exactly spotify_fact and changes nothing that was there`() {
        assertEquals(3, schema(3)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        val v2 = entities(2).associateBy(::tableName)
        val v3 = entities(3).associateBy(::tableName)
        assertEquals(setOf("spotify_fact"), v3.keys - v2.keys)
        assertTrue((v2.keys - v3.keys).isEmpty(), "no table is removed")
        for ((name, old) in v2) {
            assertEquals(createSql(old), createSql(v3[name]!!), "table $name is untouched")
            assertEquals(indexSql(old, name), indexSql(v3[name]!!, name), "indices of $name are untouched")
        }
    }

    @Test
    fun `the version 3 statements are exactly what Room wrote for spotify_fact`() {
        val added = entities(3).single { tableName(it) == "spotify_fact" }
        val expected = listOf(createSql(added)) + indexSql(added, "spotify_fact")
        assertEquals(expected, MusicBrainMigrations.V2_TO_V3)
    }

    private val v4Tables = setOf("track_dna", "artist_dna", "source_miss")

    @Test
    fun `version 4 adds exactly the DNA tables and changes nothing that was there`() {
        assertEquals(4, schema(4)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        val v3 = entities(3).associateBy(::tableName)
        val v4 = entities(4).associateBy(::tableName)
        assertEquals(v4Tables, v4.keys - v3.keys)
        assertTrue((v3.keys - v4.keys).isEmpty(), "no table is removed")
        for ((name, old) in v3) {
            assertEquals(createSql(old), createSql(v4[name]!!), "table $name is untouched")
            assertEquals(indexSql(old, name), indexSql(v4[name]!!, name), "indices of $name are untouched")
        }
    }

    @Test
    fun `the version 4 statements are exactly what Room wrote`() {
        val added = entities(4).filter { tableName(it) in v4Tables }
            .sortedBy { MusicBrainMigrations.V3_TO_V4.indexOf(createSql(it)) }
        val expected = ArrayList<String>()
        for (e in added) {
            expected.add(createSql(e))
            expected.addAll(indexSql(e, tableName(e)))
        }
        assertEquals(expected, MusicBrainMigrations.V3_TO_V4)
    }

    private val v5Tables = setOf("node_embedding", "island", "island_member")

    @Test
    fun `version 5 adds exactly the embedding and island tables and changes nothing that was there`() {
        assertEquals(5, schema(5)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        val v4 = entities(4).associateBy(::tableName)
        val v5 = entities(5).associateBy(::tableName)
        assertEquals(v5Tables, v5.keys - v4.keys)
        assertTrue((v4.keys - v5.keys).isEmpty(), "no table is removed")
        for ((name, old) in v4) {
            assertEquals(createSql(old), createSql(v5[name]!!), "table $name is untouched")
            assertEquals(indexSql(old, name), indexSql(v5[name]!!, name), "indices of $name are untouched")
        }
    }

    @Test
    fun `the version 5 statements are exactly what Room wrote`() {
        val added = entities(5).filter { tableName(it) in v5Tables }
            .sortedBy { MusicBrainMigrations.V4_TO_V5.indexOf(createSql(it)) }
        val expected = ArrayList<String>()
        for (e in added) {
            expected.add(createSql(e))
            expected.addAll(indexSql(e, tableName(e)))
        }
        assertEquals(expected, MusicBrainMigrations.V4_TO_V5)
    }

    private val v6Tables = setOf("impression")

    @Test
    fun `version 6 adds exactly the impression table and changes nothing that was there`() {
        assertEquals(6, schema(6)["database"]!!.jsonObject["version"]!!.jsonPrimitive.int)
        val v5 = entities(5).associateBy(::tableName)
        val v6 = entities(6).associateBy(::tableName)
        assertEquals(v6Tables, v6.keys - v5.keys)
        assertTrue((v5.keys - v6.keys).isEmpty(), "no table is removed")
        for ((name, old) in v5) {
            assertEquals(createSql(old), createSql(v6[name]!!), "table $name is untouched")
            assertEquals(indexSql(old, name), indexSql(v6[name]!!, name), "indices of $name are untouched")
        }
    }

    @Test
    fun `the version 6 statements are exactly what Room wrote`() {
        val added = entities(6).filter { tableName(it) in v6Tables }
            .sortedBy { MusicBrainMigrations.V5_TO_V6.indexOf(createSql(it)) }
        val expected = ArrayList<String>()
        for (e in added) {
            expected.add(createSql(e))
            expected.addAll(indexSql(e, tableName(e)))
        }
        assertEquals(expected, MusicBrainMigrations.V5_TO_V6)
    }

    @Test
    fun `the history tables are the same in every version`() {
        for (table in listOf("play_event", "track_alias", "track_merge", "artist_alias", "feedback")) {
            val sql = entities( 6).single { tableName(it) == table }.let(::createSql)
            val first = entities(if (table == "feedback") 2 else 1).single { tableName(it) == table }.let(::createSql)
            assertEquals(first, sql, table)
        }
    }
}
