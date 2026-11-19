package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.Arm
import com.theveloper.pixelplay.brain.ArmRules
import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.IslandNames
import com.theveloper.pixelplay.brain.Islands
import com.theveloper.pixelplay.brain.ListMixes
import com.theveloper.pixelplay.brain.MixPlanner
import com.theveloper.pixelplay.brain.Repetition
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.brain.WhyLine
import java.util.Locale

/*
 * The made-for-you lists of stage 2b (docs/brain-spec.md, 8.2) that BrainMixes does not plan with the station planner, and the
 * helpers the island-aware ones share: the genres next to the listener's own (arm EDGE), the names of islands, the library
 * merged with the shortlist. Everything reads the database and the caches; nothing here touches the network.
 */

private const val SIXTY_DAYS_MS = 60L * 24 * 60 * 60 * 1000
private const val EDGE_LIBRARY_CAP = 150

/** The shortlist (it has repeat activation) first, then every other library track once. */
internal fun mergedLibrary(shortlisted: List<LibraryTrack>, all: List<LibraryTrack>): List<LibraryTrack> {
    val seen = HashSet<Long>(all.size * 2)
    val out = ArrayList<LibraryTrack>(all.size)
    for (t in shortlisted) if (seen.add(t.rid)) out.add(t)
    for (t in all) if (seen.add(t.rid)) out.add(t)
    return out
}

/**
 * The genres next to the ones the listener plays (6.1 EDGE): siblings and relatives of their six most played specific genres
 * that they play next to nothing of. Empty when the atlas or the listener's genres are not known: unknown stays unknown.
 */
internal suspend fun BrainMixes.adjacentGenres(limit: Int = 8): IntArray {
    val atlas = atlasProvider.atlas() ?: return IntArray(0)
    val shares = islandStore.tagShares()
    if (shares.isEmpty()) return IntArray(0)
    val loved = shares.entries
        .filter { (atlas.genre(it.key)?.depth ?: 0) >= 1 }
        .sortedWith { a, b ->
            val c = com.theveloper.pixelplay.brain.BrainMath.orderKey(b.value).compareTo(com.theveloper.pixelplay.brain.BrainMath.orderKey(a.value))
            if (c != 0) c else a.key.compareTo(b.key)
        }
        .take(6).map { it.key }.toIntArray()
    if (loved.isEmpty()) return IntArray(0)
    return ArmRules.adjacentGenres(atlas, loved, { shares[it] ?: 0.0 }, limit)
}

/** Library tracks the listener has not played whose strongest genre is an adjacent one (arm EDGE), by recording id. */
internal suspend fun BrainMixes.edgeLibrary(nowMs: Long): Map<Long, LibraryTrack> {
    val out = LinkedHashMap<Long, LibraryTrack>()
    for (tracks in edgeByGenre(nowMs).values) for (t in tracks) out.putIfAbsent(t.rid, t)
    return out
}

/** The same tracks grouped by the adjacent genre that is their strongest, in the order of [adjacentGenres]. */
internal suspend fun BrainMixes.edgeByGenre(nowMs: Long): Map<Int, List<LibraryTrack>> {
    val adjacentList = adjacentGenres()
    val adjacent = adjacentList.toHashSet()
    if (adjacent.isEmpty()) return emptyMap()
    val all = libraryRids.all(nowMs)
    val byRid = HashMap<Long, LibraryTrack>(all.size * 2)
    for (t in all) byRid.putIfAbsent(t.rid, t)
    val heard = brain.withState { st -> byRid.keys.filterTo(HashSet()) { Affinity.isHeard(st.affinity, Family.TRACK, it) } }
    val found = HashMap<Int, ArrayList<Long>>()
    for (chunk in byRid.keys.sorted().chunked(800)) {
        for (row in trackDnaDao.getAll(chunk)) {
            if (row.rid in heard) continue
            val v = Tags.decode(row.tags)
            if (!v.isEmpty && v.top in adjacent) found.getOrPut(v.top) { ArrayList() }.add(row.rid)
        }
    }
    val out = LinkedHashMap<Int, List<LibraryTrack>>()
    var left = EDGE_LIBRARY_CAP
    for (genre in adjacentList) {
        val rids = found[genre] ?: continue
        rids.sort()
        val tracks = rids.take(left).mapNotNull { byRid[it] }
        if (tracks.isEmpty()) continue
        out[genre] = tracks
        left -= tracks.size
        if (left <= 0) break
    }
    return out
}

