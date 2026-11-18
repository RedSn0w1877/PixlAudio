package com.theveloper.pixelplay.data.brain.store

import com.theveloper.pixelplay.data.youtube.RadioChip
import com.theveloper.pixelplay.data.youtube.RadioRow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One stored radio row: the parsed row and the recording id it resolved to when it was stored. */
@Serializable
data class StoredRadioRow(
    val row: RadioRow,
    val rid: Long,
)

/**
 * `radio_list.rowsBlob` / `chipsBlob`: gzip over compact JSON of the PARSED rows. About 3 KB for a
 * 50-row radio, against more than 500 KB for the response it came from.
 */
object RadioRowCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val rowsSerializer = ListSerializer(StoredRadioRow.serializer())
    private val chipsSerializer = ListSerializer(RadioChip.serializer())

    fun encodeRows(rows: List<StoredRadioRow>): ByteArray = gzip(json.encodeToString(rowsSerializer, rows))

    fun decodeRows(blob: ByteArray): List<StoredRadioRow> =
        if (blob.isEmpty()) emptyList() else runCatching { json.decodeFromString(rowsSerializer, gunzip(blob)) }
            .getOrDefault(emptyList())

    fun encodeChips(chips: List<RadioChip>): ByteArray = gzip(json.encodeToString(chipsSerializer, chips))

    fun decodeChips(blob: ByteArray): List<RadioChip> =
        if (blob.isEmpty()) emptyList() else runCatching { json.decodeFromString(chipsSerializer, gunzip(blob)) }
            .getOrDefault(emptyList())

    private fun gzip(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length / 3 + 32)
        GZIPOutputStream(out).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return out.toByteArray()
    }

    private fun gunzip(blob: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(blob)).use { it.readBytes().toString(Charsets.UTF_8) }
}
