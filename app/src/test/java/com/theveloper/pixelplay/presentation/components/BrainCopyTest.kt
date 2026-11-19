package com.theveloper.pixelplay.presentation.components

import android.content.res.Resources
import com.theveloper.pixelplay.R
import io.mockk.every
import io.mockk.mockk
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The words the music brain shows (docs/brain-spec.md, section 8 "Copy rule", decision D15): none of
 * the words the app never says, nothing that claims learning before stage 3, and a reason line only
 * when there is something true to say.
 */
class BrainCopyTest {

    private val forbidden = listOf(
        "lossless", "hi-res", "hires", "dolby", "atmos", "360 reality", "claude", "anthropic", "binilyrics", "enhanced",
        // Decision D15: stages 1 and 2 say "based on what you play".
        "learn",
    )

    /** `name -> text` of every string of a resource file, read as text (no Android runtime needed). */
    private fun strings(file: String): Map<String, String> {
        val candidates = listOf(File("src/main/res/values/$file"), File("app/src/main/res/values/$file"))
        val xml = candidates.first { it.isFile }.readText()
        val out = LinkedHashMap<String, String>()
        for (match in Regex("<string name=\"([^\"]+)\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            out[match.groupValues[1]] = match.groupValues[2]
        }
        return out
    }

    @Test
    fun `no brain string uses a word the app never says`() {
        val brain = strings("strings_brain.xml")
        assertTrue(brain.size >= 30, "expected the brain's strings, found ${brain.size}")
        val autoplay = strings("strings_player.xml").filterKeys { it.startsWith("queue_autoplay_") }
        assertTrue(autoplay.isNotEmpty())
        for ((name, text) in brain + autoplay) {
            val lower = text.lowercase()
            for (word in forbidden) assertTrue(word !in lower, "$name says \"$word\": $text")
        }
    }

    @Test
    fun `the copy of section 8 is what the listener reads`() {
        val brain = strings("strings_brain.xml")
        assertEquals("Start station", brain["brain_start_station"])
        assertEquals("Station · %1\$s", brain["brain_station_title"])
        assertEquals("Familiar", brain["brain_novelty_familiar"])
        assertEquals("Blend", brain["brain_novelty_blend"])
        assertEquals("Discover", brain["brain_novelty_discover"])
        assertEquals("Not for me", brain["brain_not_for_me"])
        assertEquals("Got it. You won\\'t hear this in mixes again.", brain["brain_not_for_me_done"])
        assertEquals("Less from %1\$s", brain["brain_less_from"])
        assertEquals("Fans of %1\$s also play this", brain["brain_why_radio_fans"])
        assertEquals("Because you liked %1\$s", brain["brain_why_because_liked"])
        assertEquals("Because you listened to %1\$s", brain["brain_why_because_listened"])
        assertEquals("More from %1\$s", brain["brain_why_artist_more"])
        assertEquals("On repeat lately", brain["brain_why_on_repeat"])
        assertEquals("How picks work", brain["brain_how_picks_work"])
        // Stage 2a keeps the other half: a song is in one mix a day (served log, 6.4), and says so (8.9).
        assertEquals(
            "A song is never in two mixes on the same day, and a new song you skipped three times stays away for 60 days.",
            brain["brain_promise_repeat_body"],
        )
    }

    @Test
    fun `a reason line is a true statement or nothing`() {
        val resources = mockk<Resources> {
            every { getString(R.string.brain_why_radio_fans, "d4vd") } returns "Fans of d4vd also play this"
            every { getString(R.string.brain_why_on_repeat) } returns "On repeat lately"
            every { getString(R.string.brain_why_new_release, "X") } returns "New from X"
            every { getString(R.string.brain_why_similar_artist, "X") } returns "Close to X"
        }
        assertEquals("Fans of d4vd also play this", WhyLineText.format(resources, "radio_fans", "d4vd"))
        assertEquals("On repeat lately", WhyLineText.format(resources, "on_repeat", ""))
        // A sentence that needs a name is not shown without one.
        assertNull(WhyLineText.format(resources, "radio_fans", ""))
        assertNull(WhyLineText.format(resources, "because_liked", "  "))
        assertNull(WhyLineText.format(resources, "artist_more", null))
        // Stage 2a serves two more arms: new releases and similar artists.
        assertEquals("New from X", WhyLineText.format(resources, "new_release", "X"))
        assertEquals("Close to X", WhyLineText.format(resources, "similar_artist", "X"))
        assertNull(WhyLineText.format(resources, "new_release", ""))
        // Keys of arms no surface serves yet, and nonsense, show nothing.
        assertNull(WhyLineText.format(resources, "forgotten", "X"))
        assertNull(WhyLineText.format(resources, "nonsense", "X"))
        assertNull(WhyLineText.format(resources, null, "X"))
    }
}
