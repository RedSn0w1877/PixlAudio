package com.theveloper.pixelplay.data.spotiflac

import com.theveloper.pixelplay.data.database.SpotifySongEntity

/**
 * Lossless provider sources supported by the SpotiFLAC resolver chain.
 */
enum class SpotiFLACProvider(val rawValue: String) {
    /** Monochrome direct track streamer (`tracks.monochrome.st`). */
    MONOCHROME_TRACKS("monochrome_tracks"),

    /** Tidal HiFi / OpenAPI compatible instances (`api.monochrome.tf`, `tidal.kinoplus.online`, etc.). */
    TIDAL_HIFI("tidal_hifi"),

    /** Deezer public catalog (`api.deezer.com`) used for ISRC & duration enrichment. */
    DEEZER("deezer"),

    /** Odesli / Song.link cross-platform ID resolver (`api.song.link`). */
    ODESLI("odesli")
}

/**
 * Input metadata for resolving a lossless FLAC stream via SpotiFLAC.
 */
data class SpotiFLACTrackQuery(
    val spotifyId: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    val isrc: String? = null
)

fun SpotifySongEntity.toSpotiFLACQuery(): SpotiFLACTrackQuery = SpotiFLACTrackQuery(
    spotifyId = spotifyId,
    title = title,
    artist = artist,
    album = album.takeIf { it.isNotBlank() },
    durationMs = durationMs,
    isrc = isrc?.takeIf { it.isNotBlank() }
)

/**
 * ReplayGain metadata returned by Tidal HiFi / Monochrome stream manifests.
 */
data class SpotiFLACReplayGain(
    val trackGainDb: Double,
    val trackPeak: Double
)

/**
 * A candidate track returned during SpotiFLAC catalog search.
 */
data class SpotiFLACCandidate(
    val provider: SpotiFLACProvider,
    val trackId: String,
    val releaseId: String? = null,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long,
    val isrc: String? = null,
    val playable: Boolean = true,
    val quality: String? = null
)

/**
 * A resolved lossless FLAC stream ready for playback or download.
 */
data class SpotiFLACResolvedStream(
    val url: String,
    val mimeType: String = "audio/flac",
    val provider: SpotiFLACProvider,
    val providerTrackId: String,
    val isrc: String? = null,
    val quality: String = "LOSSLESS",
    val bitDepth: Int? = 16,
    val sampleRateHz: Int? = 44_100,
    val durationMs: Long? = null,
    val replayGain: SpotiFLACReplayGain? = null,
    val userAgent: String = DEFAULT_USER_AGENT
) {
    companion object {
        const val DEFAULT_USER_AGENT = "PixlAudio/1.0"
    }
}
