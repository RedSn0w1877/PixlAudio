package com.theveloper.pixelplay.data.spatial

// The contract of Spatial audio on Android (the spec is handoff/2026-11-10-plans/cloud-audio-spec.md; "§" below is
// a section of it). This package holds only what the three parts of the feature
// share: the ids, the settings, the status types and the two seams, [SpatialLibrary] (the cloud side) and
// [SpatialPlayback] (the player side). The UI reads and calls
// [com.theveloper.pixelplay.presentation.viewmodel.SpatialAudioStateHolder]. Who owns which file is in
// handoff/2026-11-10-plans/android-integration-map.md.

/**
 * The six headphone profiles of Spatial audio (§1), with the ids of §2. The declaration order is the spec's, and the
 * order the player sheet lists them in (§6.5).
 *
 * [id] is the wire value everywhere: the `spatial.profile` setting (§6.1), the library keys
 * `lib/v1/<videoId>/sp-<id>.flac` (§3), a job's `library.renders[].profile` (§4) and the profile keys of
 * `spatial_preview_v1.json` (§7). Never rename one: the worker and the iOS app use the same strings.
 */
enum class SpatialProfile(val id: String) {
    /** Studio (§6.5): clean and close, like good speakers in a quiet room. */
    STUDIO("studio"),

    /** Wide (§6.5): instruments pushed out to the sides. */
    WIDE("wide"),

    /** Intimate (§6.5): the voice right in front of you. */
    INTIMATE("intimate"),

    /** Concert Hall (§6.5), id `hall`: a big room, a few rows back. */
    HALL("hall"),

    /** Surround (§6.5): sound beside and behind you. */
    SURROUND("surround"),

    /** Night (§6.5): softer and gentler for late listening. */
    NIGHT("night");

    companion object {
        /** The value of `spatial.profile` while Spatial audio is off (§6.1). Not a profile: it reads as `null`. */
        const val OFF_ID = "off"

        /** The profile §1 marks as the default: the one to start on when Spatial audio is switched on without a pick. */
        val DEFAULT = STUDIO

        /** The profile with the wire [id] (§2), or null for `off`, null and anything unknown. */
        fun fromId(id: String?): SpatialProfile? = entries.firstOrNull { it.id == id }
    }
}

/** The ids and versions this app shares with the worker and the iOS app (§2, §7). */
object SpatialContract {
    /**
     * The library version `LIB_V` (§2): part of every library key (`lib/v1/...`) and of every manifest (`libV`). A
     * local or shared render of another version is ignored (§3.1, §6.2 step 1).
     */
    const val LIB_V = 1

    /** The render engine id (§2). The on-device table carries the same value as `engine` (§7). */
    const val ENGINE_ID = "pixl-spatial-1"

    /**
     * The on-device coefficient table in the app's assets (§7): a byte-identical copy of the worker's file, never
     * edited by hand. Its golden file is the unit-test resource `spatial/spatial_preview_golden_v1.json`.
     */
    const val PREVIEW_ASSET = "spatial_preview_v1.json"

    /** `schema` of [PREVIEW_ASSET] (§7). */
    const val PREVIEW_SCHEMA = "pixl.spatial.preview"

    /** `v` of [PREVIEW_ASSET] (§7). */
    const val PREVIEW_VERSION = 1
}
