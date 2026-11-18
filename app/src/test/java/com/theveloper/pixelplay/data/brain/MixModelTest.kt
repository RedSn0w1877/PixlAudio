package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.MixPlanner
import com.theveloper.pixelplay.brain.Repetition
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.WhyLine
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The saved-mix format and the served log it lives in (docs/brain-spec.md, 3.8 `served_list`, 8.2). */
class MixModelTest {
    private val dao = FakeServedListDao()
    private var now = 1_800_000_000_000L
    private val store = ServedListStore(dao, BrainClock { now })
    private val tz = 0

    private fun mix(kind: Int, builtAtMs: Long, vararg rids: Long) = StoredMix(
        kind = kind, builtAtMs = builtAtMs, seed = 5L, items = rids.map { rid ->
            if (rid > 0) StoredMixItem(rid = rid, songId = "song$rid", arm = Arm.FAV, slot = 0)
            else StoredMixItem(rid = rid, row = StoredRadioRow(mixRow(-rid.toInt(), "New"), rid), arm = Arm.RADIO, slot = 1,
                whyKey = WhyLine.RADIO_FANS, whyArg = 9L, whyName = "Seed Artist", retrQ = 400, radioRank = 3)
        },
    )

    @Test
    fun `a mix survives the blob, a library row and an outside row alike, with its reason`() {
        val original = mix(MixPlanner.KIND_DAILY, now, 1L, -2L).copy(novelty = Novelty.DISCOVER, tasteSignature = "3:1", seedLists = 4, regenerate = 2)
        val back = MixCodec.decode(MixCodec.encode(original))!!
        assertEquals(original, back)
        assertEquals(1, back.novelCount)
        assertEquals("Seed Artist", back.items[1].whyName)
        assertNull(MixCodec.decode(ByteArray(0)))
        assertNull(MixCodec.decode(byteArrayOf(1, 2, 3)), "a damaged blob is no mix, not a crash")
    }

    @Test
    fun `the day's mix is the newest build of the surface on today's local day, and a day starts at 04 00`() = runTest {
        val day = Repetition.DAY_MS
        val start = ServedListStore.dayStart(now, tz)
        assertTrue(start <= now && now - start < day)
        assertEquals(4 * 3_600_000L, start % day, "04:00 UTC with no offset")

        store.put(Surface.DAILY_MIX, "daily", mix(MixPlanner.KIND_DAILY, start - 60_000L, 1L), nuQ = 250)
        assertNull(store.todays(Surface.DAILY_MIX, now, tz), "03:59 belongs to yesterday's day")
        store.put(Surface.DAILY_MIX, "daily", mix(MixPlanner.KIND_DAILY, start + 60_000L, 2L), nuQ = 250)
        store.put(Surface.DAILY_MIX, "daily", mix(MixPlanner.KIND_DAILY, start + 120_000L, 3L), nuQ = 250)
        assertEquals(listOf(3L), store.todays(Surface.DAILY_MIX, now, tz)!!.items.map { it.rid })
        assertNull(store.todays(Surface.YOUR_MIX, now, tz), "another surface has its own mix")
        assertEquals(2, store.buildsToday(Surface.DAILY_MIX, now, tz))
    }

    @Test
    fun `a build from the future is not today's mix, and a new offset moves the day`() = runTest {
        store.put(Surface.YOUR_MIX, "your", mix(MixPlanner.KIND_YOUR, now + 3 * 3_600_000L, 1L), nuQ = 0)
        assertNull(store.todays(Surface.YOUR_MIX, now, tz))
        assertNotEquals(ServedListStore.dayStart(now, 0), ServedListStore.dayStart(now, 600))
    }

    @Test
    fun `the log is kept 60 days and read back as what the repetition budget wants`() = runTest {
        val old = now - 61 * Repetition.DAY_MS
        val recent = now - 2 * Repetition.DAY_MS
        dao.upsert(com.theveloper.pixelplay.data.brain.store.ServedListEntity(1L, Surface.DAILY_MIX, "daily", old, 0L, 2, 0, MixCodec.encode(mix(0, old, 9L))))
        store.put(Surface.DAILY_MIX, "daily", mix(MixPlanner.KIND_DAILY, recent, 1L, 2L, -3L), nuQ = 250)
        // put() forgets what is older than the retention
        assertEquals(1, dao.rows.size)

        val log = store.recent(now)
        assertEquals(1, log.size)
        assertEquals(Surface.DAILY_MIX, log[0].surface)
        assertEquals(setOf(1L, 2L, -3L), log[0].rids.toSet())
        assertEquals(1, Repetition.timesServed(log, now)[1L])
        assertTrue(Repetition.takenOnDay(log, now, tz).isEmpty(), "built two days ago: not today")
        assertEquals(setOf(1L, 2L, -3L), Repetition.takenOnDay(log, recent + 60_000L, tz).toSet())
    }
}
