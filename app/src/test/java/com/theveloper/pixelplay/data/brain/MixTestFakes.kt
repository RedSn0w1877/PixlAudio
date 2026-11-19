package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.FetchedRadio
import com.theveloper.pixelplay.data.brain.source.RadioSource
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.GraphEdgeDao
import com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity
import com.theveloper.pixelplay.data.brain.store.PlayableEntity
import com.theveloper.pixelplay.data.brain.store.RadioAgeRow
import com.theveloper.pixelplay.data.brain.store.RadioListDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.RecentTrackRow
import com.theveloper.pixelplay.data.brain.store.SeedDao
import com.theveloper.pixelplay.data.brain.store.ServedListDao
import com.theveloper.pixelplay.data.brain.store.ServedListEntity
import com.theveloper.pixelplay.data.model.ArtistRef
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.youtube.ArtistPage
import com.theveloper.pixelplay.data.youtube.MusicVideoKind
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioEndpoint
import com.theveloper.pixelplay.data.youtube.RadioRow
import com.theveloper.pixelplay.data.youtube.RelatedPage

/** The stage 2a tables of `brain.db` as plain maps, and the pieces the mix tests share. */
class FakeRadioListDao : RadioListDao {
    val rows = LinkedHashMap<Triple<Long, Int, String>, RadioListEntity>()
    override suspend fun get(seedRid: Long, kind: Int, chip: String) = rows[Triple(seedRid, kind, chip)]
    override suspend fun upsert(row: RadioListEntity) { rows[Triple(row.seedRid, row.kind, row.chip)] = row }
    override suspend fun touch(seedRid: Long, kind: Int, chip: String, nowMs: Long) {
        val key = Triple(seedRid, kind, chip)
        rows[key]?.let { rows[key] = RadioListEntity(it.seedRid, it.kind, it.chip, it.fetchedAt, nowMs, it.healthy, it.rowsBlob, it.chipsBlob) }
    }
    override suspend fun count() = rows.size
    override suspend fun trimTo(keep: Int) {
        val doomed = rows.values.sortedByDescending { it.lastUsedAt }.drop(keep)
        for (row in doomed) rows.remove(Triple(row.seedRid, row.kind, row.chip))
    }
    override suspend fun fetchedSince(sinceMs: Long) = rows.values.count { it.fetchedAt >= sinceMs }
}

class FakeGraphEdgeDao : GraphEdgeDao {
    val rows = LinkedHashMap<Triple<Long, Long, Int>, GraphEdgeEntity>()
    override suspend fun upsert(rows: List<GraphEdgeEntity>) { for (r in rows) this.rows[Triple(r.src, r.dst, r.type)] = r }
    override suspend fun deleteFrom(src: Long, type: Int) {
        rows.keys.filter { it.first == src && it.third == type }.forEach(rows::remove)
    }
    override suspend fun deleteStale(src: Long, type: Int, before: Long) {
        rows.entries.filter { it.key.first == src && it.key.third == type && it.value.fetchedAt < before }.map { it.key }.forEach(rows::remove)
    }
    override suspend fun touching(nodes: List<Long>) = rows.values.filter { it.src in nodes || it.dst in nodes }
    override suspend fun deleteOlderThan(before: Long): Int {
        val doomed = rows.entries.filter { it.value.fetchedAt < before }.map { it.key }
        doomed.forEach(rows::remove)
        return doomed.size
    }
    override suspend fun count() = rows.size
    override suspend fun all() = rows.values.toList()
}

class FakeServedListDao : ServedListDao {
    val rows = LinkedHashMap<Long, ServedListEntity>()
    override suspend fun upsert(row: ServedListEntity) { rows[row.recId] = row }
    override suspend fun since(since: Long) = rows.values.filter { it.builtAt >= since }.sortedBy { it.builtAt }
    override suspend fun latest(surface: Int, since: Long) =
        rows.values.filter { it.surface == surface && it.builtAt >= since }.maxByOrNull { it.builtAt }
    override suspend fun countSince(surface: Int, since: Long) = rows.values.count { it.surface == surface && it.builtAt >= since }
    override suspend fun latestById(surface: Int, surfaceId: String, since: Long) =
        rows.values.filter { it.surface == surface && it.surfaceId == surfaceId && it.builtAt >= since }.maxByOrNull { it.builtAt }
    override suspend fun countSinceById(surface: Int, surfaceId: String, since: Long) =
        rows.values.count { it.surface == surface && it.surfaceId == surfaceId && it.builtAt >= since }
    override suspend fun deleteOlderThan(before: Long): Int {
        val doomed = rows.values.filter { it.builtAt < before }.map { it.recId }
        doomed.forEach(rows::remove)
        return doomed.size
    }
}

