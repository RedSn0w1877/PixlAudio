package com.theveloper.pixelplay.data.spotiflac

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * Pure JSON & manifest parsers for SpotiFLAC provider responses.
 *
 * Every accessor is defensive: a provider that changes its shape yields no candidates
 * (and the caller falls back to YouTube) instead of an exception in the stream proxy.
 */
object SpotiFLACParsing {

    /**
     * Checks whether the first 4 bytes of a stream match the `"fLaC"` magic header (`0x66 0x4C 0x61 0x43`).
     */
    fun isFlacMagicHeader(bytes: ByteArray): Boolean {
        return bytes.size >= 4 &&
            bytes[0] == 0x66.toByte() &&
            bytes[1] == 0x4C.toByte() &&
            bytes[2] == 0x61.toByte() &&
            bytes[3] == 0x43.toByte()
    }

    // -------------------------------------------------------------------------
    // 1. Monochrome Track Streamer (`tracks.monochrome.st`)
    // -------------------------------------------------------------------------

    /**
     * Parses `GET /search/tracks?q=...` from `tracks.monochrome.st` (the same rows come back for an
     * `isrc:<ISRC>` query):
     *
     * ```
     * {"tracks":[{"id":"155102871061270528","trackId":"155102871061270528","title":"One More Time",
     *   "artistNames":["Daft Punk"],"releaseId":"155102851176075264","playable":true,
     *   "duration":320357,"isrc":"GBDUW0000053"}],"releases":[],"artists":[],...}
     * ```
     *
     * `duration` is in milliseconds.
     */
    fun parseMonochromeSearchTracks(jsonText: String): List<SpotiFLACCandidate> {
        val root = parseObject(jsonText) ?: return emptyList()
        val tracks = root.arrayOrNull("tracks")
            ?: root.objectOrNull("data")?.arrayOrNull("tracks")
            ?: return emptyList()

        return tracks.mapNotNull { elem ->
            val item = elem.asObjectOrNull() ?: return@mapNotNull null
            val trackId = item.stringOrId("trackId")
                ?: item.stringOrId("id")
                ?: return@mapNotNull null
            val title = item.stringOrNull("title")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null

            SpotiFLACCandidate(
                provider = SpotiFLACProvider.MONOCHROME_TRACKS,
                trackId = trackId,
                releaseId = item.stringOrId("releaseId"),
                title = title,
                artist = monochromeArtists(item),
                album = item.stringOrNull("releaseTitle"),
                durationMs = millisFrom(item.longOrZero("duration")),
                isrc = SpotiFLACMatcher.normalizeIsrc(item.stringOrNull("isrc")),
                playable = item.booleanOrDefault("playable", true),
                quality = "LOSSLESS"
            )
        }
    }

    /**
     * Builds the direct byte-range stream URL for a Monochrome `trackId`.
     */
    fun monochromeTrackStreamUrl(baseUrl: String, trackId: String): String {
        val trimmedBase = baseUrl.trimEnd('/')
        val safeId = trackId.trim()
        if (trimmedBase.isEmpty() || safeId.isEmpty() || !safeId.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
            return ""
        }
        return "$trimmedBase/track/$safeId"
    }

