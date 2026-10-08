package com.theveloper.pixelplay.data.ai.curator

import com.theveloper.pixelplay.data.model.Song

/**
 * Turns a [PlaylistPlan] into songs from the library. Pure and deterministic, so the whole fill
 * step is unit-tested; the model never sees or copies a song id.
 *
 * Song has no energy, mood or explicit metadata, so moods and energy go through a small genre
 * family table, and "avoid explicit" can't be honoured. Discovery uses play counts: low discovery
 * prefers songs the listener plays, high discovery prefers songs they haven't.
 */
object PlaylistPlanFiller {
    const val MAX_PER_ARTIST = 3
    const val MAX_SONGS = 150

    data class Inputs(
        val library: List<Song>,
        /** Song id -> play count. */
        val playCounts: Map<String, Int> = emptyMap(),
        val favoriteIds: Set<String> = emptySet(),
        /** Song id -> position in the day's personalised picks (0 = best), for tie-breaks. */
        val personalRank: Map<String, Int> = emptyMap(),
        /** Fallback songs (the day's picks, or the current Daily Mix when refining it). */
        val seedPool: List<Song> = emptyList(),
    )

    /** The size a request gets: its maximum, at least its minimum, never more than [MAX_SONGS]. */
    fun targetSize(minLength: Int, maxLength: Int): Int {
        val min = minLength.coerceAtLeast(1)
        return maxOf(min, maxLength.coerceAtMost(MAX_SONGS)).coerceAtMost(MAX_SONGS)
    }

    fun fill(plan: PlaylistPlan, inputs: Inputs, target: Int): List<Song> {
        val wanted = target.coerceIn(1, MAX_SONGS)
        val byId = LinkedHashMap<String, Song>()
        inputs.library.forEach { byId.putIfAbsent(it.id, it) }
        // Seed songs that somehow aren't in the library list still are library songs (same ids).
        inputs.seedPool.forEach { byId.putIfAbsent(it.id, it) }
        val candidates = byId.values.toList()
        if (candidates.isEmpty()) return emptyList()

        val rankSize = (inputs.personalRank.size).coerceAtLeast(1)
        // Normalised once: this runs for every library song, and scoring and the artist cap both ask.
        val wantedArtists = plan.artists.map { PlaylistPlanParser.normalize(it) }.filter { it.isNotEmpty() }.toSet()
        val namedById = HashMap<String, Boolean>()
        fun isNamed(song: Song): Boolean = wantedArtists.isNotEmpty() &&
            namedById.getOrPut(song.id) { normalizedArtistNames(song).any { it in wantedArtists } }

        val scored = candidates.map { song -> song to score(song, plan, inputs, rankSize, named = isNamed(song)) }
            .sortedWith(compareByDescending<Pair<Song, Double>> { it.second }.thenBy { it.first.id })

        val picked = LinkedHashMap<String, Song>()
        val perArtist = HashMap<String, Int>()
        fun tryAdd(song: Song, respectCap: Boolean): Boolean {
            if (picked.size >= wanted || picked.containsKey(song.id)) return false
            val artistKey = artistKey(song)
            val count = perArtist[artistKey] ?: 0
            // An artist the request names explicitly may fill more of the playlist.
            val cap = if (isNamed(song)) MAX_PER_ARTIST * 4 else MAX_PER_ARTIST
            if (respectCap && count >= cap) return false
            picked[song.id] = song
            perArtist[artistKey] = count + 1
            return true
        }

        // 1. Songs the plan actually matches.
        val threshold = if (plan.isEmpty) Double.MAX_VALUE else MATCH_THRESHOLD
        for ((song, score) in scored) {
            if (picked.size >= wanted) break
            if (score >= threshold) tryAdd(song, respectCap = true)
        }
        // 2. Top up from the seed pool (the day's picks / the current mix), still one artist at a time.
        for (song in inputs.seedPool) {
            if (picked.size >= wanted) break
            byId[song.id]?.let { tryAdd(it, respectCap = true) }
        }
        // 3. Then the best of the rest of the library.
        for ((song, _) in scored) {
            if (picked.size >= wanted) break
            tryAdd(song, respectCap = true)
        }
        // 4. A small library may not have enough artists for the cap: relax it rather than come up short.
        for ((song, _) in scored) {
            if (picked.size >= wanted) break
            tryAdd(song, respectCap = false)
        }
        return picked.values.toList()
    }

