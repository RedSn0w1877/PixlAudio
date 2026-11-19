package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.HomePlanner
import com.theveloper.pixelplay.brain.Islands
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.brain.store.TrackDnaEntity
import io.mockk.coEvery
import io.mockk.mockk
import java.io.File
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stage 2b, milestone 2, the parts that live in the app: the saved Home plan (a file under 50 KB), the made-for-you list ids, the
 * stored islands, genre stations (unknown genres stay absent) and the legacy surfaces that stayed as they were.
 */
class Stage2bMilestone2Test {

    // ---- the Home plan -------------------------------------------------------------------------

    private fun novelItem(i: Int, shelf: Int) = PlanItem(
        rid = 1_000_000L * shelf + i, aid = 77L + i % 5,
        row = StoredRadioRow(mixRow(shelf * 100 + i, "Some Artist ${i % 5}", title = "A fairly long song title number $i of shelf $shelf"), 1_000_000L * shelf + i),
        arm = Arm.RADIO, whyKey = WhyLine.RADIO_FANS, whyName = "Some Artist", slot = i,
    )

    @Test
    fun `a full plan of seven shelves of twelve outside tracks stays under 50 KB and reads back the same`() {
        val shelves = (0 until 7).map { s ->
            PlanShelf(
                type = HomePlanner.BECAUSE_LISTENED, id = "because_$s", titleKey = "because_listened", titleArg = "A song title",
                subtitleKey = "because_listened_subtitle", items = (0 until 12).map { novelItem(it, s) }, recId = 5L,
            )
        }
        val mixes = (1..6).map { PlanMix("daily:$it", MixKinds.DAILY, it, "Soft pop & R&B · Artist A, Artist B", 30, listOf("content://a", "content://b", "content://c"), 1L) }
        val plan = HomePlan(generatedAtMs = 1_760_000_000_000L, planKey = 20_000L, taste = "3:1", hero = PlanHero(HomePlanner.HERO_DAILY, "daily:1"), mixes = mixes, shelves = shelves)
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val text = json.encodeToString(HomePlan.serializer(), plan)
        assertTrue(text.length < HomePlanStore.MAX_BYTES, "plan is ${text.length} bytes")
        assertEquals(plan, json.decodeFromString(HomePlan.serializer(), text))
    }

    @Test
    fun `a plan written by a later version with more fields still reads`() {
        val json = Json { ignoreUnknownKeys = true }
        val text = """{"planVersion":1,"generatedAtMs":5,"planKey":9,"someNewField":[1,2],"shelves":[]}"""
        val plan = json.decodeFromString(HomePlan.serializer(), text)
        assertEquals(9L, plan.planKey)
        assertTrue(plan.shelves.isEmpty())
    }

    // ---- the lists --------------------------------------------------------------------------

    @Test
    fun `every list has its own surface id, the ids round trip, and the ones stage 2a wrote are the same`() {
        val specs = listOf(
            MixSpec(MixKinds.DAILY), MixSpec(MixKinds.DAILY, 3), MixSpec(MixKinds.YOUR), MixSpec(MixKinds.FIRST_LISTEN),
            MixSpec(MixKinds.JUST_DROPPED), MixSpec(MixKinds.ON_REPEAT), MixSpec(MixKinds.REDISCOVER),
        )
        assertEquals(specs.size, specs.map { it.surfaceId }.toSet().size)
        for (s in specs) assertEquals(s, MixSpec.of(s.surfaceId))
        assertEquals("daily", MixSpec(MixKinds.DAILY).surfaceId)
        assertEquals("your", MixSpec(MixKinds.YOUR).surfaceId)
        assertEquals(Surface.DAILY_MIX, MixSpec(MixKinds.DAILY, 2).surface)
        assertEquals(Surface.YOUR_MIX, MixSpec(MixKinds.YOUR).surface)
        assertEquals(Surface.NAMED_MIX, MixSpec(MixKinds.FIRST_LISTEN).surface)
        assertNull(MixSpec.of("daily:x"))
        assertNull(MixSpec.of("nothing"))
    }

