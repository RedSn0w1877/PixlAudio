package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song

/**
 * Explicit taste adjustments: what the listener (usually through Ask Taizo) told the app to favour or
 * avoid. They sit above everything the engine learned from plays, skips and imported history, are
 * bounded (see [TasteAdjustmentMath]) and are always undoable. Nothing here ever deletes a song or a
 * play: a mute or block only keeps a song out of what PixlAudio picks for the listener by itself.
 *
 * Pure Kotlin so the maths and the matching are unit tested (TasteAdjustmentsTest). The same constants
 * are used by the iOS app.
 */
enum class TasteKind(val key: String) {
    BOOST("boost"), REDUCE("reduce"),

    /** Never in an auto-generated mix or shelf; still playable by hand. */
    MUTE("mute"),

    /** Never recommended or auto-queued at all (also keeps Ask Taizo from building it into queues and playlists). */
    BLOCK("block");

    companion object {
        fun fromKey(key: String?): TasteKind? = entries.firstOrNull { it.key == key }
    }
}

enum class TasteTarget(val key: String) {
    ARTIST("artist"), SONG("song"), GENRE("genre"), ALBUM("album");

    companion object {
        fun fromKey(key: String?): TasteTarget? = entries.firstOrNull { it.key == key }
    }
}

enum class TasteSetBy(val key: String) {
    TAIZO("taizo"), USER("user");

    companion object {
        fun fromKey(key: String?): TasteSetBy = entries.firstOrNull { it.key == key } ?: TAIZO
    }
}

/** One stored adjustment. */
data class TasteAdjustment(
    val id: String,
    val kind: TasteKind,
    val target: TasteTarget,
    /** The folded artist / genre / album name ([TasteAdjustments.nameKey]), or the library song id. */
    val key: String,
    /** A song's Spotify track id, so the `spotify_` alias rows of the same song match too. */
    val altKey: String? = null,
    /** What the listener sees: "Radiohead", "Creep - Radiohead". */
    val label: String,
    /** 1..3 for boost / reduce, ignored otherwise. */
    val strength: Int = TasteAdjustmentMath.DEFAULT_STRENGTH,
    val reason: String = "",
    val setBy: TasteSetBy = TasteSetBy.TAIZO,
    val createdAtMs: Long = 0L,
    /** Null = until removed. */
    val expiresAtMs: Long? = null,
) {
    fun isActive(nowMs: Long): Boolean = expiresAtMs == null || expiresAtMs > nowMs

    /** The affinity multiplier of a boost (>1) or reduce (<1); 1.0 for mute and block. */
    val multiplier: Double get() = TasteAdjustmentMath.multiplier(kind, strength)
}

/** The numbers behind boost / reduce. */
object TasteAdjustmentMath {
    const val MIN_STRENGTH = 1
    const val MAX_STRENGTH = 3
    const val DEFAULT_STRENGTH = 2

    /** Strength 1 / 2 / 3 as a multiplier on the affinity (reduce uses the inverse). */
    private val UP = doubleArrayOf(1.25, 1.6, 2.2)

    /** Stacked adjustments on one dimension (say two on the same artist) never go beyond x2.5 or below x0.4. */
    const val CAP = 2.5

    /**
     * An unplayed artist has no affinity to scale, so a boost also lifts it by this much per unit of
     * multiplier above 1 (and a reduce lowers it), which is what lets "more X" move new songs too.
     */
    const val LIFT = 0.3

    /** Affinities leave the engine in -1..1; an adjusted one stays within this. */
    const val MAX_AFFINITY = 1.5

    fun strengthOf(raw: Int?): Int = (raw ?: DEFAULT_STRENGTH).coerceIn(MIN_STRENGTH, MAX_STRENGTH)

    fun multiplier(kind: TasteKind, strength: Int): Double {
        val up = UP[strengthOf(strength) - 1]
        return when (kind) {
            TasteKind.BOOST -> up
            TasteKind.REDUCE -> 1.0 / up
            TasteKind.MUTE, TasteKind.BLOCK -> 1.0
        }
    }

