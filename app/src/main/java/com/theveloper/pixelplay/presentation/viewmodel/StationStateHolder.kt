package com.theveloper.pixelplay.presentation.viewmodel

import android.content.Context
import com.theveloper.pixelplay.R
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.data.brain.BrainItemTag
import com.theveloper.pixelplay.data.brain.BrainPlayTags
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.brain.LibraryIndex
import com.theveloper.pixelplay.data.brain.Station
import com.theveloper.pixelplay.data.brain.StationSeeds
import com.theveloper.pixelplay.data.brain.StationStore
import com.theveloper.pixelplay.data.brain.endlessSeeds
import com.theveloper.pixelplay.data.database.EngagementDao
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.repository.MusicRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * "Start station" (docs/brain-spec.md, 8.3). A station is its seed track, played first, and a note of
 * what the queue is about ([StationStore]); the autoplay controller fills the rest as the seed plays.
 * This holder only decides the seed and asks the player for it. Nothing here ranks or fetches music.
 */
@Singleton
class StationStateHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stations: StationStore,
    private val playTags: BrainPlayTags,
    private val musicRepository: MusicRepository,
    private val engagementDao: EngagementDao,
    private val clock: BrainClock,
    private val castStateHolder: CastStateHolder,
    private val brainRepository: com.theveloper.pixelplay.data.brain.BrainRepository,
    private val playableStore: com.theveloper.pixelplay.data.brain.PlayableStore,
    private val genreStations: com.theveloper.pixelplay.data.brain.GenreStations,
    private val mixes: com.theveloper.pixelplay.data.brain.BrainMixes,
) {
    /** What the player does for a station; implemented by the ViewModel that owns the controller. */
    interface Player {
        val scope: CoroutineScope

        /** The media id that is current in the player, or null. */
        fun currentSongId(): String?

        /** Drops everything after the current track: the station takes over from here. */
        fun clearUpcoming()

        /** Starts a queue of just [song] under [queueName]. */
        fun playSeed(song: Song, queueName: String)

        fun toast(message: String)

        /** Plays the current queue if it is paused (a station that is already the queue's is picked up, not started again). False when there is nothing to play. */
        fun resume(): Boolean

        /** True when the queue really holds one of [ids] (a station whose queue was stopped and cleared still looks live in the store). */
        fun queueHasAny(ids: List<String>): Boolean
    }

    /** A station from [song]: it plays (or keeps playing), and music like it follows. */
    fun startSongStation(song: Song, player: Player) {
        val primary = LibraryIndex.primaryArtistName(song).takeUnless { it == EngineTrack.UNKNOWN_ARTIST }.orEmpty()
        start(Station.song(song.id, song.title, primary, clock.nowMs()), song, player)
    }

    /**
     * A station from an artist: the listener's most-played library track by that artist plays first.
     * [knownSongs] are the artist's songs when the caller already has them (the artist page); without
     * them the library is asked, off the main thread.
     */
    fun startArtistStation(artistName: String, knownSongs: List<Song>, player: Player) {
        if (artistName.isBlank()) return
        player.scope.launch {
            val seed = try {
                withContext(Dispatchers.Default) { seedFor(artistName, knownSongs) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "station: finding the artist's seed failed")
                null
            }
            // An artist with no song in the library: the artist page's own radio button starts it (8.3). One
            // `browse` and one `next` before sound, off the main thread, under the YouTube budget.
            val outsideSeed = if (seed != null) null else try {
                withContext(Dispatchers.IO) { seedFromYouTube(artistName) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "station: starting an artist with nothing in the library failed")
                null
            }
            withContext(Dispatchers.Main.immediate) {
                val chosen = seed ?: outsideSeed
                if (chosen == null) {
                    player.toast(context.getString(R.string.brain_station_nothing_found, artistName))
                } else {
                    start(Station.artist(chosen.id, artistName, clock.nowMs()), chosen, player)
                }
            }
        }
    }

    /** The seed song of an artist the library has nothing of: a track from the artist page's radio, ready to play. */
    private suspend fun seedFromYouTube(artistName: String): Song? {
        val started = brainRepository.beginArtistStation(artistName) ?: return null
        val track = EngineTrack.from(started.seedRow, started.seedRid)
        // The track must be in `playable` BEFORE its media item reaches the player (7.2).
        playableStore.register(listOf(track), clock.nowMs())
        return track.toSong()
    }

    /** A station from an album: three of its songs seed it, and none of its own songs lead (6.2). */
    fun startAlbumStation(albumName: String, songs: List<Song>, player: Player) =
        startFromSource(Station.SOURCE_ALBUM, albumName, songs, StationSeeds.ALBUM_SEEDS, player)

    /** A station from a playlist: five of its songs seed it (6.2). */
    fun startPlaylistStation(playlistName: String, songs: List<Song>, player: Player) =
        startFromSource(Station.SOURCE_PLAYLIST, playlistName, songs, StationSeeds.PLAYLIST_SEEDS, player)

    /**
     * A station from a genre or a mood of the atlas (8.3, stage 2b): the listener's most played songs under the node seed it.
     * With none of their songs under it nothing starts and [onNothing] runs (a Search tile then falls back to its text search).
     */
    fun startGenreStation(kind: Int, atlasId: Int, name: String, player: Player, onNothing: () -> Unit = {}) {
        if (name.isBlank()) return
        player.scope.launch {
            val songs = try {
                withContext(Dispatchers.Default) { genreStations.songsFor(kind, atlasId, clock.nowMs()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "station: reading the songs of a genre failed")
                emptyList()
            }
            withContext(Dispatchers.Main.immediate) {
                if (songs.isEmpty()) {
                    player.toast(context.getString(R.string.brain_station_genre_nothing, name))
                    onNothing()
                } else {
                    val source = if (kind == com.theveloper.pixelplay.data.brain.StationCard.KIND_MOOD) Station.SOURCE_MOOD else Station.SOURCE_GENRE
                    startFromSource(source, name, songs, com.theveloper.pixelplay.data.brain.GenreStations.SEEDS, player)
                }
            }
        }
    }

    /**
     * Endless Listening: starts the station from the listener's taste islands (up to five seeds, one for each; their top artists with fewer than two
     * islands), or picks it up when it is already the queue's station. Nothing is read until this is called.
     */
    fun startEndless(player: Player) {
        val live = stations.current.value
        // Picked up only when the queue really holds the station's seeds: after "stop" or a cleared queue the saved station still looks live, and
        // resuming it would play nothing. Then it starts fresh below.
        if (live != null && live.isEndless && player.queueHasAny(live.seedMediaIds) && player.resume()) return
        player.scope.launch {
            val seeds = try {
                withContext(Dispatchers.Default) { mixes.endlessSeeds(clock.nowMs()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "station: picking the seeds of Endless Listening failed")
                emptyList()
            }
            withContext(Dispatchers.Main.immediate) {
                if (seeds.isEmpty()) {
                    player.toast(context.getString(R.string.endless_nothing_yet))
                } else {
                    val station = Station.endless(seeds.map { it.song.id }, context.getString(R.string.endless_title), clock.nowMs())
                    start(station, seeds[0].song, player)
                }
            }
        }
    }

    private fun startFromSource(source: Int, name: String, songs: List<Song>, maxSeeds: Int, player: Player) {
        if (songs.isEmpty() || name.isBlank()) return
        player.scope.launch {
            val seeds = try {
                withContext(Dispatchers.Default) {
                    val ids = songs.mapTo(HashSet(songs.size * 2)) { it.id }
                    val plays = HashMap<String, Int>()
                    runCatching { for (row in engagementDao.getAllEngagements()) if (row.songId in ids) plays[row.songId] = row.playCount }
                    StationSeeds.pick(songs, plays, maxSeeds)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "station: picking the seeds failed")
                emptyList()
            }
            withContext(Dispatchers.Main.immediate) {
                if (seeds.isEmpty()) {
                    player.toast(context.getString(R.string.brain_station_nothing_found, name))
                } else {
                    val station = Station.fromSource(source, seeds.map { it.id }, name, clock.nowMs())
                    start(station, seeds[0], player, sourceIds = songs.mapTo(HashSet(songs.size * 2)) { it.id })
                }
            }
        }
    }

    private fun start(station: Station, seed: Song, player: Player, sourceIds: Set<String> = emptySet()) {
        // A Cast device plays a queue loaded once, of files this phone serves: the picks a station
        // lives on cannot reach it (docs/brain-spec.md, 14.6). Say so instead of starting one song.
        if (castStateHolder.castSession.value != null) {
            player.toast(context.getString(R.string.brain_station_off_while_casting))
            return
        }
        stations.start(station, sourceIds)
        if (player.currentSongId() == seed.id) {
            // The seed is already sounding: it keeps playing, and what was queued behind it goes.
            player.clearUpcoming()
            return
        }
        val queueName = if (station.isEndless) station.title else context.getString(R.string.brain_station_queue_name, station.title)
        playTags.expect(
            queueName = queueName,
            default = BrainItemTag(surface = Surface.nameOf(Surface.STATION), surfaceId = station.id),
            startsStation = true,
        )
        player.playSeed(seed, queueName)
    }

    /** The most-played library track whose primary artist is [artistName]; failing that, one that credits them. */
    private suspend fun seedFor(artistName: String, knownSongs: List<Song>): Song? {
        val key = BrainText.fold(artistName)
        if (key.isEmpty()) return null
        val songs = knownSongs.ifEmpty { musicRepository.getAllSongsOnce() }
        val primary = ArrayList<Song>()
        val credited = ArrayList<Song>()
        for (song in songs) {
            if (BrainText.fold(LibraryIndex.primaryArtistName(song)) == key) primary.add(song)
            else if (song.artists.any { BrainText.fold(it.name) == key }) credited.add(song)
        }
        val candidates = primary.ifEmpty { credited }
        if (candidates.isEmpty()) return null
        val ids = candidates.mapTo(HashSet(candidates.size * 2)) { it.id }
        val plays = HashMap<String, Int>()
        runCatching {
            for (row in engagementDao.getAllEngagements()) if (row.songId in ids) plays[row.songId] = row.playCount
        }
        var best = candidates[0]
        var bestPlays = plays[best.id] ?: 0
        for (i in 1 until candidates.size) {
            val count = plays[candidates[i].id] ?: 0
            if (count > bestPlays) {
                best = candidates[i]
                bestPlays = count
            }
        }
        return best
    }
}
