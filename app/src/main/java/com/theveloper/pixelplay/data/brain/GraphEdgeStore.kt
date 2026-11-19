package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.brain.MixPlanner
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.GraphEdgeDao
import com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.RadioRowCodec
import com.theveloper.pixelplay.data.brain.store.SeedDao
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/** What [BrainRepository] says when it stores a radio: the list becomes edges of the graph (3.6). */
interface RadioEdges {
    suspend fun record(seedRid: Long, kind: Int, rids: LongArray, nowMs: Long, chip: String = "")

    object None : RadioEdges {
        override suspend fun record(seedRid: Long, kind: Int, rids: LongArray, nowMs: Long, chip: String) = Unit
    }
}

/**
 * The persisted similarity graph (docs/brain-spec.md, 3.6, 3.8 `graph_edge`), stage 2a: the edges of the
 * radio lists. A radio the brain fetches is stored as a list AND as edges, so the graph outlives the
 * 500-list cap on the lists; the lists stage 1 left behind are converted once ([backfillOnce]).
 *
 * The weight of the row at rank k is `factor / log2(2 + k)` with the factor of its kind (1.0 for a
 * radio, 0.8 for the Related tab), written as a number times 65,536. When the same pair has edges of
 * several types the graph builder keeps the largest, and each node keeps its 64 heaviest: both are
 * done when the graph is built, so what is stored may be a little more than a graph needs.
 */
@Singleton
class GraphEdgeStore @Inject constructor(
    private val dao: GraphEdgeDao,
    private val seedDao: SeedDao,
    private val states: BrainStateDao,
) : RadioEdges {
    private val backfillLock = Mutex()

    override suspend fun record(seedRid: Long, kind: Int, rids: LongArray, nowMs: Long, chip: String) {
        if (seedRid == 0L) return
        val type = typeOf(kind, chip)
        val rows = edgesOf(seedRid, type, rids, nowMs)
        try {
            // The new rows go in first and the ones the fetch before it left (and this one did not write) go after:
            // a kill between the two leaves a seed with a few edges too many, never with none. The graph is a cache
            // of the lists, and the next fetch of the same seed puts it right.
            if (rows.isNotEmpty()) dao.upsert(rows)
            dao.deleteStale(seedRid, type, nowMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The graph is a cache of the lists: a failed write is not a failed radio.
            Timber.w(e, "brain: storing the edges of a radio failed")
        }
    }

    /**
     * Converts every healthy list in the cache into edges, once. Safe to call again: it does nothing
     * after the first complete run, and an interrupted run starts over (the writes are upserts).
     */
    suspend fun backfillOnce(nowMs: Long) {
        if (states.get(KEY_BACKFILL) != null) return
        backfillLock.withLock {
            if (states.get(KEY_BACKFILL) != null) return
            var offset = 0
            while (true) {
                val page = seedDao.healthyPage(PAGE, offset)
                if (page.isEmpty()) break
                for (list in page) {
                    if (list.chip == BrainRepository.ARTIST_STATION_CHIP || list.chip == BrainRepository.SIMILAR_STATION_CHIP) continue
                    val rids = RadioRowCodec.decodeRows(list.rowsBlob).map { it.rid }.toLongArray()
                    // The conversion keeps the age of the list it came from: its edges expire with it.
                    record(list.seedRid, list.kind, rids, list.fetchedAt.coerceAtMost(nowMs), list.chip)
                }
                offset += page.size
                if (page.size < PAGE) break
            }
            states.put(BrainStateEntity(KEY_BACKFILL, nowMs.toString().toByteArray(Charsets.UTF_8)))
        }
    }

    /** The stored edges that touch any of [nodes], as the extra edges a mix is planned with. */
    suspend fun edgesFor(nodes: Collection<Long>): List<MixPlanner.Edge> {
        if (nodes.isEmpty()) return emptyList()
        val out = ArrayList<MixPlanner.Edge>()
        for (chunk in nodes.distinct().chunked(CHUNK)) {
            for (row in dao.touching(chunk)) out.add(MixPlanner.Edge(row.src, row.dst, row.wQ16 / GraphEdgeEntity.UNIT))
        }
        return out
    }

    /** Drops edges whose list was fetched more than [RETENTION_MS] ago. Returns how many went. */
    suspend fun prune(nowMs: Long): Int = dao.deleteOlderThan(nowMs - RETENTION_MS)

    companion object {
        const val KEY_BACKFILL = "graph_edges_backfilled_v1"
        const val RETENTION_MS = 30L * 24 * 60 * 60 * 1000
        private const val PAGE = 50
        private const val CHUNK = 400

        fun typeOf(kind: Int, chip: String = ""): Int = when {
            chip.isNotEmpty() -> GraphEdgeEntity.TYPE_CHIP
            kind == RadioListEntity.KIND_RELATED -> GraphEdgeEntity.TYPE_RELATED
            else -> GraphEdgeEntity.TYPE_RADIO
        }

        fun factorOf(type: Int): Double = when (type) {
            GraphEdgeEntity.TYPE_RELATED -> Graph.RELATED
            GraphEdgeEntity.TYPE_CHIP -> Graph.CHIP
            else -> Graph.RADIO
        }

        /** The weight of the row at [rank] (1 for the first non-seed row) of a list of [type], times 65,536, rounded. */
        fun weightQ16(type: Int, rank: Int): Int {
            val w = factorOf(type) / BrainMath.log2(2.0 + rank.toDouble())
            return BrainMath.floorToLong(w * GraphEdgeEntity.UNIT + 0.5).toInt()
        }

        /** One edge per distinct row: the seed to the recording at its place in the list. */
        fun edgesOf(seedRid: Long, type: Int, rids: LongArray, nowMs: Long): List<GraphEdgeEntity> {
            val seen = HashSet<Long>(rids.size * 2)
            val out = ArrayList<GraphEdgeEntity>(rids.size)
            for (i in rids.indices) {
                val rid = rids[i]
                if (rid == 0L || rid == seedRid || !seen.add(rid)) continue
                out.add(GraphEdgeEntity(seedRid, rid, type, weightQ16(type, i + 1), i + 1, nowMs))
            }
            return out
        }
    }
}
