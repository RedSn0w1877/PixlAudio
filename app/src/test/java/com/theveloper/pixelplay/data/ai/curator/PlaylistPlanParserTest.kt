package com.theveloper.pixelplay.data.ai.curator

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.tais.dj.TaisIntentParser
import org.junit.jupiter.api.Test

class PlaylistPlanParserTest {

    private val vocabulary = LibraryVocabulary(
        genres = listOf("Indie Rock", "Jazz", "Hip-Hop", "Classical", "Pop"),
        artists = listOf("Radiohead", "Bon Iver", "Miles Davis", "Taylor Swift"),
    )

    @Test
    fun `a valid plan is read`() {
        val plan = PlaylistPlanParser.parse(
            """{"genres":["jazz"],"artists":["Miles Davis"],"moods":["chill"],"energy":2,"discovery":4,"eraFrom":1955,"eraTo":1970,"keywords":["blue"]}""",
            vocabulary,
        )!!
        assertThat(plan.genres).containsExactly("Jazz")
        assertThat(plan.artists).containsExactly("Miles Davis")
        assertThat(plan.moods).containsExactly("chill")
        assertThat(plan.energy).isEqualTo(2)
        assertThat(plan.discovery).isEqualTo(4)
        assertThat(plan.eraFrom).isEqualTo(1955)
        assertThat(plan.eraTo).isEqualTo(1970)
        assertThat(plan.keywords).containsExactly("blue")
    }

    @Test
    fun `JSON wrapped in prose or code fences is still read`() {
        val raw = "Sure! Here is the plan:\n```json\n{\"genres\": [\"indie\"], \"moods\": [\"relaxing\"], energy: 1}\n```\nEnjoy."
        val plan = PlaylistPlanParser.parse(raw, vocabulary)!!
        assertThat(plan.genres).containsExactly("Indie Rock")
        assertThat(plan.moods).containsExactly("chill")
        assertThat(plan.energy).isEqualTo(1)
    }

    @Test
    fun `garbage gives no plan, and the keyword fallback still makes one`() {
        assertThat(PlaylistPlanParser.parse("I can't help with that.", vocabulary)).isNull()
        val fallback = PlaylistPlanParser.fromIntent(TaisIntentParser().parse("chill jazz like Miles Davis"), vocabulary)
        assertThat(fallback.genres).containsExactly("Jazz")
        assertThat(fallback.moods).contains("chill")
        assertThat(fallback.artists).containsExactly("Miles Davis")
    }

    @Test
    fun `names map onto the library case-insensitively and unknown ones are dropped`() {
        val plan = PlaylistPlanParser.parse(
            """{"genres":["HIP HOP","Polka"],"artists":["radiohead","Unknown Band"],"moods":["sleepy","angry"],"energy":9}""",
            vocabulary,
        )!!
        assertThat(plan.genres).containsExactly("Hip-Hop")
        assertThat(plan.artists).containsExactly("Radiohead")
        assertThat(plan.moods).containsExactly("sleep")
        assertThat(plan.energy).isEqualTo(5)
    }

    @Test
    fun `a reversed era is put the right way round`() {
        val plan = PlaylistPlanParser.parse("""{"eraFrom":2010,"eraTo":1990}""", vocabulary)!!
        assertThat(plan.eraFrom).isEqualTo(1990)
        assertThat(plan.eraTo).isEqualTo(2010)
    }
}

class OrderResponseParserTest {

    @Test
    fun `a clean answer is the permutation`() {
        assertThat(OrderResponseParser.parse("3, 1, 2", 3)).containsExactly(2, 0, 1).inOrder()
    }

    @Test
    fun `repeats and out-of-range numbers are dropped and missing songs appended in order`() {
        assertThat(OrderResponseParser.parse("3,3,99,1", 4)).containsExactly(2, 0, 1, 3).inOrder()
    }

    @Test
    fun `an empty answer keeps the original order`() {
        assertThat(OrderResponseParser.parse("", 3)).containsExactly(0, 1, 2).inOrder()
        assertThat(OrderResponseParser.parse("anything", 0)).isEmpty()
    }

    @Test
    fun `prose with numbers still yields every song once`() {
        val order = OrderResponseParser.parse("Start with 2 for energy, then 4. Finally 1 and 3.", 5)
        assertThat(order).containsExactly(1, 3, 0, 2, 4).inOrder()
        assertThat(order.toSet()).hasSize(5)
    }
}
