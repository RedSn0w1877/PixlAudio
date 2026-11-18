package com.theveloper.pixelplay.data.brain.source

/**
 * How `SpotifyStreamProxy` finds the audio of a track that has no `spotify_songs` row
 * (docs/brain-spec.md, 7.2): the brain's `playable` table maps the track's synthetic 22-character id
 * to a YouTube video id. A stored `spotify_songs` match always wins over this lookup, so a track the
 * listener saved (or re-matched by hand) plays from its library row.
 */
interface EphemeralTrackLookup {
    /** The video id registered for [synthId], or null. Must be safe to call from any thread. */
    suspend fun videoIdFor(synthId: String): String?
}