    internal fun score(
        song: Song,
        plan: PlaylistPlan,
        inputs: Inputs,
        rankSize: Int,
        named: Boolean = plan.artists.any { matchesArtist(song, it) },
    ): Double {
        var score = 0.0
        val genre = PlaylistPlanParser.normalize(song.genre.orEmpty())
        val family = GenreFamilies.familyOf(genre)

        if (plan.genres.isNotEmpty() && genre.isNotEmpty()) {
            if (plan.genres.any { genreMatches(genre, PlaylistPlanParser.normalize(it)) }) score += 3.0
        }
        if (named) score += 4.0

        if (family != null) {
            if (plan.moods.any { mood -> family in GenreFamilies.familiesForMood(mood) }) score += 1.5
            plan.energy?.let { wantedEnergy ->
                val distance = kotlin.math.abs(GenreFamilies.energyOf(family) - wantedEnergy)
                score += 1.0 - distance * 0.5
            }
        }

        if (plan.eraFrom != null || plan.eraTo != null) {
            val year = song.year
            if (year > 0) {
                val from = plan.eraFrom ?: Int.MIN_VALUE
                val to = plan.eraTo ?: Int.MAX_VALUE
                score += if (year in from..to) 1.5 else -1.0
            }
        }

        if (plan.keywords.isNotEmpty()) {
            val haystack = PlaylistPlanParser.normalize("${song.title} ${song.album}")
            if (plan.keywords.any { keyword ->
                    val k = PlaylistPlanParser.normalize(keyword)
                    k.length >= 3 && " $haystack ".contains(" $k")
                }
            ) score += 1.0
        }

        val plays = inputs.playCounts[song.id] ?: 0
        val discovery = plan.discovery ?: 3
        score += when {
            // Familiar: the more it's played the better; never-played songs drop back.
            discovery <= 2 -> minOf(plays, 20) / 10.0 - if (plays == 0) 0.5 else 0.0
            // Fresh: unplayed songs first, heavy rotation last.
            discovery >= 4 -> (if (plays == 0) 1.0 else 0.0) - (if (plays > 10) 0.5 else 0.0)
            else -> minOf(plays, 20) / 40.0
        }

        val favorite = song.isFavorite || song.id in inputs.favoriteIds
        if (favorite) score += if ((plan.discovery ?: 3) <= 3) 1.0 else 0.25

        inputs.personalRank[song.id]?.let { rank ->
            score += 0.5 * (1.0 - rank.toDouble() / rankSize)
        }
        return score
    }

    private fun genreMatches(songGenre: String, wanted: String): Boolean {
        if (wanted.isEmpty()) return false
        if (songGenre == wanted || songGenre.contains(wanted) || wanted.contains(songGenre)) return true
        val a = GenreFamilies.familyOf(songGenre)
        return a != null && a == GenreFamilies.familyOf(wanted)
    }

    internal fun matchesArtist(song: Song, wanted: String): Boolean {
        val w = PlaylistPlanParser.normalize(wanted)
        if (w.isEmpty()) return false
        return normalizedArtistNames(song).any { it == w }
    }

    private fun normalizedArtistNames(song: Song): List<String> = buildList {
        add(song.artist)
        add(song.displayArtist)
        song.artists.forEach { add(it.name) }
        song.albumArtist?.let { add(it) }
    }.map(PlaylistPlanParser::normalize)

    private fun artistKey(song: Song): String =
        PlaylistPlanParser.normalize(song.primaryArtist.name.ifBlank { song.artist })

    // A song needs at least one real match (a genre, an artist, or a mood plus energy) to count
    // as "what the plan asked for" before the top-up steps.
    private const val MATCH_THRESHOLD = 1.5
}

/** Rough genre families, with an energy level and the moods they suit. */
internal object GenreFamilies {
    private data class Family(val name: String, val energy: Int, val words: List<String>)

