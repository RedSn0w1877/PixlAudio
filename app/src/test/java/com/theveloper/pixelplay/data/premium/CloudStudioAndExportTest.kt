package com.theveloper.pixelplay.data.premium

import java.io.File
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloudStudioAndExportTest {
    @Test
    fun `cloud config fails closed without consent or tls`() {
        assertTrue(CloudStudioConfig("https://example.com").validationError()!!.contains("opt in"))
        assertTrue(CloudStudioConfig("http://example.com", consentToUpload = true).validationError()!!.contains("HTTPS"))
        assertFalse(CloudStudioConfig("https://example.com", consentToUpload = true).validationError() != null)
    }

    @Test
    fun `advanced export contains stems and metadata`() {
        val directory = createTempDirectory(prefix = "pixelplayer-export-").toFile()
        try {
            val instrumental = File(directory, "instrumental.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val vocals = File(directory, "vocals.wav").apply { writeBytes(byteArrayOf(4, 5)) }
            val destination = File(directory, "bundle.zip")
            val summary = AdvancedAudioExporter.export(
                AdvancedAudioExporter.Request(destination, "Song / Name", "Artist", instrumental, vocals)
            )
            assertTrue(destination.isFile)
            assertEquals(3, summary.includedFiles.size)
            ZipFile(destination).use { zip ->
                assertTrue(zip.entries().asSequence().any { it.name.endsWith("_instrumental.wav") })
                assertTrue(zip.entries().asSequence().any { it.name.endsWith("_vocals.wav") })
                assertTrue(zip.entries().asSequence().any { it.name.endsWith("_metadata.json") })
            }
        } finally {
            directory.deleteRecursively()
        }
    }
}
