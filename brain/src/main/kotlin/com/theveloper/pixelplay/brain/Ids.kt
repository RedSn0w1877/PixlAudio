package com.theveloper.pixelplay.brain

/**
 * Identity (docs/brain-spec.md, 3.1): one Int64 per recording (`rid`) and per artist (`aid`), minted
 * the first time a key is seen and kept for good in an alias table.
 *
 * This file is pure: it reads the table through [AliasLookup] and returns the rows to write. The
 * caller applies them in one transaction. Key forms:
 *
 *  - tracks:  `s:<spotifyTrackId>`, `i:<ISRC upper case>`, `y:<videoId>`,
 *             `n:<fold(primary artist)>|<fold(clean title)>|<identity flags, lower-case hex>`
 *  - artists: `sa:<spotifyArtistId>`, `ya:<UC...>`, `da:<deezerId>`, `mb:<mbid>`, `na:<fold(name)>`
 */
object Ids {

    /** Two plays under one name are the same recording when their lengths differ by at most this. */
    const val NAME_MATCH_MAX_DURATION_DELTA_MS = 3_000

    /** What the resolver needs to read. Every method is a point lookup. */
    interface AliasLookup {
        /** The id stored for [key], or null. */
        fun idOf(key: String): Long?

        /** True when [id] already belongs to something (any alias row points at it). */
        fun isTaken(id: Long): Boolean

        /** The survivor [id] was merged into, or [id] itself. */
        fun survivorOf(id: Long): Long

        /** The duration stored with the alias row of [key], 0 when unknown. */
        fun durationMsOf(key: String): Int
    }

    /** One alias row to insert. [durationMs] is what the caller knew, 0 when unknown. */
    class AliasWrite(val key: String, val id: Long, val durationMs: Int)

    /** `from` is now the same thing as `into`. */
    class MergeWrite(val from: Long, val into: Long)

    class Resolution(
        val id: Long,
        /** True when the id did not exist before this call. */
        val minted: Boolean,
        val aliases: List<AliasWrite>,
        val merges: List<MergeWrite>,
    )

    /** The keys a caller has for one track. Blank values are treated as absent. */
    class TrackKeys(
        spotifyId: String? = null,
        isrc: String? = null,
        videoId: String? = null,
        nameKey: String? = null,
    ) {
        val s: String? = spotifyId?.takeIf { it.isNotBlank() }?.let { "s:$it" }
        val i: String? = isrc?.takeIf { it.isNotBlank() }?.let { "i:" + asciiUpper(it.trim()) }
        val y: String? = videoId?.takeIf { it.isNotBlank() }?.let { "y:$it" }
        val n: String? = nameKey?.takeIf { it.isNotBlank() }

        /** Strong keys in resolve order. */
        internal fun strong(): List<String> = listOfNotNull(s, i, y)
    }

    /** The keys a caller has for one artist. */
    class ArtistKeys(
        spotifyId: String? = null,
        youTubeChannelId: String? = null,
        deezerId: String? = null,
        mbid: String? = null,
        name: String? = null,
    ) {
        val sa: String? = spotifyId?.takeIf { it.isNotBlank() }?.let { "sa:$it" }
        val ya: String? = youTubeChannelId?.takeIf { it.isNotBlank() }?.let { "ya:$it" }
        val da: String? = deezerId?.takeIf { it.isNotBlank() }?.let { "da:$it" }
        val mb: String? = mbid?.takeIf { it.isNotBlank() }?.let { "mb:$it" }
        val na: String? = name?.let { BrainText.fold(it) }?.takeIf { it.isNotEmpty() }?.let { "na:$it" }

        internal fun strong(): List<String> = listOfNotNull(sa, ya, da, mb)
    }

