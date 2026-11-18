package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.Candidate
import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.brain.Retrieval
import com.theveloper.pixelplay.brain.Seeds
import com.theveloper.pixelplay.data.brain.store.RadioListEntity
import com.theveloper.pixelplay.data.brain.store.StoredRadioRow
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.service.player.AutoplayPolicy

/** A library track the mixes may pick, with the ids the brain knows it by. */
class LibraryTrack(
    val song: Song,
    val rid: Long,
    /** Primary artist id; 0 when unknown. */
    val aid: Long,
    val liked: Boolean,
    /** `ln(product)` of the listener's boost / reduce (5.11); 0 for none. */
    val boostLn: Double = 0.0,
)

/** One seed of a mix: a track the listener chose, and what the cache holds about its radio. */
class MixSeed(
    val rid: Long,
    /** Primary artist id; 0 when unknown. */
    val aid: Long,
    val title: String,
    val artistName: String,
    /** The song radio (or the Related tab), or null when none is cached. */
    val list: StoredRadio?,
    /** The artist's top songs and radio (`+artist` list), or empty. */
    val artistRows: List<StoredRadioRow> = emptyList(),
)

/** A track from outside the library that a mix may pick, with where it came from. */
class NovelEntry(
    val stored: StoredRadioRow,
    val track: EngineTrack,
    /** The best rank in any seed's list (1-based); 0 for an artist list. */
    val radioRank: Int,
    val seedAid: Long,
    val seedArtist: String,
    val fromArtistRadio: Boolean,
)

class MixPool(
    val candidates: List<Candidate>,
    val seedLists: List<Retrieval.SeedList>,
    val library: Map<Long, LibraryTrack>,
    val novel: Map<Long, NovelEntry>,
    /** Primary artist name by aid, for the reason lines ("Fans of X also play this"). */
    val artistNames: Map<Long, String>,
)

/**
 * The pool a Daily Mix or Your Mix is planned from (docs/brain-spec.md, 6.1, 6.2 "stage 2"): the
 * library shortlist plus the cached radios of the listener's own tracks. Pure: the callers read the
 * database and the network-free caches and hand the results over.
 *
 * A candidate that is not in the library goes through the same filters as autoplay's (official audio,
 * 60 to 900 seconds, no karaoke / cover / sped-up, live / instrumental / remix only when the seed is
 * one), and is dropped when the library already has the recording, when the listener ignored it three
 * times, or when a constraint of 5.11 excludes it. A library track appears once, as itself.
 */
object MixPoolBuilder {
    /** At most this many rows of one list are looked at: the head of a radio is where its quality is. */
    const val ROWS_PER_LIST = 50

    fun build(
        library: List<LibraryTrack>,
        seeds: List<MixSeed>,
        index: LibraryIndex,
        constraints: TasteConstraints,
        ignoredRids: Set<Long>,
        /** The primary artist id of an artist name (folded by the caller's resolver). */
        aidOfName: (String) -> Long,
    ): MixPool {
        val libraryByRid = LinkedHashMap<Long, LibraryTrack>(library.size * 2)
        for (track in library) if (track.rid != 0L) libraryByRid.putIfAbsent(track.rid, track)

        val candidates = ArrayList<Candidate>(library.size + seeds.size * 20)
        val seedLists = ArrayList<Retrieval.SeedList>(seeds.size)
        val novel = LinkedHashMap<Long, NovelEntry>()
        val artistNames = HashMap<Long, String>()

        for (track in libraryByRid.values) {
            candidates.add(
                Candidate(
                    rid = track.rid, aid = track.aid, albumKey = albumHash(track.song.album), inLibrary = true,
                    liked = track.liked, boostLn = track.boostLn,
                )
            )
            if (track.aid != 0L) artistNames.putIfAbsent(track.aid, LibraryIndex.primaryArtistName(track.song))
        }

        for (seed in seeds) {
            if (seed.aid != 0L && seed.artistName.isNotBlank()) artistNames.putIfAbsent(seed.aid, seed.artistName)
            val seedFlags = BrainText.cleanTitle(seed.title).versionFlags
            val list = seed.list
            if (list != null) {
                val rows = list.rows.take(ROWS_PER_LIST)
                seedLists.add(
                    Retrieval.SeedList(
                        seedRid = seed.rid,
                        seedAid = seed.aid,
                        rids = LongArray(rows.size) { rows[it].rid },
                        edgeFactor = if (list.kind == RadioListEntity.KIND_RELATED) Graph.RELATED else Graph.RADIO,
                    )
                )
                for (rank in rows.indices) {
                    addNovel(rows[rank], rank + 1, seed, seedFlags, fromArtist = false, index, constraints, ignoredRids, libraryByRid, novel, candidates, artistNames, aidOfName)
                }
            }
            for (stored in seed.artistRows.take(ROWS_PER_LIST)) {
                addNovel(stored, 0, seed, seedFlags, fromArtist = true, index, constraints, ignoredRids, libraryByRid, novel, candidates, artistNames, aidOfName)
            }
        }
        return MixPool(candidates, seedLists, libraryByRid, novel, artistNames)
    }