    @Test
    fun `a mix saved by stage 2a still reads and a new one keeps its names`() {
        val old = """{"kind":0,"builtAtMs":5,"seed":9,"items":[{"rid":4,"songId":"s1"}]}"""
        val decoded = MixCodec.decode(java.io.ByteArrayOutputStream().also { out ->
            java.util.zip.GZIPOutputStream(out).use { it.write(old.toByteArray()) }
        }.toByteArray())
        assertNotNull(decoded)
        assertEquals("", decoded!!.mixId)
        assertEquals(0, decoded.number)
        val mix = StoredMix(MixKinds.DAILY, 1L, 2L, items = listOf(StoredMixItem(1L, songId = "a")), mixId = "daily:2", islandId = 2, number = 2, subtitle = "Pop · Someone")
        val back = MixCodec.decode(MixCodec.encode(mix))!!
        assertEquals("daily:2", back.mixId)
        assertEquals(2, back.number)
        assertEquals("Pop · Someone", back.subtitle)
    }

    @Test
    fun `First Listen is fixed from Monday 04 00 to the next Monday`() {
        // 2025-10-13 is a Monday. A time just after 04:00 local and one on Sunday evening belong to weeks a day-and-a-bit apart.
        val monday4 = 1_760_328_000_000L // 2025-10-13T04:00:00Z
        val week = ServedListStore.weekStart(monday4 + 3_600_000L, 0)
        assertEquals(monday4, week)
        assertEquals(week, ServedListStore.weekStart(monday4 + 6L * 86_400_000L + 20L * 3_600_000L, 0))
        assertEquals(week + 7L * 86_400_000L, ServedListStore.weekStart(monday4 + 7L * 86_400_000L + 3_600_000L, 0))
        // Before 04:00 on Monday it is still last week's.
        assertEquals(week - 7L * 86_400_000L, ServedListStore.weekStart(monday4 - 3_600_000L, 0))
    }

    // ---- islands as stored -----------------------------------------------------------------

    @Test
    fun `an island survives the database round trip bit for bit`() {
        val centroid = DoubleArray(Islands.DIM) { (it - 40) / 97.0 }
        val island = Islands.Island(
            id = 4, centroid = centroid, medoidRid = 99L, topTags = intArrayOf(5, 6, 7), liftTags = intArrayOf(9), topArtists = longArrayOf(11L, 12L),
            weight = 12.5, recentWeight = 3.25, size = 31,
        )
        val back = IslandStore.islandOf(IslandStore.entityOf(island, 1L))
        assertEquals(island.id, back.id)
        assertEquals(island.medoidRid, back.medoidRid)
        assertTrue(island.centroid.contentEquals(back.centroid))
        assertEquals(island.topTags.toList(), back.topTags.toList())
        assertEquals(island.liftTags.toList(), back.liftTags.toList())
        assertEquals(island.topArtists.toList(), back.topArtists.toList())
        assertEquals(BrainMath.toQ(12.5), BrainMath.toQ(back.weight))
        assertEquals(31, back.size)
    }

    @Test
    fun `the daily islands are the six biggest in ascending id`() {
        fun island(id: Int, w: Double) = Islands.Island(id, DoubleArray(Islands.DIM), 1L, IntArray(0), IntArray(0), LongArray(0), w, 0.0, 10)
        val set = IslandSet((1..9).map { island(it, (10 - it).toDouble()) }, 1L, emptyMap())
        assertEquals(listOf(1, 2, 3, 4, 5, 6), set.dailyIslands().map { it.id })
        val shuffled = IslandSet(listOf(island(7, 9.0), island(2, 8.0), island(5, 10.0)), 1L, emptyMap())
        assertEquals(listOf(2, 5, 7), shuffled.dailyIslands().map { it.id })
        assertTrue(shuffled.hasMany)
        assertFalse(IslandSet(listOf(island(1, 1.0)), 1L, emptyMap()).hasMany)
    }

    // ---- genre stations --------------------------------------------------------------------

