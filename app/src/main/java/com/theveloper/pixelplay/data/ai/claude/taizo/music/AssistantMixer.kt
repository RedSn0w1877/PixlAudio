package com.theveloper.pixelplay.data.ai.claude.taizo.music

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.AssistantRules
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Candidate
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.MixPlanner
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.data.brain.BrainEngine
import com.theveloper.pixelplay.data.brain.BrainMixes
import com.theveloper.pixelplay.data.brain.EngineTrack
import com.theveloper.pixelplay.data.brain.LibraryIndex
import com.theveloper.pixelplay.data.brain.LibraryTrack
import com.theveloper.pixelplay.data.brain.MixSeed
import com.theveloper.pixelplay.data.brain.MixPoolBuilder
import com.theveloper.pixelplay.data.brain.Novelty
import com.theveloper.pixelplay.data.model.Song
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** What the mixer needs to know about a seed ref: the track, its identity, and what a radio request is asked with. */
class SeedMaterial(
    val ref: String,
    val rid: Long,
    val aid: Long,
    val title: String,
    val primaryArtist: String,
    val song: Song,
    val track: EngineTrack?,
    val radioSeed: com.theveloper.pixelplay.data.brain.RadioSeed?,
)

/** One pick the engine made for an assistant request, with everything a play of it must carry. */
class AssistantPickData(
    val rid: Long,
    val song: Song,
    val track: EngineTrack?,
    val libraryTrack: LibraryTrack?,
    val arm: Int,
    val whyKey: Int,
    val whyName: String,
    val retrQ: Int,
    val radioRank: Int,
    val explore: Boolean,
    val servedX: ByteArray?,
    val year: Int,
    val genreIds: IntArray,
)

class AssistantPlan(
    val picks: List<AssistantPickData>,
    val unsupported: List<String>,
    val coldStart: Boolean,
    val control: Boolean,
    val modelVersion: Int,
    val seedListCount: Int,
)

/**
 * The engine's answer to a `recommend` or `build_mix` request (docs/brain-spec.md, 9.3): the SAME pool, scorer and list builder a Your Mix
 * uses ([MixPlanner], `M0` or the learned weights while the gate has them on), over a pool the request shapes, with the request's filters
 * applied to what the data knows. The assistant only fills the request; nothing here is a model's opinion.
 *
 * The pool: the library shortlist, plus the cached (and, within the YouTube budget, fetched) radios of the seeds the request names, or of the
 * day's own seeds when it names none. A filter the data cannot answer at all is not applied and is reported ([AssistantRules.unsupportedFilters]);
 * a candidate whose own value is unknown never passes a filter on that value (unknown is absent, never guessed).
 *
 * Off the main thread: [BrainEngine.withState] and the database reads run on IO; the planner is tens of milliseconds.
 */