    fun clampMultiplier(value: Double): Double = value.coerceIn(1.0 / CAP, CAP)

    /**
     * [affinity] scaled by [multiplier]. Always moves the right way: a boost never lowers an affinity
     * and a reduce never raises one, for positive and negative affinities alike. Exactly [affinity]
     * for a multiplier of 1.
     */
    fun adjustAffinity(affinity: Double, multiplier: Double): Double {
        if (multiplier == 1.0) return affinity
        val scaled = if (affinity >= 0.0) affinity * multiplier else affinity / multiplier
        return (scaled + (multiplier - 1.0) * LIFT).coerceIn(-MAX_AFFINITY, MAX_AFFINITY)
    }

    /** "x1.6 more" / "x1.6 less" for a message to the listener. */
    fun describe(kind: TasteKind, strength: Int): String {
        val up = UP[strengthOf(strength) - 1]
        val text = if (up % 1.0 == 0.0) up.toInt().toString() else up.toString()
        return when (kind) {
            TasteKind.BOOST -> "x$text more"
            TasteKind.REDUCE -> "x$text less"
            TasteKind.MUTE -> "muted"
            TasteKind.BLOCK -> "blocked"
        }
    }
}

/**
 * The active adjustments as the engine reads them: a small immutable snapshot with the lookups already
 * built. [NONE] (no adjustments) is what the engine gets by default, and with it every ranking is
 * exactly what it was before this existed.
 */
class TasteAdjustments private constructor(val active: List<TasteAdjustment>) {

    /** How much a song's artist, genre and own affinity are scaled (1.0 = untouched). */
    data class Lift(val artist: Double, val genre: Double, val song: Double) {
        val boosted: Boolean get() = artist > 1.0 || genre > 1.0 || song > 1.0
        val reduced: Boolean get() = artist < 1.0 || genre < 1.0 || song < 1.0
    }

    val isEmpty: Boolean = active.isEmpty()

    private val multipliers: Map<TasteTarget, HashMap<String, Double>> =
        TasteTarget.entries.associateWith { HashMap<String, Double>() }
    private val excluded: Map<TasteTarget, HashSet<String>> =
        TasteTarget.entries.associateWith { HashSet<String>() }
    private val blocked: Map<TasteTarget, HashSet<String>> =
        TasteTarget.entries.associateWith { HashSet<String>() }

    init {
        for (adjustment in active) {
            val keys = keysOf(adjustment)
            when (adjustment.kind) {
                TasteKind.BOOST, TasteKind.REDUCE -> {
                    val table = multipliers.getValue(adjustment.target)
                    keys.forEach { key -> table.merge(key, adjustment.multiplier) { a, b -> a * b } }
                }
                TasteKind.MUTE -> excluded.getValue(adjustment.target).addAll(keys)
                TasteKind.BLOCK -> {
                    excluded.getValue(adjustment.target).addAll(keys)
                    blocked.getValue(adjustment.target).addAll(keys)
                }
            }
        }
    }

    /** Muted or blocked: keep it out of mixes, shelves and candidate pools. */
    fun excludes(song: Song): Boolean = !isEmpty && matches(song, excluded)

    /** Blocked: also never auto-queued, and Ask Taizo leaves it out of the queues and playlists it builds. */
    fun blocks(song: Song): Boolean = !isEmpty && matches(song, blocked)

