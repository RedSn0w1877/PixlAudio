package com.theveloper.pixelplay.data.brain.source

import com.theveloper.pixelplay.data.youtube.ArtistPage
import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsers
import com.theveloper.pixelplay.data.youtube.RadioEndpoint
import com.theveloper.pixelplay.data.youtube.RadioPage
import com.theveloper.pixelplay.data.youtube.RelatedPage
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * YouTube Music as a [RadioSource] (docs/brain-spec.md, 3.3 A). Anonymous, through [InnerTubeClient]
 * (the `@YouTubeOkHttpClient`, never the app's default client), and under the YouTube [HostBudget].
 *
 * A response is about 550 KB of JSON: it is parsed off the main thread, the rows are taken out and the
 * tree is dropped at once. Nothing raw is kept.
 */
@Singleton
class YouTubeRadioSource @Inject constructor(
    private val client: InnerTubeClient,
    private val budget: HostBudget,
) : RadioSource {

    override suspend fun songRadio(videoId: String, continuations: Int): FetchedRadio? =
        radio(continuations) { client.nextRadio(videoId, SONG_RADIO_PREFIX + videoId) }

    override suspend fun artistRadio(endpoint: RadioEndpoint): FetchedRadio? =
        radio(continuations = 0) { client.nextRadio(endpoint.videoId, endpoint.playlistId, endpoint.params) }

    override suspend fun related(browseId: String): RelatedPage? {
        val text = budget.withPermit { client.browseMusic(browseId) } ?: return null
        return parse(text) { InnerTubeMusicParsers.parseRelated(it) }
            ?.takeIf { it.youMightAlsoLike.isNotEmpty() || it.similarArtists.isNotEmpty() }
    }

    override suspend fun artistPage(channelId: String): ArtistPage? {
        val text = budget.withPermit { client.browseMusic(channelId) } ?: return null
        return parse(text) { InnerTubeMusicParsers.parseArtistPage(it) }
            ?.takeIf { it.radio != null || it.topSongs.isNotEmpty() }
    }

    private suspend fun radio(continuations: Int, firstPage: suspend () -> String?): FetchedRadio? {
        var requests = 0
        var first: RadioPage? = null
        // A response without the expected renderer is "no data"; an empty radio is retried once.
        for (attempt in 0 until 2) {
            val text = budget.withPermit { firstPage() } ?: break
            requests++
            first = parse(text) { InnerTubeMusicParsers.parseRadio(it) }
            if (first != null && first.rows.isNotEmpty()) break
        }
        // Null only when nothing came back at all. A response with no rows is returned as an empty
        // radio: the caller treats it as junk, remembers it, and tries the fallbacks.
        val page = first ?: return null

        val pager = InnerTubeMusicParsers.RadioPager()
        pager.accept(page)
        var wanted = continuations.coerceIn(0, InnerTubeMusicParsers.RadioPager.MAX_CONTINUATIONS)
        while (wanted > 0) {
            val token = pager.nextToken ?: break
            val text = budget.withPermit { client.nextRadioContinuation(token) } ?: break
            requests++
            val next = parse(text) { InnerTubeMusicParsers.parseRadio(it) } ?: break
            pager.accept(next)
            wanted--
        }
        return FetchedRadio(
            rows = pager.rows.toList(),
            chips = page.chips,
            relatedBrowseId = page.relatedBrowseId,
            requests = requests,
        )
    }

    private suspend fun <T> parse(text: String, block: (kotlinx.serialization.json.JsonElement) -> T): T? =
        withContext(Dispatchers.Default) {
            runCatching { block(json.parseToJsonElement(text)) }.getOrNull()
        }

    private companion object {
        const val SONG_RADIO_PREFIX = "RDAMVM"
        val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
