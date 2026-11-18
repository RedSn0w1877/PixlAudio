package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.Ids
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.data.history.HistorySource
import com.theveloper.pixelplay.data.history.ImportedPlayEntity
import com.theveloper.pixelplay.data.history.ListeningHistoryDao

/** Where imported plays are read from: `listening_history.db` in the app, a list in tests. */
interface ImportedPlaySource {
    /** Keyset paging by row id. */
    suspend fun page(afterId: Long, limit: Int): List<ImportedPlayEntity>

    /** The highest row id, 0 when there is none. */
    suspend fun maxId(): Long

    class OfDao(private val dao: ListeningHistoryDao) : ImportedPlaySource {
        override suspend fun page(afterId: Long, limit: Int): List<ImportedPlayEntity> = dao.playsAfter(afterId, limit)
        override suspend fun maxId(): Long = dao.maxPlayId() ?: 0L
    }
}

/**
 * Imported history as events (docs/brain-spec.md, 4.4, step 1). The plays are READ, never copied:
 * `imported_plays` stays their single home, and a rebuild merges this stream with `play_event`.
 *
 * Each row is resolved to a recording id before the pure replay sees it (keys `s:` from the Spotify
 * track id, `y:` from the video id, `n:` from the names). A distinct track is resolved once per run.
 */
class ImportedPlayAdapter(
    private val source: ImportedPlaySource,
    private val identity: TrackResolver,
) {
    private class Resolved(val rid: Long, val aid: Long, val key: String)

    suspend fun maxId(): Long = source.maxId()

    /**
     * Every imported play as an event, in no particular order. [durationOfRid] gives the length of a
     * recording that is a library track, 0 otherwise: `imported_plays` has no duration column.
     */
    suspend fun events(durationOfRid: (Long) -> Int): List<PlayEvent> {
        val out = ArrayList<PlayEvent>()
        val byTrack = HashMap<String, Resolved?>()
        val aidByArtist = HashMap<String, Long>()
        val taken = HashMap<Long, String>()
        var afterId = 0L
        while (true) {
            val page = source.page(afterId, PAGE)
            if (page.isEmpty()) break
            afterId = page.last().id
            for (row in page) {
                val cacheKey = (row.spotifyTrackId ?: "") + "|" + (row.videoId ?: "") + "|" + row.trackKey
                if (!byTrack.containsKey(cacheKey)) byTrack[cacheKey] = resolve(row, aidByArtist)
                val track = byTrack[cacheKey] ?: continue
                val origin = if (HistorySource.fromKey(row.source)?.isSpotify != false) Origin.IMPORT_SPOTIFY else Origin.IMPORT_YTM
                val playedMs = row.msPlayed?.coerceIn(0L, Int.MAX_VALUE.toLong())?.toInt() ?: -1
                // `played_at` is when the play ended.
                val startedAtMs = row.playedAt - (if (playedMs > 0) playedMs else 0)
                val eventId = PlayEvent.mintId(startedAtMs, origin, track.key) { taken[it] }
                taken[eventId] = PlayEvent.identityOf(origin, track.key)
                out.add(
                    PlayEvent(
                        eventId = eventId,
                        origin = origin,
                        rid = track.rid,
                        primaryAid = track.aid,
                        keyAtWrite = track.key,
                        startedAtMs = startedAtMs,
                        playedMs = playedMs,
                        durationMs = durationOfRid(track.rid),
                        endReason = when (row.outcome) {
                            OUTCOME_COMPLETED -> EndReason.TRACK_DONE
                            OUTCOME_SKIPPED -> EndReason.NEXT
                            else -> EndReason.UNKNOWN
                        },
                    )
                )
            }
            if (page.size < PAGE) break
        }
        return out
    }

    private suspend fun resolve(row: ImportedPlayEntity, aidByArtist: HashMap<String, Long>): Resolved? {
        val keys = Ids.TrackKeys(
            spotifyId = row.spotifyTrackId,
            videoId = row.videoId,
            nameKey = Ids.nameKey(row.artistName, row.trackName),
        )
        val key = keys.s ?: keys.y ?: keys.n ?: return null
        val rid = identity.resolveTrack(keys, 0)
        if (rid == 0L) return null
        val aid = aidByArtist.getOrPut(row.artistName) { identity.resolveArtistNames(listOf(row.artistName))[0] }
        return Resolved(rid, aid, key)
    }

    private companion object {
        const val PAGE = 1_000

        // `PlayOutcome.code` in ListeningHistoryModels.
        const val OUTCOME_COMPLETED = 1
        const val OUTCOME_SKIPPED = 2
    }
}
