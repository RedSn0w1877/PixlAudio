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
    val showWhy: (song: Song, whyKey: String, whyArg: String) -> Unit,
    val showHowPicksWork: () -> Unit,
)

val LocalBrainUi = compositionLocalOf<BrainUi?> { null }
