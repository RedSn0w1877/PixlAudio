package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stations (docs/brain-spec.md, 8.3): what a station is, how it survives a restart, when a play the
 * UI dispatches ends it, and what the Familiar / Blend / Discover switch multiplies.
 */
class StationAndPlayTagsTest {

    private var now = 1_760_000_000_000L
    private val clock = BrainClock { now }

    private fun preferences(station: String? = null, novelty: Int = 1): UserPreferencesRepository =
        mockk(relaxed = true) {
            every { brainStationFlow } returns flowOf(station)
            every { brainNoveltyFlow } returns flowOf(novelty)
        }

    private fun store(prefs: UserPreferencesRepository = preferences()) =
        StationStore(prefs, CoroutineScope(Dispatchers.Unconfined))

    @Test
    fun `a station survives being written down and read back`() {
        val song = Station.song("-3000000000123", "Espresso", "Sabrina Carpenter", 42L)
        assertEquals(song, Station.decode(song.encode()))
        assertEquals(Session.SEED_STATION_SONG, song.kind)
        assertEquals("Espresso", song.title)

        val artist = Station.artist("-3000000000123", "Sơn Tùng M-TP", 43L)
        assertEquals(artist, Station.decode(artist.encode()))
        assertTrue(artist.isArtist)
        assertEquals("Sơn Tùng M-TP", artist.title)

        // The same seed is a different station as a song and as an artist, and the id is not the seed's id.
        assertNotEquals(song.id, artist.id)
        assertTrue(song.id.length <= BrainExtras.MAX_SURFACE_ID_LENGTH)
        assertTrue("-3000000000123" !in song.id)
    }

    @Test
    fun `nonsense is not a station`() {
        assertNull(Station.decode(null))
        assertNull(Station.decode(""))
        assertNull(Station.decode("2"))
        assertNull(Station.decode("9\u001fseed\u001ft\u001fa\u001f1"))
        assertNull(Station.decode("2\u001f\u001ft\u001fa\u001f1"))
    }

    @Test
    fun `the stored station is the station after a restart`() {
        val saved = Station.song("seed", "Ditto", "NewJeans", 7L)
        val stations = store(preferences(station = saved.encode(), novelty = Novelty.DISCOVER))
        assertEquals(saved, stations.current.value)
        assertEquals(Novelty.DISCOVER, stations.novelty.value)
    }

    @Test
    fun `starting and ending a station is written to the preferences`() {
        val prefs = preferences()
        val stations = store(prefs)
        val station = Station.song("seed", "Ditto", "NewJeans", 7L)
        stations.start(station)
        assertEquals(station, stations.current.value)
        coVerify { prefs.setBrainStation(station.encode()) }
        stations.clear()
        assertNull(stations.current.value)
        coVerify { prefs.setBrainStation(null) }
    }

    @Test
    fun `the host chips change the level for this listening only and the saved level comes back`() {
        val prefs = preferences(novelty = Novelty.BLEND)
        val stations = store(prefs)
        stations.start(Station.endless(listOf("a", "b"), "Endless Listening", 1L))
        stations.setNoveltyForHost(Novelty.DISCOVER)
        assertEquals(Novelty.DISCOVER, stations.novelty.value)
        // Back to familiar returns to Blend (the level before), never to a harder Familiar the listener did not choose.
        assertEquals(Novelty.BLEND, stations.levelBeforeHost())
        stations.setNoveltyForHost(stations.levelBeforeHost())
        assertEquals(Novelty.BLEND, stations.novelty.value)
        stations.setNoveltyForHost(Novelty.DISCOVER)
        coVerify(exactly = 0) { prefs.setBrainNovelty(any()) }
        stations.clear()
        assertEquals(Novelty.BLEND, stations.novelty.value)
    }

    @Test
    fun `a switch the listener moves themselves is not put back`() {
        val stations = store(preferences(novelty = Novelty.BLEND))
        stations.setNoveltyForHost(Novelty.DISCOVER)
        stations.setNovelty(Novelty.FAMILIAR)
        stations.clear()
        assertEquals(Novelty.FAMILIAR, stations.novelty.value)
    }

    @Test
    fun `the novelty switch is 0_4, 1 and 2, and anything else is Blend`() {
        assertEquals(0.4, Novelty.factor(Novelty.FAMILIAR))
        assertEquals(1.0, Novelty.factor(Novelty.BLEND))
        assertEquals(2.0, Novelty.factor(Novelty.DISCOVER))
        assertEquals(Novelty.BLEND, Novelty.clamp(7))
        assertEquals(Novelty.BLEND, Novelty.clamp(-1))

        val prefs = preferences()
        val stations = store(prefs)
        stations.setNovelty(Novelty.FAMILIAR)
        assertEquals(Novelty.FAMILIAR, stations.novelty.value)
        coVerify { prefs.setBrainNovelty(Novelty.FAMILIAR) }
    }

    @Test
    fun `a station's own start keeps it and carries its surface`() {
        val stations = store()
        val tags = BrainPlayTags(stations, clock)
        val station = Station.song("seed", "Ditto", "NewJeans", now)
        stations.start(station)
        tags.expect(
            queueName = "Station · Ditto",
            default = BrainItemTag(surface = Surface.nameOf(Surface.STATION), surfaceId = station.id),
            startsStation = true,
        )
        val taken = tags.take("Station · Ditto")
        assertNotNull(taken)
        assertEquals("station", taken!!.tagFor("seed")?.surface)
        assertEquals(station.id, taken.tagFor("seed")?.surfaceId)
        assertEquals(station, stations.current.value)
        // It is taken once.
        assertNull(tags.take("Station · Ditto"))
    }

    @Test
    fun `any other play ends the station`() {
        val stations = store()
        val tags = BrainPlayTags(stations, clock)
        stations.start(Station.song("seed", "Ditto", "NewJeans", now))
        assertNull(tags.take("Liked Songs"))
        assertNull(stations.current.value)
    }

    @Test
    fun `a Home row's tags reach its play and end a station`() {
        val stations = store()
        val tags = BrainPlayTags(stations, clock)
        stations.start(Station.song("seed", "Ditto", "NewJeans", now))
        val card = BrainItemTag(surface = "homeShelf", surfaceId = "because_1f", recId = 5L, slot = 0, arm = "RADIO", chosen = true)
        tags.expect(queueName = "Because you listened to Ditto", bySongId = mapOf("card" to card))
        val taken = tags.take("Because you listened to Ditto")
        assertEquals(card, taken?.tagFor("card"))
        assertNull(taken?.tagFor("something else"))
        assertNull(stations.current.value)
    }

    @Test
    fun `what was announced for another queue, or too long ago, is not used`() {
        val stations = store()
        val tags = BrainPlayTags(stations, clock)
        tags.expect(queueName = "A", default = BrainItemTag(surface = "station"), startsStation = true)
        assertNull(tags.take("B"))

        tags.expect(queueName = "A", default = BrainItemTag(surface = "station"), startsStation = true)
        now += 16_000L
        assertNull(tags.take("A"))
    }
}
