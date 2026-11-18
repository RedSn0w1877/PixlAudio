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
        assertEquals(1, MusicBrainMigrations.ALL.size)
        assertEquals(1, MusicBrainMigrations.ALL[0].startVersion)
        assertEquals(2, MusicBrainMigrations.ALL[0].endVersion)
    }
}
