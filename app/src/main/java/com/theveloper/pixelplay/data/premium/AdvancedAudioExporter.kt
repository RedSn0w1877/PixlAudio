package com.theveloper.pixelplay.data.premium

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Creates a portable stem bundle for the Plus Advanced Audio Exports feature.
 * Callers are responsible for checking the user's entitlement before invoking this utility.
 */
object AdvancedAudioExporter {
    data class Request(
        val destinationZip: File,
        val title: String,
        val artist: String,
        val instrumental: File,
        val vocals: File? = null
    )

    data class Summary(val destination: File, val includedFiles: List<String>)

    fun export(request: Request): Summary {
        require(request.instrumental.isFile && request.instrumental.length() > 0) {
            "An instrumental stem is required for export."
        }
        require(request.vocals == null || (request.vocals.isFile && request.vocals.length() > 0)) {
            "The vocals stem is unavailable."
        }
        request.destinationZip.parentFile?.mkdirs()
        val temporary = File(request.destinationZip.parentFile, ".${request.destinationZip.name}.part")
        val included = mutableListOf<String>()
        try {
            ZipOutputStream(FileOutputStream(temporary)).use { zip ->
                addFile(zip, request.instrumental, "${safeName(request.title)}_instrumental.wav")
                included += "${safeName(request.title)}_instrumental.wav"
                request.vocals?.let {
                    addFile(zip, it, "${safeName(request.title)}_vocals.wav")
                    included += "${safeName(request.title)}_vocals.wav"
                }
                val metadataName = "${safeName(request.title)}_metadata.json"
                val metadata = """{"title":"${jsonEscape(request.title)}","artist":"${jsonEscape(request.artist)}","format":"wav","files":${included.joinToString(prefix = "[\"", separator = "\",\"", postfix = "\"]") { jsonEscape(it) }}}"""
                zip.putNextEntry(ZipEntry(metadataName))
                zip.write(metadata.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
                included += metadataName
            }
            if (request.destinationZip.exists() && !request.destinationZip.delete()) {
                error("Could not replace the existing export.")
            }
            check(temporary.renameTo(request.destinationZip)) { "Could not finalize the export." }
            return Summary(request.destinationZip, included.toList())
        } finally {
            temporary.delete()
        }
    }

    private fun addFile(zip: ZipOutputStream, source: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        source.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    private fun safeName(value: String): String = value.trim().replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "PixelPlayer" }.take(80)

    private fun jsonEscape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