    /** `artistNames` (strings), or `artists[].name`, joined the way Spotify rows join them. */
    private fun monochromeArtists(item: JsonObject): String {
        item.arrayOrNull("artistNames")
            ?.mapNotNull { it.stringOrNull()?.takeIf { name -> name.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.joinToString(", ") }
        item.arrayOrNull("artists")
            ?.mapNotNull { it.asObjectOrNull()?.stringOrNull("name")?.takeIf { name -> name.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it.joinToString(", ") }
        return item.stringOrNull("artistName") ?: item.stringOrNull("artist") ?: ""
    }

    // -------------------------------------------------------------------------
    // 2. Tidal HiFi / OpenAPI Instances
    // -------------------------------------------------------------------------

    /**
     * Parses `GET /search/?i=<ISRC>` or `GET /search/?s=<query>` from Tidal HiFi API instances.
     */
    fun parseHiFiSearchResponse(jsonText: String): List<SpotiFLACCandidate> {
        val rootElement = parseElement(jsonText) ?: return emptyList()

        val itemsArray = when {
            rootElement.isJsonArray -> rootElement.asJsonArray
            rootElement.isJsonObject -> {
                val obj = rootElement.asJsonObject
                obj.objectOrNull("data")?.arrayOrNull("items")
                    ?: obj.objectOrNull("tracks")?.arrayOrNull("items")
                    ?: obj.arrayOrNull("items")
                    ?: obj.arrayOrNull("data")
            }
            else -> null
        } ?: return emptyList()

        return itemsArray.mapNotNull { elem ->
            val rawItem = elem.asObjectOrNull() ?: return@mapNotNull null
            val item = rawItem.objectOrNull("item") ?: rawItem
            val trackId = item.stringOrId("id") ?: return@mapNotNull null
            val title = item.stringOrNull("title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val artist = item.objectOrNull("artist")?.stringOrNull("name")
                ?: item.arrayOrNull("artists")
                    ?.firstOrNull()?.asObjectOrNull()?.stringOrNull("name")
                ?: item.stringOrNull("artist")
                ?: ""

            val album = item.objectOrNull("album")?.stringOrNull("title")
                ?: item.stringOrNull("album")

            val isrc = SpotiFLACMatcher.normalizeIsrc(item.stringOrNull("isrc"))
            val playable = item.booleanOrDefault("allowStreaming", true) &&
                item.booleanOrDefault("streamReady", true)
            val quality = item.stringOrNull("audioQuality") ?: "LOSSLESS"

            SpotiFLACCandidate(
                provider = SpotiFLACProvider.TIDAL_HIFI,
                trackId = trackId,
                releaseId = item.objectOrNull("album")?.stringOrId("id"),
                title = title,
                artist = artist,
                album = album,
                durationMs = millisFrom(item.longOrZero("duration")),
                isrc = isrc,
                playable = playable,
                quality = quality
            )
        }
    }

    /**
     * Parses `GET /track/?id=<id>&quality=LOSSLESS` from Tidal HiFi API instances.
     * Supports both `OriginalTrackUrl` and base64-encoded `application/vnd.tidal.bts` `manifest` payloads.
     */
    fun parseHiFiStreamResponse(
        jsonText: String,
        fallbackTrackId: String,
        isrc: String? = null
    ): SpotiFLACResolvedStream? {
        val rootElement = parseElement(jsonText) ?: return null

        val objects: List<JsonObject> = when {
            rootElement.isJsonArray -> rootElement.asJsonArray.mapNotNull { it.asObjectOrNull() }
            rootElement.isJsonObject -> {
                val obj = rootElement.asJsonObject
                val dataElem = obj.get("data")
                when {
                    dataElem != null && dataElem.isJsonObject -> listOf(dataElem.asJsonObject, obj)
                    dataElem != null && dataElem.isJsonArray -> dataElem.asJsonArray.mapNotNull { it.asObjectOrNull() } + obj
                    else -> listOf(obj)
                }
            }
            else -> emptyList()
        }

        var directUrl: String? = null
        var manifestMimeType = "audio/flac"
        var trackId = fallbackTrackId
        var quality = "LOSSLESS"
        var bitDepth: Int? = 16
        var sampleRateHz: Int? = 44_100
        var replayGain: SpotiFLACReplayGain? = null

        for (obj in objects) {
            obj.stringOrId("trackId")?.let { trackId = it }
            obj.stringOrNull("audioQuality")?.let { quality = it }
            obj.intOrNull("bitDepth")?.let { bitDepth = it }
            obj.intOrNull("sampleRate")?.let { sampleRateHz = it }

            val rg = obj.doubleOrNull("trackReplayGain")
            val peak = obj.doubleOrNull("trackPeakAmplitude") ?: 1.0
            if (rg != null) {
                replayGain = SpotiFLACReplayGain(trackGainDb = rg, trackPeak = peak)
            }

            // Reject 30-second preview responses.
            val presentation = obj.stringOrNull("assetPresentation")?.uppercase()
            if (presentation == "PREVIEW") return null

            val orig = obj.stringOrNull("OriginalTrackUrl")?.takeIf { it.isNotEmpty() }
            if (orig != null) {
                directUrl = orig
            }

            if (directUrl == null) {
                val manifestB64 = obj.stringOrNull("manifest")?.takeIf { it.isNotEmpty() }
                if (manifestB64 != null) {
                    val manifestMime = obj.stringOrNull("manifestMimeType") ?: ""
                    // Skip MPD XML DASH manifests when we require direct single-file FLAC URLs.
                    if (!manifestMime.contains("dash+xml")) {
                        val decodedBytes = runCatching { Base64.getDecoder().decode(manifestB64) }.getOrNull()
                        if (decodedBytes != null) {
                            val manifestObj = parseObject(String(decodedBytes, Charsets.UTF_8))
                            if (manifestObj != null) {
                                manifestObj.stringOrNull("mimeType")?.takeIf { it.isNotEmpty() }?.let {
                                    manifestMimeType = it
                                }
                                val firstUrl = manifestObj.arrayOrNull("urls")
                                    ?.firstOrNull()
                                    ?.stringOrNull()
                                    ?.takeIf { it.isNotEmpty() }
                                if (firstUrl != null) {
                                    directUrl = firstUrl
                                }
                            }
                        }
                    }
                }
            }
        }

        val resolvedUrl = directUrl?.takeIf { it.isNotEmpty() } ?: return null
        return SpotiFLACResolvedStream(
            url = resolvedUrl,
            mimeType = manifestMimeType,
            provider = SpotiFLACProvider.TIDAL_HIFI,
            providerTrackId = trackId,
            isrc = SpotiFLACMatcher.normalizeIsrc(isrc),
            quality = quality,
            bitDepth = bitDepth,
            sampleRateHz = sampleRateHz,
            replayGain = replayGain
        )
    }

    // -------------------------------------------------------------------------
    // 3. Deezer Public API (`api.deezer.com`) — metadata enrichment by ISRC
    // -------------------------------------------------------------------------

    /**
     * Parses `GET /2.0/track/isrc:<ISRC>` or `GET /2.0/track/<id>` from `api.deezer.com`
     * (`duration` is in seconds there).
     */
    fun parseDeezerTrack(jsonText: String): SpotiFLACCandidate? {
        val obj = parseObject(jsonText) ?: return null
        if (obj.has("error")) return null

        val trackId = obj.stringOrId("id") ?: return null
        val title = obj.stringOrNull("title")?.takeIf { it.isNotEmpty() } ?: return null
        val artist = obj.objectOrNull("artist")?.stringOrNull("name") ?: ""
        val album = obj.objectOrNull("album")?.stringOrNull("title")
        val durationSec = obj.longOrZero("duration")
        val isrc = SpotiFLACMatcher.normalizeIsrc(obj.stringOrNull("isrc"))
        val readable = obj.booleanOrDefault("readable", true)

        return SpotiFLACCandidate(
            provider = SpotiFLACProvider.DEEZER,
            trackId = trackId,
            releaseId = obj.objectOrNull("album")?.stringOrId("id"),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationSec * 1_000L,
            isrc = isrc,
            playable = readable,
            quality = "LOSSLESS"
        )
    }

    /** Provider durations: milliseconds, except values that can only be seconds (up to an hour). */
    private fun millisFrom(raw: Long): Long = if (raw in 1..3_600) raw * 1_000L else raw.coerceAtLeast(0L)

    private fun parseElement(jsonText: String): JsonElement? =
        try {
            JsonParser.parseString(jsonText)
        } catch (e: RuntimeException) {
            null
        }

    private fun parseObject(jsonText: String): JsonObject? = parseElement(jsonText).asObjectOrNull()

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        if (this != null && this.isJsonObject) this.asJsonObject else null

    private fun JsonElement?.stringOrNull(): String? =
        if (this != null && this.isJsonPrimitive) runCatching { this.asString }.getOrNull() else null

    private fun JsonObject.objectOrNull(key: String): JsonObject? = get(key).asObjectOrNull()

    private fun JsonObject.arrayOrNull(key: String): JsonArray? {
        val elem = get(key) ?: return null
        return if (elem.isJsonArray) elem.asJsonArray else null
    }

    private fun JsonObject.stringOrNull(key: String): String? = get(key).stringOrNull()

    private fun JsonObject.stringOrId(key: String): String? {
        val elem = get(key) ?: return null
        if (!elem.isJsonPrimitive) return null
        val prim = elem.asJsonPrimitive
        return when {
            prim.isString -> prim.asString.takeIf { it.isNotEmpty() }
            prim.isNumber -> runCatching { prim.asBigInteger.toString() }.getOrNull()
            else -> null
        }
    }

    private fun JsonObject.longOrZero(key: String): Long {
        val elem = get(key) ?: return 0L
        if (!elem.isJsonPrimitive) return 0L
        return runCatching { elem.asDouble.toLong() }.getOrDefault(0L)
    }

    private fun JsonObject.intOrNull(key: String): Int? {
        val elem = get(key) ?: return null
        if (!elem.isJsonPrimitive) return null
        return runCatching { elem.asInt }.getOrNull()
    }

    private fun JsonObject.doubleOrNull(key: String): Double? {
        val elem = get(key) ?: return null
        if (!elem.isJsonPrimitive) return null
        return runCatching { elem.asDouble }.getOrNull()
    }

    private fun JsonObject.booleanOrDefault(key: String, default: Boolean): Boolean {
        val elem = get(key) ?: return default
        if (!elem.isJsonPrimitive || !elem.asJsonPrimitive.isBoolean) return default
        return elem.asBoolean
    }
}
