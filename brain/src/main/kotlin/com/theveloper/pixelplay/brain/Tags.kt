package com.theveloper.pixelplay.brain

/**
 * The tag vector of a track (docs/brain-spec.md, 3.4 and 5.3): what the sources say its genres are, merged, mapped to
 * atlas nodes, spread a little to the parents, weighted toward the specific, and quantised to 12 bytes of weight.
 *
 * ```
 * per atlas id g:   w(g)   = 1 - PRODUCT over sources s of (1 - trust_s * conf_s(g))        noisy-OR
 * propagate:        w(parent) = max(w(parent), 0.5 * w(child)); grandparent 0.25
 * weight:           w(g)  *= min(2.4, 1 + 0.35 * depth(g))
 * keep the 12 largest (ties: smaller atlas id); L2-normalise; quantise  q = floor(255 * w + 0.5)
 * unknown:          if no source reached w >= 0.25 before propagation, the vector is empty
 * ```
 * Sources 7, 9 and 10 run only when the others are weak (`max w < 0.5`). Unknown stays unknown: an empty vector is
 * the answer when the sources said nothing the atlas can place, and nothing here invents a genre.
 */
object Tags {
    const val SRC_RECORDING = 1
    const val SRC_ARTIST = 2
    const val SRC_SPOTIFY = 3
    const val SRC_CHIP = 4
    const val SRC_DISCOGS = 5
    const val SRC_ITUNES = 6
    const val SRC_RELATED_ARTISTS = 7
    const val SRC_DEEZER_ALBUM = 8
    const val SRC_SHARED = 9
    const val SRC_ASSISTANT = 10

    /** Trust of each source, by its number (index 0 unused). */
    private val TRUST = doubleArrayOf(0.0, 1.0, 0.8, 0.7, 0.6, 0.5, 0.4, 0.35, 0.3, 0.3, 0.25)

    const val MAX_TAGS = 12
    private const val KNOWN_FLOOR = 0.25
    private const val WEAK_BELOW = 0.5
    private const val ARTIST_FALLBACK = 0.7

    fun trust(source: Int): Double = if (source in 1..10) TRUST[source] else 0.0

    /** Sources that run only when the rest left the track weakly tagged (3.4). */
    private fun isLate(source: Int): Boolean = source == SRC_RELATED_ARTISTS || source == SRC_SHARED || source == SRC_ASSISTANT

    /** One source's claim that a track is atlas genre [atlasId], with the share of its votes in 0..1. */
    class Input(val source: Int, val atlasId: Int, val conf: Double)

    /** One genre string a source returned: its words, a MusicBrainz genre id when it gave one, and its vote count or -1. */
    class Raw(val label: String, val mbid: String = "", val votes: Int = -1)

    class Tag(val atlasId: Int, val q: Int)

    /** At most [MAX_TAGS] tags, strongest first (ties: smaller atlas id). Empty means "unknown", never "no genre". */
    class Vector(val tags: List<Tag>) {
        val isEmpty: Boolean get() = tags.isEmpty()

        /** The heaviest tag's atlas id, or [GenreAtlas.NONE]. */
        val top: Int get() = if (tags.isEmpty()) GenreAtlas.NONE else tags[0].atlasId

        override fun toString(): String = tags.joinToString(",") { "${it.atlasId}:${it.q}" }

        companion object {
            val EMPTY = Vector(emptyList())
        }
    }

    /**
     * Turns one source's raw genre strings into [Input]s. [table] is the source table to try first ("deezer",
     * "itunes", "chip"; null for sources whose strings are plain names). A string with a MusicBrainz genre id is
     * placed by that id. Strings the atlas cannot place are dropped. `conf` is `votes / max votes` over the raws that
     * carry votes, and 1 for a raw without votes.
     */
    fun inputs(atlas: GenreAtlas, source: Int, table: String?, raws: List<Raw>): List<Input> {
        var maxVotes = 0
        for (r in raws) if (r.votes > maxVotes) maxVotes = r.votes
        val out = ArrayList<Input>(raws.size)
        for (r in raws) {
            val conf = if (r.votes > 0 && maxVotes > 0) r.votes.toDouble() / maxVotes.toDouble() else if (r.votes == 0) 0.0 else 1.0
            if (conf <= 0.0) continue
            val byId = if (r.mbid.isNotEmpty()) atlas.idForMbid(r.mbid) else GenreAtlas.NONE
            if (byId != GenreAtlas.NONE) {
                out.add(Input(source, byId, conf))
                continue
            }
            val ids = if (table != null) atlas.resolve(table, r.label) else atlas.resolve("", r.label)
            for (id in ids) out.add(Input(source, id, conf))
        }
        return out
    }

