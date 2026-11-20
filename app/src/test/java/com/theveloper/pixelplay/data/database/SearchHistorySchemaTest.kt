package com.theveloper.pixelplay.data.database

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 5-to-6 migration of the main database, held against the schema files Room exported
 * (`app/schemas/.../PixelPlayDatabase/5.json` and `6.json`), the same way `MusicBrainMigrationsTest` does it for
 * `brain.db`: Room validates a migrated database against its schema on open, so a statement that differs from what
 * Room itself wrote would fail on the first query of a phone with real data. The device-side test that migrates
 * a real version 5 file is `SearchHistoryMigrationTest`.
 */
class SearchHistorySchemaTest {

    private fun entities(version: Int): List<JsonObject> {
        val relative = "schemas/com.theveloper.pixelplay.data.database.PixelPlayDatabase/$version.json"
        val file = listOf(File(relative), File("app/$relative")).first { it.isFile }
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject["entities"]!!.jsonArray.map { it.jsonObject }
    }

    private fun table(version: Int, name: String) = entities(version).first { it["tableName"]!!.jsonPrimitive.content == name }

    private fun fields(entity: JsonObject): Map<String, JsonObject> =
        entity["fields"]!!.jsonArray.map { it.jsonObject }.associateBy { it["columnName"]!!.jsonPrimitive.content }

    @Test
    fun `version 6 adds exactly the new search history columns and the key index`() {
        val v5 = fields(table(5, "search_history"))
        val v6 = fields(table(6, "search_history"))
        assertEquals(setOf("key", "kind", "target_id", "target_title", "image_url", "count"), v6.keys - v5.keys)
        for ((name, old) in v5) assertEquals(old, v6[name], "column $name is untouched")
        val index = table(6, "search_history")["indices"]!!.jsonArray.map { it.jsonObject }.single()
        assertEquals(
            PixelPlayDatabase.SEARCH_HISTORY_KEY_INDEX,
            index["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", "search_history")
        )
        assertEquals(true, index["unique"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `the ALTER statements match the columns Room declares`() {
        val v6 = fields(table(6, "search_history"))
        val altered = PixelPlayDatabase.SEARCH_HISTORY_ADD_COLUMNS.map { sql ->
            val match = Regex("ALTER TABLE `search_history` ADD COLUMN `(\\w+)` (\\w+)( NOT NULL)?( DEFAULT (.+))?").matchEntire(sql)
            assertTrue(match != null, sql)
            match!!
        }
        assertEquals(v6.keys - setOf("id", "query", "timestamp"), altered.map { it.groupValues[1] }.toSet())
        for (m in altered) {
            val column = v6[m.groupValues[1]]!!
            assertEquals(column["affinity"]!!.jsonPrimitive.content, m.groupValues[2], m.value)
            assertEquals(column["notNull"]?.jsonPrimitive?.content?.toBoolean() ?: false, m.groupValues[3].isNotEmpty(), m.value)
            assertEquals(column["defaultValue"]?.jsonPrimitive?.content, m.groupValues[5].ifEmpty { null }, m.value)
        }
    }

    @Test
    fun `no other table changed between 5 and 6`() {
        val v5 = entities(5).associateBy { it["tableName"]!!.jsonPrimitive.content }
        val v6 = entities(6).associateBy { it["tableName"]!!.jsonPrimitive.content }
        assertEquals(v5.keys, v6.keys)
        for ((name, old) in v5) if (name != "search_history") assertEquals(old, v6[name], "table $name is untouched")
    }
}
