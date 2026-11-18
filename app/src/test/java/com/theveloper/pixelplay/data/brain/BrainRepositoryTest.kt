package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.data.brain.source.FetchedRadio
import com.theveloper.pixelplay.data.brain.source.HostBudget
import com.theveloper.pixelplay.data.brain.source.RadioSource
import com.theveloper.pixelplay.data.brain.store.RadioListDao
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.youtube.ArtistPage
import com.theveloper.pixelplay.data.youtube.InnerTubeMusicParsers
import com.theveloper.pixelplay.data.youtube.MusicVideoKind
import com.theveloper.pixelplay.data.youtube.RadioArtistRef
import com.theveloper.pixelplay.data.youtube.RadioEndpoint
import com.theveloper.pixelplay.data.youtube.RadioRow
import com.theveloper.pixelplay.data.youtube.RelatedPage
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The radio cache: what is fetched, what is stored, and the junk fallbacks (docs/brain-spec.md, 3.3). */
class BrainRepositoryTest {

    private class FakeRadioListDao : RadioListDao {
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

    /** Identity over the reference in-memory alias table of `:brain`. */
    private class MemoryResolver : TrackResolver {
        val table = Ids.MemoryAliasTable()
        val videoIdsByRid = HashMap<Long, MutableList<String>>()
        override suspend fun resolveTracks(requests: List<TrackRequest>): LongArray =
            LongArray(requests.size) { i ->
                val rid = table.resolveTrack(requests[i].keys, requests[i].durationMs) ?: 0L
                requests[i].keys.y?.let { videoIdsByRid.getOrPut(rid) { ArrayList() }.add(it.removePrefix("y:")) }
                rid
            }
        override suspend fun otherVideoIds(rid: Long, exceptVideoId: String?): List<String> =
            videoIdsByRid[rid].orEmpty().filter { it != exceptVideoId }.distinct()
    }

