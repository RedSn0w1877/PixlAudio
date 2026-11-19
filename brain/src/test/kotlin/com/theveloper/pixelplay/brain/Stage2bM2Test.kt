package com.theveloper.pixelplay.brain

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Stage 2b, milestone 2: the pure core of embeddings, retrieval, islands, the arms, the simple mixes, the
 * island-aware planner and the Home page rules (docs/brain-spec.md, 5.3, 5.4, 6.1, 6.2, 8.1, 11). The synthetic
 * listeners here have taste clusters planted in them, so "the islands recover the clusters" is a thing a test can say.
 */
private typealias Planted = Stage2bM2Golden.Planted

class Stage2bM2Test {
    private val atlas by lazy { GenreAtlas.parse(Stage2bGolden.atlasText(File("."))) }

    private fun Planted.purity(fit: Islands.Fit): Double {
        // For each planted cluster, the share of its tracks that sit in its most common island.
        var good = 0
        var total = 0
        val byCluster = HashMap<Int, HashMap<Int, Int>>()
        for ((i, t) in tracks.withIndex()) {
            val island = fit.islandOf(t.rid)
            byCluster.getOrPut(cluster[i]) { HashMap() }.merge(island, 1, Int::plus)
        }
        for (counts in byCluster.values) {
            good += counts.values.max()
            total += counts.values.sum()
        }
        return good.toDouble() / total.toDouble()
    }


    // ---- FastRP and PageRank ----------------------------------------------------------------------

    private fun twoCommunities(): Pair<Graph, List<LongArray>> {
        val a = LongArray(20) { BrainHash.fnv1a64("n:a $it") }
        val b = LongArray(20) { BrainHash.fnv1a64("n:b $it") }
        val builder = Graph.Builder()
        val rng = BrainHash.Rng(7L)
        for (group in listOf(a, b)) for (i in group.indices) for (k in 1..4) {
            val j = (i + k) % group.size
            builder.addEdge(group[i], group[j], 0.5 + 0.05 * (k % 3))
        }
        // One thin bridge.
        builder.addEdge(a[0], b[0], 0.1)
        rng.nextLong()
        return builder.build() to listOf(a, b)
    }

    @Test
    fun `fastrp puts neighbours closer than strangers and is deterministic`() {
        val (graph, groups) = twoCommunities()
        val e1 = FastRp.embed(graph)
        val e2 = FastRp.embed(graph)
        assertEquals(e1.dataCopy().toList(), e2.dataCopy().toList())
        var within = 0.0
        var across = 0.0
        var nW = 0
        var nA = 0
        for (i in 0 until 20) for (j in i + 1 until 20) {
            within += e1.cos(groups[0][i], groups[0][j]); nW++
            across += e1.cos(groups[0][i], groups[1][j]); nA++
        }
        assertTrue(within / nW > across / nA + 0.2, "within ${within / nW} across ${across / nA}")
        for (v in e1.dataCopy()) assertTrue(v.toInt() in -127..127)
    }

    @Test
    fun `fastrp of a graph with no nodes is empty and an unknown id has no vector`() {
        val e = FastRp.embed(Graph.EMPTY)
        assertEquals(0, e.size)
        assertEquals(0.0, e.cos(1L, 2L))
    }

    @Test
    fun `personalised pagerank favours the seed's own side and divides by degree`() {
        val (graph, groups) = twoCommunities()
        val seeds = longArrayOf(groups[0][0], groups[0][1], groups[0][2])
        val r = Ppr.run(graph, seeds, doubleArrayOf(3.0, 2.0, 1.0), 40)
        assertTrue(r.size > 0)
        val sideA = groups[0].toSet()
        val top = r.ids.take(15)
        assertTrue(top.count { it in sideA } >= 12, "the first 15 should be mostly side A: ${top.count { it in sideA }}")
        // Scores are non-increasing in the order given.
        for (i in 1 until r.size) assertTrue(BrainMath.orderKey(r.scores[i - 1]) >= BrainMath.orderKey(r.scores[i]))
        assertEquals(0, Ppr.run(graph, LongArray(0), DoubleArray(0)).size)
    }

    // ---- islands ----------------------------------------------------------------------------------

