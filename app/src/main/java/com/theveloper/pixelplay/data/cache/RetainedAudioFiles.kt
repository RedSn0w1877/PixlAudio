package com.theveloper.pixelplay.data.cache

import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Publish complete audio without destroying an existing good copy if a render/copy is interrupted. */
internal object RetainedAudioFiles {
    fun copyAtomically(source: File, destination: File): File {
        require(source.isFile && source.length() > 0L) { "Audio file is empty or missing" }
        if (source.canonicalFile == destination.canonicalFile) return destination
        destination.parentFile?.mkdirs()
        val pending = File.createTempFile("retained_", ".part", destination.parentFile)
        try {
            source.inputStream().use { input ->
                pending.outputStream().use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            check(pending.length() == source.length()) { "Incomplete audio copy" }
            publish(pending, destination)
            return destination
        } finally {
            pending.delete()
        }
    }

    fun publish(pending: File, destination: File) {
        if (!pending.isFile || pending.length() == 0L) throw IOException("Audio file is empty")
        try {
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(pending.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
