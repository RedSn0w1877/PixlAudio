package com.theveloper.pixelplay.data.recommendation

import com.theveloper.pixelplay.data.model.Song
import java.util.Random
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The Home recommendation speed-up (normalise once per song instead of a new Regex + Unicode fold on
 * every comparison, planner keys cached) must not change a single score, pick or order. This runs the
 * frozen pre-optimisation copies ([LegacyRecommendationEngine], [LegacyHomeRecommendationPlanner])
 * next to the real code on a seeded synthetic library and requires identical output.
 */
class RecommendationGoldenEqualityTest {

    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private class Fixture(
        val library: List<Song>,
        val discoveries: List<Song>,
        val releases: List<Song>,
        val favorites: Set<String>,
        val signals: Map<String, MusicRecommendationEngine.Signal>,
        val history: Map<String, MusicRecommendationEngine.History>,
    ) {
        val legacySignals = signals.mapValues { (_, s) ->
            LegacyRecommendationEngine.Signal(s.sessions, s.completions, s.earlySkips, s.voluntaryPlays, s.lastPlayedMs, s.listenedMs)
        }
        val legacyHistory = history.mapValues { (_, h) ->
            LegacyRecommendationEngine.History(h.plays, h.listenedMs, h.lastPlayedMs)
        }
    }

    // Awkward-on-purpose spellings: the normaliser has to fold case, width, tabs, runs of spaces,
    // non-breaking / ideographic spaces and compatibility characters exactly like before.
    private val artistPool = listOf(
        "Radiohead", "RADIOHEAD", " radiohead ", "Radio head", "The  Weeknd", "the weeknd", "THE\tWEEKND",
        "Ｂｅｙｏｎｃé", "beyoncé", "Beyoncé", "Björk", "BJÖRK", "Sigur Rós", "sigur   rós", "ABBA", "abba",
        "Daft Punk", "daft\npunk", "Ｄａｆｔ Ｐｕｎｋ", "Kendrick Lamar", "Taylor Swift", "TAYLOR SWIFT", "Unknown Artist",
        "", "   ", "　", "İstanbul Band", "istanbul band", "Mötley Crüe", "motley crue", "Queen", "queen ", "QUEEN",
        "Fleetwood Mac", "Fleetwood  Mac", "Nirvana", "NİRVANA", "Lorde"
    )
    private val genrePool = listOf("Rock", "rock", "ROCK ", "Pop", "pop", "Hip-Hop", "Electronic", "Jazz", "unknown", "Unknown", "", "  ", null, null)
    private val albumPool = listOf("", "  ", "Greatest Hits", "greatest   hits", "GREATEST HITS", "Live", "Debut", "debut ", "Ｄebut", "Second", "A\tB")

    private fun fixture(size: Int, seed: Long, withOnline: Boolean = true): Fixture {
        val random = Random(seed)
        val titleCount = size / 2 + 40 // forces many same-recording duplicates across ids
        fun song(id: String, online: Boolean = false): Song {
            val artist = artistPool[random.nextInt(artistPool.size)]
            val title = when (random.nextInt(9)) {
                0 -> "Track ${random.nextInt(titleCount)}"
                1 -> "TRACK ${random.nextInt(titleCount)}"
                2 -> " track  ${random.nextInt(titleCount)} "
                3 -> "Ｔrack ${random.nextInt(titleCount)}"
                4 -> ""
                else -> "Song ${random.nextInt(titleCount)}"
            }
            return Song.emptySong().copy(
                id = id,
                title = title,
                artist = artist,
                album = albumPool[random.nextInt(albumPool.size)],
                genre = genrePool[random.nextInt(genrePool.size)],
                duration = if (random.nextInt(10) == 0) 0L else 30_000L + random.nextInt(600_000),
                dateAdded = if (random.nextBoolean()) (now - random.nextInt(60) * day) / 1000 else now - random.nextInt(60) * day,
                isFavorite = random.nextInt(15) == 0,
                spotifyId = if (online) "sp$id" else null
            )
        }

        val library = List(size) { song("l$it") }
        val discoveries = if (withOnline) List(30) { song("d$it", online = true) } else emptyList()
        val releases = if (withOnline) List(12) { song("r$it", online = true) } else emptyList()
        // Some online songs are the same recording as library songs (a different id).
        val aliased = if (withOnline) library.take(6).mapIndexed { i, s -> s.copy(id = "alias$i", spotifyId = "spa$i") } else emptyList()

        val favorites = library.filter { random.nextInt(12) == 0 }.mapTo(hashSetOf()) { it.id }
        val signals = HashMap<String, MusicRecommendationEngine.Signal>()
        val history = HashMap<String, MusicRecommendationEngine.History>()
        for (s in library) {
            when (random.nextInt(4)) {
                0 -> signals[s.id] = MusicRecommendationEngine.Signal(
                    sessions = random.nextInt(30), completions = random.nextInt(20), earlySkips = random.nextInt(25),
                    voluntaryPlays = random.nextInt(10), lastPlayedMs = now - random.nextInt(90) * day - random.nextInt(86_400_000),
                    listenedMs = random.nextInt(5_000_000).toLong()
                )
            }
            when (random.nextInt(3)) {
                0 -> history[s.id] = MusicRecommendationEngine.History(
                    plays = random.nextInt(60), listenedMs = random.nextInt(9_000_000).toLong(),
                    lastPlayedMs = if (random.nextInt(5) == 0) 0 else now - random.nextInt(120) * day - random.nextInt(86_400_000)
                )
            }
        }
        return Fixture(library + aliased, discoveries, releases, favorites, signals, history)
    }

