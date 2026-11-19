package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GenrePageTest {
    private val atlas: GenreAtlas by lazy { GenreAtlas.parse(Stage2bGolden.atlasText(File("."))) }

    @Test
    fun `a page holds only what the atlas holds`() {
        // Every genre the atlas has gets a page whose links all point at genres the atlas has.
        var described = 0
        for (id in atlas.allIds) {
            val page = GenrePage.of(atlas, id)
            assertNotNull(page, "page of $id")
            page!!
            assertEquals(atlas.genre(id)!!.name, page.name)
            for (link in page.parents + page.children + page.related + page.influences) assertNotNull(atlas.genre(link), "link $link of $id")
            for (child in page.children) assertTrue(id in atlas.genre(child)!!.parents)
            if (page.description != null) {
                described++
                assertTrue(page.description.isNotBlank())
            }
            // A genre never links to itself as its own parent or child.
            assertTrue(id !in page.parents)
            assertTrue(id !in page.children)
        }
        assertTrue(described in 100..400, "about 200 genres are described: $described")
    }

    @Test
    fun `an unknown genre has no page`() {
        assertNull(GenrePage.of(atlas, GenreAtlas.NONE))
        assertNull(GenrePage.of(atlas, 10_000_000))
    }

    @Test
    fun `a genre with no description shows no description and one with a start year shows its decade`() {
        val plain = atlas.allIds.first { atlas.genre(it)!!.description.isBlank() }
        assertNull(GenrePage.of(atlas, plain)!!.description)
        val dated = atlas.allIds.firstOrNull { atlas.genre(it)!!.eraStart in 1000..2999 }
        if (dated != null) {
            val origin = GenrePage.of(atlas, dated)!!.origin
            assertNotNull(origin)
            assertTrue(origin!!.endsWith("s"), origin)
        }
    }
}
