package com.theveloper.pixelplay.data.ai.claude.taizo

import com.theveloper.pixelplay.data.model.Lyrics
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.network.lyrics.SongIsrcResolver
import com.theveloper.pixelplay.data.preferences.PlaylistPreferencesRepository
import com.theveloper.pixelplay.data.recommendation.MusicTasteRepository
import com.theveloper.pixelplay.data.repository.LyricsRepository
import com.theveloper.pixelplay.data.repository.MusicRepository
import com.theveloper.pixelplay.data.stats.PlaybackStatsRepository
import com.theveloper.pixelplay.data.stats.StatsTimeRange
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gathers what Ask Taizo always knows: the listener's profile (from the same stores the stats
 * screen and the mixes use, so imported Spotify / YouTube Music history counts too) and what is
 * playing now. Every source is best-effort: a store that fails just leaves its line out.
 */
@Singleton
class TaizoContextProvider @Inject constructor(
    private val musicRepository: MusicRepository,
    private val statsRepository: PlaybackStatsRepository,
    private val playlists: PlaylistPreferencesRepository,
    private val taste: MusicTasteRepository,
    private val lyricsRepository: LyricsRepository,
    private val isrcResolver: SongIsrcResolver,
    private val tasteProfiles: TasteProfileProvider,
) {
    suspend fun profile(): TaizoProfile = withContext(Dispatchers.IO) {
        val songs = safely { musicRepository.getAllSongsOnce() }.orEmpty()
        val summary = if (songs.isEmpty()) null else safely { statsRepository.loadSummary(StatsTimeRange.ALL, songs) }
        val byId = songs.associateBy { it.id }
        val recent = safely { statsRepository.loadPlaybackHistory(RECENT_PLAYS * 3) }.orEmpty()
            .mapNotNull { entry -> byId[entry.songId] }
            .distinctBy { it.id }
            .take(RECENT_PLAYS)
            .map { "${it.title} - ${it.displayArtist}" }
        val tasteState = safely { taste.state.first() }
        TaizoProfile(
            librarySongs = songs.size,
            libraryArtists = songs.map { it.primaryArtist.name.ifBlank { it.artist } }.filter { it.isNotBlank() }.distinct().size,
            totalPlays = summary?.totalPlayCount ?: 0,
            listeningMinutes = (summary?.totalDurationMs ?: 0L) / 60_000L,
            topArtists = summary?.topArtists.orEmpty().take(TOP_ARTISTS).map { it.artist to it.playCount },
            topGenres = summary?.topGenres.orEmpty()
                .filter { it.genre != PlaybackStatsRepository.UNKNOWN_GENRE_LABEL }
                .take(TOP_GENRES)
                .map { it.genre to it.playCount },
            topSongs = summary?.topSongs.orEmpty().take(TOP_SONGS).map { "${it.title} - ${it.artist} (${it.playCount} plays)" },
            recentPlays = recent,
            playlists = safely { playlists.userPlaylistsFlow.first() }.orEmpty().map { it.name }.take(MAX_PLAYLISTS),
            skips = tasteState?.skips ?: 0,
            completions = tasteState?.completions ?: 0,
            // Read from the same stores as get_taste_profile (cached until a new play or taste change).
            tasteDigest = safely { tasteProfiles.digest() },
        )
    }

    suspend fun nowPlaying(player: TaizoPlayer?): TaizoNowPlaying? {
        val song = player?.currentSong() ?: return null
        val lyrics = withTimeoutOrNull(LYRICS_TIMEOUT_MS) { lyricsText(song) }
        val isrc = withTimeoutOrNull(ISRC_TIMEOUT_MS) { safely { isrcResolver.isrcFor(song) } }
        return TaizoNowPlaying(
            songId = song.id,
            title = song.title,
            artists = song.displayArtist,
            album = song.album,
            year = song.year,
            durationMs = song.duration,
            positionMs = player.positionMs(),
            isPlaying = player.isPlaying(),
            spotifyId = song.spotifyId,
            isrc = isrc,
            instrumentalReady = player.instrumentalReady(),
            lyrics = lyrics,
        )
    }

    /** Plain lyrics for [song], or null. */
    suspend fun lyricsText(song: Song): String? = safely {
        withContext(Dispatchers.IO) { lyricsRepository.getLyrics(song)?.let { plainText(it) } }
    }?.takeIf { it.isNotBlank() }

    private suspend fun <T> safely(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    companion object {
        const val TOP_ARTISTS = 15
        const val TOP_GENRES = 10
        const val TOP_SONGS = 20
        const val RECENT_PLAYS = 25
        const val MAX_PLAYLISTS = 40
        const val LYRICS_TIMEOUT_MS = 4_000L
        const val ISRC_TIMEOUT_MS = 1_500L

        /** The words of [lyrics], one line each, without timestamps. */
        fun plainText(lyrics: Lyrics): String {
            val synced = lyrics.synced
            if (!synced.isNullOrEmpty()) return synced.joinToString("\n") { it.line }
            return lyrics.plain.orEmpty().joinToString("\n")
        }
    }
}