    private val FAMILIES = listOf(
        Family("metal", 5, listOf("metal", "metalcore", "deathcore", "thrash")),
        Family("punk", 5, listOf("punk", "hardcore", "emo")),
        Family("electronic", 4, listOf("electronic", "edm", "house", "techno", "trance", "dubstep", "drum and bass", "dnb", "electro", "dance", "garage")),
        Family("hiphop", 4, listOf("hip hop", "hip-hop", "hiphop", "rap", "trap", "drill", "grime")),
        Family("rock", 4, listOf("rock", "alternative", "grunge", "garage rock", "britpop")),
        Family("latin", 4, listOf("latin", "reggaeton", "salsa", "bachata", "cumbia")),
        Family("kpop", 4, listOf("k-pop", "kpop", "j-pop", "jpop", "c-pop")),
        Family("pop", 3, listOf("pop", "synthpop", "dance pop", "v-pop", "vpop")),
        Family("funk", 3, listOf("funk", "disco", "groove")),
        Family("rnb", 3, listOf("r&b", "rnb", "soul", "neo soul")),
        Family("country", 3, listOf("country", "americana", "bluegrass")),
        Family("indie", 3, listOf("indie", "shoegaze", "dream pop")),
        Family("reggae", 2, listOf("reggae", "dub", "ska")),
        Family("blues", 2, listOf("blues")),
        Family("jazz", 2, listOf("jazz", "bossa nova", "swing", "bebop")),
        Family("folk", 2, listOf("folk", "acoustic", "singer-songwriter", "singer songwriter")),
        Family("soundtrack", 2, listOf("soundtrack", "score", "film", "anime", "game")),
        Family("classical", 1, listOf("classical", "orchestral", "piano", "baroque", "opera")),
        Family("ambient", 1, listOf("ambient", "lofi", "lo-fi", "lo fi", "chillout", "chill", "new age", "downtempo")),
    )

    private val MOOD_FAMILIES = mapOf(
        "chill" to setOf("ambient", "folk", "jazz", "rnb", "indie", "classical"),
        "energetic" to setOf("rock", "electronic", "hiphop", "metal", "punk", "pop", "kpop", "latin"),
        "sad" to setOf("indie", "folk", "blues", "rnb", "classical", "soundtrack"),
        "happy" to setOf("pop", "funk", "reggae", "kpop", "latin", "country"),
        "romantic" to setOf("rnb", "jazz", "pop", "folk", "latin"),
        "workout" to setOf("electronic", "hiphop", "rock", "metal", "punk", "latin"),
        "party" to setOf("electronic", "pop", "hiphop", "latin", "funk", "kpop"),
        "focus" to setOf("ambient", "classical", "jazz", "electronic", "soundtrack"),
        "sleep" to setOf("ambient", "classical", "folk"),
    )

    // Every family word normalised and padded once, longest first, so "dream pop" is indie and
    // "hip hop" isn't "pop". familyOf runs several times per library song: nothing is rebuilt,
    // sorted or regex-normalised per call, and each distinct genre is resolved once.
    private val WORDS_LONGEST_FIRST: List<Pair<String, String>> by lazy {
        FAMILIES.flatMap { family -> family.words.map { family.name to it } }
            .sortedByDescending { it.second.length }
            .map { (name, word) -> name to " ${PlaylistPlanParser.normalize(word)} " }
    }
    private val familyByGenre = java.util.concurrent.ConcurrentHashMap<String, String>()
    private const val NO_FAMILY = ""
    private const val MAX_CACHED_GENRES = 4_096

    fun familyOf(normalizedGenre: String): String? {
        if (normalizedGenre.isBlank()) return null
        familyByGenre[normalizedGenre]?.let { return it.ifEmpty { null } }
        val padded = " $normalizedGenre "
        val family = WORDS_LONGEST_FIRST.firstOrNull { (_, word) -> padded.contains(word) }?.first ?: NO_FAMILY
        if (familyByGenre.size >= MAX_CACHED_GENRES) familyByGenre.clear()
        familyByGenre[normalizedGenre] = family
        return family.ifEmpty { null }
    }

    fun energyOf(family: String): Int = FAMILIES.firstOrNull { it.name == family }?.energy ?: 3

    fun familiesForMood(mood: String): Set<String> = MOOD_FAMILIES[mood].orEmpty()
}
