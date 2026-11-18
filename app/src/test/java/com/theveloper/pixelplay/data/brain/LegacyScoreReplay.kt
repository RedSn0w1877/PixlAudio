package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.recommendation.MusicRecommendationEngine

/**
 * The legacy engine's score as a predictor, for test 1 of the evaluation harness (docs/brain-spec.md,
 * 11: "AUC at least 0.05 above the legacy engine's score used as a predictor").
 *
 * The legacy engine ranks a library on demand and keeps no history of what it would have said, so the
 * score of an event is rebuilt: the legacy counters ([MusicRecommendationEngine.Signal]) are fed with
 * the events in order, exactly as `ListeningStatsTracker` feeds them, and the real
 * [MusicRecommendationEngine.rank] is run once a day over the library (the way the legacy mixes are built
 * once a day). An event takes the score its track had that morning, from the plays before that morning.
 *
 * What this is not: the legacy engine never saw imported history here (`History` stays empty), and the
 * library is every track the export ever mentions (the export does not say what was in the library on
 * a given day). Both approximations help the legacy score or leave it alone, so a gap in the brain's
 * favour measured against it is not flattered. The numbers are only as real as the export.
 */
object LegacyScoreReplay {
    private const val DAY_MS = 86_400_000L

    /**
     * Event id to legacy score, for the events from [fromIndex] on (the held-out ones). [favoriteRids]
     * are the tracks the legacy engine counts as favourites.
     */
    fun scores(events: List<PlayEvent>, fromIndex: Int, favoriteRids: Set<Long> = emptySet()): Map<Long, Double> {
        val songs = LinkedHashMap<Long, Song>()
        for (e in events) {
            if (songs.containsKey(e.rid)) continue
            songs[e.rid] = Song.emptySong().copy(id = e.rid.toString(), title = "track ${e.rid}", artist = "artist ${e.primaryAid}", artistId = e.primaryAid)
        }
        val list = songs.values.toList()
        val favorites = favoriteRids.mapTo(HashSet()) { it.toString() }
        val signals = HashMap<String, MusicRecommendationEngine.Signal>()
        val out = HashMap<Long, Double>()
        var rankedDay = Long.MIN_VALUE
        var dayScores: Map<String, Double> = emptyMap()
        for (i in events.indices) {
            val e = events[i]
            if (i >= fromIndex) {
                val day = Math.floorDiv(e.startedAtMs, DAY_MS)
                if (day != rankedDay) {
                    rankedDay = day
                    val picks = MusicRecommendationEngine.rank(
                        songs = list, favorites = favorites, signals = signals, history = emptyMap(),
                        nowMs = day * DAY_MS, seed = day,
                    )
                    dayScores = picks.associate { it.song.id to it.score }
                }
                out[e.eventId] = dayScores[e.rid.toString()] ?: 0.0
            }
            val key = e.rid.toString()
            val heard = if (e.playedMs > 0) e.playedMs.toLong() else 0L
            val changed = e.endReason == EndReason.NEXT || e.endReason == EndReason.PICKED_OTHER || e.endReason == EndReason.PREV
            val voluntary = e.startReason == StartReason.TAP || e.startReason == StartReason.NEXT || e.startReason == StartReason.PREV
            signals[key] = MusicRecommendationEngine.record(
                previous = signals[key] ?: MusicRecommendationEngine.Signal(),
                listenedMs = heard, durationMs = e.durationMs.toLong(), voluntary = voluntary, changedTrack = changed,
                nowMs = e.startedAtMs + heard,
            )
        }
        return out
    }
}