class FakeBrainStateDao : BrainStateDao {
    val values = LinkedHashMap<String, ByteArray>()
    override suspend fun get(key: String) = values[key]
    override suspend fun put(row: BrainStateEntity) { values[row.key] = row.blob }
    override fun getBlocking(key: String) = values[key]
    override fun putBlocking(row: BrainStateEntity) { values[row.key] = row.blob }
    override fun deleteBlocking(key: String) { values.remove(key) }
}

/** What the seed pick reads, set by the test. */
class FakeSeedDao(private val radioLists: FakeRadioListDao? = null) : SeedDao {
    var recent: List<RecentTrackRow> = emptyList()
    var saved: List<RecentTrackRow> = emptyList()
    var artists: List<RecentTrackRow> = emptyList()
    var playables: List<PlayableEntity> = emptyList()
    override suspend fun recentChosen(since: Long, limit: Int) = recent.filter { it.lastMs >= since }.take(limit)
    override suspend fun recentlySaved(since: Long, limit: Int) = saved.filter { it.lastMs >= since }.take(limit)
    override suspend fun artistsOf(rids: List<Long>) = artists.filter { it.rid in rids }
    override suspend fun cachedLists(rids: List<Long>): List<RadioAgeRow> =
        radioLists?.rows?.values?.filter { it.healthy && it.seedRid in rids }?.map { RadioAgeRow(it.seedRid, it.fetchedAt) }.orEmpty()
    override suspend fun playableByRid(rids: List<Long>) = playables.filter { it.rid in rids }
    override suspend fun listsOf(rids: List<Long>) = radioLists?.rows?.values?.filter { it.healthy && it.seedRid in rids }.orEmpty()
    override suspend fun healthyPage(limit: Int, offset: Int): List<RadioListEntity> =
        radioLists?.rows?.values?.filter { it.healthy }?.sortedWith(compareBy({ it.seedRid }, { it.kind }, { it.chip }))
            ?.drop(offset)?.take(limit).orEmpty()
    override suspend fun fetchedSince(since: Long) = radioLists?.rows?.values?.count { it.fetchedAt >= since } ?: 0
    override suspend fun qualifiedPlaysSince(since: Long) = 0
    override suspend fun healthyListCount() = radioLists?.rows?.values?.count { it.healthy } ?: 0
    override suspend fun recentCompleted(since: Long, limit: Int) = recent.filter { it.lastMs >= since }.take(limit)
}

class FakeRadioSource : RadioSource {
    val songRadios = HashMap<String, FetchedRadio?>()
    val artistPages = HashMap<String, ArtistPage?>()
    val artistRadios = HashMap<String, FetchedRadio?>()
    val calls = ArrayList<String>()
    override suspend fun songRadio(videoId: String, continuations: Int): FetchedRadio? {
        calls.add("song:$videoId")
        return songRadios[videoId]
    }
    override suspend fun related(browseId: String): RelatedPage? = null
    override suspend fun artistPage(channelId: String): ArtistPage? {
        calls.add("artist:$channelId")
        return artistPages[channelId]
    }
    override suspend fun artistRadio(endpoint: RadioEndpoint): FetchedRadio? {
        calls.add("artistRadio:${endpoint.playlistId}")
        return artistRadios[endpoint.playlistId]
    }

    val discographies = HashMap<String, List<com.theveloper.pixelplay.data.youtube.ReleaseRef>?>()
    val releaseRows = HashMap<String, List<RadioRow>?>()
    val channels = HashMap<String, String?>()
    override suspend fun discography(channelId: String): List<com.theveloper.pixelplay.data.youtube.ReleaseRef>? {
        calls.add("discography:$channelId")
        return discographies[channelId]
    }
    override suspend fun releaseTracks(release: com.theveloper.pixelplay.data.youtube.ReleaseRef): List<RadioRow>? {
        calls.add("release:${release.browseId}")
        return releaseRows[release.browseId]
    }
    override suspend fun channelIdFor(artistName: String): String? {
        calls.add("channel:$artistName")
        return channels[artistName]
    }
}

/** A library song with its split artist list, as the library has it. */
fun mixSong(id: String, title: String, artist: String, durationMs: Long = 200_000L, album: String = "Album", spotifyId: String? = null, favorite: Boolean = false) = Song(
    id = id, title = title, artist = artist, artistId = 1L,
    artists = listOf(ArtistRef(id = 1L, name = artist, isPrimary = true)),
    album = album, albumId = 1L, path = "", contentUriString = "content://media/$id", albumArtUriString = null,
    duration = durationMs, mimeType = null, bitrate = null, sampleRate = null, spotifyId = spotifyId, isFavorite = favorite,
)

/** An official-audio radio row of [artist]. */
fun mixRow(i: Int, artist: String, title: String = "Song $i", seconds: Int = 200, official: Boolean = true) = RadioRow(
    videoId = "vid%07d".format(i), title = title, artists = listOf(RadioArtistRef(artist, "UC$artist")),
    album = "Album $artist", durationSec = seconds, kind = if (official) MusicVideoKind.ATV else MusicVideoKind.OMV,
)