@Singleton
class AssistantMixer @Inject constructor(
    private val mixes: BrainMixes,
    private val brain: BrainEngine,
    private val assistCache: com.theveloper.pixelplay.data.brain.AssistCache,
) {
    suspend fun plan(query: AssistantRules.Query, seeds: List<SeedMaterial>, excludedArtists: Set<String>, regen: Int): AssistantPlan? {
        val now = mixes.clock.nowMs()
        val tz = BrainMixes.tzOffsetMin(now)
        val taste = mixes.constraints.snapshot(now)
        val salt = mixes.salt()
        val library = mixes.shortlist(now, tz, salt, taste)
        val ignored = runCatching { mixes.playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }.getOrDefault(HashSet())
        val byRid = HashMap<Long, LibraryTrack>(library.size * 2)
        for (t in library) byRid.putIfAbsent(t.rid, t)

        // The seeds the request named; none named means the day's own seeds (the ones a Your Mix uses), from the cache only.
        val mixSeeds = ArrayList<MixSeed>()
        for (s in seeds) {
            val list = mixes.brainRepository.cachedRadio(s.rid)
                ?: s.radioSeed?.let { runCatching { mixes.brainRepository.radioFor(it, allowNetwork = true) }.getOrNull() }
            val artistRows = mixes.brainRepository.cachedArtistRadio(s.rid)
            if (list == null && artistRows.isEmpty()) continue
            mixSeeds.add(MixSeed(s.rid, s.aid, s.title, s.primaryArtist, list, artistRows))
        }
        if (mixSeeds.isEmpty()) {
            for (p in mixes.pickSeeds(now, tz, salt, byRid, taste)) {
                val list = mixes.brainRepository.cachedRadio(p.rid)
                val artistRows = mixes.brainRepository.cachedArtistRadio(p.rid)
                if (list == null && artistRows.isEmpty()) continue
                mixSeeds.add(MixSeed(p.rid, p.aid, p.title, p.artist, list, artistRows))
            }
        }
        // "Let Taizo help the engine" (off by default): songs the assistant suggested by artists the listener likely does not know join the pool as
        // an artist list, so they are candidates like any other (arm ASK, scored by the same ranker); a request for familiar music or for no new music leaves them out.
        if (query.novelty != AssistantRules.FAMILIAR && query.noveltySharePct != 0) {
            val suggested = runCatching { assistCache.suggestedRows() }.getOrDefault(emptyList())
            if (suggested.isNotEmpty()) mixSeeds.add(MixSeed(0L, 0L, "", "", null, suggested))
        }
        if (library.isEmpty() && mixSeeds.isEmpty()) return null

        val names = LinkedHashSet<String>()
        for (s in mixSeeds) {
            s.list?.rows?.forEach { r -> r.row.artists.firstOrNull()?.name?.let(names::add) }
            s.artistRows.forEach { r -> r.row.artists.firstOrNull()?.name?.let(names::add) }
        }
        val nameList = names.toList()
        val nameAids = mixes.identity.resolveArtistNames(nameList)
        val aidByName = HashMap<String, Long>(nameList.size * 2)
        for (i in nameList.indices) aidByName[nameList[i]] = nameAids[i]

        val index = LibraryIndex(mixes.musicRepository.getAllSongsOnce())
        val pool = MixPoolBuilder.build(library, mixSeeds, index, taste, ignored) { aidByName[it] ?: 0L }
        if (pool.candidates.isEmpty()) return null

        // What the data says about every candidate: year, genres (ancestors included), tempo, energy, moods.
        val atlas = mixes.atlasProvider.atlas()
        val dna = HashMap<Long, com.theveloper.pixelplay.data.brain.store.TrackDnaEntity>()
        for (chunk in pool.candidates.map { it.rid }.chunked(DNA_CHUNK)) {
            for (row in mixes.trackDnaDao.getAll(chunk)) dna[row.rid] = row
        }
        val heard = brain.withState { st ->
            pool.candidates.filter { Affinity.isHeard(st.affinity, Family.TRACK, it.rid) }.mapTo(HashSet()) { it.rid }
        }
        val facts = HashMap<Long, AssistantRules.Facts>(pool.candidates.size * 2)
        val genresOf = HashMap<Long, IntArray>()
        for (c in pool.candidates) {
            val libTrack = pool.library[c.rid]
            val novelEntry = pool.novel[c.rid]
            val row = dna[c.rid]
            val artist = libTrack?.let { LibraryIndex.primaryArtistName(it.song) } ?: novelEntry?.track?.artists?.firstOrNull()?.name.orEmpty()
            val year = row?.firstYear?.takeIf { it > 0 } ?: libTrack?.song?.year?.takeIf { it > 0 } ?: novelEntry?.stored?.row?.year ?: 0
            val genres = genresFor(atlas, row)
            genresOf[c.rid] = genres
            facts[c.rid] = AssistantRules.Facts(
                year = year,
                genreIds = genres,
                novel = c.rid !in heard,
                bpm = row?.tempoBpmX10?.takeIf { it > 0 }?.let { it / 10 } ?: 0,
                artistKey = BrainText.fold(artist),
                energy = row?.energy?.takeIf { it >= 0 } ?: -1,
                moodIds = IntArray(row?.moods?.size ?: 0) { (row!!.moods[it].toInt() and 0xFF) + 1 },
            )
        }
        val seedEnergies = seeds.mapNotNull { dna[it.rid]?.energy?.takeIf { e -> e >= 0 } }
        val energyRef = if (seedEnergies.isNotEmpty()) seedEnergies.sum() / seedEnergies.size else AssistantRules.ENERGY_MIDDLE
        val ctx = AssistantRules.FilterContext(
            tempoKnown = facts.values.any { it.bpm > 0 },
            energyKnown = facts.values.any { it.energy >= 0 },
            moodKnown = facts.values.any { it.moodIds.isNotEmpty() },
            energyRef = energyRef,
            excludedArtists = excludedArtists,
        )
        val kept = ArrayList<Candidate>(pool.candidates.size)
        for (c in pool.candidates) if (AssistantRules.passes(query, facts.getValue(c.rid), ctx)) kept.add(c)
        val unsupported = AssistantRules.unsupportedFilters(query, ctx)
        if (kept.isEmpty()) return AssistantPlan(emptyList(), unsupported, coldStart = !mixes.hasState(), control = false, modelVersion = 0, seedListCount = mixSeeds.count { it.list != null })

        val edges = mixes.graphEdges.edgesFor(mixSeeds.map { it.rid } + pool.novel.keys)
        val (enriched, known) = mixes.candidateDna.enrich(kept)
        val level = Novelty.clamp(mixes.stations.novelty.value)
        val request = MixPlanner.Request(
            kind = MixPlanner.KIND_YOUR,
            candidates = enriched,
            seeds = pool.seedLists,
            nowMs = now,
            tzOffsetMin = tz,
            nuMultiplier = Novelty.factor(level),
            salt = salt,
            // A different request draws another list; the same request on the same day draws the same one.
            regenerate = regen,
            excludedRids = ignored,
            servedTimes = emptyMap(),
            edges = edges,
            count = query.count,
            embeddings = known.embeddings,
            tagsByRid = known.tags,
        )
        var control = false
        var model = 0
        val plan = brain.withState { st ->
            val listSeed = com.theveloper.pixelplay.brain.Ranker.listSeed(
                salt, "assistant:$regen", com.theveloper.pixelplay.brain.Repetition.mixDay(now, tz),
            )
            val choice = st.rankingChoice(st.session.sessionId, listSeed)
            control = choice.control
            model = choice.modelVersion
            request.ranking = choice.source
            request.nuOverride = AssistantRules.noveltyShare(query, MixPlanner.NU0 * Novelty.factor(level))
            MixPlanner.plan(request, st.affinity)
        }
        val out = ArrayList<AssistantPickData>(plan.picks.size)
        for (pick in plan.picks) {
            val novel = pool.novel[pick.rid]
            val libTrack = pool.library[pick.rid]
            val song = novel?.track?.toSong() ?: libTrack?.song ?: continue
            val f = facts[pick.rid]
            val whyName = when (pick.whyKey) {
                com.theveloper.pixelplay.brain.WhyLine.RADIO_FANS, com.theveloper.pixelplay.brain.WhyLine.ARTIST_MORE -> pool.artistNames[pick.whyArg].orEmpty()
                else -> ""
            }
            out.add(
                AssistantPickData(
                    rid = pick.rid, song = song, track = novel?.track, libraryTrack = libTrack,
                    // Music the listener does not have is what the assistant "asked for" (arm ASK); their own songs keep the arm that found them,
                    // so the ASK arm's record is about asking for new music, not about their library.
                    arm = if (novel != null) Arm.ASK else pick.arm,
                    whyKey = pick.whyKey, whyName = whyName, retrQ = pick.retrQ,
                    radioRank = novel?.radioRank?.takeIf { it > 0 } ?: pick.radioRank, explore = pick.explore,
                    servedX = pick.servedX, year = f?.year ?: 0, genreIds = genresOf[pick.rid] ?: IntArray(0),
                )
            )
        }
        return AssistantPlan(
            out, unsupported, coldStart = !mixes.hasState(), control = control, modelVersion = model,
            seedListCount = mixSeeds.count { it.list != null },
        )
    }

    /** The atlas ids of a track's genres, ancestors included, or none when the DNA has no tags. Unknown stays unknown. */
    private fun genresFor(atlas: GenreAtlas?, row: com.theveloper.pixelplay.data.brain.store.TrackDnaEntity?): IntArray {
        if (atlas == null || row == null) return IntArray(0)
        val tags = Tags.decode(row.tags)
        if (tags.isEmpty) return IntArray(0)
        val out = LinkedHashSet<Int>()
        for (t in tags.tags) {
            out.add(t.atlasId)
            for (a in atlas.ancestors(t.atlasId)) out.add(a)
        }
        return out.toIntArray()
    }

    companion object {
        private const val SIXTY_DAYS_MS = 60L * 24 * 60 * 60 * 1000
        private const val DNA_CHUNK = 800

        /** A stable small number from a request, so the same request on the same day is the same list and another request is another. */
        fun regenOf(query: AssistantRules.Query, bump: Int): Int {
            val text = StringBuilder()
            text.append(query.title).append('|').append(query.seedRefs).append('|').append(query.genreIds).append('|').append(query.moodIds)
                .append('|').append(query.yearFrom).append('-').append(query.yearTo).append('|').append(query.energy).append(query.tempo)
                .append(query.novelty).append(query.noveltySharePct).append('|').append(query.excludeArtistRefs).append(query.excludeGenreIds)
            return 1000 + (BrainHash.fnv1a64(text.toString()) ushr 40).toInt() + bump
        }
    }
}
