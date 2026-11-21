package com.theveloper.pixelplay.data.service.player

import com.theveloper.pixelplay.brain.HostVoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HostVoiceLogTest {

    private fun entry(i: Int, spoke: Boolean = false, cancelled: Boolean = false, reason: String = "r$i", text: String = "") =
        HostVoiceLogEntry(i.toLong(), spoke, cancelled, reason, text)

    @Test
    fun `the log keeps the last twenty decisions, newest first`() {
        var log = emptyList<HostVoiceLogEntry>()
        for (i in 1..35) log = HostVoiceLog.add(log, entry(i))
        assertEquals(HostVoice.LOG_SIZE, log.size)
        assertEquals(20, HostVoice.LOG_SIZE)
        assertEquals(35L, log.first().atMs)
        assertEquals(16L, log.last().atMs)
    }

    @Test
    fun `adding never changes the list it was given`() {
        val one = HostVoiceLog.add(emptyList(), entry(1))
        val two = HostVoiceLog.add(one, entry(2))
        assertEquals(1, one.size)
        assertEquals(2, two.size)
    }

    @Test
    fun `an entry reads as said, stopped or skipped with its reason and line`() {
        assertEquals("spoke, \"From your Bedroom pop side.\"", HostVoiceLog.describe(entry(1, spoke = true, reason = "speak", text = "From your Bedroom pop side.")))
        assertEquals("stopped: skip, \"Released in 2019.\"", HostVoiceLog.describe(entry(1, cancelled = true, reason = "skip", text = "Released in 2019.")))
        assertEquals("skipped: transition", HostVoiceLog.describe(entry(1, reason = "transition")))
    }
}
