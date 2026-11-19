package com.theveloper.pixelplay.brain

import java.io.File
import java.security.MessageDigest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The genre atlas asset (docs/brain-spec.md, 3.5): its bytes are pinned by a SHA-256 (the iOS app bundles the same
 * file), it parses, and what it says about the genres the spec names holds. When the atlas is rebuilt on purpose
 * (`python tools/atlas/build_atlas.py`), the build writes the new hash next to this test; run
 * `./gradlew :brain:generateGolden` and commit the new golden files with it.
 */
class AtlasTest {
    private val gz = File(Stage2bGolden.ATLAS_ASSET).readBytes()
    private val atlas by lazy { GenreAtlas.parse(Stage2bGolden.atlasText(File("."))) }

    @Test
    fun `the asset is the pinned bytes`() {
        val want = File("src/test/resources/atlas/genre-atlas-v1.sha256").readText().trim().substring(0, 64)
        val got = MessageDigest.getInstance("SHA-256").digest(gz).joinToString("") { "%02x".format(it) }
        assertEquals(want, got, "the atlas asset changed: rebuild the golden files and commit the new hash")
    }

    @Test
    fun `it is a gzip of about the size the spec estimates`() {
        assertTrue(gz.size in 100_000..260_000, "gzip bytes ${gz.size}")
        assertEquals(0x1f, gz[0].toInt() and 0xFF)
        assertEquals(0x8b, gz[1].toInt() and 0xFF)
        // mtime 0 in the header: the build is reproducible byte for byte.
        for (i in 4..7) assertEquals(0, gz[i].toInt())
    }

    @Test
    fun `every musicbrainz genre is in and ids are unique and small`() {
        assertTrue(atlas.size >= 2200, "genres ${atlas.size}")
        assertTrue(atlas.allIds.last() < 32000)
        assertEquals(atlas.allIds.toSet().size, atlas.allIds.size)
        assertEquals(24, atlas.families.size)
        assertTrue(atlas.moods.size in 30..50)
    }

    @Test
    fun `parents form no loop`() {
        for (id in atlas.allIds) {
            val up = atlas.ancestors(id)
            assertFalse(id in up, "genre $id is its own ancestor")
            assertEquals(up.toSet().size, up.size)
        }
    }

    @Test
    fun `descriptors are in range`() {
        var described = 0
        for (id in atlas.allIds) {
            val g = atlas.genre(id)!!
            val d = g.descriptor ?: continue
            described++
            for (v in intArrayOf(d.energy, d.valence, d.danceability, d.acousticness)) assertTrue(v in 0..255, "genre $id")
            assertTrue(d.tempoLo in 30..320 && d.tempoHi >= d.tempoLo, "genre $id tempo")
            assertTrue(d.moods.size <= 4)
            for (m in d.moods) assertTrue(m in atlas.moods.indices)
            assertTrue(g.depth >= 0)
        }
        assertTrue(described > 1000, "described $described")
    }

    @Test
    fun `the spec's own genres are there`() {
        for (n in listOf("shoegaze", "k-pop", "v-pop", "vinahouse", "hip hop", "trap", "bedroom pop", "city pop", "nhạc vàng", "vietnamese hip hop")) {
            assertNotEquals(GenreAtlas.NONE, atlas.idForName(n), n)
        }
        // Names and aliases fold the same way: case, accents, spacing and hyphens do not matter.
        assertEquals(atlas.idForName("K-Pop"), atlas.idForName("k pop"))
        assertEquals(atlas.idForName("Nhạc Vàng"), atlas.idForName("nhac vang"))
        assertEquals(atlas.idForName("Hip-Hop"), atlas.idForName("hip hop"))
        assertEquals(GenreAtlas.NONE, atlas.idForName("not a genre at all"))
        assertEquals(GenreAtlas.NONE, atlas.idForName(""))
    }

    @Test
    fun `a source table marks what is not a genre and never guesses`() {
        assertEquals(0, atlas.idsForSource("deezer", "All")!!.size)
        assertEquals(0, atlas.idsForSource("itunes", "Holiday")!!.size)
        // "Kids" is not a genre however its words look: resolve stays empty.
        assertEquals(0, atlas.resolve("deezer", "Kids").size)
        assertNull(atlas.idsForSource("deezer", "zzz"))
        assertEquals(0, atlas.resolve("deezer", "zzz").size)
        val hip = atlas.resolve("deezer", "Rap/Hip Hop")
        assertEquals(listOf(atlas.idForName("hip hop")), hip.toList())
        assertEquals(2, atlas.resolve("itunes", "R&B/Soul").size)
    }

    @Test
    fun `the vietnamese and korean corner is curated`() {
        val vh = atlas.genre(atlas.idForName("vietnamese hip hop"))!!
        assertEquals("", vh.mbid)
        assertTrue(vh.parents.contains(atlas.idForName("hip hop")))
        assertNotEquals(GenreAtlas.NONE, atlas.idForName("rap việt"))
        assertEquals(atlas.idForName("vietnamese hip hop"), atlas.idForName("viet rap"))
        assertEquals(atlas.idForName("korean hip hop"), atlas.idForName("k-rap"))
        assertEquals(atlas.idForName("k-pop"), atlas.idForName("k-pop girl group"))
        assertEquals(atlas.idForName("v-pop"), atlas.idForName("nhạc trẻ"))
    }

    @Test
    fun `a descendant without its own descriptor inherits one`() {
        val trap = atlas.genre(atlas.idForName("trap"))!!
        assertTrue(trap.descriptor != null)
        var inherited = 0
        for (id in atlas.allIds) {
            val g = atlas.genre(id)!!
            if (g.descriptor != null && g.description.isEmpty()) inherited++
        }
        assertTrue(inherited > 500, "inherited $inherited")
    }

    @Test
    fun `an old atlas version or junk is refused`() {
        assertFalse(runCatching { GenreAtlas.parse("""{"v":2,"g":[]}""") }.isSuccess)
        assertFalse(runCatching { GenreAtlas.parse("[]") }.isSuccess)
        assertEquals(0, GenreAtlas.parse("""{"v":1,"fam":[],"moods":[],"g":[]}""").size)
    }
}