    @Test
    fun `islands recover the clusters planted in a synthetic listener`() {
        for ((clusters, per) in listOf(3 to 70, 4 to 100, 5 to 120, 6 to 150)) {
            val p = Stage2bM2Golden.planted(atlas, "test:recover:$clusters", clusters, per, 6)
            val fit = Islands.fit(p.tracks)
            assertEquals(clusters, fit.islands.size, "islands for $clusters planted clusters")
            assertTrue(p.purity(fit) >= 0.95, "purity ${p.purity(fit)} for $clusters clusters")
            // Every placed track belongs to one island.
            assertEquals(p.tracks.size, fit.memberRids.size)
        }
    }

    @Test
    fun `fewer than 60 tracks make one island, and unplaceable tracks are left out`() {
        val few = Stage2bM2Golden.planted(atlas, "test:few", 3, 15, 6)
        assertEquals(1, Islands.fit(few.tracks).islands.size)
        val blind = listOf(Islands.Track(1L, 1L, 5.0, 0.0, null, null), Islands.Track(2L, 1L, 4.0, 0.0, null, Tags.Vector.EMPTY))
        val fit = Islands.fit(blind)
        assertEquals(0, fit.islands.size, "a track with no embedding and no tag cannot be placed")
    }

    @Test
    fun `an island keeps its id when the taste moves a little and a new island gets a new one`() {
        val p = Stage2bM2Golden.planted(atlas, "test:stable", 4, 100, 6)
        val before = Islands.fit(p.tracks)
        val drifted = p.tracks.filterIndexed { i, _ -> i % 7 != 0 }.map {
            Islands.Track(it.rid, it.aid, it.weight * 0.8, it.recentWeight, it.emb, it.tags)
        }
        val after = Islands.fit(drifted, before.islands, before.maxId)
        assertEquals(before.islands.map { it.id }.sorted(), after.islands.map { it.id }.sorted())
        // The same medoid track still names the same island.
        for (island in after.islands) {
            val old = before.islands.first { it.id == island.id }
            assertTrue(Islands.dot(old.centroid, island.centroid) > 0.9)
        }
        val extra = Stage2bM2Golden.planted(atlas, "test:stable:extra", 1, 100, 6)
        val grown = Islands.fit(drifted + extra.tracks, after.islands, after.maxId)
        assertEquals(5, grown.islands.size)
        assertEquals(1, grown.islands.count { it.id > before.maxId })
        assertTrue(grown.maxId > before.maxId)
    }

    @Test
    fun `an island is named from the genres of its tracks and a track with no genre gives no name`() {
        val p = Stage2bM2Golden.planted(atlas, "test:names", 3, 80, 6)
        val fit = Islands.fit(p.tracks)
        for (island in fit.islands) {
            assertTrue(island.liftTags.isNotEmpty())
            for (id in island.liftTags) assertNotNull(atlas.genre(id), "every tag id is a real atlas genre")
            assertTrue(island.topArtists.isNotEmpty())
        }
        val noTags = p.tracks.map { Islands.Track(it.rid, it.aid, it.weight, it.recentWeight, it.emb, null) }
        for (island in Islands.fit(noTags).islands) assertEquals(0, island.liftTags.size, "unknown genres stay absent")
    }

    // ---- the island features and the planner ------------------------------------------------------

    @Test
    fun `the island features are live with islands and exactly zero without`() {
        val p = Stage2bM2Golden.planted(atlas, "test:features", 3, 40, 6)
        val fit = Islands.fit(p.tracks)
        val t = p.tracks[0]
        val c = Candidate(t.rid, t.aid, emb = t.emb, tags = t.tags, popPermille = 800, fresh = true)
        val x = DoubleArray(Features.D)
        val affinity = Affinity.State()
        Features.compute(c, Arm.RADIO, 0.0, 0, affinity, SessionState(), Graph.EMPTY, 1_000L, 0.5, false, x)
        assertEquals(0.0, x[Features.ISLAND_SIM])
        assertEquals(0.0, x[Features.ISLAND_WEIGHT])
        assertEquals(0.0, x[Features.NOVELTY_DIST])
        assertEquals(0.3, x[Features.POP], 1e-12)
        assertEquals(1.0, x[Features.FRESH])
        Features.compute(c, Arm.RADIO, 0.0, 0, affinity, SessionState(), Graph.EMPTY, 1_000L, 0.5, false, x, IslandContext(fit.islands))
        assertTrue(x[Features.ISLAND_SIM] > 0.5, "the track sits in its own island: ${x[Features.ISLAND_SIM]}")
        assertTrue(x[Features.ISLAND_WEIGHT] > 0.0)
        // An unheard track: its novelty distance is how far it is from every island.
        assertEquals(1.0 - x[Features.ISLAND_SIM], x[Features.NOVELTY_DIST], 1e-12)
    }

