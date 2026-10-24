package com.theveloper.pixelplay.data.ai.claude.taizo

import com.google.common.truth.Truth.assertThat
import com.theveloper.pixelplay.data.history.ArtistInsight
import com.theveloper.pixelplay.data.history.TrackInsight
import com.theveloper.pixelplay.data.recommendation.TasteAdjustment
import com.theveloper.pixelplay.data.recommendation.TasteKind
import com.theveloper.pixelplay.data.recommendation.TasteTarget
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/** The taste profile is built by code from numbers: shares, skip rates, times, confidence and what it leaves out. */
class TasteProfileBuilderTest {
    private val now = 1_800_000_000_000L
    private val utc: ZoneId = ZoneOffset.UTC

    private fun input(
        appPlays: Int = 400,
        importedPlays: Int = 600,
        artists: List<ArtistInsight> = listOf(
            ArtistInsight("Radiohead", score = 60.0, plays = 60, listenedMs = 0),
            ArtistInsight("Phoebe Bridgers", score = 30.0, plays = 30, listenedMs = 0),
            ArtistInsight("Drake", score = 10.0, plays = 12, listenedMs = 0),
        ),
        outcomes: List<ArtistOutcome> = emptyList(),
        playTimes: List<Long> = emptyList(),
        adjustments: List<TasteAdjustment> = emptyList(),
        period: TastePeriod = TastePeriod.ALL_TIME,
    ) = TasteProfileInput(
        period = period, nowMs = now, zone = utc, librarySongs = 1200,
        appPlays = appPlays, importedPlays = importedPlays, spotifyPlays = importedPlays - 100, youTubePlays = 100,
        artists = artists,
        tracks = listOf(TrackInsight("Creep", "Radiohead", score = 20.0, plays = 20, listenedMs = 0, songId = "-7")),
        outcomes = outcomes,
        playTimes = playTimes,
        adjustments = adjustments,
    )

    private fun JsonObject.obj(key: String) = this.getValue(key).jsonObject
    private fun JsonObject.arr(key: String): JsonArray = this.getValue(key).jsonArray
    private fun JsonObject.num(key: String) = this.getValue(key).jsonPrimitive.doubleOrNull!!

    @Test
    fun `top artists carry plays and their share of all plays`() {
        val profile = TasteProfileBuilder.build(input())
        val artists = profile.arr("top_artists").map { it.jsonObject }
        assertThat(artists.map { it.getValue("name").jsonPrimitive.content }).containsExactly("Radiohead", "Phoebe Bridgers", "Drake").inOrder()
        assertThat(artists[0].num("share_percent")).isEqualTo(60.0)
        assertThat(artists[1].num("share_percent")).isEqualTo(30.0)
        assertThat(artists[0].num("plays")).isEqualTo(60.0)
        assertThat(profile.arr("top_tracks")[0].jsonObject.getValue("id").jsonPrimitive.content).isEqualTo("-7")
        assertThat(profile.getValue("period").jsonPrimitive.content).isEqualTo("all_time")
    }

    @Test
    fun `the source weights are reported`() {
        val sources = TasteProfileBuilder.build(input()).obj("sources")
        assertThat(sources.num("spotify_weight")).isEqualTo(1.0)
        assertThat(sources.num("youtube_music_weight")).isEqualTo(0.35)
        assertThat(sources.num("youtube_music_plays")).isEqualTo(100.0)
    }

    @Test
    fun `confidence says how many plays it rests on`() {
        assertThat(TasteProfileBuilder.confidenceLevel(0)).isEqualTo("none")
        assertThat(TasteProfileBuilder.confidenceLevel(49)).isEqualTo("low")
        assertThat(TasteProfileBuilder.confidenceLevel(50)).isEqualTo("medium")
        assertThat(TasteProfileBuilder.confidenceLevel(299)).isEqualTo("medium")
        assertThat(TasteProfileBuilder.confidenceLevel(300)).isEqualTo("high")

        val thin = TasteProfileBuilder.build(input(appPlays = 25, importedPlays = 15)).obj("confidence")
        assertThat(thin.getValue("level").jsonPrimitive.content).isEqualTo("low")
        assertThat(thin.num("total_plays")).isEqualTo(40.0)
        assertThat(thin.getValue("note").jsonPrimitive.content).contains("40")
        val empty = TasteProfileBuilder.build(input(appPlays = 0, importedPlays = 0, artists = emptyList())).obj("confidence")
        assertThat(empty.getValue("level").jsonPrimitive.content).isEqualTo("none")
    }

    @Test
    fun `skipped artists need enough plays and a high early skip rate`() {
        val outcomes = listOf(
            ArtistOutcome("drake", "Drake", plays = 10, completions = 2, earlySkips = 7),
            ArtistOutcome("radiohead", "Radiohead", plays = 100, completions = 80, earlySkips = 4),
            ArtistOutcome("new band", "New Band", plays = 3, completions = 0, earlySkips = 3), // too few plays to say
            ArtistOutcome("meh", "Meh", plays = 20, completions = 10, earlySkips = 6),
        )
        val profile = TasteProfileBuilder.build(input(outcomes = outcomes))
        val skipped = profile.arr("skipped_artists").map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertThat(skipped).containsExactly("Drake", "Meh").inOrder()
        val overall = profile.obj("listening_outcomes")
        assertThat(overall.num("finished")).isEqualTo(92.0)
        assertThat(overall.num("skipped_early")).isEqualTo(20.0)
        assertThat(overall.num("skip_rate_percent")).isEqualTo(15.0)
        // The top artist carries its own outcome numbers.
        val radiohead = profile.arr("top_artists")[0].jsonObject
        assertThat(radiohead.num("skipped_early")).isEqualTo(4.0)
        assertThat(radiohead.num("skip_rate_percent")).isEqualTo(4.0)
    }

