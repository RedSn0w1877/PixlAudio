package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.LibraryFacts
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.Replay
import com.theveloper.pixelplay.data.brain.store.BrainStore
import com.theveloper.pixelplay.data.history.ImportedPlayEntity
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The one-shot migration and the rebuild (docs/brain-spec.md, 4.4, steps 1, 2 and 4; 5.2 "Late events
 * and clock changes"): idempotent, safe with events that arrive late, safe with a clock that jumped.
 */
class BrainMigrationTest {

    private val day = 86_400_000L
    private val now = 1_760_000_000_000L
    private val store = MemoryBrainStore()
    private val resolver = MemoryTrackResolver()
    private val catalog = FakeTrackCatalog().apply {
        add("s1", "Nirvana", "Lithium", durationMs = 257_000L)
        add("s2", "NewJeans", "Ditto", durationMs = 186_000L)
        add("s3", "Hoàng Thùy Linh", "See Tình", durationMs = 185_000L)
    }
    private val factsFor: suspend (String) -> TrackFacts? = { catalog.factsFor(it, null) }

    private fun history(): List<LegacyMigration.HistoryRow> = listOf(
        LegacyMigration.HistoryRow("s1", now - 30 * day, 257_000L),
        LegacyMigration.HistoryRow("s2", now - 29 * day, 12_000L),
        LegacyMigration.HistoryRow("s1", now - 10 * day, 250_000L),
        LegacyMigration.HistoryRow("gone", now - 9 * day, 100_000L), // a song that is no longer anywhere
        LegacyMigration.HistoryRow("s3", now - 2 * day, 185_000L),
        LegacyMigration.HistoryRow("s3", now + 3 * day, 185_000L), // ended after the brain's first use
    )

    private fun dump(state: Affinity.State): List<String> =
        BrainStore.allRows(state).map { "${it.family}:${it.key}:${it.posQ}:${it.negQ}:${it.lastMs}:${it.posRQ}:${it.negRQ}" }.sorted()

    private fun imported(id: Long, artist: String, title: String, playedAt: Long, ms: Long?, outcome: Int, source: String = "spotify_export") =
        ImportedPlayEntity(
            id = id, dedupeKey = "k$id", source = source, format = "extended", playedAt = playedAt,
            trackKey = "$artist|$title", artistKey = artist, trackName = title, artistName = artist, albumName = null,
            spotifyTrackId = null, videoId = null, msPlayed = ms, outcome = outcome, weight = 1.0, qualified = true, voluntary = true,
        )

    private class ListSource(val rows: List<ImportedPlayEntity>) : ImportedPlaySource {
        override suspend fun page(afterId: Long, limit: Int) = rows.filter { it.id > afterId }.sortedBy { it.id }.take(limit)
        override suspend fun maxId() = rows.maxOfOrNull { it.id } ?: 0L
    }

    // ── The history copy ─────────────────────────────────────────────────────

    @Test
    fun `the copy writes one migrated event per old play, with the facts it has and no more`() = runTest {
        val copied = LegacyMigration(store, resolver).copy(history(), cutoffMs = now, factsFor = factsFor)
        assertEquals(4, copied)
        val events = store.events.values.toList()
        assertTrue(events.all { it.origin == Origin.MIGRATED_HISTORY })
        val first = events.first()
        assertEquals(resolver.rid("Nirvana", "Lithium"), first.rid)
        assertEquals(resolver.aid("Nirvana"), first.primaryAid)
        assertEquals(257_000, first.playedMs)
        assertEquals(257_000, first.durationMs)
        // The history stores when a play ended; the log stores when it started.
        assertEquals(now - 30 * day - 257_000L, first.startedAtMs)
        assertEquals(EndReason.UNKNOWN, first.endReason)
        assertEquals(PlayEvent.TZ_UNKNOWN, first.tzOffsetMin)
        // In time order, whatever order the file had them in.
        assertEquals(events.map { it.startedAtMs }.sorted(), events.map { it.startedAtMs })
    }

    @Test
    fun `the copy is idempotent - run twice, or interrupted and run again, it adds nothing`() = runTest {
        val migration = LegacyMigration(store, resolver)
        assertEquals(4, migration.copy(history(), now, factsFor))
        val before = store.events.keys.toList()
        assertEquals(0, migration.copy(history(), now, factsFor))
        assertEquals(0, LegacyMigration(store, resolver).copy(history().reversed(), now, factsFor))
        assertEquals(before, store.events.keys.toList())
        // Half of it ran, then all of it: the second run only adds the other half.
        val other = MemoryBrainStore()
        val half = LegacyMigration(other, resolver)
        assertEquals(2, half.copy(history().take(2), now, factsFor))
        assertEquals(2, half.copy(history(), now, factsFor))
        assertEquals(before, other.events.keys.toList())
    }

    @Test
    fun `plays from after the brain's first use are not copied - they are recorded live`() = runTest {
        LegacyMigration(store, resolver).copy(history(), cutoffMs = now, factsFor = factsFor)
        assertTrue(store.events.values.none { it.endedAtMs >= now })
        // With an earlier cutoff, less is copied.
        val other = MemoryBrainStore()
        assertEquals(2, LegacyMigration(other, resolver).copy(history(), cutoffMs = now - 20 * day, factsFor = factsFor))
    }

    @Test
    fun `the play that first woke the brain is not copied a second time`() = runTest {
        // First use is written while the first finished play is recorded, after that play ended. The
        // log is live from the START of that play: its legacy row is not history to migrate.
        val firstUse = now
        val firstLiveStart = now - 200_000L
        assertEquals(firstLiveStart, LegacyMigration.cutoff(firstUse, firstLiveStart))
        // A row that ended inside that first live play is left alone; older ones are copied.
        val rows = listOf(
            LegacyMigration.HistoryRow("s1", now - 1_000L, 199_000L),
            LegacyMigration.HistoryRow("s1", now - 3 * day, 250_000L),
        )
        assertEquals(1, LegacyMigration(store, resolver).copy(rows, LegacyMigration.cutoff(firstUse, firstLiveStart), factsFor))
        // No live play yet, or a live play that started after first use: first use is the line.
        assertEquals(firstUse, LegacyMigration.cutoff(firstUse, null))
        assertEquals(firstUse, LegacyMigration.cutoff(firstUse, firstUse + 5_000L))
    }

    @Test
    fun `two plays of one song in the same millisecond keep both`() = runTest {
        val rows = listOf(
            LegacyMigration.HistoryRow("s1", now - day, 10_000L),
            LegacyMigration.HistoryRow("s2", now - day, 10_000L),
        )
        assertEquals(2, LegacyMigration(store, resolver).copy(rows, now, factsFor))
        assertEquals(1, store.events.values.map { it.startedAtMs }.toSet().size)
        assertEquals(2, store.events.size)
    }

    // ── Imported history ─────────────────────────────────────────────────────

    @Test
    fun `imported plays become events without being copied into the log`() = runTest {
        val rows = listOf(
            imported(1, "Nirvana", "Lithium", now - 400 * day, 250_000L, outcome = 1),
            imported(2, "nirvana", "LITHIUM", now - 399 * day, 4_000L, outcome = 2),
            imported(3, "Burial", "Archangel", now - 398 * day, null, outcome = 0, source = "ytmusic"),
            imported(4, "Burial", "Archangel", now - 397 * day, 90_000L, outcome = 3),
        )
        val adapter = ImportedPlayAdapter(ListSource(rows), resolver)
        val lithium = resolver.rid("Nirvana", "Lithium")
        val events = adapter.events { rid -> if (rid == lithium) 257_000 else 0 }
        assertEquals(4, events.size)
        assertEquals(4L, adapter.maxId())
        assertTrue(store.events.isEmpty())
        // One recording whatever the capitals; its library length is known.
        assertEquals(listOf(lithium, lithium), events.take(2).map { it.rid })
        assertEquals(257_000, events[0].durationMs)
        assertEquals(EndReason.TRACK_DONE, events[0].endReason)
        assertEquals(EndReason.NEXT, events[1].endReason)
        assertEquals(now - 400 * day - 250_000L, events[0].startedAtMs)
        // A play without ms_played: -1, graded 0.75 by rule 3; YouTube Music weighs less.
        assertEquals(-1, events[2].playedMs)
        assertEquals(Origin.IMPORT_YTM, events[2].origin)
        assertEquals(now - 398 * day, events[2].startedAtMs)
        assertEquals(EndReason.UNKNOWN, events[3].endReason)
        assertEquals(Origin.IMPORT_SPOTIFY, events[3].origin)
        assertEquals(0, events[3].durationMs)
    }

    @Test
    fun `an imported play that is the same listen as a stored one is dropped`() = runTest {
        LegacyMigration(store, resolver).copy(listOf(LegacyMigration.HistoryRow("s1", now - 5 * day, 250_000L)), now, factsFor)
        val adapter = ImportedPlayAdapter(
            ListSource(
                listOf(
                    imported(1, "Nirvana", "Lithium", now - 5 * day + 60_000L, 250_000L, outcome = 1), // 60 s apart: the same listen
                    imported(2, "Nirvana", "Lithium", now - 5 * day + 200_000L, 250_000L, outcome = 1), // another listen
                    imported(3, "NewJeans", "Ditto", now - 5 * day, 180_000L, outcome = 1), // another song at the same time
                )
            ),
            resolver,
        )
        val result = ReplayMerger(store).rebuild(adapter.events { 0 }, LibraryFacts.EMPTY, now) { it }
        assertEquals(1, result.importedDropped)
        assertEquals(2, result.importedUsed)
        assertEquals(2, result.state.completions[resolver.rid("Nirvana", "Lithium")])
    }

    // ── Rebuild: order, late events, clock jumps ─────────────────────────────

    @Test
    fun `a rebuild is the same whatever order the imports arrive in, and the same twice`() = runTest {
        LegacyMigration(store, resolver).copy(history(), now, factsFor)
        val rows = (1..40L).map { i ->
            imported(i, if (i % 2 == 0L) "Nirvana" else "Burial", "Song ${i % 7}", now - (500 - i * 3) * day, 30_000L * (i % 9), outcome = (i % 4).toInt())
        }
        val events = ImportedPlayAdapter(ListSource(rows), resolver).events { 0 }
        val merger = ReplayMerger(store)
        val a = dump(merger.rebuild(events, LibraryFacts.EMPTY, now) { it }.state)
        val b = dump(merger.rebuild(events.reversed(), LibraryFacts.EMPTY, now) { it }.state)
        val c = dump(merger.rebuild(events.shuffled(java.util.Random(7)), LibraryFacts.EMPTY, now) { it }.state)
        assertEquals(a, b)
        assertEquals(a, c)
        assertTrue(a.isNotEmpty())
    }

    @Test
    fun `late events - an import older than every live play lands where it belongs`() = runTest {
        // Live plays first, as the app records them...
        LegacyMigration(store, resolver).copy(history(), now, factsFor)
        val liveOnly = ReplayMerger(store).rebuild(emptyList(), LibraryFacts.EMPTY, now) { it }.state
        // ...then an import that is years older arrives.
        val old = ImportedPlayAdapter(
            ListSource((1..30L).map { imported(it, "Nirvana", "Lithium", now - (900 + it) * day, 250_000L, outcome = 1) }),
            resolver,
        ).events { 257_000 }
        val merged = ReplayMerger(store).rebuild(old, LibraryFacts.EMPTY, now) { it }.state
        val rid = resolver.rid("Nirvana", "Lithium")
        val before = liveOnly.counter(Family.TRACK, rid)!!
        val after = merged.counter(Family.TRACK, rid)!!
        // The newest play still dates the counter; the old plays added a little, decayed by their age.
        assertEquals(before.lastMs, after.lastMs)
        assertTrue(after.posQ > before.posQ)
        assertTrue(after.posQ - before.posQ < 30L * 1_048_576L / 8, "thirty plays from 2.5 years ago weigh little")
        assertEquals(32, merged.completions[rid])

        // The live path meets the same situation without a rebuild: an older play applied after a newer one.
        val live = Replay.rebuild(store.events.values.iterator(), LibraryFacts.EMPTY, now)
        val tracker = com.theveloper.pixelplay.brain.GradeTracker(live.completions)
        for (e in old.sortedBy { it.eventId }) Affinity.applyEvent(live, e, tracker.grade(e), e.startedAtMs)
        val lateApplied = live.counter(Family.TRACK, rid)!!
        assertEquals(after.lastMs, lateApplied.lastMs)
        // Decay makes "applied late" and "replayed in order" agree, to within rounding of each step.
        assertTrue(Math.abs(lateApplied.posQ - after.posQ) < 64, "${lateApplied.posQ} vs ${after.posQ}")
    }

    @Test
    fun `a clock jump - events from the future are clamped, and the result is finite and current`() = runTest {
        // The phone's clock ran two years ahead for a day: those plays carry future times.
        val ahead = history().map { LegacyMigration.HistoryRow(it.songId, it.endMs + 730 * day, it.listenedMs) }
        LegacyMigration(store, resolver).copy(history() + ahead, cutoffMs = now + 800 * day, factsFor = factsFor)
        val state = ReplayMerger(store).rebuild(emptyList(), LibraryFacts.EMPTY, now) { it }.state
        assertEquals(now, state.newestMs)
        assertFalse(Affinity.clockJumpedBack(state, now))
        for (row in BrainStore.allRows(state)) {
            assertTrue(row.lastMs <= now, "no counter is dated in the future")
            assertTrue(row.posQ >= 0 && row.negQ >= 0)
        }
        val p0 = Affinity.p0(state, now)
        assertTrue(Affinity.aff(state, Family.TRACK, resolver.rid("Nirvana", "Lithium"), now, p0) in -3.0..3.0)
    }

    @Test
    fun `merged recordings are folded into their survivor at a rebuild`() = runTest {
        LegacyMigration(store, resolver).copy(history(), now, factsFor)
        val lithium = resolver.rid("Nirvana", "Lithium")
        val ditto = resolver.rid("NewJeans", "Ditto")
        val state = ReplayMerger(store).rebuild(emptyList(), LibraryFacts.EMPTY, now) { if (it == ditto) lithium else it }.state
        assertFalse(Affinity.isHeard(state, Family.TRACK, ditto))
        assertNotNull(state.counter(Family.TRACK, lithium))
    }

    // ── The engine's swap ────────────────────────────────────────────────────

    @Test
    fun `the first use triggers the rebuild once, and plays recorded meanwhile are not lost or doubled`() = runTest {
        val catalog = this@BrainMigrationTest.catalog
        var rebuilds = 0
        val rebuilder = object : StateRebuilder {
            override suspend fun isNeeded(): Boolean = store.getState(BrainStore.KEY_HISTORY_MIGRATED) == null
            override suspend fun rebuild(nowMs: Long): StateRebuilder.Rebuilt {
                rebuilds++
                LegacyMigration(store, resolver).copy(history(), store.getState(BrainStore.KEY_FIRST_USE_MS)!!.toLong(), factsFor)
                store.putState(BrainStore.KEY_HISTORY_MIGRATED, "1")
                val result = ReplayMerger(store).rebuild(emptyList(), LibraryFacts.EMPTY, nowMs) { it }
                return StateRebuilder.Rebuilt(result.state, result.recentEventIds, mapOf(BrainStore.KEY_SPEC_VERSION to "1"))
            }
        }
        var clockMs = now
        val engine = BrainEngine(store, { rebuilder }, { clockMs })
        val recorder = BrainEventRecorder(engine, resolver, catalog, store)
        fun live(songId: String, played: Long): FinishedPlay {
            val started = clockMs
            clockMs += played + 1_000L
            return FinishedPlay(songId, PlayContext(title = "", artistDisplay = ""), started, played, 200_000L, EndReason.TRACK_DONE)
        }
        // The first play of the process loads the state and starts the migration in the background.
        assertNotNull(recorder.record(live("s2", 186_000L)))
        engine.awaitRebuild()
        assertEquals(1, rebuilds)
        assertEquals(1, store.affinityRewrites)
        assertEquals(4, store.countEvents(Origin.MIGRATED_HISTORY))
        assertEquals(1, store.countEvents(Origin.LIVE))

        // The state in memory is the rebuilt one, and it holds the live play exactly once.
        val ditto = resolver.rid("NewJeans", "Ditto")
        val fromLog = Replay.rebuild(store.events.values.iterator(), LibraryFacts.EMPTY, clockMs)
        engine.withState { state ->
            assertEquals(dump(fromLog), dump(state.affinity))
            assertEquals(1, state.affinity.completions[ditto])
        }
        // What is stored is what is in memory.
        val reloaded = Affinity.State().also(store::loadAffinity)
        assertEquals(dump(fromLog), dump(reloaded))

        // A second process: nothing to migrate, nothing rebuilt.
        val again = BrainEngine(store, { rebuilder }, { clockMs })
        again.withState { }
        again.awaitRebuild()
        assertEquals(1, rebuilds)
    }
}
