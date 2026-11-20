package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test

/** The grounding rule over a whole conversation (docs/brain-spec.md, 1 principle 9 and 9.4): what a tool returned may be said, nothing else. */
class TaizoGroundingTest {
    private val g = TaizoGrounding()
    private val placeholder = "(unchecked)"

    @Test
    fun `a name a tool returned may be named, marked or not, and an invented one is replaced`() {
        g.noteTrack(TrackInfo("yt:abcdefghijk", "Espresso", "Sabrina Carpenter, Chappell Roan", 2024, inLibrary = false, album = "Short n' Sweet"))
        val v = g.check("Try [[Espresso]] by [[Sabrina Carpenter]], from [[Short n' Sweet]]. Also [[Good Luck, Babe!]].", placeholder)
        assertThat(v.ungroundedNames).containsExactly("Good Luck, Babe!")
        assertThat(v.cleaned).isEqualTo("Try Espresso by Sabrina Carpenter, from Short n' Sweet. Also (unchecked).")
        // Each credited artist is a name of its own.
        assertThat(g.check("[[Chappell Roan]]", placeholder).ok).isTrue()
        assertThat(g.check("[[Sabrina Carpenter & Chappell Roan]]", placeholder).ok).isTrue()
    }

    @Test
    fun `a ref is usable once a tool issued it, and not before`() {
        assertThat(g.isKnownRef("yt:abcdefghijk")).isFalse()
        g.noteTrack(TrackInfo("yt:abcdefghijk", "Espresso", "Sabrina Carpenter", 2024, false))
        assertThat(g.isKnownRef("yt:abcdefghijk")).isTrue()
        val unknown = ArrayList<String>()
        assertThat(g.known(listOf("yt:abcdefghijk", "yt:zzzzzzzzzzz"), unknown)).containsExactly("yt:abcdefghijk")
        assertThat(unknown).containsExactly("yt:zzzzzzzzzzz")
        g.reset()
        assertThat(g.isKnownRef("yt:abcdefghijk")).isFalse()
        assertThat(g.check("[[Espresso]]", placeholder).ok).isFalse()
    }

    @Test
    fun `any tool result, the older ones too, makes its names and numbers sayable`() {
        g.noteToolResult("""{"results":[{"id":"12","title":"Blinding Lights","artist":"The Weeknd","plays":212}],"note":"x"}""")
        assertThat(g.check("[[Blinding Lights]] by [[The Weeknd]]: 212 plays.", placeholder).ok).isTrue()
        assertThat(g.check("[[Save Your Tears]].", placeholder).ok).isFalse()
        assertThat(g.check("That is 400 plays.", placeholder).ok).isFalse()
    }

    @Test
    fun `what the app showed the model counts, line by line`() {
        g.noteContext("Top artists: Radiohead (212), Phoebe Bridgers (90)\nMost played songs:\n- Motion Sickness - Phoebe Bridgers (40 plays)")
        assertThat(g.check("[[Radiohead]] and [[Phoebe Bridgers]] and [[Motion Sickness]]; 212 plays.", placeholder).ok).isTrue()
        assertThat(g.check("[[Thom Yorke]]", placeholder).ok).isFalse()
    }

    @Test
    fun `what the listener wrote is theirs, across turns`() {
        g.noteUser("play something like Please Please Please")
        g.noteUser("make it 30 songs")
        assertThat(g.check("[[Please Please Please]] is a good place to start, with 30 songs.", placeholder).ok).isTrue()
        assertThat(g.check("[[Taste]] and 99 songs.", placeholder).ok).isFalse()
    }

    @Test
    fun `an older chat's tool results and words come back from its history`() {
        val history: List<JsonObject> = listOf(
            buildJsonObject { put("role", "user"); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "who is Boygenius") }) }) },
            buildJsonObject {
                put("role", "user")
                put(
                    "content",
                    buildJsonArray {
                        add(buildJsonObject { put("type", "tool_result"); put("tool_use_id", "t1"); put("content", """{"artists":[{"name":"Lucy Dacus","score":88}]}""") })
                    },
                )
            },
            buildJsonObject { put("role", "assistant"); put("content", buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", "[[Lucy Dacus]] is one of them.") }) }) },
        )
        g.noteHistory(history)
        assertThat(g.isSeeded).isTrue()
        assertThat(g.check("[[Lucy Dacus]] and [[Boygenius]].", placeholder).ok).isTrue()
        assertThat(g.check("[[Julien Baker]]", placeholder).ok).isFalse()
    }

    @Test
    fun `a name that is too long to be a name is not registered`() {
        g.noteName("x".repeat(500))
        assertThat(g.knownNames).isEqualTo(0)
        g.noteName("   ")
        g.noteName(null)
        assertThat(g.knownNames).isEqualTo(0)
    }

    @Test
    fun `credits split on the joiners listeners write`() {
        assertThat(TaizoGrounding.splitCredits("A, B & C feat. D")).containsExactly("A", "B", "C", "D").inOrder()
        assertThat(TaizoGrounding.splitCredits("Tyler, The Creator")).containsExactly("Tyler", "The Creator").inOrder()
        assertThat(Json.parseToJsonElement("{}")).isNotNull()
    }

    @Test
    fun `a name with no mark is caught, and refs and genre ids come back from a result or an older chat`() {
        g.noteName("Espresso")
        assertThat(g.check("You could also try Radiohead.", placeholder).ungroundedNames).containsExactly("Radiohead")
        assertThat(g.check("Espresso is a good place to start.", placeholder).ok).isTrue()
        // A result's own refs and ids are known without the track details being kept.
        g.noteToolResult("""{"results":[{"ref":"yt:abcdefghijk","title":"Espresso"}],"id":12,"name":"Trap"}""")
        assertThat(g.isKnownRef("yt:abcdefghijk")).isTrue()
        assertThat(g.isKnownRef("yt:zzzzzzzzzzz")).isFalse()
        val unknown = ArrayList<String>()
        assertThat(g.knownGenreIds(listOf(12, 13), unknown)).containsExactly(12)
        assertThat(unknown).containsExactly("genre 13")
        assertThat(g.genreName(12)).isEqualTo("Trap")
    }
}
