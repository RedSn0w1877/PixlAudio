package com.theveloper.pixelplay.data.cloudstudio

import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * The Cloud Studio job list as one JSON file, `noBackupFilesDir/cloud_studio/jobs.json` (design §7.1; iOS keeps the
 * same list in `Application Support/CloudStudio/jobs.json`). Not a Room table: the main database stays untouched, so
 * there is no schema bump or migration, and Android's auto-backup never carries the list (or the uploads beside it)
 * to another device.
 *
 * Writes go to a temporary file that replaces the old one in one rename, so a process killed mid-write leaves the
 * previous list. A file that can't be read is kept aside as `jobs.json.unreadable` (never overwritten silently) and
 * the queue starts empty.
 */
class CloudJobStore(private val directory: File) : CloudJobPersistence {
    private val file: File get() = File(directory, FILE_NAME)
    private val serializer = ListSerializer(CloudJobRecord.serializer())

    override suspend fun load(): List<CloudJobRecord> = withContext(Dispatchers.IO) {
        val current = file
        if (!current.isFile) return@withContext emptyList()
        try {
            CloudJson.decodeFromString(serializer, current.readText())
        } catch (error: Exception) {
            current.renameTo(File(directory, "$FILE_NAME.unreadable"))
            emptyList()
        }
    }

    override suspend fun save(jobs: List<CloudJobRecord>): Unit = withContext(Dispatchers.IO) {
        directory.mkdirs()
        val temporary = File(directory, "$FILE_NAME.tmp")
        FileOutputStream(temporary).use { output ->
            output.write(CloudJson.encodeToString(serializer, jobs).toByteArray())
            output.fd.sync()
        }
        if (!temporary.renameTo(file)) {
            // Some file systems refuse to rename over an existing file.
            file.delete()
            check(temporary.renameTo(file)) { "Couldn't save the cloud queue" }
        }
    }

    companion object {
        const val FILE_NAME = "jobs.json"
    }
}
