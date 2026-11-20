package com.theveloper.pixelplay.presentation.utils

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import com.theveloper.pixelplay.data.model.Song

/**
 * The music brain's actions as the UI sees them (docs/brain-spec.md, 8.3, 8.5, 8.8), provided once at
 * the app root so any song menu, queue row or artist page can offer "Start station" and "Not for me"
 * without threading parameters through every call site (the same idea as [LocalBlockedArtistsUi]).
 */
@Immutable
class BrainUi(
    /** A station from a song: it plays first, then music like it. */
    val startSongStation: (Song) -> Unit,
    /** A station from an artist. [songs] are the artist's library songs when the caller has them. */
    val startArtistStation: (artistName: String, songs: List<Song>) -> Unit,
    /** Mutes the song in everything the app picks by itself; skips it when it is playing. */
    val notForMe: (Song) -> Unit,
    /** Opens the sheet behind a pick's reason line. */
    val showWhy: (song: Song, whyKey: String, whyArg: String, servedX: String, model: Int) -> Unit,
    val showHowPicksWork: () -> Unit,
    /** A station from an album: a few of its songs seed it, and none of its own songs lead. */
    val startAlbumStation: (albumName: String, songs: List<Song>) -> Unit = { _, _ -> },
    /** A station from a playlist. */
    val startPlaylistStation: (playlistName: String, songs: List<Song>) -> Unit = { _, _ -> },
    /** "More like this": the song's artist is boosted for two weeks. */
    val moreLikeThis: (Song) -> Unit = {},
    /** "Don't count this": the song's plays stop shaping the picks. */
    val dontCount: (Song) -> Unit = {},
    /** An engine pick was swiped out of the queue without being heard: a weak negative for the session. */
    val swiped: (Song) -> Unit = {},
    /** "Refresh picks" on the Autoplay line. */
    val refreshPicks: () -> Unit = {},
    /** A station from a genre or a mood of the atlas: (kind, atlas id, name). */
    val startGenreStation: (kind: Int, atlasId: Int, name: String) -> Unit = { _, _, _ -> },
)

val LocalBrainUi = compositionLocalOf<BrainUi?> { null }
