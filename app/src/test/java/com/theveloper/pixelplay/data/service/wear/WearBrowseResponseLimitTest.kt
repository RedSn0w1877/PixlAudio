package com.theveloper.pixelplay.data.service.wear

import com.theveloper.pixelplay.shared.WearBrowseResponse
import com.theveloper.pixelplay.shared.WearLibraryItem
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WearBrowseResponseLimitTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun item(index: Int, titleLength: Int) = WearLibraryItem(
        id = "id$index",
        title = "T$index-" + "x".repeat(titleLength),
        subtitle = "artist $index",
        type = WearLibraryItem.TYPE_SONG,
    )

    @Test
    fun `a reply that fits is sent unchanged`() {
        val response = WearBrowseResponse("r1", items = (0 until 20).map { item(it, 30) })

        val bytes = encodeBrowseResponseWithinLimit(response, json)

        assertEquals(response, json.decodeFromString<WearBrowseResponse>(bytes.decodeToString()))
    }

    @Test
    fun `an oversized reply is cut to the leading items that fit`() {
        val response = WearBrowseResponse("r2", items = (0 until 500).map { item(it, 220) })
        val full = json.encodeToString(WearBrowseResponse.serializer(), response)
        assertTrue(full.toByteArray(Charsets.UTF_8).size > WEAR_MESSAGE_SAFE_BYTES)

        val bytes = encodeBrowseResponseWithinLimit(response, json)

        assertTrue(bytes.size <= WEAR_MESSAGE_SAFE_BYTES)
        val decoded = json.decodeFromString<WearBrowseResponse>(bytes.decodeToString())
        assertEquals("r2", decoded.requestId)
        assertTrue(decoded.items.isNotEmpty())
        assertEquals(response.items.take(decoded.items.size), decoded.items)
    }

    @Test
    fun `an error reply with no items is never trimmed`() {
        val response = WearBrowseResponse("r3", error = "boom")

        val bytes = encodeBrowseResponseWithinLimit(response, json, maxBytes = 10)

        assertEquals(response, json.decodeFromString<WearBrowseResponse>(bytes.decodeToString()))
    }
}
