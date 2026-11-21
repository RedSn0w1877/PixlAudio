package com.theveloper.pixelplay.data.songtap.ring

import com.theveloper.pixelplay.data.youtube.InnerTubeClient
import com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsers
import com.theveloper.pixelplay.data.youtube.RadioRow
import com.theveloper.pixelplay.songtap.SongTapPayload
import com.theveloper.pixelplay.songtap.SongTapPayloads
import com.theveloper.pixelplay.songtap.SongTapText
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import timber.log.Timber

/**
 * Turns the 11-character video id a ring code carries into the song the receiver's card and library need: title, artist,
 * length and art. The code carries nothing else, so this needs the internet; a failure is `null` and the screen offers the QR code
 * (which carries the name and works offline).
 */
interface RingCodeResolver {
    suspend fun resolve(videoId: String, nowMs: Long): SongTapPayload?
}

/** The real one: YouTube Music's `get_queue` for one id (the same call the music brain uses to name a track), parsed by the app's own parser. */
@Singleton
class InnerTubeRingCodeResolver @Inject constructor(
    private val client: InnerTubeClient,
) : RingCodeResolver {

    override suspend fun resolve(videoId: String, nowMs: Long): SongTapPayload? = try {
        val text = client.getQueue(listOf(videoId))
        if (text == null) {
            null
        } else {
            val row = withContext(Dispatchers.Default) {
                InnerTubeMusicParsers.parseGetQueue(JSON.parseToJsonElement(text)).firstOrNull { it.videoId == videoId }
            }
            row?.let { RingCodeSongs.payloadFrom(it, nowMs) }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "ring code: the song could not be looked up")
        null
    }

    private companion object {
        val JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

object RingCodeSongs {
    /** Art links the card may load: https on one of the same four hosts a beam's art may use, plain printable characters, at most this long. */
    const val MAX_ART_URL = 400

    /** What a ring code's lookup becomes: a payload with the video id and the text the card shows. Null when there is no usable title. */
    fun payloadFrom(row: RadioRow, nowMs: Long): SongTapPayload? {
        val title = SongTapText.sanitize(row.title, SongTapPayloads.MAX_TITLE_BYTES)
        if (title.isEmpty() || !SongTapPayloads.isVideoId(row.videoId)) return null
        var artist = SongTapText.sanitize(row.artistDisplay, SongTapPayloads.MAX_ARTIST_BYTES)
        if (artist.isEmpty()) artist = SongTapPayloads.UNKNOWN_ARTIST
        val duration = (row.durationSec.coerceAtLeast(0).toLong() * 1000L).coerceAtMost(SongTapPayloads.MAX_DURATION_MS.toLong()).toInt()
        return SongTapPayload(
            v = SongTapPayloads.VERSION,
            ts = nowMs.coerceAtLeast(0L),
            ytVideoId = row.videoId,
            spotifyId = null,
            isrc = null,
            title = title,
            artist = artist,
            durationMs = duration,
            artUrl = artFor(row),
        )
    }

    /** The row's own cover when it is on an allowed host, else the video's thumbnail. */
    fun artFor(row: RadioRow): String? {
        val own = row.thumbnailUrl
        if (own != null && isAllowedArt(own)) return own
        return "https://i.ytimg.com/vi/${row.videoId}/hqdefault.jpg"
    }

    fun isAllowedArt(url: String): Boolean {
        if (url.length > MAX_ART_URL || !url.startsWith("https://")) return false
        for (c in url) if (c.code < 0x21 || c.code > 0x7E || c == '"' || c == '\\' || c == '<' || c == '>') return false
        val hostEnd = url.indexOf('/', 8)
        if (hostEnd < 0) return false
        return url.substring(8, hostEnd) in SongTapPayloads.ART_HOSTS
    }
}
