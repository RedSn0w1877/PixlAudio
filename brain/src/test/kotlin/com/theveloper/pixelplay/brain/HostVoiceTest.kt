package com.theveloper.pixelplay.brain

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HostVoiceTest {

    private val note = "From your Bedroom pop side."

    private fun inputs(
        enabled: Boolean = true, endless: Boolean = true, notesOn: Boolean = true, line: String = note, sameAsLast: Boolean = false,
        tracksSinceLast: Int = HostVoice.NEVER, sinceStartMs: Long = 2_000L, currentIsTarget: Boolean = true, transitionRunning: Boolean = false,
        playing: Boolean = true, inCall: Boolean = false, msSinceRoute: Long = 60_000L, positionMs: Long = 2_000L, remainingMs: Long = 120_000L,
        voice: Int = HostVoice.VOICE_READY, canDuck: Boolean = true,
    ) = HostVoice.Inputs(
        enabled, endless, notesOn, line, sameAsLast, tracksSinceLast, sinceStartMs, currentIsTarget, transitionRunning, playing, inCall, msSinceRoute,
        positionMs, remainingMs, voice, canDuck,
    )

    private fun reasonOf(i: HostVoice.Inputs) = HostVoice.decide(i).reason

    @Test
    fun `a calm moment speaks`() {
        val d = HostVoice.decide(inputs())
        assertEquals(HostVoice.ACT_SPEAK, d.action)
        assertEquals("speak", d.reason)
    }

    @Test
    fun `nothing is said with the switch off or off Endless Listening`() {
        assertEquals("off", reasonOf(inputs(enabled = false)))
        assertEquals("not_endless", reasonOf(inputs(endless = false)))
        assertEquals("notes_off", reasonOf(inputs(notesOn = false)))
        assertEquals("no_line", reasonOf(inputs(line = "")))
        assertEquals(HostVoice.ACT_SKIP, HostVoice.decide(inputs(enabled = false)).action)
    }

    @Test
    fun `never over a transition and a transition is waited for`() {
        val d = HostVoice.decide(inputs(transitionRunning = true))
        assertEquals(HostVoice.ACT_WAIT, d.action)
        assertEquals("transition", d.reason)
        // The engine still plays the previous song while the new one is already shown: wait, do not speak.
        assertEquals("not_current", reasonOf(inputs(currentIsTarget = false)))
        assertEquals(HostVoice.ACT_WAIT, HostVoice.decide(inputs(currentIsTarget = false)).action)
    }

    @Test
    fun `a line starts at least one and a half seconds into the track`() {
        val early = HostVoice.decide(inputs(positionMs = 400L))
        assertEquals(HostVoice.ACT_WAIT, early.action)
        assertEquals("too_early", early.reason)
        assertEquals(1_100L, early.waitMs)
        assertEquals(HostVoice.ACT_SPEAK, HostVoice.decide(inputs(positionMs = 1_500L)).action)
        assertEquals("too_early", reasonOf(inputs(positionMs = 1_499L)))
    }

    @Test
    fun `not in a call, not paused, not just after a route change`() {
        assertEquals("call", reasonOf(inputs(inCall = true)))
        assertEquals("paused", reasonOf(inputs(playing = false)))
        assertEquals("route_changed", reasonOf(inputs(msSinceRoute = 3_999L)))
        assertEquals("speak", reasonOf(inputs(msSinceRoute = 4_000L)))
        // A call beats everything that could be waited for.
        assertEquals("call", reasonOf(inputs(inCall = true, transitionRunning = true, positionMs = 0L)))
    }

    @Test
    fun `a repeat, a line too soon after the last one and a late line are dropped`() {
        assertEquals("repeat", reasonOf(inputs(sameAsLast = true)))
        assertEquals("too_soon", reasonOf(inputs(tracksSinceLast = 3)))
        assertEquals("speak", reasonOf(inputs(tracksSinceLast = 4)))
        assertEquals("window_over", reasonOf(inputs(sinceStartMs = 15_001L)))
        assertEquals("speak", reasonOf(inputs(sinceStartMs = 15_000L)))
    }

    @Test
    fun `the song must have time left for the line and the next transition`() {
        val need = HostVoice.estimateMs(note) + HostVoice.TRANSITION_MARGIN_MS
        assertEquals("short_left", reasonOf(inputs(remainingMs = need - 1)))
        assertEquals("speak", reasonOf(inputs(remainingMs = need)))
        assertEquals("unknown_length", reasonOf(inputs(remainingMs = -1L)))
    }

    @Test
    fun `no voice means silence, a voice that is still starting is waited for, an engine that cannot duck is not spoken over`() {
        assertEquals("no_voice", reasonOf(inputs(voice = HostVoice.VOICE_NONE)))
        val pending = HostVoice.decide(inputs(voice = HostVoice.VOICE_PENDING))
        assertEquals(HostVoice.ACT_WAIT, pending.action)
        assertEquals("voice_pending", pending.reason)
        assertEquals("cant_duck", reasonOf(inputs(canDuck = false)))
    }

    @Test
    fun `a wait is never shorter than a few milliseconds`() {
        assertTrue(HostVoice.decide(inputs(positionMs = 1_499L)).waitMs >= 50L)
    }

    private fun running(
        enabled: Boolean = true, endless: Boolean = true, notesOn: Boolean = true, songChanged: Boolean = false, playing: Boolean = true,
        transitionRunning: Boolean = false, focusLost: Boolean = false, inCall: Boolean = false, routeChanged: Boolean = false,
        volumeTaken: Boolean = false, sinceSpeechStartMs: Long = 1_000L,
    ) = HostVoice.Running(enabled, endless, notesOn, songChanged, playing, transitionRunning, focusLost, inCall, routeChanged, volumeTaken, sinceSpeechStartMs)

    @Test
    fun `a line goes on while nothing changes`() {
        assertEquals("", HostVoice.cancelReason(running()))
        assertEquals("", HostVoice.cancelReason(running(sinceSpeechStartMs = 8_000L)))
    }

    @Test
    fun `a skip, a pause, a transition, a focus loss, a call, a route change or a foreign volume write stop it at once`() {
        assertEquals("skip", HostVoice.cancelReason(running(songChanged = true)))
        assertEquals("pause", HostVoice.cancelReason(running(playing = false)))
        assertEquals("transition", HostVoice.cancelReason(running(transitionRunning = true)))
        assertEquals("focus", HostVoice.cancelReason(running(focusLost = true)))
        assertEquals("call", HostVoice.cancelReason(running(inCall = true)))
        assertEquals("route", HostVoice.cancelReason(running(routeChanged = true)))
        assertEquals("volume_taken", HostVoice.cancelReason(running(volumeTaken = true)))
        assertEquals("too_long", HostVoice.cancelReason(running(sinceSpeechStartMs = 8_001L)))
        assertEquals("off", HostVoice.cancelReason(running(enabled = false)))
        assertEquals("notes_off", HostVoice.cancelReason(running(notesOn = false)))
    }

    @Test
    fun `the duck ramp has exact ends and moves in a straight line`() {
        val down = HostVoice.DUCK_PERMILLE
        assertEquals(1000, HostVoice.ramp(1000, down, 0L, HostVoice.RAMP_DOWN_MS))
        assertEquals(down, HostVoice.ramp(1000, down, HostVoice.RAMP_DOWN_MS, HostVoice.RAMP_DOWN_MS))
        assertEquals(down, HostVoice.ramp(1000, down, 9_999L, HostVoice.RAMP_DOWN_MS))
        assertEquals(675, HostVoice.ramp(1000, down, 125L, HostVoice.RAMP_DOWN_MS))
        assertEquals(1000, HostVoice.ramp(down, 1000, HostVoice.RAMP_UP_MS, HostVoice.RAMP_UP_MS))
        assertEquals(down, HostVoice.ramp(down, 1000, -5L, HostVoice.RAMP_UP_MS))
        // No length: already there.
        assertEquals(1000, HostVoice.ramp(down, 1000, 0L, 0L))
        // Monotone downwards.
        var last = 1000
        var t = 0L
        while (t <= 250L) {
            val v = HostVoice.ramp(1000, down, t, 250L)
            assertTrue(v <= last)
            last = v
            t += 10L
        }
    }

    @Test
    fun `the line is the note, cleaned, and a long or odd one stays on the screen only`() {
        assertEquals("From your Bedroom pop side.", HostVoice.line("From your Bedroom pop side."))
        assertEquals("From your V-pop and Hip hop side.", HostVoice.line("From your V-pop & Hip hop side."))
        assertEquals("Spaced out words.", HostVoice.line("  Spaced   out   words.  "))
        assertEquals("Say this and that.", HostVoice.line("Say *this* and [that]."))
        assertEquals("", HostVoice.line(""))
        assertEquals("", HostVoice.line("   "))
        assertEquals("", HostVoice.line("One. Two. Three."))
        assertEquals("", HostVoice.line("A very very very very very very very very very very long sentence here."))
        assertEquals("", HostVoice.line("Learn the genre."))
        assertEquals("", HostVoice.line("Listen to the lossless sound."))
    }

    @Test
    fun `no spoken sentence has more than twelve words and at most two sentences are said`() {
        val twelve = "one two three four five six seven eight nine ten eleven twelve."
        assertEquals(twelve, HostVoice.line(twelve))
        assertEquals("", HostVoice.line("one two three four five six seven eight nine ten eleven twelve thirteen."))
        assertEquals("Short one. Another short one.", HostVoice.line("Short one. Another short one."))
    }

    @Test
    fun `a set note is said when it is short and stays on the screen when its names make it long`() {
        val short = SetNotes.text(SetNotes.Note(0, SetNotes.KIND_GENRE_THEN_NEW, 3, "V-pop", "", "HIEUTHUHAI"))
        assertEquals("Three from your V-pop side, then something new from HIEUTHUHAI.", HostVoice.line(short))
        val long = SetNotes.text(SetNotes.Note(0, SetNotes.KIND_GENRE_THEN_NEW, 3, "Alternative Dance Electronic Indie", "", "Florence Alexandra Margaret Of The Machine"))
        assertEquals("", HostVoice.line(long))
        // A line is the note, word for word, when nothing needs cleaning.
        assertEquals(short, HostVoice.line(short))
    }

    @Test
    fun `every host note the app can make is a line or stays silent, never a long one`() {
        val kinds = listOf(
            HostNotes.Note(0, HostNotes.KIND_NEW_FROM_ARTIST, "HIEUTHUHAI", ""),
            HostNotes.Note(0, HostNotes.KIND_SIDEWAYS, "Shoegaze", "V-pop"),
            HostNotes.Note(0, HostNotes.KIND_ISLAND, "Soft pop & R&B", ""),
            HostNotes.Note(0, HostNotes.KIND_GENRE_FACT, "Shoegaze", "A guitar wall of sound"),
            HostNotes.Note(0, HostNotes.KIND_YEAR, "2019", ""),
        )
        for (n in kinds) {
            val line = HostVoice.line(HostNotes.text(n))
            assertTrue(line.isNotEmpty(), HostNotes.text(n))
            assertTrue(HostNotes.isSpeakable(line))
            assertTrue(!line.contains('&'))
        }
    }
}
