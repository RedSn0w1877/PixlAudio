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
    )

    /**
     * Decision D15, as stage 3 relaxes it: "learn" may appear only in the strings the spec names for Your sound (8.6), each of which is shown
     * only when it is true of the model at that moment (see ModelVoice). Every other string still fails on it.
     */
    private val learnAllowed = setOf("brain_sound_model_learning", "brain_sound_reset", "brain_sound_reset_title")

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

    /** `name -> items` of every plurals resource of a file, so a plural cannot slip a forbidden word past the test. */
    private fun plurals(file: String): Map<String, List<String>> {
        val candidates = listOf(File("src/main/res/values/$file"), File("app/src/main/res/values/$file"))
        val xml = candidates.first { it.isFile }.readText()
        val out = LinkedHashMap<String, List<String>>()
        for (match in Regex("<plurals name=\"([^\"]+)\">(.*?)</plurals>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            out[match.groupValues[1]] = Regex("<item[^>]*>(.*?)</item>", RegexOption.DOT_MATCHES_ALL).findAll(match.groupValues[2]).map { it.groupValues[1] }.toList()
        }
        return out
    }

    @Test
    fun `no brain string uses a word the app never says`() {
        val brain = strings("strings_brain.xml")
        // Plurals are held to the same rule: each of their items is checked as a string of that name.
        for ((name, items) in plurals("strings_brain.xml")) {
            assertTrue(items.isNotEmpty(), "$name has items")
            for (text in items) {
                val lower = text.lowercase()
                for (word in forbidden) assertTrue(word !in lower, "$name says \"$word\": $text")
                if (name !in learnAllowed) assertTrue("learn" !in lower, "$name says \"learn\": $text")
            }
        }
        assertEquals(listOf("Learning from %1\$d play.", "Learning from %1\$d plays."), plurals("strings_brain.xml")["brain_sound_model_learning"])
        assertTrue(brain.size >= 30, "expected the brain's strings, found ${brain.size}")
        val autoplay = strings("strings_player.xml").filterKeys { it.startsWith("queue_autoplay_") }
        assertTrue(autoplay.isNotEmpty())
        for ((name, text) in brain + autoplay) {
            val lower = text.lowercase()
            for (word in forbidden) assertTrue(word !in lower, "$name says \"$word\": $text")
            if (name !in learnAllowed) assertTrue("learn" !in lower, "$name says \"learn\": $text")
        }
        // The allowed strings are really there, and the allowance is not a loophole for anything else.
        assertEquals("Reset what I\\'ve learned", brain["brain_sound_reset"])
        for (name in learnAllowed) assertTrue(name in brain || name in plurals("strings_brain.xml"), "$name is a brain string")
    }

    /**
     * Ask Taizo's words (stage 4): its cards, its status lines and the settings rows around it. Never a forbidden word, never the vendor or the
     * model, and "learn" in none of them: the only place stage 3 lets that word in is Your sound, where it follows the model's real state.
     */
    @Test
    fun `what Ask Taizo and its settings say follows the same rules`() {
        val all = strings("strings.xml").filterKeys {
            it.startsWith("taizo_") || it.startsWith("settings_ai_") || it.startsWith("settings_ask_taizo") || it.startsWith("settings_claude_")
        }
        assertTrue(all.size >= 20, "expected Taizo's strings, found ${all.size}")
        val brain = strings("strings_brain.xml").filterKeys { it.startsWith("brain_taizo_") || it.startsWith("brain_assist_") || it.startsWith("brain_weekly_") || it.startsWith("brain_why_factors_") }
        assertTrue(brain.size >= 12, "expected the new cards' strings, found ${brain.size}")
        for ((name, text) in all + brain) {
            val lower = text.lowercase()
            for (word in forbidden) assertTrue(word !in lower, "$name says \"$word\": $text")
            assertTrue("learn" !in lower, "$name says \"learn\": $text")
        }
        for ((name, items) in plurals("strings_brain.xml").filterKeys { it.startsWith("brain_taizo_") }) {
            for (text in items) for (word in forbidden + "learn") assertTrue(word !in text.lowercase(), "$name says \"$word\": $text")
        }
        // The switch is called what the spec calls it, and says it is off until turned on.
        assertEquals("Let Taizo help the engine", brain["brain_assist_title"])
        assertTrue("Off unless you turn it on." in brain.getValue("brain_assist_body"))
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

    /**
     * Endless Listening's words: never a forbidden word, never "learn", never the name or the branding of another service's host feature, and the
     * intro says exactly what the design says.
     */
    @Test
    fun `Endless Listening copy is plain, says no forbidden word and keeps the intro copy exact`() {
        val endless = strings("strings_endless.xml")
        assertTrue(endless.isNotEmpty())
        for ((name, text) in endless) {
            val lower = text.lowercase()
            for (word in forbidden) assertTrue(word !in lower, "$name says \"$word\": $text")
            assertTrue("learn" !in lower, "$name says \"learn\": $text")
            assertTrue("spotify" !in lower && "dj" !in lower.split(Regex("[^a-z]+")), "$name names another service or its host: $text")
        }
        assertEquals("Music that keeps going.", endless["endless_intro_title_1"])
        assertEquals("Endless Listening starts from what you play and keeps adding more until you stop it.", endless["endless_intro_body_1"])
        assertEquals("A host, if you want one.", endless["endless_intro_title_2"])
        assertEquals("Short notes between songs say why something is here. Ask for something new, or why a song was picked.", endless["endless_intro_body_2"])
        assertEquals("You decide.", endless["endless_intro_title_3"])
        assertEquals("Choose how much is new, skip anything, and turn the notes off any time.", endless["endless_intro_body_3"])
        assertEquals("Start listening", endless["endless_intro_start"])
        assertEquals("Not now", endless["endless_intro_not_now"])
        assertEquals("Ask the host", endless["endless_ask_host"])
    }
}
