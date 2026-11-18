package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.data.brain.store.BrainStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The one-shot copy of the app's own older plays into the event log (docs/brain-spec.md, 4.4, step 2):
 * `playback_history.json` / `.log` rows become `play_event` rows with origin `migratedHistory`.
 *
 * Idempotent by construction: the event id is a function of the row (its start time, its origin and
 * the key it resolved to), and the insert ignores an id that is already there. Rows that ended at or
 * after [copy]'s cutoff are left alone: from that moment on plays are recorded live, and the legacy
 * history keeps being written too, so copying them would count them twice.
 *
 * The counters (`feedback_v1`, `music_taste.json`, `song_engagements`) are not migrated: they
 * aggregate the same sessions this history holds.
 */
class LegacyMigration(
    private val store: BrainStore,
    private val identity: TrackResolver,
) {
    /** One row of the legacy history: a song id, when the session ended, how long was listened. */
    class HistoryRow(val songId: String, val endMs: Long, val listenedMs: Long)

    private class Resolved(val rid: Long, val primaryAid: Long, val otherAids: LongArray, val key: String, val durationMs: Int)

    /**
     * Copies [rows] that ended before [cutoffMs]. [factsFor] says what a song id is (the library, or
     * the `playable` table); a row whose song is gone is skipped: nothing identifies it any more.
     * Returns how many events were new.
     */
    suspend fun copy(rows: List<HistoryRow>, cutoffMs: Long, factsFor: suspend (String) -> TrackFacts?): Int {
        val bySong = HashMap<String, Resolved?>()
        val events = ArrayList<PlayEvent>(rows.size)
        val takenInThisRun = HashMap<Long, String>()
        for (row in rows) {
            if (row.endMs <= 0L || row.endMs >= cutoffMs || row.songId.isBlank()) continue
            if (!bySong.containsKey(row.songId)) bySong[row.songId] = resolve(factsFor(row.songId))
            val track = bySong[row.songId] ?: continue
            val playedMs = row.listenedMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            // The history stores when a session ended; the log stores when a play started.
            val startedAtMs = row.endMs - playedMs
            val eventId = PlayEvent.mintId(startedAtMs, Origin.MIGRATED_HISTORY, track.key) { id ->
                takenInThisRun[id] ?: store.eventIdentityAt(id)
            }
            takenInThisRun[eventId] = PlayEvent.identityOf(Origin.MIGRATED_HISTORY, track.key)
            events.add(
                PlayEvent(
                    eventId = eventId,
                    origin = Origin.MIGRATED_HISTORY,
                    rid = track.rid,
                    primaryAid = track.primaryAid,
                    otherAids = track.otherAids,
                    keyAtWrite = track.key,
                    startedAtMs = startedAtMs,
                    playedMs = playedMs,
                    durationMs = track.durationMs,
                )
            )
        }
        if (events.isEmpty()) return 0
        return withContext(Dispatchers.IO) { store.transaction { store.insertEvents(events) } }
    }

    private suspend fun resolve(facts: TrackFacts?): Resolved? {
        facts ?: return null
        val durationMs = facts.durationMs.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
        val rid = identity.resolveTrack(facts.keys, durationMs)
        if (rid == 0L) return null
        val aids = identity.resolveArtistNames(listOf(facts.primaryArtist) + facts.otherArtists.take(PlayEvent.MAX_OTHER_AIDS))
        return Resolved(rid, aids[0], aids.copyOfRange(1, aids.size), facts.keyAtWrite, durationMs)
    }
}