    private fun candidatesOf(p: Planted): List<Candidate> = p.tracks.mapIndexed { k, t ->
        Candidate(t.rid, t.aid, BrainHash.fnv1a64("alb ${t.rid / 9}"), inLibrary = k % 3 != 0, emb = t.emb, tags = t.tags, artistNew = k % 3 == 0 && k % 2 == 0)
    }

    private fun planOf(p: Planted, kind: Int, island: Int, fit: Islands.Fit, now: Long = 1_760_000_000_000L): StationPlanner.Plan {
        val request = MixPlanner.Request(
            kind = kind, candidates = candidatesOf(p), seeds = emptyList(), nowMs = now, tzOffsetMin = 0,
            salt = 5L, islands = IslandContext(fit.islands), islandIdx = island,
        )
        return MixPlanner.plan(request, Affinity.State())
    }

    @Test
    fun `a Daily Mix of an island holds only tracks close to that island and each island has its own list`() {
        val p = Stage2bM2Golden.planted(atlas, "test:daily", 4, 100, 6)
        val fit = Islands.fit(p.tracks)
        val ctx = IslandContext(fit.islands)
        val byRid = p.tracks.associateBy { it.rid }
        val rids = ArrayList<Set<Long>>()
        for (k in fit.islands.indices) {
            val plan = planOf(p, MixPlanner.KIND_DAILY, k, fit)
            assertTrue(plan.picks.size >= 20, "mix $k has ${plan.picks.size}")
            for (pick in plan.picks) {
                val t = byRid[pick.rid]!!
                val sim = Islands.similarity(fit.islands[k], Islands.point(t.emb, t.tags)!!)
                assertTrue(sim >= MixPlanner.ISLAND_FLOOR, "island sim $sim")
            }
            rids.add(plan.picks.map { it.rid }.toSet())
        }
        // Different islands give different lists (no track appears in two mixes of the day once the day's other mixes are excluded).
        for (i in rids.indices) for (j in i + 1 until rids.size) {
            val shared = rids[i].intersect(rids[j])
            assertTrue(shared.size <= 3, "islands $i and $j share ${shared.size}")
        }
        assertTrue(ctx.islands.size == 4)
    }

    @Test
    fun `Your Mix across islands keeps the listener's mix of islands`() {
        val p = Stage2bM2Golden.planted(atlas, "test:your", 4, 100, 6)
        val fit = Islands.fit(p.tracks)
        val plan = planOf(p, MixPlanner.KIND_YOUR, -1, fit)
        val counts = IntArray(fit.islands.size)
        for (pick in plan.picks) counts[fit.islands.indexOfFirst { it.id == fit.islandOf(pick.rid) }]++
        for (c in counts) assertTrue(c >= 3, "every island is in the mix: ${counts.toList()}")
        val total = counts.sum().toDouble()
        val weights = DoubleArray(fit.islands.size) { fit.islands[it].weight }
        val cal = Calibration(weights)
        for (i in counts.indices) repeat(counts[i]) { cal.add(i) }
        assertTrue(cal.divergence() < 0.15, "calibration KL ${cal.divergence()} (test 5 asks at most 0.15), shares ${counts.map { it / total }}")
    }

    @Test
    fun `calibration pulls an under-represented island up`() {
        val cal = Calibration(doubleArrayOf(100.0, 100.0, 100.0))
        repeat(9) { cal.add(0) }
        assertTrue(cal.gain(1) > cal.gain(0))
        assertEquals(0.0, cal.gain(-1))
    }

