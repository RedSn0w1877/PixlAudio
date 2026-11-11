package com.theveloper.pixelplay.data.spatial.library

import com.theveloper.pixelplay.data.cloudstudio.CloudJson
import com.theveloper.pixelplay.data.cloudstudio.CloudKeys
import com.theveloper.pixelplay.data.spatial.SpatialContract
import com.theveloper.pixelplay.data.spatial.SpatialProfile
import com.theveloper.pixelplay.data.spatial.SpatialRender
import java.io.File
import kotlinx.serialization.Serializable

// A render's manifest in the shared library, `lib/v1/<videoId>/sp-<profile>.json` (cloud-audio-spec §3.1), and the
// one rule for accepting it. The worker writes it last, after the audio; this phone keeps a copy of it next to the
// FLAC in its local store, where the file plus a valid manifest is the index (§6.2 "Local store").

/** `sp-<profile>.json` (§3.1). Unknown fields are ignored (a newer worker may add some, and must not break this one). */
@Serializable
data class SpatialRenderManifest(
    val schema: String,
    val v: Int,
    val libV: Int,
    val videoId: String,
    val kind: String,
    val profile: String,
    val audio: Audio,
    val loudness: Loudness? = null,
    val source: Source? = null,
    val render: Render? = null,
    val worker: Worker? = null,
    val createdAt: String? = null,
) {
    /** The render file: FLAC 16-bit at the uploaded file's rate, the source's frame count, no added delay. */
    @Serializable
    data class Audio(
        val key: String,
        val bytes: Long,
        val sha256: String,
        val codec: String? = null,
        val bits: Int? = null,
        val sampleRate: Int,
        val samples: Long,
        val durationMs: Long,
    )

    /** The level: every render sits `headroomDb` (4.0) under the source's integrated loudness (§3.1 "Level"). */
    @Serializable
    data class Loudness(
        val integratedLufs: Double? = null,
        val sourceLufs: Double? = null,
        val headroomDb: Double? = null,
        val truePeakDb: Double? = null,
        val limiterMaxDb: Double? = null,
    )

    /** The stream the phone uploaded from, and what the worker decoded of it. */
    @Serializable
    data class Source(
        val itag: Int? = null,
        val codec: String? = null,
        val sampleRate: Int? = null,
        val decodedSamples: Long? = null,
        val sha256: String? = null,
    )

    /** How it was rendered: the engine id, the stem separator and the stem mode (`stems6`, `stems4` or `mix`). */
    @Serializable
    data class Render(val engine: String? = null, val stems: String? = null, val stemMode: String? = null)

    @Serializable
    data class Worker(val version: String? = null, val gitSha: String? = null)
}

/** Reading and checking render manifests (§3.1): the only place that decides whether one is accepted. */
object SpatialManifests {
    const val SCHEMA = "pixl.library.render"
    const val VERSION = 1
    const val KIND = "spatial"

    /** §3.1: the level the renders sit under the source when a manifest doesn't say (4 dB today). */
    const val DEFAULT_HEADROOM_DB = 4.0f

    /** Manifests are a few hundred bytes; anything far larger is not one. */
    const val MAX_MANIFEST_BYTES = 64 * 1024

    /** The manifest in [bytes], or null when it is not one this app can read. */
    fun decode(bytes: ByteArray): SpatialRenderManifest? {
        if (bytes.isEmpty() || bytes.size > MAX_MANIFEST_BYTES) return null
        return try {
            CloudJson.decodeFromString(SpatialRenderManifest.serializer(), bytes.toString(Charsets.UTF_8))
        } catch (error: Exception) {
            null
        }
    }

    /**
     * Why [manifest] is not the render asked for, or null when it is (§3.1: "A phone accepts a manifest only when
     * `schema`, `v`, `libV`, `videoId`, `kind` and `profile` are what it asked for"). The audio must be at its canonical
     * key, with a size, a SHA-256, a rate and a length; whether the downloaded bytes match is [fileMatches].
     */
    fun refusal(manifest: SpatialRenderManifest, videoId: String, profile: SpatialProfile): String? = when {
        manifest.schema != SCHEMA -> "Not a render manifest."
        manifest.v != VERSION -> "A render manifest of another version."
        manifest.libV != SpatialContract.LIB_V -> "A render of another library version."
        manifest.videoId != videoId -> "A render of another video."
        manifest.kind != KIND -> "Not a Spatial audio render."
        manifest.profile != profile.id -> "A render of another profile."
        manifest.audio.key != CloudKeys.libraryAudio(videoId, profile.id) -> "The render's audio is not where it should be."
        manifest.audio.bytes <= 0 -> "The render's size is missing."
        !isSha256(manifest.audio.sha256) -> "The render's checksum is missing."
        manifest.audio.sampleRate <= 0 || manifest.audio.samples <= 0 -> "The render's length is missing."
        else -> null
    }

    /** The downloaded file is the one [manifest] describes: the same byte count and SHA-256 (§3.1). */
    fun fileMatches(manifest: SpatialRenderManifest, bytes: Long, sha256: String): Boolean =
        bytes == manifest.audio.bytes && sha256.equals(manifest.audio.sha256, ignoreCase = true)

    /** The facts the player and Developer mode's diagnostics take from an accepted manifest. */
    fun render(manifest: SpatialRenderManifest, profile: SpatialProfile, file: File): SpatialRender = SpatialRender(
        videoId = manifest.videoId,
        profile = profile,
        file = file,
        sampleRate = manifest.audio.sampleRate,
        samples = manifest.audio.samples,
        durationMs = manifest.audio.durationMs,
        headroomDb = manifest.loudness?.headroomDb?.toFloat()?.takeIf { it.isFinite() && it >= 0f } ?: DEFAULT_HEADROOM_DB,
        sourceCodec = manifest.source?.codec,
        stemMode = manifest.render?.stemMode,
        integratedLufs = manifest.loudness?.integratedLufs?.toFloat(),
        sourceLufs = manifest.loudness?.sourceLufs?.toFloat(),
    )

    private fun isSha256(value: String): Boolean =
        value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
}
