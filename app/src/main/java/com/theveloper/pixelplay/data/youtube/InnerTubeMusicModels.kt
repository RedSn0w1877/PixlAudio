package com.theveloper.pixelplay.data.youtube

import kotlinx.serialization.Serializable

/**
 * Plain rows read out of YouTube Music responses (docs/brain-spec.md, 3.3). No JSON, no Android
 * type: these are what the brain stores (`radio_list.rowsBlob`) and what both platforms' parser
 * tests compare against the shared fixtures.
 */

/** An artist credit. [channelId] is the `UC...` browse id; null when the byline run carried no link. */
@Serializable
data class RadioArtistRef(
    val name: String,
    val channelId: String? = null,
)

/** What kind of video a row is. Only [ATV] (official audio) is a playable candidate in stage 1. */
enum class MusicVideoKind {
    ATV, OMV, UGC, PODCAST,

    /** The response did not say. Never treated as official audio. */
    UNKNOWN;

    companion object {
        fun from(musicVideoType: String?): MusicVideoKind = when (musicVideoType) {
            "MUSIC_VIDEO_TYPE_ATV" -> ATV
            "MUSIC_VIDEO_TYPE_OMV" -> OMV
            "MUSIC_VIDEO_TYPE_UGC" -> UGC
            "MUSIC_VIDEO_TYPE_PODCAST_EPISODE" -> PODCAST
            else -> UNKNOWN
        }
    }
}

/** One track of a radio, a Related shelf or an artist's top songs. */
@Serializable
data class RadioRow(
    val videoId: String,
    val title: String,
    /** Credited artists, primary first. */
    val artists: List<RadioArtistRef> = emptyList(),
    val album: String? = null,
    /** `MPREb_...` */
    val albumBrowseId: String? = null,
    /** 0 = unknown. */
    val year: Int = 0,
    /** 0 = unknown (Related-tab and top-song rows carry no length). */
    val durationSec: Int = 0,
    val thumbnailUrl: String? = null,
    val explicit: Boolean = false,
    val kind: MusicVideoKind = MusicVideoKind.UNKNOWN,
    /** From "3.4B plays" on an artist page; null elsewhere. */
    val playCount: Long? = null,
) {
    val isOfficialAudio: Boolean get() = kind == MusicVideoKind.ATV

    /** "A, B": for display only. Never split this again. */
    val artistDisplay: String get() = artists.joinToString(", ") { it.name }
}

/** A tuning chip of a radio ("Popular", "Deep cuts", "V-pop"). Kept as text for stage 2's tag source. */
@Serializable
data class RadioChip(
    val label: String,
    val playlistId: String,
    val params: String? = null,
)

/** One `next` response: a radio's first page, or a continuation page. */
data class RadioPage(
    val rows: List<RadioRow>,
    /** Token for the next page; null when the response had none. */
    val continuation: String?,
    val chips: List<RadioChip> = emptyList(),
    /** `MPTRt_...` of the Related tab; only on a first page. */
    val relatedBrowseId: String? = null,
) {
    companion object {
        val EMPTY = RadioPage(emptyList(), null)
    }
}

/** The Related tab of a track. */
data class RelatedPage(
    val youMightAlsoLike: List<RadioRow>,
    /** Other recordings of the same song: a dedupe list, never candidates. */
    val otherPerformances: List<RadioRow>,
    val similarArtists: List<RadioArtistRef>,
) {
    companion object {
        val EMPTY = RelatedPage(emptyList(), emptyList(), emptyList())
    }
}

/** The three values of an artist page's "radio" button, sent back to `next` exactly as they came. */
@Serializable
data class RadioEndpoint(
    val videoId: String?,
    val playlistId: String,
    val params: String? = null,
)

/** An album, single or EP on an artist page. */
data class ReleaseRef(
    val browseId: String,
    val title: String,
    val year: Int = 0,
)

data class ArtistPage(
    val name: String,
    val radio: RadioEndpoint?,
    val topSongs: List<RadioRow>,
    val albums: List<ReleaseRef>,
    val singles: List<ReleaseRef>,
    val fansAlsoLike: List<RadioArtistRef>,
) {
    companion object {
        val EMPTY = ArtistPage("", null, emptyList(), emptyList(), emptyList(), emptyList())
    }
}