    @Test
    fun `First Listen is unheard music with at most two per artist and some artists the listener never played`() {
        val p = Stage2bM2Golden.planted(atlas, "test:first", 4, 60, 6)
        val fit = Islands.fit(p.tracks)
        val affinity = Affinity.State()
        // The listener has heard a quarter of the tracks.
        val heard = p.tracks.filterIndexed { i, _ -> i % 4 == 0 }
        for ((n, t) in heard.withIndex()) Affinity.apply(affinity, Family.TRACK, t.rid, 1.0, 1.0, 1.0, 1_700_000_000_000L + n * 1000L)
        val cands = p.tracks.mapIndexed { k, t ->
            Candidate(t.rid, t.aid, BrainHash.fnv1a64("alb ${t.rid / 9}"), inLibrary = false, emb = t.emb, tags = t.tags, artistNew = k % 2 == 0)
        }
        val request = MixPlanner.Request(
            kind = MixPlanner.KIND_FIRST_LISTEN, candidates = cands, seeds = emptyList(), nowMs = 1_760_000_000_000L, tzOffsetMin = 0,
            islands = IslandContext(fit.islands),
        )
        val plan = MixPlanner.plan(request, affinity)
        assertTrue(plan.picks.size >= 25, "picks ${plan.picks.size}")
        val heardRids = heard.map { it.rid }.toSet()
        assertTrue(plan.picks.none { it.rid in heardRids }, "no heard track")
        val byRid = p.tracks.associateBy { it.rid }
        val perArtist = plan.picks.groupBy { byRid[it.rid]!!.aid }.mapValues { it.value.size }
        assertTrue(perArtist.values.all { it <= 2 }, "at most 2 per artist: $perArtist")
        val newShare = plan.picks.count { pick -> cands.first { it.rid == pick.rid }.artistNew }.toDouble() / plan.picks.size
        assertTrue(newShare >= 0.4, "new-artist share $newShare")
    }

    // ---- the arms and the simple lists --------------------------------------------------------------

    @Test
    fun `BACK needs five completions, a year of silence, and a track outside the library`() {
        val now = 1_760_000_000_000L
        val longAgo = now - 400L * 86_400_000L
        assertTrue(ArmRules.isBack(5, longAgo, now, false))
        assertFalse(ArmRules.isBack(4, longAgo, now, false))
        assertFalse(ArmRules.isBack(9, now - 100L * 86_400_000L, now, false))
        assertFalse(ArmRules.isBack(9, longAgo, now, true))
        assertFalse(ArmRules.isBack(9, 0L, now, false))
    }

    @Test
    fun `adjacent genres are siblings of loved ones the listener does not play, never the loved one itself`() {
        val loved = atlas.idForName("shoegaze")
        assertTrue(loved != GenreAtlas.NONE)
        val out = ArmRules.adjacentGenres(atlas, intArrayOf(loved), { 0.0 }, 10)
        assertTrue(out.isNotEmpty())
        assertFalse(out.contains(loved))
        for (id in out) assertNotNull(atlas.genre(id))
        // A genre the listener already plays is not adjacent.
        val played = ArmRules.adjacentGenres(atlas, intArrayOf(loved), { if (it == out[0]) 0.5 else 0.0 }, 10)
        assertFalse(played.contains(out[0]))
        // An atlas id that does not exist gives nothing.
        assertEquals(0, ArmRules.adjacentGenres(atlas, intArrayOf(999_999), { 0.0 }, 10).size)
    }

    @Test
    fun `On Repeat is what was played in the last 30 days and Rediscover what was loved and left for 90`() {
        val now = 1_760_000_000_000L
        val day = 86_400_000L
        val affinity = Affinity.State()
        val rids = LongArray(60) { 1000L + it }
        val aids = LongArray(60) { 7000L + it / 3 }
        val albums = LongArray(60) { 9000L + it / 2 }
        // 0..19: finished 3 times a week ago; 20..49: finished 5 times 120 days ago; 50..59: never played.
        for (i in 0 until 20) for (k in 0 until 3) Affinity.apply(affinity, Family.TRACK, rids[i], 1.0, 1.0, 1.0, now - (7 + k) * day)
        for (i in 20 until 50) for (k in 0 until 5) Affinity.apply(affinity, Family.TRACK, rids[i], 1.0, 1.0, 1.0, now - (120 + k) * day)
        val onRepeat = ListMixes.fill(ListMixes.onRepeat(affinity, rids, aids, albums, now), 30, emptySet(), 1L)
        assertTrue(onRepeat.isNotEmpty() && onRepeat.all { it.rid in rids.take(20) })
        val rediscover = ListMixes.fill(ListMixes.rediscover(affinity, rids, aids, albums, emptyMap(), now), 30, emptySet(), 1L)
        assertTrue(rediscover.isNotEmpty() && rediscover.all { it.rid in rids.slice(20 until 50) })
        // Served often in the last 30 days: steps aside for the ones that were not.
        val served = rids.slice(20 until 30).associateWith { 4 }
        val rotated = ListMixes.fill(ListMixes.rediscover(affinity, rids, aids, albums, served, now), 8, emptySet(), 1L)
        assertTrue(rotated.none { it.rid in served.keys }, "rotated list should not hold the ones just served")
        // Spacing: never the same artist within 4 slots.
        for (i in 4 until onRepeat.size) for (k in 1..4) assertTrue(onRepeat[i].aid != onRepeat[i - k].aid)
        // Nothing another mix of the day holds.
        val skipped = onRepeat.take(5).map { it.rid }.toSet()
        val again = ListMixes.fill(ListMixes.onRepeat(affinity, rids, aids, albums, now), 30, skipped, 1L)
        assertTrue(again.none { it.rid in skipped })
    }