    private val atlas by lazy {
        val gz = File("../brain/../app/src/main/assets/genre-atlas-v1.json.gz").takeIf { it.isFile }
            ?: File("src/main/assets/genre-atlas-v1.json.gz")
        GenreAtlas.parse(String(GZIPInputStream(gz.inputStream()).readBytes(), Charsets.UTF_8))
    }

    private fun dnaRow(rid: Long, tags: Tags.Vector, moods: ByteArray = ByteArray(0)) = TrackDnaEntity(
        rid = rid, spotifyId = null, isrc = null, deezerId = null, mbRecordingId = null, ytAudioIds = "", artistIds = "", albumKey = null,
        title = "t", artistDisplay = "a", versionFlags = 0, firstYear = 0, yearSource = 0, durationMs = 0, explicit = -1, popPermille = -1,
        tempoBpmX10 = 0, tempoSource = 0, gainDbX10 = -32768, scriptLang = 0, tags = Tags.encode(tags), moods = moods, energy = -1, valence = -1,
        embVersion = 0, confidence = 0, fetchedMask = 0, updatedAtMs = 0, tagsAtMs = 0, popAtMs = 0, atlasVersion = atlas.version,
        tagInputs = ByteArray(0), yearClaims = "", popClaims = "",
    )

    @Test
    fun `a genre station is seeded by the listener's own songs under the genre and a song with no genre is under none`() = runTest {
        val rock = atlas.idForName("rock")
        val shoegaze = atlas.idForName("shoegaze")
        assertTrue(rock != GenreAtlas.NONE && shoegaze != GenreAtlas.NONE)
        val songs = (0 until 12).map { mixSong("s$it", "Song $it", "Artist $it") }
        val tracks = songs.mapIndexed { i, s -> LibraryTrack(s, 100L + i, 500L + i, liked = false) }
        val dna = tracks.mapIndexed { i, t ->
            // Ten songs are rock, two have no genre at all.
            dnaRow(t.rid, if (i < 10) Tags.Vector(listOf(Tags.Tag(rock, 200))) else Tags.Vector.EMPTY)
        }
        val libraryRids = mockk<LibraryRids> { coEvery { all(any()) } returns tracks }
        val dao = mockk<TrackDnaDao> { coEvery { getAll(any()) } returns dna }
        val provider = mockk<AtlasProvider> { coEvery { atlas() } returns atlas }
        val brain = mockk<BrainEngine>(relaxed = true)
        val islandStore = mockk<IslandStore> { coEvery { embeddingCount() } returns 0 }
        // The listener's plays are not needed for this: the order falls back to the recording id.
        coEvery { brain.withState<Map<Long, Double>>(any()) } returns emptyMap()
        val stations = GenreStations(libraryRids, dao, provider, brain, islandStore)
        val under = stations.songsFor(StationCard.KIND_GENRE, rock, 1L)
        assertEquals(10, under.size)
        assertTrue(under.none { it.id == "s10" || it.id == "s11" }, "a song with no genre is under no genre")
        assertTrue(stations.songsFor(StationCard.KIND_GENRE, shoegaze, 1L).isEmpty(), "none of the listener's songs: nothing starts")
        // A card needs at least eight songs under a specific genre; a family root is not a station.
        val cards = stations.cards(1L, IntArray(0))
        assertTrue(cards.all { it.name.isNotBlank() && it.count >= GenreStations.MIN_SONGS })
        // A name the atlas lacks gives no tile and no id.
        assertEquals(GenreAtlas.NONE, stations.idForName("Zxqvy nonsense"))
        assertEquals(setOf("Rock"), stations.knownNames(listOf("Rock", "Zxqvy nonsense")))
    }

    @Test
    fun `a genre or mood station keeps its source through the saved queue`() {
        val genre = Station.fromSource(Station.SOURCE_GENRE, listOf("a", "b"), "Shoegaze", 5L)
        val mood = Station.fromSource(Station.SOURCE_MOOD, listOf("a"), "Chill", 5L)
        assertEquals(Station.SOURCE_GENRE, Station.decode(genre.encode())!!.source)
        assertEquals(Station.SOURCE_MOOD, Station.decode(mood.encode())!!.source)
        assertTrue(genre.id != mood.id)
    }
}
