package com.theveloper.pixelplay.data.brain

import javax.inject.Inject
import javax.inject.Singleton

/**
 * What the next play the UI dispatches should carry (docs/brain-spec.md, 4.2). The dispatch path only
 * knows a list of songs and the display name of the queue; a station start and a Home row know more
 * (the surface, the list id, each pick's arm and reason). They say so here just before they ask for
 * playback, and the dispatch path takes it when it builds the media items.
 *
 * It is also where a station ends: any play the UI dispatches that is not that station's own start
 * makes the queue something else.
 */
@Singleton
class BrainPlayTags @Inject constructor(
    private val stations: StationStore,
    private val clock: BrainClock,
) {
    class Expected(
        val queueName: String,
        /** For every item of the play. */
        val default: BrainItemTag?,
        /** Per song id; wins over [default]. */
        val bySongId: Map<String, BrainItemTag>,
        /** The play is the start of the current station. */
        val startsStation: Boolean,
        val atMs: Long,
    ) {
        fun tagFor(songId: String): BrainItemTag? = bySongId[songId] ?: default
    }

    @Volatile
    private var expected: Expected? = null

    fun expect(
        queueName: String,
        default: BrainItemTag? = null,
        bySongId: Map<String, BrainItemTag> = emptyMap(),
        startsStation: Boolean = false,
    ) {
        expected = Expected(queueName, default, bySongId, startsStation, clock.nowMs())
    }

    /**
     * Called by the dispatch path for every queue it starts. Returns what was announced for
     * [queueName], if anything, and ends a station the new queue does not belong to.
     */
    fun take(queueName: String?): Expected? {
        val pending = expected
        expected = null
        val match = pending?.takeIf { it.queueName == queueName && clock.nowMs() - it.atMs in 0 until MAX_AGE_MS }
        if (match == null || !match.startsStation) stations.clear()
        return match
    }

    private companion object {
        const val MAX_AGE_MS = 15_000L
    }
}
