package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import java.time.LocalDate
import java.util.Locale
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap

data class HomeMusicSection(
    val id: String,
    val title: String,
    val subtitle: String,
    val songs: ImmutableList<Song>,
    /**
     * Por qué el ranking eligió cada canción, indexado por [Song.id]. El motivo ya se calculaba
     * en [MusicRecommendationEngine] y en [Muselle] y se tiraba aquí al quedarnos solo con la
     * canción: era visible únicamente en la vista previa de Ajustes, nunca en la propia
     * recomendación.
     */
    val reasons: ImmutableMap<String, String> = persistentMapOf()
)

data class HomeRecommendations(
    val mixes: ImmutableList<HomeMusicSection>,
    val shelves: ImmutableList<HomeMusicSection>
)

/** Deterministic daily choices using the same completion, skip and artist signals as Your Mix. */
object HomeRecommendationPlanner {
    fun plan(
        library: List<Song>,
        favorites: Set<String>,
        signals: Map<String, MusicRecommendationEngine.Signal>,
        history: Map<String, MusicRecommendationEngine.History>,
        discoveries: List<Song> = emptyList(),
        releases: List<Song> = emptyList(),
        nowMs: Long,
        seed: Long,
        variant: MuselleVariant = MuselleVariant.BASIC
    ): HomeRecommendations {
        val rank = Muselle.rank(variant, library + discoveries + releases, favorites, signals, history, nowMs, seed)
        val localKeys = library.mapTo(hashSetOf(), MusicRecommendationEngine::recordingKey)
        val local = rank.filter { MusicRecommendationEngine.recordingKey(it.song) in localKeys }
        fun isFavorite(song: Song) = song.isFavorite || song.id in favorites || "spotify_${song.spotifyId}" in favorites
        fun lastPlayed(song: Song) = maxOf(history[song.id]?.lastPlayedMs ?: 0, signals[song.id]?.lastPlayedMs ?: 0)
        fun section(id: String, title: String, subtitle: String, picks: List<MusicRecommendationEngine.Pick>, limit: Int = 24): HomeMusicSection {
            val selected = MusicRecommendationEngine.select(picks, limit)
            return HomeMusicSection(
                id = id,
                title = title,
                subtitle = subtitle,
                songs = selected.map { it.song }.toImmutableList(),
                // Un motivo negativo ("se salta mucho") explica por qué una canción sale MENOS,
                // así que enseñarlo justo en la tarjeta que la está recomendando se contradice.
                reasons = selected
                    .filterNot { it.reason.startsWith(MusicRecommendationEngine.REASON_PREFIX_DEMOTED) }
                    .associate { it.song.id to it.reason }
                    .toImmutableMap()
            )
        }

        // Mixes are different ways into the library; overlap between playlists is intentional.
        val mixes = buildList {
            val favoritesAndRepeats = local.filter { isFavorite(it.song) || !it.unheard }
            if (favoritesAndRepeats.isNotEmpty()) add(section("comfort", "Comfort zone", "Favorites and familiar voices", favoritesAndRepeats))
            val unfamiliar = local.filter { it.unheard && !isFavorite(it.song) }
            if (unfamiliar.isNotEmpty()) add(section("fresh_ears", "Fresh ears", "Give an unplayed song a chance", unfamiliar))
            val genres = local.filter { !it.song.genre.isNullOrBlank() && !it.song.genre.equals("unknown", true) }
                .groupBy { it.song.genre!!.lowercase(Locale.ROOT).trim() }
                .filterValues { it.size >= 4 }.entries.sortedByDescending { it.value.take(6).sumOf { pick -> pick.score } }
            genres.firstOrNull()?.let { (_, picks) ->
                add(section("genre_${picks.first().song.genre}", "${picks.first().song.genre} mix", "A sound you keep coming back to", picks))
            }
            val quick = local.filter { it.song.duration in 1L..(4 * 60_000L) }
            if (size < 3 && quick.size >= 3) {
                add(section("quick_listen", "Quick listens", "Short tracks for a small window of time", quick))
            }
            val long = local.filter { it.song.duration >= 7 * 60_000L }
            if (size < 3 && long.size >= 3) {
                add(section("deep_listen", "Settle in", "Longer tracks for an uninterrupted session", long))
            }
            if (size < 3 && local.size >= 4) {
                add(section("rotation", "Open rotation", "A little familiar, a little unexpected", local))
            }
        }.distinctBy { it.songs.map(MusicRecommendationEngine::recordingKey).toSet() }.take(3)

        // A recording only appears on one song shelf, even when catalog IDs differ.
        val used = hashSetOf<String>()
        val shelves = buildList {
            fun addShelf(id: String, title: String, subtitle: String, songs: List<Song>, limit: Int = 12) {
                val keys = songs.mapTo(hashSetOf(), MusicRecommendationEngine::recordingKey)
                val picks = rank.filter { MusicRecommendationEngine.recordingKey(it.song) in keys && MusicRecommendationEngine.recordingKey(it.song) !in used }
                val next = section(id, title, subtitle, picks, limit)
                if (next.songs.isNotEmpty()) {
                    add(next)
                    next.songs.forEach { used += MusicRecommendationEngine.recordingKey(it) }
                }
            }
            addShelf("recent_releases", "New from artists you enjoy", "Released in the past six months", releases)
            addShelf("discovery", "Beyond your library", "New finds shaped by what you play", discoveries.filter { MusicRecommendationEngine.recordingKey(it) !in localKeys })
            // Estas tres estanterías se filtran de verdad, no solo se ordenan. `addShelf` marca
            // como usada cada canción que coloca, así que una estantería que recibe la biblioteca
            // entera se lleva por delante a todas las de abajo (favoritos, "Back in rotation",
            // "Waiting to be heard" se quedaban vacías). Además, una sección llamada "Recently
            // added" que enseña la biblioteca entera ordenada no dice la verdad.
            addShelf("recently_added", "Recently added", "New to your library", local.filter {
                val added = normalizeTimestampMs(it.song.dateAdded)
                added > 0L && nowMs - added <= 30 * DAY
            }.sortedByDescending { it.song.dateAdded }.map { it.song })

            // "Lo que repites" es, por definición, lo contrario de "lo que hace tiempo que no
            // escuchas": sin la condición de escucha reciente, esta se quedaba con las canciones
            // que después buscaba `rediscover`.
            addShelf("on_repeat", "On repeat", "The songs you keep coming back to", local.filter {
                val past = history[it.song.id]
                val signal = signals[it.song.id]
                val repeats = (past?.plays ?: 0) + (signal?.completions ?: 0)
                val last = lastPlayed(it.song)
                repeats >= 2 && last > 0L && nowMs - last < 7 * DAY
            }.sortedByDescending {
                val past = history[it.song.id]
                val signal = signals[it.song.id]
                (past?.plays ?: 0) * 1.0 + (signal?.completions ?: 0) * 1.5 + (signal?.voluntaryPlays ?: 0) * 0.4
            }.map { it.song })

            val favoriteArtist = local.groupBy { MusicRecommendationEngine.artistKey(it.song) }
                .filterValues { it.size >= MIN_ARTIST_RADIO_SONGS } // una sola canción no es una "radio"
                .maxByOrNull { (_, picks) -> picks.sumOf { it.score } }
            favoriteArtist?.let { (artistKey, _) ->
                addShelf("artist_radio_$artistKey", "Artist radio", "A focused run around an artist you enjoy", local.filter {
                    MusicRecommendationEngine.artistKey(it.song) == artistKey
                }.map { it.song })
            }
            addShelf("favorites", "Always a good choice", "Your favorites, ready for another listen", local.filter { isFavorite(it.song) }.map { it.song })
            addShelf("rediscover", "Back in rotation", "Good songs you haven't played in a while", local.filter {
                val last = lastPlayed(it.song)
                last > 0 && nowMs - last >= 7 * DAY
            }.map { it.song })
            addShelf("unplayed", "Waiting to be heard", "Fresh picks already in your library", local.filter { it.unheard }.map { it.song })
            addShelf("quick_listen", "Quick listens", "Short tracks when you only have a few minutes", local.filter { it.song.duration in 1L..(4 * 60_000L) }.map { it.song })
            addShelf("deep_listen", "Settle in", "Longer tracks for an uninterrupted session", local.filter { it.song.duration >= 7 * 60_000L }.map { it.song })
            addShelf("more_for_you", "Keep exploring", "More picks from your collection", local.map { it.song })
        }
        return HomeRecommendations(mixes.toImmutableList(), shelves.toImmutableList())
    }

    /** Partial release dates remain conservative: a year alone is never called a recent release. */
    fun isRecentRelease(value: String?, today: LocalDate): Boolean {
        if (value == null || value.length != 10) return false
        val date = runCatching { LocalDate.parse(value) }.getOrNull() ?: return false
        return !date.isAfter(today) && !date.isBefore(today.minusDays(180))
    }

    private const val DAY = 86_400_000L

    /** Menos canciones que esto de un artista no dan para una "radio" de ese artista. */
    private const val MIN_ARTIST_RADIO_SONGS = 3

    /**
     * `dateAdded` llega en segundos desde MediaStore pero en milisegundos desde otras rutas.
     * Misma heurística que [com.theveloper.pixelplay.data.DailyMixManager.computeNoveltyScore],
     * para que "hace 30 días" signifique lo mismo en los dos sitios.
     */
    private fun normalizeTimestampMs(value: Long): Long = when {
        value <= 0L -> 0L
        value < 10_000_000_000L -> value * 1_000L
        else -> value
    }
}
