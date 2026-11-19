package com.theveloper.pixelplay.brain

/**
 * The genre atlas (docs/brain-spec.md, 3.5): every MusicBrainz genre plus the hand-curated Vietnamese, Korean and
 * soundtrack corner, with parents, families, depth, descriptors and the per-source name tables, parsed once from
 * `genre-atlas-v1.json.gz` (decompressed by the caller: the core opens no file and no stream).
 *
 * Two lookups the rest of the brain needs: by MusicBrainz id, and by name. A name is folded with
 * [BrainText.fold] and then looked up by the FNV-1a hash of its UTF-8 bytes (10.3 rule 13: text is never compared
 * as a Swift or Kotlin string, because the two disagree on canonical equivalence). Names and aliases of one node,
 * and the names of the source tables, share one index; when two nodes claim the same folded name the smaller id wins.
 *
 * Unknown stays unknown: every lookup answers [NONE] (or null) for what the atlas does not have, and nothing here
 * guesses a genre.
 */
class GenreAtlas private constructor(
    val version: Int,
    val families: List<String>,
    val moods: List<String>,
    private val genres: Array<Genre?>,
    private val ids: IntArray,
    private val byMbid: HashMap<String, Int>,
    private val byName: HashMap<Long, Int>,
    private val sources: HashMap<String, HashMap<Long, IntArray>>,
) {
    /** What a genre sounds like: 0..255 scales, a tempo range, and up to 4 mood ids (index into [moods]). */
    class Descriptor(
        val energy: Int,
        val valence: Int,
        val danceability: Int,
        val acousticness: Int,
        val tempoLo: Int,
        val tempoHi: Int,
        val moods: IntArray,
    )

    class Genre(
        val id: Int,
        /** MusicBrainz genre id; empty for a curated node. */
        val mbid: String,
        val wikidata: String,
        val name: String,
        val aliases: List<String>,
        val parents: IntArray,
        val influencedBy: IntArray,
        val fusionOf: IntArray,
        val related: IntArray,
        /** Index into [families]; -1 when no root is reachable. */
        val family: Int,
        /** Steps below the family root along `parents` (a root is 0); 1 when it has no family. */
        val depth: Int,
        val regions: List<String>,
        /** The year the genre began, 0 unknown. */
        val eraStart: Int,
        /** Its own descriptor or the nearest described ancestor's (inherited at build time); null when none. */
        val descriptor: Descriptor?,
        /** A one-paragraph description for the genres the atlas describes by hand; empty otherwise. */
        val description: String,
    )

    /** Atlas ids in ascending order. */
    val allIds: IntArray get() = ids

    val size: Int get() = ids.size

    fun genre(id: Int): Genre? = if (id in genres.indices) genres[id] else null

    fun descriptor(id: Int): Descriptor? = genre(id)?.descriptor

    fun depth(id: Int): Int = genre(id)?.depth ?: 0

    /** The atlas id of a MusicBrainz genre id (lower case, hyphenated), or [NONE]. */
    fun idForMbid(mbid: String): Int = byMbid[mbid] ?: NONE

    /** The atlas id a name or alias means, or [NONE]. Case, accents, spacing and punctuation do not matter. */
    fun idForName(name: String): Int {
        val key = BrainText.fold(name).trim()
        if (key.isEmpty()) return NONE
        return byName[BrainHash.fnv1a64(key)] ?: NONE
    }

    /**
     * What a source's own genre string means ([source]: "deezer", "itunes", "chip"). null when the table has no
     * entry for the string (the caller may still try [idForName]); an empty array when the table says it is not a
     * genre ("All", "Kids"): it must never be mapped or guessed.
     */
    fun idsForSource(source: String, raw: String): IntArray? {
        val table = sources[source] ?: return null
        val key = BrainText.fold(raw).trim()
        if (key.isEmpty()) return null
        return table[BrainHash.fnv1a64(key)]
    }

    /**
     * [idsForSource], then [idForName]: every atlas id a raw genre string from [source] stands for, empty when none.
     * A string the table marks "not a genre" stays empty even if its words are a genre's name.
     */
    fun resolve(source: String, raw: String): IntArray {
        idsForSource(source, raw)?.let { return it }
        val id = idForName(raw)
        return if (id == NONE) EMPTY else intArrayOf(id)
    }

    /**
     * The genre's ancestors, nearest first (breadth first over `parents`; within one step the smaller id first),
     * each once, without the genre itself.
     */
    fun ancestors(id: Int): IntArray {
        val start = genre(id) ?: return EMPTY
        val out = ArrayList<Int>()
        val seen = HashSet<Int>()
        seen.add(id)
        var level = start.parents.toList().sorted()
        while (level.isNotEmpty()) {
            val next = ArrayList<Int>()
            for (p in level) {
                if (!seen.add(p)) continue
                out.add(p)
                val g = genre(p) ?: continue
                for (q in g.parents) next.add(q)
            }
            level = next.distinct().sorted()
        }
        return out.toIntArray()
    }

    companion object {
        const val NONE = -1
        private val EMPTY = IntArray(0)

        /**
         * Parses the atlas's JSON text. Throws [IllegalArgumentException] on a document that is not an atlas of a
         * version this code knows, so a corrupt asset is noticed at once rather than read as "no genres".
         */
        fun parse(json: String): GenreAtlas {
            val root = BrainJson.parse(json) as? Map<*, *> ?: throw IllegalArgumentException("not an atlas")
            val version = (root["v"] as? Long)?.toInt() ?: throw IllegalArgumentException("no atlas version")
            if (version != 1) throw IllegalArgumentException("atlas version $version is not known")
            val families = strings(root["fam"])
            val moods = strings(root["moods"])
            val list = root["g"] as? List<*> ?: throw IllegalArgumentException("no genres")
            var maxId = 0
            for (g in list) maxId = maxOf(maxId, ((g as Map<*, *>)["i"] as Long).toInt())
            val genres = arrayOfNulls<Genre>(maxId + 1)
            val ids = ArrayList<Int>(list.size)
            val byMbid = HashMap<String, Int>(list.size * 2)
            for (raw in list) {
                val m = raw as Map<*, *>
                val id = (m["i"] as Long).toInt()
                val x = m["x"] as? Map<*, *>
                val descriptor = if (x == null) null else {
                    val t = x["t"] as List<*>
                    Descriptor(
                        energy = (x["e"] as Long).toInt(),
                        valence = (x["v"] as Long).toInt(),
                        danceability = (x["dn"] as Long).toInt(),
                        acousticness = (x["ac"] as Long).toInt(),
                        tempoLo = (t[0] as Long).toInt(),
                        tempoHi = (t[1] as Long).toInt(),
                        moods = ints(x["mood"]),
                    )
                }
                val era = (m["era"] as? List<*>)?.firstOrNull() as? Long
                val g = Genre(
                    id = id,
                    mbid = m["m"] as? String ?: "",
                    wikidata = m["w"] as? String ?: "",
                    name = m["n"] as String,
                    aliases = strings(m["a"]),
                    parents = ints(m["p"]),
                    influencedBy = ints(m["inf"]),
                    fusionOf = ints(m["fus"]),
                    related = ints(m["rel"]),
                    family = (m["fam"] as? Long)?.toInt() ?: -1,
                    depth = (m["d"] as? Long)?.toInt() ?: 1,
                    regions = strings(m["reg"]),
                    eraStart = era?.toInt() ?: 0,
                    descriptor = descriptor,
                    description = m["desc"] as? String ?: "",
                )
                if (genres[id] != null) throw IllegalArgumentException("atlas id $id appears twice")
                genres[id] = g
                ids.add(id)
                if (g.mbid.isNotEmpty()) byMbid[g.mbid] = id
            }
            ids.sort()
            val idArray = ids.toIntArray()
            // Names, in id order, so that when two nodes fold to one name the smaller id keeps it.
            val byName = HashMap<Long, Int>(list.size * 4)
            fun claim(text: String, id: Int) {
                val key = BrainText.fold(text).trim()
                if (key.isEmpty()) return
                val h = BrainHash.fnv1a64(key)
                if (!byName.containsKey(h)) byName[h] = id
            }
            for (id in idArray) {
                val g = genres[id]!!
                claim(g.name, id)
            }
            for (id in idArray) {
                val g = genres[id]!!
                for (a in g.aliases) claim(a, id)
            }
            val sources = HashMap<String, HashMap<Long, IntArray>>()
            val src = root["src"] as? Map<*, *> ?: emptyMap<Any, Any>()
            for ((name, table) in src) {
                val t = HashMap<Long, IntArray>()
                for ((raw, target) in table as Map<*, *>) {
                    val key = BrainText.fold(raw as String).trim()
                    if (key.isEmpty()) continue
                    val h = BrainHash.fnv1a64(key)
                    if (!t.containsKey(h)) t[h] = ints(target)
                }
                sources[name as String] = t
            }
            return GenreAtlas(version, families, moods, genres, idArray, byMbid, byName, sources)
        }

        private fun strings(v: Any?): List<String> = (v as? List<*>)?.map { it as String } ?: emptyList()

        private fun ints(v: Any?): IntArray {
            val l = v as? List<*> ?: return EMPTY
            return IntArray(l.size) { (l[it] as Long).toInt() }
        }
    }
}