    private fun at(hour: Int, dayOfMonth: Int = 5, month: Int = 1): Long =
        ZonedDateTime.of(2027, month, dayOfMonth, hour, 30, 0, 0, utc).toInstant().toEpochMilli()

    @Test
    fun `listening times split by part of day and weekday when plays have real times`() {
        // 2027-01-05 is a Tuesday; 2027-01-09 a Saturday.
        val evenings = List(30) { at(19, 5) }
        val nights = List(10) { at(23, 9) }
        val profile = TasteProfileBuilder.build(input(playTimes = evenings + nights).copy(nowMs = at(12, 20)))
        val times = profile.obj("listening_times")
        assertThat(times.num("plays_with_time")).isEqualTo(40.0)
        assertThat(times.obj("part_of_day_percent").num("evening (17-21)")).isEqualTo(75.0)
        assertThat(times.obj("part_of_day_percent").num("night (22-4)")).isEqualTo(25.0)
        assertThat(times.obj("part_of_day_percent").num("morning (5-11)")).isEqualTo(0.0)
        assertThat(times.obj("weekday_percent").num("Tuesday")).isEqualTo(75.0)
        assertThat(times.obj("weekday_percent").num("Saturday")).isEqualTo(25.0)
        assertThat(times.num("peak_hour")).isEqualTo(19.0)
    }

    @Test
    fun `with too few timed plays there is no time pattern, only a note`() {
        val profile = TasteProfileBuilder.build(input(playTimes = List(TasteProfileBuilder.MIN_TIMED_PLAYS - 1) { at(8) }))
        assertThat(profile.containsKey("listening_times")).isFalse()
        assertThat(profile.containsKey("listening_times_note")).isTrue()
    }

    @Test
    fun `a period only counts the plays inside it`() {
        val old = List(40) { now - 400L * 86_400_000L }
        val profile = TasteProfileBuilder.build(input(playTimes = old, period = TastePeriod.MONTH))
        assertThat(profile.containsKey("listening_times")).isFalse()
    }

    @Test
    fun `what the data does not know is named, never invented`() {
        val profile = TasteProfileBuilder.build(input())
        assertThat(profile.arr("not_known").map { it.jsonPrimitive.content }).containsAtLeast("language", "energy")
        assertThat(profile.containsKey("genres_from_library_plays")).isFalse()
        assertThat(profile.containsKey("release_decades")).isFalse()
        val withGenres = TasteProfileBuilder.build(
            input().copy(libraryGenres = listOf("Rock" to 75.0, "Pop" to 25.0), decades = listOf("1990s" to 3.0, "2010s" to 1.0))
        )
        val genres = withGenres.arr("genres_from_library_plays").map { it.jsonObject }
        assertThat(genres[0].getValue("genre").jsonPrimitive.content).isEqualTo("Rock")
        assertThat(genres[0].num("share_percent")).isEqualTo(75.0)
        assertThat(withGenres.arr("release_decades")[0].jsonObject.getValue("decade").jsonPrimitive.content).isEqualTo("1990s")
    }

    @Test
    fun `the taste changes in force are part of the profile and the digest`() {
        val adjustment = TasteAdjustment(
            id = "ab12", kind = TasteKind.REDUCE, target = TasteTarget.ARTIST, key = "drake", label = "Drake",
            strength = 3, reason = "too much lately", createdAtMs = now - 3 * 86_400_000L,
        )
        val built = input(adjustments = listOf(adjustment))
        val shown = TasteProfileBuilder.build(built).arr("adjustments")[0].jsonObject
        assertThat(shown.getValue("id").jsonPrimitive.content).isEqualTo("ab12")
        assertThat(shown.getValue("action").jsonPrimitive.content).isEqualTo("reduce")
        assertThat(shown.getValue("reason").jsonPrimitive.content).isEqualTo("too much lately")
        assertThat(shown.num("age_days")).isEqualTo(3.0)

        val digest = TasteProfileBuilder.digest(built, recentPlays = listOf("Creep - Radiohead"))
        assertThat(digest).contains("Radiohead (60)")
        assertThat(digest).contains("Last plays (newest first): Creep - Radiohead")
        assertThat(digest).contains("Drake")
        assertThat(digest).contains("id ab12")
        assertThat(digest).contains("confidence=\"high\"")
        assertThat(digest).contains("YouTube Music 0.35")
    }

    @Test
    fun `the digest stays short for a large profile`() {
        val many = (1..40).map { ArtistInsight("Artist $it", score = 100.0 - it, plays = 100 - it, listenedMs = 0) }
        val digest = TasteProfileBuilder.digest(input(artists = many), recentPlays = (1..30).map { "Song $it - Artist" })
        assertThat(digest).contains("Artist 15 (85)")
        assertThat(digest).doesNotContain("Artist 16 ")
        assertThat(digest).contains("Song 10 - Artist")
        assertThat(digest).doesNotContain("Song 11 - Artist")
        assertThat(digest.length).isLessThan(1_500)
    }

    @Test
    fun `the period key defaults to six months`() {
        assertThat(TastePeriod.fromKey("month")).isEqualTo(TastePeriod.MONTH)
        assertThat(TastePeriod.fromKey("all_time")).isEqualTo(TastePeriod.ALL_TIME)
        assertThat(TastePeriod.fromKey(null)).isEqualTo(TastePeriod.SIX_MONTHS)
        assertThat(TastePeriod.fromKey("decade")).isEqualTo(TastePeriod.SIX_MONTHS)
    }
}
