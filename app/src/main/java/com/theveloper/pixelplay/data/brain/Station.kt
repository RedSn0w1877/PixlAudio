package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.Session
import com.theveloper.pixelplay.data.preferences.UserPreferencesRepository
import com.theveloper.pixelplay.di.AppScope
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * A station (docs/brain-spec.md, 8.3): the listener asked for music like one song or one artist. The
 * queue itself holds the tracks; this is only what the queue is about. It stays the queue's station
 * for as long as its seed track is in the queue and the listener has not started something else.
 */
data class Station(
    /** The `surfaceId` its plays are logged under: a hash, never the seed's own id. */
    val id: String,
    /** `Session.SEED_STATION_SONG` or `Session.SEED_STATION_ARTIST`. */
    val kind: Int,
    /** The media id of the track the station starts with. */
    val seedMediaId: String,
    /** What the queue header names after "Station": the song's title, or the artist's name. */
    val title: String,
    /** ONE artist name: the artist of an artist station, the seed's primary artist of a song station. */
    val artistName: String,
    val startedAtMs: Long,
    /** What the listener started it from: one of the `SOURCE_*` of the companion. */
    val source: Int = if (kind == Session.SEED_STATION_ARTIST) SOURCE_ARTIST else SOURCE_SONG,
    /**
     * The media ids of every seed, [seedMediaId] first: one for a song or artist station, up to five for an
     * album or playlist station (6.2). They are library tracks; the radios of all of them are merged.
     */
    val seedMediaIds: List<String> = listOf(seedMediaId),
) {
    val isArtist: Boolean get() = kind == Session.SEED_STATION_ARTIST

    fun encode(): String =
        listOf(
            kind.toString(), seedMediaId, clean(title), clean(artistName), startedAtMs.toString(), source.toString(),
            seedMediaIds.joinToString(IDS_SEPARATOR) { clean(it) },
        ).joinToString(SEPARATOR)

    companion object {
        private const val SEPARATOR = "\u001f"
        private const val IDS_SEPARATOR = "\u001e"

        const val SOURCE_SONG = 0
        const val SOURCE_ARTIST = 1
        const val SOURCE_ALBUM = 2
        const val SOURCE_PLAYLIST = 3

        /** A station from an album ([SOURCE_ALBUM]) or a playlist ([SOURCE_PLAYLIST]): [seedMediaIds] are its seeds, best first. */
        fun fromSource(source: Int, seedMediaIds: List<String>, title: String, nowMs: Long): Station {
            val first = seedMediaIds.first()
            return Station(
                id = idOf(Session.SEED_STATION_SONG, first, source), kind = Session.SEED_STATION_SONG, seedMediaId = first,
                title = title, artistName = "", startedAtMs = nowMs, source = source, seedMediaIds = seedMediaIds,
            )
        }

        fun song(seedMediaId: String, title: String, primaryArtist: String, nowMs: Long): Station =
            Station(idOf(Session.SEED_STATION_SONG, seedMediaId), Session.SEED_STATION_SONG, seedMediaId, title, primaryArtist, nowMs)

        fun artist(seedMediaId: String, artistName: String, nowMs: Long): Station =
            Station(idOf(Session.SEED_STATION_ARTIST, seedMediaId), Session.SEED_STATION_ARTIST, seedMediaId, artistName, artistName, nowMs)

        fun idOf(kind: Int, seedMediaId: String, source: Int = -1): String =
            java.lang.Long.toHexString(
                BrainHash.fnv1a64(
                    // A song or artist station's id is what it always was; an album or playlist one adds where it came from.
                    if (source == SOURCE_ALBUM || source == SOURCE_PLAYLIST) "station|$kind|$seedMediaId|$source" else "station|$kind|$seedMediaId"
                )
            )

        fun decode(text: String?): Station? {
            if (text.isNullOrEmpty()) return null
            val parts = text.split(SEPARATOR)
            // Five parts: a station saved before album and playlist stations existed.
            if (parts.size != 5 && parts.size != 7) return null
            val kind = parts[0].toIntOrNull() ?: return null
            if (kind != Session.SEED_STATION_SONG && kind != Session.SEED_STATION_ARTIST) return null
            val seed = parts[1].takeIf { it.isNotEmpty() } ?: return null
            val startedAt = parts[4].toLongOrNull() ?: return null
            if (parts.size == 5) return Station(idOf(kind, seed), kind, seed, parts[2], parts[3], startedAt)
            val source = parts[5].toIntOrNull()?.takeIf { it in SOURCE_SONG..SOURCE_PLAYLIST } ?: return null
            val ids = parts[6].split(IDS_SEPARATOR).filter { it.isNotEmpty() }.ifEmpty { listOf(seed) }
            return Station(idOf(kind, seed, source), kind, seed, parts[2], parts[3], startedAt, source, ids)
        }

        private fun clean(text: String): String = text.replace(SEPARATOR, " ")
    }
}

