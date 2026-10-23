package com.theveloper.pixelplay.data.service.wear

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LatestWinsGateTest {

    @Test
    fun `a lone request runs`() = runTest {
        val gate = LatestWinsGate()
        var ran = false

        val result = gate.runIfLatest(gate.newTicket()) { ran = true }

        assertTrue(result)
        assertTrue(ran)
    }

    @Test
    fun `a superseded request that waited for the lock is dropped and the newest runs last`() = runTest {
        val gate = LatestWinsGate()
        val order = mutableListOf<String>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()

        val first = gate.newTicket()
        val running = launch {
            gate.runIfLatest(first) {
                order += "first"
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }
        firstStarted.await()

        // Two more arrive while the first is still publishing (a slow artwork download).
        val second = gate.newTicket()
        val third = gate.newTicket()
        val secondRan = launch { assertFalse(gate.runIfLatest(second) { order += "second" }) }
        val thirdRan = launch { assertTrue(gate.runIfLatest(third) { order += "third" }) }

        releaseFirst.complete(Unit)
        running.join()
        secondRan.join()
        thirdRan.join()

        assertEquals(listOf("first", "third"), order)
    }
}