    @Test
    fun `Just Dropped puts loved artists first and at most two a artist`() {
        val n = 20
        val rids = LongArray(n) { 100L + it }
        val aids = LongArray(n) { 500L + it % 4 }
        val albums = LongArray(n) { 800L + it }
        val aff = DoubleArray(n) { if (it % 4 == 0) 2.0 else -1.0 }
        val heard = BooleanArray(n) { it % 4 != 3 }
        val recency = DoubleArray(n) { it.toDouble() }
        val out = ListMixes.fill(ListMixes.justDropped(rids, aids, albums, aff, heard, recency), 30, emptySet(), 1L, 2)
        assertTrue(out.size in 1..8)
        assertTrue(out.groupBy { it.aid }.values.all { it.size <= 2 })
        assertEquals(500L, out[0].aid, "the loved artist comes first")
    }

    // ---- the Home page rules ------------------------------------------------------------------------

    private fun shelf(type: Int, id: String, count: Int, artists: Int = 12, salt: Long = 0L): HomePlanner.ShelfIn {
        val card = HomePlanner.isCardShelf(type)
        return HomePlanner.ShelfIn(type, id, List(count) { HomePlanner.Item(if (card) 0L else 10_000L * type + it + salt, 1L + it % artists + 100L * type, it) })
    }

    @Test
    fun `a shelf with too few playable items is hidden, not padded`() {
        val plan = HomePlanner.plan(
            listOf(
                shelf(HomePlanner.MADE_FOR_YOU, "mixes", 3),
                shelf(HomePlanner.ON_REPEAT, "repeat", 5),
                shelf(HomePlanner.REDISCOVER, "re", 9),
                shelf(HomePlanner.STATIONS, "st", 3),
            )
        )
        assertEquals(listOf(HomePlanner.MADE_FOR_YOU, HomePlanner.REDISCOVER), plan.map { it.type })
    }

    @Test
    fun `no track twice on the page and artists limited per shelf and per page`() {
        // Every shelf offers the same recordings by the same few artists.
        val common = List(30) { HomePlanner.Item(500L + it, 1L + it % 3, it) }
        val plan = HomePlanner.plan(
            listOf(
                HomePlanner.ShelfIn(HomePlanner.ON_REPEAT, "a", common),
                HomePlanner.ShelfIn(HomePlanner.REDISCOVER, "b", common + List(30) { HomePlanner.Item(900L + it, 50L + it % 20, 100 + it) }),
            )
        )
        val seen = HashSet<Long>()
        val perArtist = HashMap<Long, Int>()
        for (s in plan) {
            val shelfArtists = HashMap<Long, Int>()
            for (i in s.items) {
                assertTrue(seen.add(i.rid), "track ${i.rid} twice")
                shelfArtists.merge(i.aid, 1, Int::plus)
                perArtist.merge(i.aid, 1, Int::plus)
            }
            assertTrue(shelfArtists.values.all { it <= HomePlanner.ARTIST_PER_SHELF })
        }
        assertTrue(perArtist.values.all { it <= HomePlanner.ARTIST_PER_PAGE })
    }