    private fun asciiUpper(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) out.append(if (c in 'a'..'z') (c.code - 32).toChar() else c)
        return out.toString()
    }

    /**
     * The names-only key of a track: `n:<fold(artist)>|<clean title>|<identity flags hex>`, or null when
     * either part folds to nothing. [primaryArtist] must be ONE artist (the first of the library's own
     * split list), never a joined display string.
     */
    fun nameKey(primaryArtist: String, rawTitle: String): String? {
        val artist = BrainText.fold(primaryArtist)
        val clean = BrainText.cleanTitle(rawTitle)
        if (artist.isEmpty() || clean.title.isEmpty()) return null
        return "n:" + artist + "|" + clean.title + "|" + java.lang.Integer.toHexString(clean.identityFlags)
    }

    /**
     * Mints an id for [firstKey]: `fnv1a64(utf8(firstKey))`, or with `#1`, `#2`, ... appended while the
     * value is 0 or [isTaken].
     */
    fun mint(firstKey: String, isTaken: (Long) -> Boolean): Long {
        var candidate = BrainHash.fnv1a64(firstKey)
        var n = 0
        while (candidate == 0L || isTaken(candidate)) {
            n++
            candidate = BrainHash.fnv1a64("$firstKey#$n")
        }
        return candidate
    }

    /** Resolves a track to its rid (the four steps of 3.1), returning the rows to write. */
    fun resolveTrack(keys: TrackKeys, durationMs: Int, table: AliasLookup): Resolution? {
        val strong = keys.strong()
        val nameKey = keys.n
        if (strong.isEmpty() && nameKey == null) return null
        val duration = if (durationMs > 0) durationMs else 0

        // Step 1: the first strong key the table knows decides.
        var rid = 0L
        var found = false
        for (key in strong) {
            val hit = table.idOf(key)
            if (hit != null) {
                rid = table.survivorOf(hit)
                found = true
                break
            }
        }

        // Step 2: the name key, unless it is clearly another recording under the same name.
        var storeNameKey = true
        if (!found && nameKey != null) {
            val hit = table.idOf(nameKey)
            if (hit != null) {
                val stored = table.durationMsOf(nameKey)
                val delta = if (duration > stored) duration - stored else stored - duration
                val accept = strong.isEmpty() || duration == 0 || stored <= 0 ||
                    delta <= NAME_MATCH_MAX_DURATION_DELTA_MS
                if (accept) {
                    rid = table.survivorOf(hit)
                    found = true
                } else {
                    storeNameKey = false
                }
            }
        }

        // Step 3: mint from the caller's strongest key.
        var minted = false
        if (!found) {
            val firstKey = strong.firstOrNull() ?: nameKey!!
            rid = mint(firstKey, table::isTaken)
            minted = true
        }

        // Step 4: store what is new; a strong key that lives under another rid merges the two.
        val aliases = ArrayList<AliasWrite>(4)
        val merges = ArrayList<MergeWrite>(1)
        for (key in strong) {
            val hit = table.idOf(key)
            if (hit == null) {
                aliases.add(AliasWrite(key, rid, duration))
            } else {
                val other = table.survivorOf(hit)
                if (other != rid && merges.none { it.from == other }) merges.add(MergeWrite(other, rid))
            }
        }
        if (nameKey != null && storeNameKey && table.idOf(nameKey) == null) {
            aliases.add(AliasWrite(nameKey, rid, duration))
        }
        return Resolution(rid, minted, aliases, merges)
    }

    /** Resolves an artist to its aid. Order `sa`, `ya`, `da`, `mb`, `na`; a name hit is always accepted. */
    fun resolveArtist(keys: ArtistKeys, table: AliasLookup): Resolution? {
        val strong = keys.strong()
        val nameKey = keys.na
        if (strong.isEmpty() && nameKey == null) return null
        var aid = 0L
        var found = false
        for (key in strong) {
            val hit = table.idOf(key)
            if (hit != null) {
                aid = table.survivorOf(hit)
                found = true
                break
            }
        }
        if (!found && nameKey != null) {
            val hit = table.idOf(nameKey)
            if (hit != null) {
                aid = table.survivorOf(hit)
                found = true
            }
        }
        var minted = false
        if (!found) {
            aid = mint(strong.firstOrNull() ?: nameKey!!, table::isTaken)
            minted = true
        }
        val aliases = ArrayList<AliasWrite>(3)
        for (key in strong) if (table.idOf(key) == null) aliases.add(AliasWrite(key, aid, 0))
        if (nameKey != null && table.idOf(nameKey) == null) aliases.add(AliasWrite(nameKey, aid, 0))
        // Artists are not merged in stage 1: two ids for one artist stay two until stage 2 brings real ids.
        return Resolution(aid, minted, aliases, emptyList())
    }

    /**
     * An in-memory alias table: the reference behaviour for tests, golden vectors and any caller that
     * resolves a batch before writing it. Not thread-safe.
     *
     * Keys are compared as exact code units (equivalently, as their UTF-8 bytes): two strings that
     * are canonically equivalent but spelled differently (one precomposed, one decomposed) are two
     * keys. `fold` leaves Hangul, kana and CJK as they arrive, so this matters for real titles. A port
     * to a language whose string equality is canonical equivalence (Swift's `String` as a dictionary
     * key) must key its table by the UTF-8 bytes instead, or it merges recordings this table keeps
     * apart. `ids.jsonl` has no such pair yet, so the golden test alone would not catch it.
     */
    class MemoryAliasTable : AliasLookup {
        private val ids = HashMap<String, Long>()
        private val durations = HashMap<String, Int>()
        private val taken = HashSet<Long>()
        private val merged = HashMap<Long, Long>()

        override fun idOf(key: String): Long? = ids[key]
        override fun isTaken(id: Long): Boolean = id in taken
        override fun survivorOf(id: Long): Long = merged[id] ?: id
        override fun durationMsOf(key: String): Int = durations[key] ?: 0

        /** Applies a resolution, flattening merge chains at write time. */
        fun apply(resolution: Resolution) {
            taken.add(resolution.id)
            for (alias in resolution.aliases) {
                ids[alias.key] = alias.id
                durations[alias.key] = alias.durationMs
                taken.add(alias.id)
            }
            for (merge in resolution.merges) merge(merge.from, merge.into)
        }

        fun merge(from: Long, into: Long) {
            val target = survivorOf(into)
            if (from == target) return
            merged[from] = target
            // Flatten: anything that pointed at `from` now points at the survivor.
            for (entry in merged.entries) if (entry.value == from) entry.setValue(target)
        }

        fun resolveTrack(keys: TrackKeys, durationMs: Int): Long? =
            Ids.resolveTrack(keys, durationMs, this)?.also(::apply)?.id

        fun resolveArtist(keys: ArtistKeys): Long? = Ids.resolveArtist(keys, this)?.also(::apply)?.id

        val aliasCount: Int get() = ids.size
    }
}
