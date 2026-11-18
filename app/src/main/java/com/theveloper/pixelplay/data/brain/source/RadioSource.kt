package com.theveloper.pixelplay.data.brain.source

import com.theveloper.pixelplay.data.youtube.ArtistPage
import com.theveloper.pixelplay.data.youtube.RadioChip
import com.theveloper.pixelplay.data.youtube.RadioEndpoint
import com.theveloper.pixelplay.data.youtube.RadioRow
import com.theveloper.pixelplay.data.youtube.RelatedPage

/**
 * A radio as fetched: merged, deduplicated rows and what the first page said about itself. [rows] can be
 * empty: the request was answered, with nothing in it.
 */
data class FetchedRadio(
    val rows: List<RadioRow>,
    val chips: List<RadioChip> = emptyList(),
    /** `MPTRt_...` of the seed's Related tab, when the response named one. */
    val relatedBrowseId: String? = null,
    /** How many network requests this took (first page, retry, continuations). */
    val requests: Int = 1,
)

/**
 * Where related music comes from (docs/brain-spec.md, 2.1). The brain talks to this interface, so the
 * YouTube Music source can be switched off or replaced without touching what uses it. Every call
 * returns null when the source is unavailable, over budget, or had no data: never an exception.
 */
interface RadioSource {
    /** The radio of one song: seed first, then up to [continuations] further pages merged by video id. */
    suspend fun songRadio(videoId: String, continuations: Int = 0): FetchedRadio?

    /** The Related tab of a track. */
    suspend fun related(browseId: String): RelatedPage?

    /** An artist's page: top songs, releases, similar artists and the radio button's endpoint. */
    suspend fun artistPage(channelId: String): ArtistPage?

    /** An artist radio, from the endpoint an artist page carried. */
    suspend fun artistRadio(endpoint: RadioEndpoint): FetchedRadio?
}