    private fun addNovel(
        stored: StoredRadioRow,
        rank: Int,
        seed: MixSeed,
        seedFlags: Int,
        fromArtist: Boolean,
        index: LibraryIndex,
        constraints: TasteConstraints,
        ignoredRids: Set<Long>,
        libraryByRid: Map<Long, LibraryTrack>,
        novel: MutableMap<Long, NovelEntry>,
        candidates: MutableList<Candidate>,
        artistNames: MutableMap<Long, String>,
        aidOfName: (String) -> Long,
    ) {
        val row = stored.row
        val rid = stored.rid
        if (rid == 0L || row.artists.isEmpty() || rid in ignoredRids || rid in libraryByRid) return
        if (novel.containsKey(rid)) {
            // The same recording in another seed's list: keep the better rank, nothing else changes.
            val old = novel[rid]!!
            if (rank > 0 && (old.radioRank == 0 || rank < old.radioRank) && !fromArtist) {
                novel[rid] = NovelEntry(old.stored, old.track, rank, seed.aid, seed.artistName, old.fromArtistRadio)
            }
            return
        }
        // Music the listener already has is not a card here: it is a library candidate.
        if (index.match(row) != null) return
        val track = EngineTrack.from(row, rid)
        val policy = AutoplayPolicy.Candidate(
            mediaId = track.songId.toString(),
            rid = rid,
            artistKey = BrainRepository.artistKeyOf(row),
            albumKey = null,
            durationMs = track.durationMs,
            versionFlags = BrainText.cleanTitle(row.title).versionFlags,
            officialAudio = row.isOfficialAudio,
            inLibrary = false,
            arm = if (fromArtist) Arm.ARTIST else Arm.RADIO,
            radioRank = rank,
        )
        if (!AutoplayPolicy.passesFilters(policy, seedFlags)) return
        val song = track.toSong()
        if (!constraints.allows(song)) return
        val primary = row.artists.first().name
        val aid = aidOfName(primary)
        if (aid != 0L) artistNames.putIfAbsent(aid, primary)
        novel[rid] = NovelEntry(stored, track, rank, seed.aid, seed.artistName, fromArtist)
        candidates.add(
            Candidate(
                rid = rid, aid = aid, albumKey = albumHash(row.album), inLibrary = false,
                fromArtistRadio = fromArtist, boostLn = constraints.boostLn(song),
            )
        )
    }

    /** A hash of the folded album name; 0 for none or "unknown album". */
    fun albumHash(album: String?): Long {
        val key = album?.let(BrainText::fold)?.takeIf { it.isNotEmpty() && it != "unknown album" } ?: return 0L
        return BrainHash.fnv1a64(key)
    }
}

/**
 * The four groups of seeds (3.6, "Stage 2 before islands exist") in the order [Seeds.select] reads
 * them. Pure: the callers hand over what the event log and the affinity tables say.
 */
object SeedGroups {
    /** A track that may be a seed, with where its YouTube id comes from; [usable] says a radio can be asked for it. */
    class Source(val rid: Long, val aid: Long)

    fun groups(
        recent: List<Source>,
        top: List<Source>,
        saved: List<Source>,
        randomRids: LongArray,
        aidOf: (Long) -> Long,
        usable: (Long) -> Boolean,
    ): List<List<Seeds.Seed>> = listOf(
        recent.filter { usable(it.rid) }.map { Seeds.Seed(it.rid, it.aid, Seeds.RECENT) },
        top.filter { usable(it.rid) }.map { Seeds.Seed(it.rid, it.aid, Seeds.TOP) },
        saved.filter { usable(it.rid) }.map { Seeds.Seed(it.rid, it.aid, Seeds.SAVED) },
        randomRids.filter(usable).map { Seeds.Seed(it, aidOf(it), Seeds.RANDOM) },
    )
}
