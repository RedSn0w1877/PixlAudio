package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoPlayer
import com.theveloper.pixelplay.data.model.Song

/**
 * A [MusicBackend] that holds only what a test puts in it. Nothing is invented here: a track that is not in [catalog] is not found, a
 * mix is built from [mixTracks] in order, and every call is recorded so a test can say what the tools asked the engine for.
 */
class FakeMusicBackend : MusicBackend {
    val catalog = LinkedHashMap<String, TrackInfo>()
    var skipAfter = Int.MAX_VALUE
    var mixTracks: List<TrackInfo> = emptyList()
    var unsupported: List<String> = emptyList()
    var coldStart = false
    var explanation: TrackExplanation? = null
    var taste: TasteExplanation = TasteExplanation("summary", emptyList(), emptyList())
    var genre: GenreLookup = GenreLookup(null, emptyList())
    var similar: SimilarArtists = SimilarArtists(emptyList())
    var releases: ReleaseList = ReleaseList(emptyList())
    var stationOutcome = StationOutcome(false, null, "no")
    var steerOutcome = SteerOutcome(false, 0, "no")

    val resolveCalls = ArrayList<List<Pair<String, String>>>()
    val recommendQueries = ArrayList<AssistantRules.Query>()
    val mixQueries = ArrayList<AssistantRules.Query>()
    val stationCalls = ArrayList<Triple<String, String, String>>()
    val steerCalls = ArrayList<Triple<String, String, String>>()
    private var looked = 0

    private fun key(title: String, artist: String) = title.trim().lowercase() + "|" + artist.trim().lowercase()

    fun add(ref: String, title: String, artist: String, year: Int = 0, inLibrary: Boolean = false): TrackInfo =
        TrackInfo(ref, title, artist, year, inLibrary).also { catalog[key(title, artist)] = it }

    override suspend fun resolve(items: List<Pair<String, String>>): List<Lookup> {
        resolveCalls.add(items)
        return items.map { (t, a) ->
            if (looked++ >= skipAfter) Lookup(null, skipped = true) else Lookup(catalog[key(t, a)])
        }
    }

    override suspend fun recommend(query: AssistantRules.Query): Recommendation {
        recommendQueries.add(query)
        return Recommendation(mixTracks.take(query.count).map { PickInfo(it, "radio_fans", listOf("Sabrina Carpenter")) }, unsupported, coldStart)
    }

    override suspend fun buildMix(query: AssistantRules.Query): BuiltMix? {
        mixQueries.add(query)
        if (mixTracks.isEmpty()) return null
        return BuiltMix(
            mixId = "m1", title = query.title.ifBlank { "Made for you" }, tracks = mixTracks.take(query.count).map { PickInfo(it, "asked", emptyList()) },
            unsupportedFilters = unsupported, query = query,
        )
    }

    override suspend fun adjustMix(mixId: String, chip: AssistantRules.Chip): BuiltMix? = null

    override suspend fun songsForMix(mixId: String): List<Song>? = null

    override suspend fun startStation(seedRef: String, seedKind: String, mode: String, player: TaizoPlayer?): StationOutcome {
        stationCalls.add(Triple(seedRef, seedKind, mode))
        return stationOutcome
    }

    override suspend fun steer(change: String, target: String, scope: String, player: TaizoPlayer?): SteerOutcome {
        steerCalls.add(Triple(change, target, scope))
        return steerOutcome
    }

    override suspend fun explainTrack(ref: String, player: TaizoPlayer?): TrackExplanation? = explanation

    override suspend fun explainTaste(scope: String): TasteExplanation = taste

    override suspend fun lookupGenre(query: String): GenreLookup = genre

    override suspend fun similarArtists(artist: String, count: Int, onlyUnplayed: Boolean): SimilarArtists = similar

    override suspend fun newReleases(scope: String, count: Int): ReleaseList = releases

    override suspend fun songForRef(ref: String): Song? = null

    override fun expectTags(queueName: String, songs: List<Song>) = Unit
}
