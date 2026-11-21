package com.theveloper.pixelplay.data.songtap

import com.theveloper.pixelplay.songtap.SongTapPayload
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** What the receiver's card shows after a beam. */
sealed interface SongTapSaveResult {
    /** The song as the card shows it: the validated beam text, never anything the beam could use to run code. */
    data class Saved(val card: SongTapCard) : SongTapSaveResult

    /** It was in the library already: nothing added, nothing to undo. */
    data class AlreadyHad(val card: SongTapCard) : SongTapSaveResult

    /** No video could be found for it here: nothing saved. */
    data object NotFound : SongTapSaveResult

    data object Failed : SongTapSaveResult
}

/** The title, artist and art the card shows, plus the key to undo and the library id to play. */
data class SongTapCard(
    val videoId: String,
    val songId: String,
    val title: String,
    val artist: String,
    val artUrl: String?,
)

/**
 * Turns a validated beam into a library song, using the importer the Search screen's Save uses.
 *
 * It touches the library only. Nothing here is a listening event: no play is recorded, the music brain
 * is not told, and no "saved while playing" flag is set (a beamed song is not playing on this phone), so a
 * private session needs no special case on the receiving side.
 */
@Singleton
class SongTapReceiver @Inject constructor(
    private val library: SongTapLibrary,
) {

    suspend fun save(p: SongTapPayload): SongTapSaveResult = try {
        resolveAndSave(p)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "song tap: saving a beam failed")
        SongTapSaveResult.Failed
    }

    private suspend fun resolveAndSave(p: SongTapPayload): SongTapSaveResult {
        val spotifyId = p.spotifyId
        val videoId = p.ytVideoId ?: run {
            // No video came with it: the Spotify row the listener already has is enough.
            if (spotifyId != null && library.hasSpotifyId(spotifyId)) return SongTapSaveResult.AlreadyHad(card(p, "", library.songIdForSpotify(spotifyId)))
            library.findVideo(p.title, p.artist, p.durationMs.toLong(), p.isrc, spotifyId) ?: return SongTapSaveResult.NotFound
        }
        if (library.hasVideo(videoId) || (spotifyId != null && library.hasSpotifyId(spotifyId))) {
            return SongTapSaveResult.AlreadyHad(card(p, videoId, library.songId(videoId)))
        }
        val added = library.import(videoId, p.title, p.artist, p.durationMs.toLong(), p.artUrl)
        val card = card(p, videoId, library.songId(videoId))
        return if (added) SongTapSaveResult.Saved(card) else SongTapSaveResult.AlreadyHad(card)
    }

    private fun card(p: SongTapPayload, videoId: String, songId: String) =
        SongTapCard(videoId = videoId, songId = songId, title = p.title, artist = p.artist, artUrl = p.artUrl)

    /** Undo within the card's window: removes what [save] added. */
    suspend fun undo(videoId: String): Boolean = try {
        library.remove(videoId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.w(e, "song tap: undo failed")
        false
    }
}