    private fun describe(picks: List<MusicRecommendationEngine.Pick>) =
        picks.map { "${it.song.id}|${it.score}|${it.reason}|${it.unheard}" }

    private fun describeLegacy(picks: List<LegacyRecommendationEngine.Pick>) =
        picks.map { "${it.song.id}|${it.score}|${it.reason}|${it.unheard}" }

    private fun describe(result: HomeRecommendations) = buildList {
        for ((kind, sections) in listOf("mix" to result.mixes, "shelf" to result.shelves)) {
            for (section in sections) {
                add("$kind|${section.id}|${section.title}|${section.subtitle}")
                add(section.songs.joinToString(",") { it.id })
                add(section.reasons.entries.joinToString(",") { "${it.key}=${it.value}" })
            }
        }
    }

    @Test
    fun `key normalisation is unchanged for awkward artist and title spellings`() {
        val samples = artistPool + albumPool + listOf("a\u000Bb", "a\u000Cb", "a   b", "x\r\ny", "  \t ", "ǅ", "ﬁne", "①②", "ｱｲｳ")
        for (value in samples) {
            val song = Song.emptySong().copy(id = "x", artist = value, title = value, album = value)
            val legacy = LegacyRecommendationEngine
            assertEquals(legacy.artistKey(song), MusicRecommendationEngine.artistKey(song), "artistKey of <$value>")
            assertEquals(legacy.recordingKey(song), MusicRecommendationEngine.recordingKey(song), "recordingKey of <$value>")
        }
    }

    @Test
    fun `rank returns the same scores reasons and order`() {
        for (seed in listOf(1L, 7L, 99L)) {
            val f = fixture(900, seed)
            val songs = f.library + f.discoveries + f.releases
            val expected = describeLegacy(
                LegacyRecommendationEngine.rank(songs, f.favorites, f.legacySignals, f.legacyHistory, now, 20_000L + seed)
            )
            val actual = describe(
                MusicRecommendationEngine.rank(songs, f.favorites, f.signals, f.history, now, 20_000L + seed)
            )
            assertEquals(expected, actual, "rank, fixture seed $seed")
        }
    }

    @Test
    fun `select returns the same picks for every limit and exploration fraction`() {
        val f = fixture(1_200, 5L)
        val songs = f.library + f.discoveries + f.releases
        val legacyRanked = LegacyRecommendationEngine.rank(songs, f.favorites, f.legacySignals, f.legacyHistory, now, 33L)
        val ranked = MusicRecommendationEngine.rank(songs, f.favorites, f.signals, f.history, now, 33L)
        assertEquals(describeLegacy(legacyRanked), describe(ranked))

        val pools = listOf(
            "all" to (legacyRanked to ranked),
            "head 40" to (legacyRanked.take(40) to ranked.take(40)),
            "unheard only" to (legacyRanked.filter { it.unheard } to ranked.filter { it.unheard }),
            "every 3rd" to (
                legacyRanked.filterIndexed { i, _ -> i % 3 == 0 } to ranked.filterIndexed { i, _ -> i % 3 == 0 }
                ),
        )
        for ((name, pool) in pools) {
            for (limit in listOf(-1, 0, 1, 2, 7, 24, 60, 300, 100_000)) {
                for (fraction in listOf(0f, 0.25f, 0.5f, 0.6f, 0.9f)) {
                    assertEquals(
                        describeLegacy(LegacyRecommendationEngine.select(pool.first, limit, fraction)),
                        describe(MusicRecommendationEngine.select(pool.second, limit, fraction)),
                        "select pool=$name limit=$limit fraction=$fraction"
                    )
                }
            }
        }
        // Default fraction too.
        assertEquals(
            describeLegacy(LegacyRecommendationEngine.select(legacyRanked, 24)),
            describe(MusicRecommendationEngine.select(ranked, 24))
        )
    }

    @Test
    fun `plan returns the same mixes and shelves`() {
        for ((size, seed) in listOf(600 to 11L, 1_500 to 12L, 40 to 13L, 5 to 14L)) {
            val f = fixture(size, seed)
            val expected = describe(
                LegacyHomeRecommendationPlanner.plan(
                    f.library, f.favorites, f.legacySignals, f.legacyHistory,
                    discoveries = f.discoveries, releases = f.releases, nowMs = now, seed = 19_000L + seed
                )
            )
            val actual = describe(
                HomeRecommendationPlanner.plan(
                    f.library, f.favorites, f.signals, f.history,
                    discoveries = f.discoveries, releases = f.releases, nowMs = now, seed = 19_000L + seed
                )
            )
            assertTrue(expected.isNotEmpty(), "fixture $size produced no sections at all")
            assertEquals(expected, actual, "plan, library size $size")
        }
    }

    @Test
    fun `a 5000 song plan stays well inside its time budget`() {
        val f = fixture(5_000, 21L)
        // Warm up the JIT / class loading once, then time a full plan.
        HomeRecommendationPlanner.plan(f.library.take(300), f.favorites, f.signals, f.history, nowMs = now, seed = 1L)
        val startNs = System.nanoTime()
        val result = HomeRecommendationPlanner.plan(
            f.library, f.favorites, f.signals, f.history,
            discoveries = f.discoveries, releases = f.releases, nowMs = now, seed = 2L
        )
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
        println("HomeRecommendationPlanner.plan(5000 songs) took $elapsedMs ms")
        assertTrue(result.shelves.isNotEmpty())
        // The old code needed several CPU-seconds for this; a generous bound still catches a regression
        // back to per-comparison regex compilation without making CI flaky.
        assertTrue(elapsedMs < 4_000, "plan took $elapsedMs ms")
    }
}