/** The listener's Familiar / Blend / Discover switch (8.3, 5.10): how much of the engine's picks is new music. */
object Novelty {
    const val FAMILIAR = 0
    const val BLEND = 1
    const val DISCOVER = 2

    fun clamp(level: Int): Int = if (level < FAMILIAR || level > DISCOVER) BLEND else level

    /** What the switch multiplies the surface's novelty share by. */
    fun factor(level: Int): Double = when (level) {
        FAMILIAR -> 0.4
        DISCOVER -> 2.0
        else -> 1.0
    }
}

/**
 * The station of the current queue and the novelty switch, in memory and in the preferences, so a
 * restored queue is still a station after a restart. Read lazily from the preferences the app already
 * has open; nothing here touches the network or the brain's database.
 */
@Singleton
class StationStore @Inject constructor(
    private val preferences: UserPreferencesRepository,
    @AppScope private val scope: CoroutineScope,
) {
    private val _current = MutableStateFlow<Station?>(null)
    val current: StateFlow<Station?> = _current.asStateFlow()

    private val _novelty = MutableStateFlow(Novelty.BLEND)
    val novelty: StateFlow<Int> = _novelty.asStateFlow()

    // Set once anything was written in this process: a late first read must not undo it.
    @Volatile private var stationTouched = false
    @Volatile private var noveltyTouched = false

    init {
        scope.launch {
            try {
                val storedStation = Station.decode(preferences.brainStationFlow.first())
                if (!stationTouched && storedStation != null) _current.value = storedStation
                val storedNovelty = Novelty.clamp(preferences.brainNoveltyFlow.first())
                if (!noveltyTouched) _novelty.value = storedNovelty
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: reading the station failed")
            }
        }
    }

    /**
     * The media ids of the source an album or playlist station came from, for the first batch (6.2: none of
     * the source's own songs lead). Memory only: after a restart the first batch is long served.
     */
    @Volatile
    var sourceIds: Set<String> = emptySet()
        private set

    fun start(station: Station, sourceIds: Set<String> = emptySet()) {
        stationTouched = true
        this.sourceIds = sourceIds
        _current.value = station
        persistStation(station)
    }

    /** The queue is no longer this station's. Safe to call when there is none. */
    fun clear() {
        if (_current.value == null && stationTouched) return
        stationTouched = true
        sourceIds = emptySet()
        _current.value = null
        persistStation(null)
    }

    fun setNovelty(level: Int) {
        val next = Novelty.clamp(level)
        noveltyTouched = true
        if (_novelty.value == next) return
        _novelty.value = next
        scope.launch {
            try {
                preferences.setBrainNovelty(next)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: saving the novelty switch failed")
            }
        }
    }

    private fun persistStation(station: Station?) {
        scope.launch {
            try {
                preferences.setBrainStation(station?.encode())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: saving the station failed")
            }
        }
    }

    companion object {
        /** A station this young is not ended for a queue that does not hold its seed yet: the play is on its way. */
        const val START_GRACE_MS = 30_000L
    }
}
