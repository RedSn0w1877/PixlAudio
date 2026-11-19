package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import timber.log.Timber

/** An artist the listener said they love at the start (docs/brain-spec.md, 8.10), with the track their artist station starts from. */
@Serializable
data class PickedArtist(val name: String, val seed: StoredRadioRow, val channelId: String)

/**
 * "Pick three artists you love" (8.10), for a thin or empty library. A pick starts that artist's own radio (the artist page's button,
 * the same call an artist station makes), whose songs and radio are cached as the artist's list, and the pick is kept as a seed:
 * the mixes then have something of the listener's own to build from, so Your Mix and a Daily Mix exist before any play.
 *
 * Tied to the network once per pick (one songs search, the artist page, one radio); under the YouTube budget.
 */
@Singleton
class PickedArtists @Inject constructor(
    private val states: BrainStateDao,
    private val repository: BrainRepository,
    private val playableStore: PlayableStore,
    private val brainSwitch: BrainSwitch,
    private val clock: BrainClock,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()

    suspend fun all(): List<PickedArtist> {
        val bytes = states.get(KEY) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PickedArtist.serializer()), bytes.toString(Charsets.UTF_8)) }.getOrDefault(emptyList())
    }

    suspend fun hasPicks(): Boolean = states.get(KEY) != null && all().isNotEmpty()

    /** Starts the picked artists' radios. Returns the names that worked; the listener is told about the rest. */
    suspend fun pick(names: List<String>): List<String> = lock.withLock {
        val kept = LinkedHashMap<String, PickedArtist>()
        for (p in all()) kept[p.name] = p
        val worked = ArrayList<String>()
        for (name in names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            try {
                val start = repository.beginArtistStation(name)
                if (start == null) {
                    Timber.d("brain: no radio for the picked artist")
                    continue
                }
                val track = EngineTrack.from(start.seedRow, start.seedRid)
                playableStore.register(listOf(track), clock.nowMs())
                kept[name] = PickedArtist(name, StoredRadioRow(start.seedRow, start.seedRid), start.channelId)
                worked.add(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "brain: starting a picked artist failed")
            }
        }
        if (worked.isNotEmpty()) {
            states.put(BrainStateEntity(KEY, json.encodeToString(ListSerializer(PickedArtist.serializer()), kept.values.toList()).toByteArray(Charsets.UTF_8)))
            // The brain has something of the listener's own now: the mixes may be built from it.
            runCatching { brainSwitch.markBrainStateSeen() }
        }
        worked
    }

    companion object {
        const val KEY = "picked_artists"
        const val PICKS = 3
    }
}