    /** How this song's artist, genre and own affinity are scaled. */
    fun liftFor(song: Song): Lift {
        var artist = 1.0
        val artistTable = multipliers.getValue(TasteTarget.ARTIST)
        if (artistTable.isNotEmpty()) for (key in artistKeys(song)) artist *= artistTable[key] ?: 1.0
        var genre = 1.0
        val genreTable = multipliers.getValue(TasteTarget.GENRE)
        if (genreTable.isNotEmpty()) for (key in genreKeys(song)) genre *= genreTable[key] ?: 1.0
        var own = 1.0
        val songTable = multipliers.getValue(TasteTarget.SONG)
        if (songTable.isNotEmpty()) for (key in songKeys(song)) own *= songTable[key] ?: 1.0
        val albumTable = multipliers.getValue(TasteTarget.ALBUM)
        if (albumTable.isNotEmpty()) albumKey(song)?.let { own *= albumTable[it] ?: 1.0 }
        return Lift(
            TasteAdjustmentMath.clampMultiplier(artist),
            TasteAdjustmentMath.clampMultiplier(genre),
            TasteAdjustmentMath.clampMultiplier(own),
        )
    }

    /** One number for rankers that have a single score (the prepare-ahead ranker): artist x genre x song, capped. */
    fun combinedMultiplier(song: Song): Double {
        if (isEmpty) return 1.0
        val lift = liftFor(song)
        return TasteAdjustmentMath.clampMultiplier(lift.artist * lift.genre * lift.song)
    }

    private fun matches(song: Song, table: Map<TasteTarget, HashSet<String>>): Boolean {
        val artists = table.getValue(TasteTarget.ARTIST)
        if (artists.isNotEmpty() && artistKeys(song).any { it in artists }) return true
        val genres = table.getValue(TasteTarget.GENRE)
        if (genres.isNotEmpty() && genreKeys(song).any { it in genres }) return true
        val songs = table.getValue(TasteTarget.SONG)
        if (songs.isNotEmpty() && songKeys(song).any { it in songs }) return true
        val albums = table.getValue(TasteTarget.ALBUM)
        if (albums.isNotEmpty()) {
            val album = albumKey(song)
            if (album != null && album in albums) return true
        }
        return false
    }

    companion object {
        val NONE = TasteAdjustments(emptyList())

        /** The adjustments of [all] that have not expired at [nowMs]. */
        fun of(all: List<TasteAdjustment>, nowMs: Long): TasteAdjustments {
            val active = all.filter { it.isActive(nowMs) }
            return if (active.isEmpty()) NONE else TasteAdjustments(active)
        }

        /** The key an artist / genre / album name is stored and matched under. */
        fun nameKey(name: String): String = MusicRecommendationEngine.normalizeKey(name.trim().removeSuffix(" - Topic"))

        private fun keysOf(adjustment: TasteAdjustment): List<String> =
            if (adjustment.target == TasteTarget.SONG) {
                listOfNotNull(adjustment.key, adjustment.altKey, adjustment.altKey?.let { "spotify_$it" })
            } else {
                listOf(adjustment.key)
            }

        /** Every credited artist of a song, folded (library rows join credits with ", "). */
        fun artistKeys(song: Song): List<String> {
            val out = LinkedHashSet<String>(4)
            for (ref in song.artists) nameKey(ref.name).takeIf { it.isNotEmpty() }?.let(out::add)
            for (part in song.artist.split(',')) nameKey(part).takeIf { it.isNotEmpty() }?.let(out::add)
            return out.toList()
        }

        /** The genre as stored, plus its parts when it lists several ("pop, rock", "hip hop/rap"). */
        fun genreKeys(song: Song): List<String> {
            val raw = song.genre ?: return emptyList()
            val whole = nameKey(raw)
            if (whole.isEmpty() || whole == "unknown") return emptyList()
            val out = LinkedHashSet<String>(3)
            out += whole
            for (part in raw.split(',', ';', '/')) nameKey(part).takeIf { it.isNotEmpty() && it != "unknown" }?.let(out::add)
            return out.toList()
        }

        fun albumKey(song: Song): String? = nameKey(song.album).takeIf { it.isNotEmpty() && it != "unknown album" }

        private fun songKeys(song: Song): List<String> = listOfNotNull(song.id, song.spotifyId, song.spotifyId?.let { "spotify_$it" })
    }
}
