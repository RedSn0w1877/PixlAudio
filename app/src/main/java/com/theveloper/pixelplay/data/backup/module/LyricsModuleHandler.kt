package com.theveloper.pixelplay.data.backup.module

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.theveloper.pixelplay.data.backup.model.BackupSection
import com.theveloper.pixelplay.data.database.LyricsDao
import com.theveloper.pixelplay.data.database.LyricsEntity
import com.theveloper.pixelplay.di.BackupGson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics backup: every Room row, plus the `filesDir/lyrics/{id}.json` store of songs whose ids
 * aren't numeric (streaming songs have no Room row, so a lyrics sync the user made for one
 * lives only there). Those files travel as extra array elements `{"jsonFile": name, "json": …}`,
 * which keeps the module a plain JSON array for the schema validator.
 */
@Singleton
class LyricsModuleHandler @Inject constructor(
    private val lyricsDao: LyricsDao,
    @BackupGson private val gson: Gson,
    @ApplicationContext private val context: Context,
) : BackupModuleHandler {

    override val section = BackupSection.LYRICS

    private val lyricsDir: File get() = File(context.filesDir, "lyrics")

    override suspend fun export(): String = withContext(Dispatchers.IO) {
        val array = gson.toJsonTree(lyricsDao.getAll()).asJsonArray
        for (file in nonNumericJsonFiles()) {
            val content = try {
                file.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                Timber.w(e, "Skipping unreadable lyrics file %s in backup", file.name)
                continue
            }
            array.add(JsonObject().apply {
                addProperty(KEY_FILE, file.name)
                addProperty(KEY_JSON, content)
            })
        }
        gson.toJson(array)
    }

    override suspend fun countEntries(): Int = withContext(Dispatchers.IO) {
        lyricsDao.getAll().size + nonNumericJsonFiles().size
    }

    override suspend fun snapshot(): String = export()

    override suspend fun restore(payload: String) = withContext(Dispatchers.IO) {
        val array: JsonArray = JsonParser.parseString(payload).asJsonArray
        val rows = ArrayList<LyricsEntity>(array.size())
        val files = ArrayList<Pair<String, String>>()
        for (element in array) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            val fileName = obj.get(KEY_FILE)?.takeIf { it.isJsonPrimitive }?.asString
            if (fileName != null) {
                val json = obj.get(KEY_JSON)?.takeIf { it.isJsonPrimitive }?.asString ?: continue
                if (isSafeNonNumericName(fileName) && json.length <= MAX_FILE_CHARS) files += fileName to json
                continue
            }
            gson.fromJson(obj, LyricsEntity::class.java)?.let(rows::add)
        }
        lyricsDao.replaceAll(rows)
        if (files.isNotEmpty()) {
            lyricsDir.mkdirs()
            for ((name, json) in files) writeAtomically(File(lyricsDir, name), json)
        }
    }

    override suspend fun rollback(snapshot: String) = restore(snapshot)

    private fun nonNumericJsonFiles(): List<File> =
        lyricsDir.listFiles()?.filter { it.isFile && isSafeNonNumericName(it.name) }.orEmpty()

    private fun isSafeNonNumericName(name: String): Boolean {
        if (!name.endsWith(".json") || name.length > 255) return false
        if (name.contains('/') || name.contains('\\') || name.startsWith(".")) return false
        val id = name.removeSuffix(".json")
        return id.isNotEmpty() && id.toLongOrNull() == null
    }

    private fun writeAtomically(target: File, content: String) {
        val temporary = File.createTempFile("lyrics-restore-", ".tmp", target.parentFile)
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(content.toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) Timber.w("Could not restore lyrics file %s", target.name)
            }
        } catch (e: Exception) {
            Timber.w(e, "Could not restore lyrics file %s", target.name)
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val KEY_FILE = "jsonFile"
        const val KEY_JSON = "json"
        const val MAX_FILE_CHARS = 4 * 1024 * 1024
    }
}
