package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.RadioRowCodec
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The persisted similarity graph (docs/brain-spec.md, 3.6): a radio becomes edges, once, with the weights of the graph. */
class GraphEdgeStoreTest {
    private val edges = FakeGraphEdgeDao()
    private val lists = FakeRadioListDao()
    private val seedDao = FakeSeedDao(lists)
    private val states = FakeBrainStateDao()
    private val store = GraphEdgeStore(edges, seedDao, states)

    private fun listOf(seed: Long, kind: Int, chip: String, vararg rids: Long, fetchedAt: Long = 1_000L): RadioListEntity {
        val rows = rids.map { StoredRadioRow(mixRow(it.toInt(), "Artist$it"), it) }
        return RadioListEntity(seed, kind, chip, fetchedAt, fetchedAt, true, RadioRowCodec.encodeRows(rows), RadioRowCodec.encodeChips(emptyList()))
    }

    @Test
    fun `an edge weighs what the in-memory graph weighs, to 16 bits`() {
        val rids = longArrayOf(11, 12, 13, 14, 15)
        for (kind in intArrayOf(RadioListEntity.KIND_SONG, RadioListEntity.KIND_RELATED)) {
            val type = GraphEdgeStore.typeOf(kind)
            val stored = GraphEdgeStore.edgesOf(7L, type, rids, 5L)
            val graph = Graph.Builder().addList(7L, rids, GraphEdgeStore.factorOf(type)).build()
            assertEquals(rids.size, stored.size)
            for (e in stored) {
                assertEquals(graph.edgeWeight(e.src, e.dst), e.wQ16 / GraphEdgeEntity.UNIT, 1.0 / 65_536.0)
                assertEquals(rids.indexOf(e.dst) + 1, e.rank)
            }
        }
    }

    @Test
    fun `the seed, a zero id and a repeated row make no edge`() {
        val stored = GraphEdgeStore.edgesOf(7L, GraphEdgeEntity.TYPE_RADIO, longArrayOf(7, 0, 8, 8, 9), 1L)
        assertEquals(listOf(8L, 9L), stored.map { it.dst })
        assertEquals(listOf(3, 5), stored.map { it.rank }, "rank is the place in the list, repeats and gaps included")
    }

    @Test
    fun `a refreshed radio replaces the edges its earlier fetch left`() = runTest {
        store.record(7L, RadioListEntity.KIND_SONG, longArrayOf(1, 2, 3), 10L)
        store.record(7L, RadioListEntity.KIND_SONG, longArrayOf(3, 4), 20L)
        assertEquals(setOf(3L, 4L), edges.rows.values.map { it.dst }.toSet())
        assertTrue(edges.rows.values.all { it.fetchedAt == 20L })
        // The Related tab of the same seed is another type and stays.
        store.record(7L, RadioListEntity.KIND_RELATED, longArrayOf(9), 30L)
        assertEquals(setOf(3L, 4L, 9L), edges.rows.values.map { it.dst }.toSet())
    }

    @Test
    fun `a tuned or continued list keeps its own edges and does not replace the first page's`() = runTest {
        store.record(7L, RadioListEntity.KIND_SONG, longArrayOf(1, 2, 3), 10L)
        store.record(7L, RadioListEntity.KIND_SONG, longArrayOf(8, 9), 20L, chip = "+songs")
        assertEquals(setOf(1L, 2L, 3L, 8L, 9L), edges.rows.values.map { it.dst }.toSet())
        // The chip's edges weigh with the chip factor, not the radio's.
        val chipEdge = edges.rows.values.first { it.dst == 8L }
        assertEquals(GraphEdgeEntity.TYPE_CHIP, chipEdge.type)
        assertEquals(GraphEdgeStore.weightQ16(GraphEdgeEntity.TYPE_CHIP, 1), chipEdge.wQ16)
        // Refetching the first page leaves the chip list's edges alone.
        store.record(7L, RadioListEntity.KIND_SONG, longArrayOf(4), 30L)
        assertEquals(setOf(4L, 8L, 9L), edges.rows.values.map { it.dst }.toSet())
    }

    @Test
    fun `the lists stage 1 left behind become edges once, an artist list does not, and the second run does nothing`() = runTest {
        lists.upsert(listOf(1L, RadioListEntity.KIND_SONG, "", 11, 12))
        lists.upsert(listOf(2L, RadioListEntity.KIND_RELATED, "", 21))
        lists.upsert(listOf(3L, RadioListEntity.KIND_ARTIST, BrainRepository.ARTIST_STATION_CHIP, 31, 32))
        lists.upsert(listOf(4L, RadioListEntity.KIND_SONG, "", 41).copy(healthy = false))

        store.backfillOnce(9_999L)

        assertEquals(setOf(1L to 11L, 1L to 12L, 2L to 21L), edges.rows.values.map { it.src to it.dst }.toSet())
        assertTrue(edges.rows.values.all { it.fetchedAt == 1_000L }, "an edge is as old as the list it came from")
        assertNotNull(states.values[GraphEdgeStore.KEY_BACKFILL])

        edges.rows.clear()
        store.backfillOnce(20_000L)
        assertTrue(edges.rows.isEmpty(), "done once means done")
    }

    @Test
    fun `a mix reads the edges that touch its nodes, and old ones are pruned`() = runTest {
        store.record(1L, RadioListEntity.KIND_SONG, longArrayOf(11, 12), 100L)
        store.record(2L, RadioListEntity.KIND_SONG, longArrayOf(21), 100L)
        val read = store.edgesFor(listOf(11L, 2L))
        assertEquals(setOf(1L to 11L, 2L to 21L), read.map { it.a to it.b }.toSet())
        assertTrue(read.all { it.weight > 0.0 && it.weight <= 1.0 })
        assertTrue(store.edgesFor(emptyList()).isEmpty())

        assertEquals(0, store.prune(100L + GraphEdgeStore.RETENTION_MS))
        assertEquals(3, store.prune(100L + GraphEdgeStore.RETENTION_MS + 1))
        assertFalse(edges.rows.isNotEmpty())
    }
}
