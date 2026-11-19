package com.theveloper.pixelplay.brain

/**
 * A genre page's facts (docs/brain-spec.md, 8.7): only what the atlas holds. The description exists for the roughly 200 genres the atlas
 * describes by hand; for the others the page shows facts alone, and nothing is written at run time. A fact the atlas does not have
 * is absent, never guessed.
 */
object GenrePage {
    class Page(
        val id: Int,
        val name: String,
        /** The atlas's own paragraph; null for a genre it does not describe. */
        val description: String?,
        /** "Seoul · 1990s": the first regions and the decade the genre began; null when the atlas has neither. */
        val origin: String?,
        /** "Comes from". */
        val parents: IntArray,
        /** "Branches": the genres that list this one as a parent. */
        val children: IntArray,
        /** "Close to". */
        val related: IntArray,
        /** "Shaped by": the genres it was influenced by, and the ones it fuses. */
        val influences: IntArray,
    )

    /** The page of [id], or null when the atlas has no such genre. */
    fun of(atlas: GenreAtlas, id: Int): Page? {
        val g = atlas.genre(id) ?: return null
        val children = ArrayList<Int>()
        for (other in atlas.allIds) {
            val o = atlas.genre(other) ?: continue
            for (p in o.parents) if (p == id) {
                children.add(other)
                break
            }
        }
        val influences = LinkedHashSet<Int>()
        for (i in g.influencedBy.sortedArray()) influences.add(i)
        for (i in g.fusionOf.sortedArray()) influences.add(i)
        val regions = g.regions.filter { it.isNotBlank() }.take(2).joinToString(", ")
        val era = if (g.eraStart in 1000..2999) "${(g.eraStart / 10) * 10}s" else ""
        val origin = listOf(regions, era).filter { it.isNotEmpty() }.joinToString(" · ").ifEmpty { null }
        return Page(
            id = id,
            name = g.name,
            description = g.description.ifBlank { null },
            origin = origin,
            parents = g.parents.sortedArray(),
            children = children.toIntArray(),
            related = g.related.sortedArray(),
            influences = influences.toIntArray(),
        )
    }
}