/** "Soft pop & R&B · Sabrina Carpenter, Chappell Roan": the island's two highest-lift genres and its two biggest artists, real ones only. */
internal suspend fun BrainMixes.islandSubtitle(island: Islands.Island, nowMs: Long): String {
    val atlas = atlasProvider.atlas()
    // An island is named only when enough of it has a genre (unknown stays unknown), and only by a genre that is among its biggest shares.
    val named = atlas != null && taggedShare(island) >= NAME_MIN_TAGGED
    val liftTags = if (named) island.liftTags.filter { it in island.topTags }.toIntArray() else IntArray(0)
    val genres = if (atlas == null) emptyList() else IslandNames.pick(atlas, liftTags).toList().mapNotNull { AtlasNames.genreName(atlas, it) }
    val names = HashMap<Long, String>()
    for (t in libraryRids.all(nowMs)) if (t.aid != 0L) names.putIfAbsent(t.aid, LibraryIndex.primaryArtistName(t.song))
    val artists = island.topArtists.take(2).mapNotNull { names[it]?.takeIf { n -> n.isNotBlank() } }
    val genreText = genres.joinToString(" & ").replaceFirstChar { it.titlecase(Locale.getDefault()) }
    return listOf(genreText, artists.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** The share of the island's tracks that carry a genre at all (0..1): the placed tracks of the island, looked up in the DNA. */
internal suspend fun BrainMixes.taggedShare(island: Islands.Island): Double {
    val members = islandStore.current()?.memberOf ?: return 0.0
    val rids = ArrayList<Long>(island.size)
    for ((rid, id) in members) if (id == island.id) rids.add(rid)
    if (rids.isEmpty()) return 0.0
    var tagged = 0
    try {
        for (chunk in rids.chunked(800)) for (row in trackDnaDao.getAll(chunk)) if (!Tags.decode(row.tags).isEmpty) tagged++
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        return 0.0
    }
    return tagged.toDouble() / rids.size
}

/** At least this share of an island's tracks must have a genre before the island gets a genre name. */
internal const val NAME_MIN_TAGGED = 0.4

/** On Repeat, Rediscover and Just Dropped (6.2): ranked by one number each and filled under the hard constraints of 6.4. */
internal suspend fun BrainMixes.buildSimple(spec: MixSpec, now: Long, tz: Int, regenerate: Int): StoredMix? {
    val level = Novelty.clamp(stations.novelty.value)
    val salt = salt()
    val ignored = runCatching { playEventDao.ignoredThreeTimes(now - SIXTY_DAYS_MS).toHashSet() }.getOrDefault(HashSet())
    val log = served.recent(now)
    val taken = Repetition.takenOnDay(log.newestPerList(), now, tz, exceptSurface = spec.surface, exceptId = spec.surfaceId)
    // This week's First Listen is held all week (8.2): no other list repeats it.
    taken.addAll(firstListenRids(now, tz))
    val times = Repetition.timesServed(log, now)
    val excluded = HashSet<Long>(taken.size + ignored.size)
    excluded.addAll(taken)
    excluded.addAll(ignored)
    val tie = BrainHash.splitmix64(salt xor BrainHash.splitmix64(Repetition.mixDay(now, tz) * 8L + spec.kind.toLong() + regenerate.toLong() * 1024L))
    val items = ArrayList<StoredMixItem>(ListMixes.SIZE)
    when (spec.kind) {
        MixKinds.ON_REPEAT, MixKinds.REDISCOVER -> {
            val tracks = libraryRids.all(now).distinctBy { it.rid }
            if (tracks.isEmpty()) return null
            val rids = LongArray(tracks.size) { tracks[it].rid }
            val aids = LongArray(tracks.size) { tracks[it].aid }
            val albums = LongArray(tracks.size) { MixPoolBuilder.albumHash(tracks[it].song.album) }
            val picked = brain.withState { st ->
                val ranked = if (spec.kind == MixKinds.ON_REPEAT) ListMixes.onRepeat(st.affinity, rids, aids, albums, now)
                else ListMixes.rediscover(st.affinity, rids, aids, albums, times, now)
                ListMixes.fill(ranked, ListMixes.SIZE, excluded, tie)
            }
            for (p in picked) {
                val track = tracks[p.tag]
                // The list's own title says what the songs have in common; a line under each would only repeat it.
                items.add(StoredMixItem(rid = p.rid, songId = track.song.id, arm = p.arm, slot = items.size, whyKey = WhyLine.NONE))
            }
        }
        else -> {
            val allSongs = musicRepository.getAllSongsOnce()
            val cards = newReleases.cards(blockedArtists.current, tasteAdjustments.snapshot(now), LibraryIndex(allSongs))
            if (cards.isEmpty()) return null
            val rids = LongArray(cards.size) { cards[it].stored.rid }
            val names = cards.map { it.stored.row.artists.firstOrNull()?.name.orEmpty() }
            val aids = identity.resolveArtistNames(names)
            val albums = LongArray(cards.size) { MixPoolBuilder.albumHash(cards[it].stored.row.album) }
            val recency = DoubleArray(cards.size) { (cards.size - it).toDouble() }
            val picked = brain.withState { st ->
                val p0 = Affinity.p0(st.affinity, now)
                val aff = DoubleArray(cards.size) { if (aids[it] == 0L) 0.0 else Affinity.aff(st.affinity, Family.ARTIST, aids[it], now, p0) }
                val heard = BooleanArray(cards.size) { aids[it] != 0L && Affinity.isHeard(st.affinity, Family.ARTIST, aids[it]) }
                ListMixes.fill(ListMixes.justDropped(rids, aids, albums, aff, heard, recency), ListMixes.SIZE, excluded, tie, 2)
            }
            for (p in picked) {
                val card = cards[p.tag]
                items.add(
                    StoredMixItem(
                        rid = p.rid, row = card.stored, arm = Arm.NEW, slot = items.size, whyKey = WhyLine.NEW_RELEASE,
                        whyArg = p.aid, whyName = card.release.artistName.takeIf { it.isNotBlank() }.orEmpty(),
                    )
                )
            }
        }
    }
    if (items.size < MIN_SIMPLE_ITEMS) return null
    return StoredMix(
        kind = spec.kind, builtAtMs = now, seed = tie, regenerate = regenerate, novelty = level,
        tasteSignature = constraints.signature(), seedLists = 0, items = items, mixId = spec.surfaceId,
    )
}

/** The recording ids of this week's First Listen, or none. */
internal suspend fun BrainMixes.firstListenRids(now: Long, tz: Int): Set<Long> {
    val stored = served.todays(MixSpec(MixKinds.FIRST_LISTEN).surface, now, tz, MixSpec(MixKinds.FIRST_LISTEN).surfaceId, weekly = true)
        ?: return emptySet()
    return stored.items.mapTo(HashSet()) { it.rid }
}

/** A Just Dropped or First Listen list needs at least this many to be worth a card. */
internal const val MIN_SIMPLE_ITEMS = 6