    /** Merges every source's claims into one vector (3.4). Order of [inputs] does not matter. */
    fun merge(atlas: GenreAtlas, inputs: List<Input>): Vector {
        // conf[source][id], the largest when a source repeats an id; ids ascending below.
        val perId = java.util.TreeMap<Int, DoubleArray>()
        for (i in inputs) {
            if (i.source !in 1..10 || atlas.genre(i.atlasId) == null) continue
            val c = if (i.conf > 1.0) 1.0 else if (i.conf < 0.0) 0.0 else i.conf
            val a = perId.getOrPut(i.atlasId) { DoubleArray(11) }
            if (c > a[i.source]) a[i.source] = c
        }
        if (perId.isEmpty()) return Vector.EMPTY

        var w = noisyOr(perId, includeLate = false)
        var maxW = 0.0
        for (v in w.values) if (v > maxW) maxW = v
        if (maxW < WEAK_BELOW) {
            w = noisyOr(perId, includeLate = true)
            maxW = 0.0
            for (v in w.values) if (v > maxW) maxW = v
        }
        if (maxW < KNOWN_FLOOR) return Vector.EMPTY

        // Spread to the parents from the weights before any spreading, so the order does not matter.
        val spread = java.util.TreeMap<Int, Double>()
        for ((id, v) in w) spread[id] = v
        for ((id, v) in w) {
            val g = atlas.genre(id) ?: continue
            for (p in g.parents) {
                val half = 0.5 * v
                if (half > (spread[p] ?: 0.0)) spread[p] = half
                val pg = atlas.genre(p) ?: continue
                for (gp in pg.parents) {
                    val quarter = 0.25 * v
                    if (quarter > (spread[gp] ?: 0.0)) spread[gp] = quarter
                }
            }
        }
        // Specific beats generic.
        val atlasIds = ArrayList<Int>(spread.size)
        val values = ArrayList<Double>(spread.size)
        for ((id, v) in spread) {
            val boost = BrainMath.min(2.4, 1.0 + 0.35 * atlas.depth(id).toDouble())
            atlasIds.add(id)
            values.add(v * boost)
        }
        // The 12 largest, by quantised weight then smaller id.
        val order = ArrayList<Int>(atlasIds.size)
        for (k in atlasIds.indices) order.add(k)
        order.sortWith { a, b ->
            val ka = BrainMath.orderKey(values[a])
            val kb = BrainMath.orderKey(values[b])
            if (ka != kb) kb.compareTo(ka) else atlasIds[a].compareTo(atlasIds[b])
        }
        val keep = order.take(MAX_TAGS).sortedBy { atlasIds[it] } // ascending id: a fixed summation order
        var sumSq = 0.0
        for (k in keep) sumSq += values[k] * values[k]
        if (sumSq <= 0.0) return Vector.EMPTY
        val norm = BrainMath.sqrt(sumSq)
        val tags = ArrayList<Tag>(keep.size)
        for (k in keep) {
            val q = BrainMath.floorToLong(255.0 * (values[k] / norm) + 0.5).toInt()
            if (q > 0) tags.add(Tag(atlasIds[k], if (q > 255) 255 else q))
        }
        tags.sortWith { a, b -> if (a.q != b.q) b.q.compareTo(a.q) else a.atlasId.compareTo(b.atlasId) }
        return Vector(tags)
    }

    private fun noisyOr(perId: java.util.TreeMap<Int, DoubleArray>, includeLate: Boolean): java.util.TreeMap<Int, Double> {
        val out = java.util.TreeMap<Int, Double>()
        for ((id, conf) in perId) {
            var keep = 1.0
            var any = false
            for (s in 1..10) {
                if (!includeLate && isLate(s)) continue
                val c = conf[s]
                if (c <= 0.0) continue
                keep *= 1.0 - TRUST[s] * c
                any = true
            }
            if (any) out[id] = 1.0 - keep
        }
        return out
    }

    /**
     * A track with no tags of its own takes its primary artist's vector at 0.7 weight (3.4): the quantised weights
     * are scaled and rounded, and a weight that rounds to 0 is dropped.
     */
    fun fromArtist(artist: Vector): Vector {
        if (artist.isEmpty) return Vector.EMPTY
        val out = ArrayList<Tag>(artist.tags.size)
        for (t in artist.tags) {
            val q = BrainMath.floorToLong(ARTIST_FALLBACK * t.q.toDouble() + 0.5).toInt()
            if (q > 0) out.add(Tag(t.atlasId, q))
        }
        return Vector(out)
    }

    /** `sum over shared ids of (wa / 255) * (wb / 255)` (5.3), summed in ascending atlas id. */
    fun tagdot(a: Vector, b: Vector): Double {
        if (a.isEmpty || b.isEmpty) return 0.0
        val x = a.tags.sortedBy { it.atlasId }
        val y = b.tags.sortedBy { it.atlasId }
        var i = 0
        var j = 0
        var sum = 0.0
        while (i < x.size && j < y.size) {
            val ia = x[i].atlasId
            val ib = y[j].atlasId
            if (ia == ib) {
                sum += (x[i].q.toDouble() / 255.0) * (y[j].q.toDouble() / 255.0)
                i++
                j++
            } else if (ia < ib) i++ else j++
        }
        return sum
    }

    /** Three bytes a tag: the atlas id (Int16, big endian) and the weight. The empty vector is no bytes. */
    fun encode(v: Vector): ByteArray {
        val out = ByteArray(v.tags.size * 3)
        for ((k, t) in v.tags.withIndex()) {
            out[k * 3] = (t.atlasId ushr 8).toByte()
            out[k * 3 + 1] = t.atlasId.toByte()
            out[k * 3 + 2] = t.q.toByte()
        }
        return out
    }

    fun decode(bytes: ByteArray?): Vector {
        if (bytes == null || bytes.size < 3) return Vector.EMPTY
        val n = bytes.size / 3
        val tags = ArrayList<Tag>(n)
        for (k in 0 until n) {
            val id = ((bytes[k * 3].toInt() and 0xFF) shl 8) or (bytes[k * 3 + 1].toInt() and 0xFF)
            val q = bytes[k * 3 + 2].toInt() and 0xFF
            tags.add(Tag(id, q))
        }
        return Vector(tags)
    }
}
