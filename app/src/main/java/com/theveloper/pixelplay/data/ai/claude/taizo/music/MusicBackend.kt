package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.data.ai.claude.taizo.TaizoPlayer
import com.theveloper.pixelplay.data.model.Song

/**
 * What the ten music tools of Ask Taizo (docs/brain-spec.md, 9.3) need from the app, as plain types. [TaizoMusicTools] turns a tool call
 * into one of these calls and the answer into the JSON the model reads; [BrainAssistantEngine] is the implementation over the brain's real
 * tables. The split exists so the tools can be tested with a fake that holds only what the test puts in it: the assistant never ranks and
 * never invents anything, so every field below is something the engine or a store returned (or an explicit "unknown").
 */
interface MusicBackend {
    /**
     * For each (title, artist) in order: the track it is, or not found. An item is [Lookup.skipped] when the day's YouTube requests were
     * used up before it could be asked (the alias table and the library are still tried first and cost nothing).
     */
    suspend fun resolve(items: List<Pair<String, String>>): List<Lookup>

    /** Ranked picks for [query] from the engine's own pool (library, cached radios, arms), already filtered. */
    suspend fun recommend(query: AssistantRules.Query): Recommendation

    /** A saved list for [query], or null when the engine had nothing to build one from. */
    suspend fun buildMix(query: AssistantRules.Query): BuiltMix?

    /** The mix again with a chip applied (a delta on the stored request, no model call), or null for a mix this chat never built. */
    suspend fun adjustMix(mixId: String, chip: AssistantRules.Chip): BuiltMix?

    /** The songs of a mix this chat built, in order, ready to play (engine tracks registered in `playable`); null for an unknown id. */
    suspend fun songsForMix(mixId: String): List<Song>?

    /** Starts a station and plays it through [player]; the outcome says why when it could not start. */
    suspend fun startStation(seedRef: String, seedKind: String, mode: String, player: TaizoPlayer?): StationOutcome

    suspend fun steer(change: String, target: String, scope: String, player: TaizoPlayer?): SteerOutcome

    /** What the engine really holds about [ref] ("now" is the song playing); fields it does not hold are null (and listed as unknown by the tool). */
    suspend fun explainTrack(ref: String, player: TaizoPlayer?): TrackExplanation?

    suspend fun explainTaste(scope: String): TasteExplanation

    suspend fun lookupGenre(query: String): GenreLookup

    suspend fun similarArtists(artist: String, count: Int, onlyUnplayed: Boolean): SimilarArtists

    suspend fun newReleases(scope: String, count: Int): ReleaseList

    /** The song a ref plays as: a library row, or an engine track registered in `playable` first. Null for a ref this chat never saw. */
    suspend fun songForRef(ref: String): Song?

    /** Announces the tags (surface assistant, the pick's arm and served vector) the next play of [songs] under [queueName] carries. */
    fun expectTags(queueName: String, songs: List<Song>)
}

/** One looked-up item: the track, or null; [skipped] when the YouTube budget of the day stopped the search. */
class Lookup(val track: TrackInfo?, val skipped: Boolean = false)

class TrackInfo(
    val ref: String,
    val title: String,
    val artist: String,
    /** 0 when unknown. */
    val year: Int,
    val inLibrary: Boolean,
    val album: String = "",
)

class PickInfo(
    val track: TrackInfo,
    /** `WhyLine.nameOf` key, or "" for none. */
    val whyKey: String,
    /** The names the reason is about (the artist of "Fans of X also play this"). */
    val whyArgs: List<String>,
)

class Recommendation(
    val picks: List<PickInfo>,
    /** Filters the request asked for that no data answered, and so were not applied. */
    val unsupportedFilters: List<String>,
    /** True when the engine had nothing of the listener's to rank (a thin history): the picks are the radios' own order. */
    val coldStart: Boolean = false,
)

class BuiltMix(
    val mixId: String,
    val title: String,
    val tracks: List<PickInfo>,
    val unsupportedFilters: List<String>,
    /** The request the mix was built from, kept for the chips (a delta on it is applied on the device, with no model call). */
    val query: AssistantRules.Query,
    /** The artist and genre the mix has most of, for the chips ("Less Sabrina", "More R&B"): "" and 0 when unknown. */
    val topArtistRef: String = "",
    val topArtistName: String = "",
    val topGenreId: Int = 0,
    val topGenreName: String = "",
)

class StationOutcome(val started: Boolean, val firstTrack: TrackInfo?, val reason: String = "")

class SteerOutcome(val applied: Boolean, val tailChanged: Int, val note: String = "")

/** The fields that are null are unknown: the tool says so in `unknown`, and the model is told to say so. */
class TrackExplanation(
    val ref: String,
    val title: String,
    val artist: String,
    /** The arm the pick was served under (`Arm.nameOf`), or null for a track the engine did not serve. */
    val arm: String?,
    val whyKey: String?,
    val whyArgs: List<String>,
    /** From the vector the pick was served with; null when no served vector is stored. */
    val factors: List<AssistantRules.Factor>?,
    val genres: List<String>?,
    /** 0 when unknown. */
    val year: Int,
    /** How many times the listener played it, or null when the engine has no record. */
    val plays: Int?,
    /** Share of those plays that were finished, 0.0 to 1.0; null with fewer than [MIN_PLAYS_FOR_RATE]. */
    val completionRate: Double?,
) {
    companion object {
        const val MIN_PLAYS_FOR_RATE = 3
    }
}

/** One section of Your sound, as named facts. [unknown] lists what the engine cannot say yet (and why, in words a listener understands). */
class TasteExplanation(
    val scope: String,
    val lines: List<TasteLine>,
    val unknown: List<String>,
)

class TasteLine(val name: String, val value: String, val detail: String = "")

class GenreLookup(
    /** The one genre asked for; null when the atlas does not have it (then [nearest] may name others). */
    val genre: GenreInfo?,
    val nearest: List<GenreRef>,
)

class GenreRef(val id: Int, val name: String)

class GenreInfo(
    val id: Int,
    val name: String,
    val description: String,
    val parents: List<GenreRef>,
    val children: List<GenreRef>,
    val related: List<GenreRef>,
    val influences: List<GenreRef>,
    val region: String,
    /** 0 when unknown. */
    val era: Int,
    val descriptors: List<String>,
    /** A mood of the atlas's mood list (its id is the list position plus one), not a genre. */
    val isMood: Boolean = false,
)

class SimilarArtist(val name: String, val ref: String, val score: Double, val sources: List<String>)

class SimilarArtists(val artists: List<SimilarArtist>, val note: String = "")

class ReleaseInfo(val title: String, val artist: String, val type: String, val year: Int, val ref: String)

class ReleaseList(val releases: List<ReleaseInfo>, val note: String = "")