    @Test
    fun `the page rules hold on random pages`() {
        val types = intArrayOf(
            HomePlanner.MADE_FOR_YOU, HomePlanner.BECAUSE_LISTENED, HomePlanner.BECAUSE_LISTENED, HomePlanner.NEW_FROM_LOVED,
            HomePlanner.ON_REPEAT, HomePlanner.REDISCOVER, HomePlanner.STATIONS, HomePlanner.STEP_SIDEWAYS,
            HomePlanner.MORE_LIKE_ALBUM, HomePlanner.POPULAR,
        )
        for (c in 0 until 400) {
            val rng = BrainHash.Rng(BrainHash.fnv1a64("page:$c"))
            val shelves = ArrayList<HomePlanner.ShelfIn>()
            for ((s, type) in types.withIndex()) {
                if (rng.uniform() < 0.2) continue
                val count = (rng.uniform() * 22).toInt()
                val card = HomePlanner.isCardShelf(type)
                shelves.add(
                    HomePlanner.ShelfIn(type, "s$s", List(count) {
                        HomePlanner.Item(if (card) 0L else 1L + (rng.uniform() * 70).toInt(), 1L + (rng.uniform() * 10).toInt(), it)
                    })
                )
            }
            val plan = HomePlanner.plan(shelves)
            assertTrue(plan.size <= HomePlanner.MAX_SHELVES)
            val seen = HashSet<Long>()
            val artists = HashMap<Long, Int>()
            for (s in plan) {
                assertTrue(s.items.size >= HomePlanner.minItems(s.type), "a shelf is never padded or short")
                assertTrue(s.items.size <= HomePlanner.ITEMS_PER_SHELF)
                if (!HomePlanner.isCardShelf(s.type)) {
                    val shelfArtists = HashMap<Long, Int>()
                    for (i in s.items) {
                        assertTrue(seen.add(i.rid))
                        shelfArtists.merge(i.aid, 1, Int::plus)
                        artists.merge(i.aid, 1, Int::plus)
                    }
                    assertTrue(shelfArtists.values.all { it <= 2 })
                }
            }
            assertTrue(artists.values.all { it <= 4 })
            for (i in 1 until plan.size) assertFalse(HomePlanner.isAllNovel(plan[i].type) && HomePlanner.isAllNovel(plan[i - 1].type), "two all-new shelves side by side")
            if (plan.any { !HomePlanner.isAllNovel(it.type) }) {
                assertTrue(plan.take(3).any { !HomePlanner.isAllNovel(it.type) }, "a familiar shelf among the first three")
            }
        }
    }

    @Test
    fun `the hero is First Listen on Mondays, then a release, then a daily mix that rotates`() {
        val monday = 1_760_400_000_000L // 2025-10-14 00:00 UTC is a Tuesday; step to a Monday below
        var ms = monday
        while (HomePlanner.weekday(ms - 4L * 3_600_000L, 0) != 0) ms += 86_400_000L
        assertEquals(HomePlanner.HERO_FIRST_LISTEN, HomePlanner.hero(true, true, 3, ms, 0, 1L).kind)
        assertEquals(HomePlanner.HERO_RELEASE, HomePlanner.hero(false, true, 3, ms, 0, 1L).kind)
        val daily = HomePlanner.hero(false, false, 3, ms, 0, 1L)
        assertEquals(HomePlanner.HERO_DAILY, daily.kind)
        assertTrue(daily.index in 0..2)
        assertEquals(HomePlanner.HERO_NONE, HomePlanner.hero(false, false, 0, ms, 0, 1L).kind)
        // Another daypart can show another mix; the same daypart shows the same one.
        val again = HomePlanner.hero(false, false, 3, ms, 0, 1L)
        assertEquals(daily.index, again.index)
    }

    @Test
    fun `the dayparts and the plan key change where they should`() {
        val base = 1_760_400_000_000L
        val a = HomePlanner.planKey(base, 0)
        assertEquals(a, HomePlanner.planKey(base + 60_000L, 0))
        var changed = 0
        var last = a
        for (h in 1..24) {
            val k = HomePlanner.planKey(base + h * 3_600_000L, 0)
            if (k != last) changed++
            last = k
        }
        assertTrue(changed in 3..5, "a day has about four dayparts, saw $changed changes")
    }

    @Test
    fun `jump back in takes the last distinct contexts`() {
        val idx = HomePlanner.distinctContexts(listOf("a", "b", "a", "c", "b", "d", "e", "f", "g"), 6)
        assertEquals(listOf(0, 1, 3, 5, 6, 7), idx)
    }
}
