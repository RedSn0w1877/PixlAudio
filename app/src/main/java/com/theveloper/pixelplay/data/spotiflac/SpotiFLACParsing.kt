package com.theveloper.pixelplay.data.spotiflac

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.Base64

/**
 * Pure JSON & manifest parsers for SpotiFLAC provider responses.
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
     * Parses `GET /search/tracks?q=...` from `tracks.monochrome.st`.
     */
    fun parseMonochromeSearchTracks(jsonText: String): List<SpotiFLACCandidate> {
        val root = parseObject(jsonText) ?: return emptyList()
        val results = root.getAsJsonArray("results") ?: return emptyList()

        return results.mapNotNull { elem ->
            val item = elem.asObjectOrNull() ?: return@mapNotNull null
            val trackId = item.stringOrId("track_id")
                ?: item.stringOrId("id")
                ?: return@mapNotNull null
            val title = item.stringOrNull("title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artist = item.stringOrNull("artist_name")
                ?: item.stringOrNull("artist")
                ?: ""
            val releaseId = item.stringOrId("release_id")
            val album = item.stringOrNull("release_title") ?: item.stringOrNull("album")
            val durationMs = item.longOrZero("duration")
            val isrc = SpotiFLACMatcher.normalizeIsrc(item.stringOrNull("isrc"))

            SpotiFLACCandidate(
                provider = SpotiFLACProvider.MONOCHROME_TRACKS,
                trackId = trackId,
                releaseId = releaseId,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                isrc = isrc,
                playable = true,
                quality = "LOSSLESS"
            )
        }
    }

    /**
     * Parses `GET /releases/:id` from `tracks.monochrome.st`, extracting all tracks with ISRC and duration.
     */
    fun parseMonochromeReleaseTracks(jsonText: String): List<SpotiFLACCandidate> {
        val root = parseObject(jsonText) ?: return emptyList()
        val releaseObj = root.getAsJsonObject("release") ?: root
        val releaseId = releaseObj.stringOrId("id")
        val albumTitle = releaseObj.stringOrNull("title")
        val releaseArtist = releaseObj.stringOrNull("artist_name") ?: ""

        val tracks = root.getAsJsonArray("tracks")
            ?: releaseObj.getAsJsonArray("tracks")
            ?: return emptyList()

        return tracks.mapNotNull { elem ->
            val item = elem.asObjectOrNull() ?: return@mapNotNull null
            val trackId = item.stringOrId("id")
                ?: item.stringOrId("track_id")
                ?: return@mapNotNull null
            val title = item.stringOrNull("title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artist = item.stringOrNull("artist_name")
                ?.takeIf { it.isNotEmpty() }
                ?: releaseArtist
            val durationMs = item.longOrZero("duration")
            val isrc = SpotiFLACMatcher.normalizeIsrc(item.stringOrNull("isrc"))
            val playable = item.booleanOrDefault("playable", true)

            SpotiFLACCandidate(
                provider = SpotiFLACProvider.MONOCHROME_TRACKS,
                trackId = trackId,
                releaseId = releaseId,
                title = title,
                artist = artist,
                album = albumTitle,
                durationMs = durationMs,
                isrc = isrc,
                playable = playable,
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
        if (trimmedBase.isEmpty() || safeId.isEmpty()) return ""
        return "$trimmedBase/track/$safeId"
    }

    // -------------------------------------------------------------------------
    // 2. Tidal HiFi / OpenAPI Instances (`api.monochrome.tf`, `tidal.kinoplus.online`, etc.)
    // -------------------------------------------------------------------------

    /**
     * Parses `GET /search/?i=<ISRC>` or `GET /search/?s=<query>` from Tidal HiFi API instances.
     */
    fun parseHiFiSearchResponse(jsonText: String): List<SpotiFLACCandidate> {
        val rootElement = runCatching { JsonParser.parseString(jsonText) }.getOrNull() ?: return emptyList()

        val itemsArray = when {
            rootElement.isJsonArray -> rootElement.asJsonArray
            rootElement.isJsonObject -> {
                val obj = rootElement.asJsonObject
                obj.getAsJsonObject("data")?.getAsJsonArray("items")
                    ?: obj.getAsJsonObject("tracks")?.getAsJsonArray("items")
                    ?: obj.getAsJsonArray("items")
                    ?: obj.getAsJsonArray("data")
            }
            else -> null
        } ?: return emptyList()

        return itemsArray.mapNotNull { elem ->
            val rawItem = elem.asObjectOrNull() ?: return@mapNotNull null
            val item = rawItem.getAsJsonObject("item") ?: rawItem
            val trackId = item.stringOrId("id") ?: return@mapNotNull null
            val title = item.stringOrNull("title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null

            val artist = item.getAsJsonObject("artist")?.stringOrNull("name")
                ?: item.getAsJsonArray("artists")
                    ?.firstOrNull()?.asObjectOrNull()?.stringOrNull("name")
                ?: item.stringOrNull("artist")
                ?: ""

            val album = item.getAsJsonObject("album")?.stringOrNull("title")
                ?: item.stringOrNull("album")

            val rawDuration = item.longOrZero("duration")
            val durationMs = if (rawDuration in 1..3_600) rawDuration * 1_000L else rawDuration
            val isrc = SpotiFLACMatcher.normalizeIsrc(item.stringOrNull("isrc"))
            val playable = item.booleanOrDefault("allowStreaming", true) &&
                item.booleanOrDefault("streamReady", true)
            val quality = item.stringOrNull("audioQuality") ?: "LOSSLESS"

            SpotiFLACCandidate(
                provider = SpotiFLACProvider.TIDAL_HIFI,
                trackId = trackId,
                releaseId = item.getAsJsonObject("album")?.stringOrId("id"),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
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
        val rootElement = runCatching { JsonParser.parseString(jsonText) }.getOrNull() ?: return null

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
                            val decodedText = String(decodedBytes, Charsets.UTF_8)
                            val manifestObj = parseObject(decodedText)
                            if (manifestObj != null) {
                                manifestObj.stringOrNull("mimeType")?.takeIf { it.isNotEmpty() }?.let {
                                    manifestMimeType = it
                                }
                                val firstUrl = manifestObj.getAsJsonArray("urls")
                                    ?.firstOrNull()
                                    ?.let { if (it.isJsonPrimitive) it.asString else null }
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
    // 3. Deezer Public API (`api.deezer.com`) — ISRC & Metadata Enrichment
    // -------------------------------------------------------------------------

    /**
     * Parses `GET /2.0/track/isrc:<ISRC>` or `GET /2.0/track/<id>` from `api.deezer.com`.
     */
    fun parseDeezerTrack(jsonText: String): SpotiFLACCandidate? {
        val obj = parseObject(jsonText) ?: return null
        if (obj.has("error")) return null

        val trackId = obj.stringOrId("id") ?: return null
        val title = obj.stringOrNull("title")?.takeIf { it.isNotEmpty() } ?: return null
        val artist = obj.getAsJsonObject("artist")?.stringOrNull("name") ?: ""
        val album = obj.getAsJsonObject("album")?.stringOrNull("title")
        val durationSec = obj.longOrZero("duration")
        val isrc = SpotiFLACMatcher.normalizeIsrc(obj.stringOrNull("isrc"))
        val readable = obj.booleanOrDefault("readable", true)

        return SpotiFLACCandidate(
            provider = SpotiFLACProvider.DEEZER,
            trackId = trackId,
            releaseId = obj.getAsJsonObject("album")?.stringOrId("id"),
            title = title,
            artist = artist,
            album = album,
            durationMs = durationSec * 1_000L,
            isrc = isrc,
            playable = readable,
            quality = "LOSSLESS"
        )
    }

    /**
     * Parses `GET /2.0/search?q=...` from `api.deezer.com`.
     */
    fun parseDeezerSearchTracks(jsonText: String): List<SpotiFLACCandidate> {
        val root = parseObject(jsonText) ?: return emptyList()
        val data = root.getAsJsonArray("data") ?: return emptyList()

        return data.mapNotNull { elem ->
            val obj = elem.asObjectOrNull() ?: return@mapNotNull null
            val trackId = obj.stringOrId("id") ?: return@mapNotNull null
            val title = obj.stringOrNull("title")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val artist = obj.getAsJsonObject("artist")?.stringOrNull("name") ?: ""
            val album = obj.getAsJsonObject("album")?.stringOrNull("title")
            val durationSec = obj.longOrZero("duration")
            val isrc = SpotiFLACMatcher.normalizeIsrc(obj.stringOrNull("isrc"))
            val readable = obj.booleanOrDefault("readable", true)

            SpotiFLACCandidate(
                provider = SpotiFLACProvider.DEEZER,
                trackId = trackId,
                releaseId = obj.getAsJsonObject("album")?.stringOrId("id"),
                title = title,
                artist = artist,
                album = album,
                durationMs = durationSec * 1_000L,
                isrc = isrc,
                playable = readable,
                quality = "LOSSLESS"
            )
        }
    }

    private fun parseObject(jsonText: String): JsonObject? =
        runCatching { JsonParser.parseString(jsonText).asObjectOrNull() }.getOrNull()

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        if (this != null && this.isJsonObject) this.asJsonObject else null

    private fun JsonObject.stringOrNull(key: String): String? {
        val elem = get(key) ?: return null
        if (!elem.isJsonPrimitive) return null
        return runCatching { elem.asString }.getOrNull()
    }

    private fun JsonObject.stringOrId(key: String): String? {
        val elem = get(key) ?: return null
        if (!elem.isJsonPrimitive) return null
        val prim = elem.asJsonPrimitive
        return when {
            prim.isString -> prim.asString.takeIf { it.isNotEmpty() }
            prim.isNumber -> prim.asLong.toString()
            else -> null
        }
    }

    private fun JsonObject.longOrZero(key: String): Long {
        val elem = get(key) ?: return 0L
        if (!elem.isJsonPrimitive) return 0L
        return runCatching { elem.asLong }.getOrDefault(0L)
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
        if (!elem.isJsonPrimitive) return default
        return runCatching { elem.asBoolean }.getOrDefault(default)
    }
}