    private class FakeRadioSource : RadioSource {
        var songRadios = HashMap<String, FetchedRadio?>()
        var relatedPages = HashMap<String, RelatedPage?>()
        var artistPages = HashMap<String, ArtistPage?>()
        var artistRadios = HashMap<String, FetchedRadio?>()
        val calls = ArrayList<String>()
        override suspend fun songRadio(videoId: String, continuations: Int): FetchedRadio? {
            calls.add("song:$videoId:$continuations")
            return songRadios[videoId]
        }
        override suspend fun related(browseId: String): RelatedPage? {
            calls.add("related:$browseId")
            return relatedPages[browseId]
        }
        override suspend fun artistPage(channelId: String): ArtistPage? {
            calls.add("artist:$channelId")
            return artistPages[channelId]
        }
        override suspend fun artistRadio(endpoint: RadioEndpoint): FetchedRadio? {
            calls.add("artistRadio:${endpoint.playlistId}")
            return artistRadios[endpoint.playlistId]
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
    private var now = 1_000_000_000_000L
    private val dao = FakeRadioListDao()
    private val resolver = MemoryResolver()
    private val source = FakeRadioSource()
    private val repository = BrainRepository(dao, resolver, source, BrainClock { now })

    private fun fixtureRadio(name: String): FetchedRadio {
        val text = javaClass.classLoader!!.getResourceAsStream("innertube/$name.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val page = InnerTubeMusicParsers.parseRadio(json.parseToJsonElement(text))
        return FetchedRadio(page.rows, page.chips, page.relatedBrowseId)
    }

    private fun row(i: Int, artist: String, channel: String? = "UC$artist", title: String = "Song $i", seconds: Int = 200) = RadioRow(
        videoId = "vid%07d".format(i), title = title, artists = listOf(RadioArtistRef(artist, channel)),
        durationSec = seconds, kind = MusicVideoKind.ATV,
    )

    /** 45 rows after the seed, the seed's artist again at rows 3 and 9: a healthy radio. */
    private fun healthyRadio(seed: RadioRow): FetchedRadio = FetchedRadio(
        rows = listOf(seed) + List(45) { i ->
            if (i == 2 || i == 8) row(100 + i, seed.artists[0].name, seed.artists[0].channelId) else row(100 + i, "Other${i % 9}")
        },
        relatedBrowseId = "MPTRt_seed",
    )

    private fun seedOf(row: RadioRow, spotifyId: String? = "4uLU6hMCjMI75M1A2tKUQC") = RadioSeed(
        mediaId = "-3000000000001", videoId = row.videoId, title = row.title,
        primaryArtist = row.artists[0].name, durationMs = row.durationSec * 1000L, spotifyId = spotifyId,
    )

    @Test
    fun `a real radio is stored without its seed, every row with a recording id, and then served from the cache`() = runTest {
        val fetched = fixtureRadio("radio_song")
        val seedRow = fetched.rows[0]
        source.songRadios[seedRow.videoId] = fetched

        val first = repository.radioFor(seedOf(seedRow), allowNetwork = true)!!
        assertFalse(first.fromCache)
        assertEquals(RadioListEntity.KIND_SONG, first.kind)
        assertEquals(49, first.rows.size)
        assertTrue(first.rows.none { it.row.videoId == seedRow.videoId })
        assertTrue(first.rows.all { it.rid != 0L })
        assertEquals(first.rows.size, first.rows.map { it.rid }.toSet().size, "one row per recording")
        assertEquals(fetched.rows.drop(1).map { it.videoId }, first.rows.map { it.row.videoId }, "radio order is kept")
        assertEquals(listOf("song:${seedRow.videoId}:0"), source.calls)

        val second = repository.radioFor(seedOf(seedRow), allowNetwork = true)!!
        assertTrue(second.fromCache)
        assertEquals(first.rows, second.rows)
        assertEquals(1, source.calls.size, "no second request")
    }

    @Test
    fun `the seed is one recording whether it is asked about by its spotify id or met as a radio row`() = runTest {
        val seed = row(1, "Adele", title = "Hello")
        source.songRadios[seed.videoId] = healthyRadio(seed)
        val radio = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        // The same recording under another audio id, in someone else's radio, is the same rid.
        val viaName = resolver.table.resolveTrack(
            Ids.TrackKeys(videoId = "otherAudioId", nameKey = Ids.nameKey("ADELE", "Hello (Remastered)")), 200_500
        )
        assertEquals(radio.seedRid, viaName)
    }

    @Test
    fun `two rows that are one recording under two audio ids are stored once`() = runTest {
        val seed = row(1, "Seed")
        val healthy = healthyRadio(seed)
        val duplicate = healthy.rows[5].copy(videoId = "anotherAudio")
        source.songRadios[seed.videoId] = healthy.copy(rows = healthy.rows + duplicate)
        val radio = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        assertEquals(45, radio.rows.size)
        assertTrue(radio.rows.none { it.row.videoId == "anotherAudio" })
    }

    @Test
    fun `offline answers from the cache only, however old, and never asks the network`() = runTest {
        val seed = row(1, "Seed")
        assertNull(repository.radioFor(seedOf(seed), allowNetwork = false))
        assertTrue(source.calls.isEmpty())

        source.songRadios[seed.videoId] = healthyRadio(seed)
        repository.radioFor(seedOf(seed), allowNetwork = true)
        source.calls.clear()
        now += 90L * 24 * 60 * 60 * 1000 // far past the two-week freshness
        val stale = repository.radioFor(seedOf(seed), allowNetwork = false)
        assertNotNull(stale)
        assertTrue(stale!!.fromCache)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `a stale list is refetched when the network is allowed`() = runTest {
        val seed = row(1, "Seed")
        source.songRadios[seed.videoId] = healthyRadio(seed)
        repository.radioFor(seedOf(seed), allowNetwork = true)
        now += BrainRepository.CACHE_TTL_MS + 1
        val again = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        assertFalse(again.fromCache)
        assertEquals(2, source.calls.size)
    }

    @Test
    fun `a transport failure stores nothing and is not remembered as junk`() = runTest {
        val seed = row(1, "Seed")
        source.songRadios[seed.videoId] = null
        assertNull(repository.radioFor(seedOf(seed), allowNetwork = true))
        assertEquals(0, dao.count())
        // The next attempt asks again.
        source.songRadios[seed.videoId] = healthyRadio(seed)
        assertNotNull(repository.radioFor(seedOf(seed), allowNetwork = true))
    }

    @Test
    fun `a junk radio falls back to the related tab, minus other performances of the same song`() = runTest {
        val seed = row(1, "Seed")
        val junk = FetchedRadio(rows = listOf(seed) + List(20) { row(200 + it, "Stranger$it") }, relatedBrowseId = "MPTRt_seed")
        source.songRadios[seed.videoId] = junk
        val alsoLike = List(20) { row(300 + it, "Related$it", seconds = 0) }
        source.relatedPages["MPTRt_seed"] = RelatedPage(
            youMightAlsoLike = alsoLike,
            otherPerformances = listOf(alsoLike[0], alsoLike[1]),
            similarArtists = emptyList(),
        )

        val radio = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        assertEquals(RadioListEntity.KIND_RELATED, radio.kind)
        assertEquals(18, radio.rows.size)
        assertEquals(listOf("song:${seed.videoId}:0", "related:MPTRt_seed"), source.calls)

        // The junk marker keeps the seed from being asked again for a day; the fallback list answers.
        source.calls.clear()
        val again = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        assertTrue(again.fromCache)
        assertEquals(RadioListEntity.KIND_RELATED, again.kind)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `a radio of music videos has nothing playable - it takes the junk path to the related tab`() = runTest {
        val seed = row(1, "Seed").copy(kind = MusicVideoKind.OMV)
        // Long enough and full of the seed's artist, but every row is a music video.
        val videos = healthyRadio(seed).let { radio -> radio.copy(rows = radio.rows.map { it.copy(kind = MusicVideoKind.OMV) }) }
        source.songRadios[seed.videoId] = videos
        source.relatedPages["MPTRt_seed"] = RelatedPage(
            youMightAlsoLike = List(20) { row(300 + it, "Related$it", seconds = 0) },
            otherPerformances = emptyList(),
            similarArtists = emptyList(),
        )
        val radio = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        assertEquals(RadioListEntity.KIND_RELATED, radio.kind)
        assertTrue(radio.rows.all { it.row.isOfficialAudio })
    }

    @Test
    fun `an empty radio is junk too - remembered, and the related tab is tried`() = runTest {
        val seed = row(1, "Seed")
        source.songRadios[seed.videoId] = FetchedRadio(rows = emptyList(), relatedBrowseId = "MPTRt_seed")
        source.relatedPages["MPTRt_seed"] = RelatedPage(
            youMightAlsoLike = List(12) { row(300 + it, "Related$it", seconds = 0) },
            otherPerformances = emptyList(),
            similarArtists = emptyList(),
        )
        val radio = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        assertEquals(RadioListEntity.KIND_RELATED, radio.kind)
        assertEquals(12, radio.rows.size)
        // The empty radio left its marker: the seed is not asked again.
        source.calls.clear()
        assertTrue(repository.radioFor(seedOf(seed), allowNetwork = true)!!.fromCache)
        assertTrue(source.calls.isEmpty())
    }

    @Test
    fun `junk with no related tab falls back to the artist radio`() = runTest {
        val seed = row(1, "Seed", channel = "UCseedArtist")
        source.songRadios[seed.videoId] = FetchedRadio(rows = listOf(seed) + List(10) { row(200 + it, "Stranger$it") })
        source.artistPages["UCseedArtist"] = ArtistPage(
            name = "Seed", radio = RadioEndpoint("vidRadio", "RDEMseed", "wAEB"),
            topSongs = emptyList(), albums = emptyList(), singles = emptyList(), fansAlsoLike = emptyList(),
        )
        source.artistRadios["RDEMseed"] = FetchedRadio(
            rows = List(46) { i -> if (i % 5 == 0) row(400 + i, "Seed", "UCseedArtist") else row(400 + i, "Near${i % 6}") }
        )
        val radio = repository.radioFor(seedOf(seed), allowNetwork = true)!!
        assertEquals(RadioListEntity.KIND_ARTIST, radio.kind)
        assertEquals(listOf("song:${seed.videoId}:0", "artist:UCseedArtist", "artistRadio:RDEMseed"), source.calls)
        assertTrue(radio.rows.size >= 40)
    }

    @Test
    fun `junk with no fallback gives nothing and is not asked again for a day`() = runTest {
        val seed = row(1, "Seed", channel = null)
        source.songRadios[seed.videoId] = FetchedRadio(rows = listOf(seed) + List(10) { row(200 + it, "Stranger$it") })
        assertNull(repository.radioFor(seedOf(seed), allowNetwork = true))
        source.calls.clear()
        assertNull(repository.radioFor(seedOf(seed), allowNetwork = true))
        assertTrue(source.calls.isEmpty(), "junk is remembered")
        now += BrainRepository.JUNK_RETRY_MS + 1
        repository.radioFor(seedOf(seed), allowNetwork = true)
        assertEquals(1, source.calls.size)
    }

    @Test
    fun `no more than sixty radios are fetched in a day`() = runTest {
        repeat(HostBudget.DAILY_LIMIT) { i ->
            val seed = row(1000 + i, "Artist$i", title = "Title $i")
            source.songRadios[seed.videoId] = healthyRadio(seed)
            assertNotNull(repository.radioFor(seedOf(seed, spotifyId = null), allowNetwork = true), "seed $i")
        }
        val extra = row(5000, "Extra", title = "One too many")
        source.songRadios[extra.videoId] = healthyRadio(extra)
        source.calls.clear()
        assertNull(repository.radioFor(seedOf(extra, spotifyId = null), allowNetwork = true))
        assertTrue(source.calls.isEmpty())
        // A day later the budget is back.
        now += HostBudget.DAY_MS + 1
        assertNotNull(repository.radioFor(seedOf(extra, spotifyId = null), allowNetwork = true))
    }

    @Test
    fun `a deeper request is fetched once and then cached beside the first-page list`() = runTest {
        val seed = row(1, "Seed")
        source.songRadios[seed.videoId] = healthyRadio(seed)
        repository.radioFor(seedOf(seed), allowNetwork = true)
        assertNotNull(repository.radioFor(seedOf(seed), allowNetwork = true, continuations = 2))
        assertEquals(listOf("song:${seed.videoId}:0", "song:${seed.videoId}:2"), source.calls)
        assertTrue(repository.radioFor(seedOf(seed), allowNetwork = true, continuations = 2)!!.fromCache)
        assertEquals(2, source.calls.size)
        assertEquals(2, dao.count())
        // Offline, a first-page list still answers a request for more.
        val other = row(2, "Other", title = "Else")
        source.songRadios[other.videoId] = healthyRadio(other)
        repository.radioFor(seedOf(other, spotifyId = null), allowNetwork = true)
        assertNotNull(repository.radioFor(seedOf(other, spotifyId = null), allowNetwork = false, continuations = 2))
    }
}
