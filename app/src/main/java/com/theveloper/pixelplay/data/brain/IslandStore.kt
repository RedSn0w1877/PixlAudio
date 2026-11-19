package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.Affinity
import com.theveloper.pixelplay.brain.BrainMath
import com.theveloper.pixelplay.brain.Candidate
import com.theveloper.pixelplay.brain.Embeddings
import com.theveloper.pixelplay.brain.Family
import com.theveloper.pixelplay.brain.FastRp
import com.theveloper.pixelplay.brain.Graph
import com.theveloper.pixelplay.brain.Islands
import com.theveloper.pixelplay.brain.Tags
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.BrainStateDao
import com.theveloper.pixelplay.data.brain.store.BrainStateEntity
import com.theveloper.pixelplay.data.brain.store.EmbeddingDao
import com.theveloper.pixelplay.data.brain.store.GraphEdgeDao
import com.theveloper.pixelplay.data.brain.store.GraphEdgeEntity
import com.theveloper.pixelplay.data.brain.store.IslandDao
import com.theveloper.pixelplay.data.brain.store.IslandEntity
import com.theveloper.pixelplay.data.brain.store.IslandMemberEntity
import com.theveloper.pixelplay.data.brain.store.NodeEmbeddingEntity
import com.theveloper.pixelplay.data.brain.store.SeedDao
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.diagnostics.PerformanceMetrics
import java.nio.ByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber

/** The listener's taste islands as stored (docs/brain-spec.md, 5.4): the islands, who belongs to which, and the tag shares. */
class IslandSet(
    /** Largest first (by `W_c`); each carries its stable id. */
    val islands: List<Islands.Island>,
    val fitAtMs: Long,
    /** Recording id to island id, for the tracks the fit placed. */
    val memberOf: Map<Long, Int>,
) {
    val isEmpty: Boolean get() = islands.isEmpty()

    fun indexOfId(islandId: Int): Int = islands.indexOfFirst { it.id == islandId }

    /** The islands that get a Daily Mix: the biggest [MAX_DAILY] by weight, in ascending id (so "Daily Mix 2" stays one island). */
    fun dailyIslands(): List<Islands.Island> =
        islands.take(MAX_DAILY).sortedBy { it.id }

    /** True when there are several islands: the surfaces that need several are shown only then (5.4). */
    val hasMany: Boolean get() = islands.size >= 2

    companion object {
        const val MAX_DAILY = 6
    }
}

/**
 * Embeddings and islands, as stored in `brain.db` (version 5). Reads are small and lazy; nothing is opened at app
 * start. The fit itself is [IslandBuilder].
 */
@Singleton
class IslandStore @Inject constructor(
    private val embeddings: EmbeddingDao,
    private val islandDao: IslandDao,
    private val states: BrainStateDao,
) {
    private val lock = Mutex()
    @Volatile private var cached: IslandSet? = null
    @Volatile private var loaded = false

    /** The stored islands, or null when none have been fitted. Cached in memory after the first read. */
    suspend fun current(): IslandSet? {
        if (loaded) return cached
        return lock.withLock {
            if (!loaded) {
                cached = read()
                loaded = true
            }
            cached
        }
    }

    private suspend fun read(): IslandSet? = withContext(Dispatchers.IO) {
        try {
            val rows = islandDao.islands()
            if (rows.isEmpty()) return@withContext null
            val members = HashMap<Long, Int>()
            for (m in islandDao.members()) members[m.rid] = m.islandId
            val islands = rows.map(::islandOf).sortedWith { a, b ->
                val c = BrainMath.orderKey(b.weight).compareTo(BrainMath.orderKey(a.weight))
                if (c != 0) c else a.medoidRid.compareTo(b.medoidRid)
            }
            IslandSet(islands, rows.maxOf { it.fitAtMs }, members)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: reading the islands failed")
            null
        }
    }

    suspend fun save(fit: Islands.Fit, nowMs: Long) = withContext(Dispatchers.IO) {
        islandDao.clearIslands()
        islandDao.clearMembers()
        islandDao.upsertIslands(fit.islands.map { entityOf(it, nowMs) })
        for (chunk in fit.memberRids.indices.chunked(500)) {
            islandDao.upsertMembers(chunk.map { IslandMemberEntity(fit.memberRids[it], fit.memberIslandIds[it]) })
        }
        states.put(BrainStateEntity(KEY_LAST_ID, fit.maxId.toString().toByteArray(Charsets.UTF_8)))
        val members = HashMap<Long, Int>()
        for (i in fit.memberRids.indices) members[fit.memberRids[i]] = fit.memberIslandIds[i]
        lock.withLock {
            cached = if (fit.islands.isEmpty()) null else IslandSet(fit.islands, nowMs, members)
            loaded = true
        }
    }

    suspend fun lastId(): Int = states.get(KEY_LAST_ID)?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0

    /** The tag shares of the last fit: how much of the listener's listening each atlas tag is (for EDGE, 6.1). */
    suspend fun tagShares(): Map<Int, Double> {
        val bytes = states.get(KEY_TAG_SHARES) ?: return emptyMap()
        val text = bytes.toString(Charsets.UTF_8)
        val out = HashMap<Int, Double>()
        for (part in text.split(';')) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val id = part.substring(0, i).toIntOrNull() ?: continue
            val share = part.substring(i + 1).toDoubleOrNull() ?: continue
            out[id] = share
        }
        return out
    }

    suspend fun saveTagShares(fit: Islands.Fit) {
        val sb = StringBuilder()
        for (i in fit.tagIds.indices) {
            if (fit.tagShares[i] < 0.001) continue
            if (sb.isNotEmpty()) sb.append(';')
            sb.append(fit.tagIds[i]).append(':').append(fit.tagShares[i])
        }
        states.put(BrainStateEntity(KEY_TAG_SHARES, sb.toString().toByteArray(Charsets.UTF_8)))
    }

    /** The embeddings of [ids] only (never the whole table), as one lookup table; null when none is stored. */
    suspend fun embeddingsFor(ids: Collection<Long>): Embeddings? = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext null
        val rows = ArrayList<NodeEmbeddingEntity>()
        try {
            for (chunk in ids.distinct().chunked(CHUNK)) rows.addAll(embeddings.get(chunk))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: reading embeddings failed")
            return@withContext null
        }
        if (rows.isEmpty()) return@withContext null
        rows.sortBy { it.nodeId }
        val ordered = rows.filter { it.vec.size == Embeddings.DIM }
        val ids2 = LongArray(ordered.size) { ordered[it].nodeId }
        val data = ByteArray(ordered.size * Embeddings.DIM)
        for (i in ordered.indices) System.arraycopy(ordered[i].vec, 0, data, i * Embeddings.DIM, Embeddings.DIM)
        Embeddings.of(FastRp.VERSION, ids2, data)
    }

    suspend fun saveEmbeddings(table: Embeddings) = withContext(Dispatchers.IO) {
        embeddings.clear()
        val ids = table.idsCopy()
        val data = table.dataCopy()
        for (chunk in ids.indices.chunked(400)) {
            embeddings.upsertAll(chunk.map { i ->
                NodeEmbeddingEntity(ids[i], table.version, data.copyOfRange(i * Embeddings.DIM, (i + 1) * Embeddings.DIM))
            })
        }
        states.put(BrainStateEntity(KEY_EMB_COUNT, ids.size.toString().toByteArray(Charsets.UTF_8)))
    }

    suspend fun embeddingCount(): Int = states.get(KEY_EMB_COUNT)?.toString(Charsets.UTF_8)?.toIntOrNull() ?: 0

    companion object {
        const val KEY_LAST_ID = "island_last_id"
        const val KEY_TAG_SHARES = "island_tag_shares"
        const val KEY_EMB_COUNT = "emb_count"
        const val KEY_FIT_AT = "island_fit_at"
        const val KEY_FIT_EDGES = "island_fit_edges"
        private const val CHUNK = 800

        fun entityOf(i: Islands.Island, nowMs: Long): IslandEntity {
            val c = ByteBuffer.allocate(Islands.DIM * 8)
            for (v in i.centroid) c.putLong(v.toRawBits())
            val tags = ByteBuffer.allocate(i.topTags.size * 4).also { b -> for (t in i.topTags) b.putInt(t) }
            val lift = ByteBuffer.allocate(i.liftTags.size * 4).also { b -> for (t in i.liftTags) b.putInt(t) }
            val artists = ByteBuffer.allocate(i.topArtists.size * 8).also { b -> for (a in i.topArtists) b.putLong(a) }
            return IslandEntity(
                islandId = i.id, centroid = c.array(), medoidRid = i.medoidRid, topTags = tags.array(), liftTags = lift.array(),
                topArtists = artists.array(), weightQ = BrainMath.toQ(i.weight), recentWeightQ = BrainMath.toQ(i.recentWeight),
                size = i.size, fitAtMs = nowMs,
            )
        }

        fun islandOf(e: IslandEntity): Islands.Island {
            val c = ByteBuffer.wrap(e.centroid)
            val centroid = DoubleArray(Islands.DIM) { if (c.remaining() >= 8) Double.fromBits(c.long) else 0.0 }
            val tags = ByteBuffer.wrap(e.topTags)
            val lift = ByteBuffer.wrap(e.liftTags)
            val artists = ByteBuffer.wrap(e.topArtists)
            return Islands.Island(
                id = e.islandId, centroid = centroid, medoidRid = e.medoidRid,
                topTags = IntArray(e.topTags.size / 4) { tags.int }, liftTags = IntArray(e.liftTags.size / 4) { lift.int },
                topArtists = LongArray(e.topArtists.size / 8) { artists.long },
                weight = BrainMath.fromQ(e.weightQ), recentWeight = BrainMath.fromQ(e.recentWeightQ), size = e.size,
            )
        }
    }
}

/**
 * Fits the embeddings and the islands (docs/brain-spec.md, 5.3, 5.4): FastRP over the persisted graph, then
 * weighted spherical k-means over the listener's best 1,500 tracks. Background only; it reads the database, the
 * affinity tables and the DNA, and never the network. Due weekly, or after 200 qualified plays (5.4).
 */
@Singleton
class IslandBuilder @Inject constructor(
    private val store: IslandStore,
    private val brain: BrainEngine,
    private val library: LibraryRids,
    private val edgeDao: GraphEdgeDao,
    private val dnaDao: TrackDnaDao,
    private val seedDao: SeedDao,
    private val states: BrainStateDao,
    private val clock: BrainClock,
) {
    private val buildLock = Mutex()

    /** True when the stored islands are older than a week, or 200 plays have been finished since they were fitted. */
    suspend fun isDue(nowMs: Long): Boolean {
        val set = store.current() ?: return true
        if (nowMs - set.fitAtMs > REFIT_AGE_MS) return true
        return runCatching { seedDao.qualifiedPlaysSince(set.fitAtMs) >= REFIT_PLAYS }.getOrDefault(false)
    }

    /**
     * The islands, fitted now when they are due and the brain has enough to go on; the stored ones otherwise. Null when
     * there is nothing to fit yet. Never throws.
     */
    suspend fun ensure(force: Boolean = false): IslandSet? = buildLock.withLock {
        val now = clock.nowMs()
        try {
            if (!force && !isDue(now)) return@withLock store.current()
            val has = brain.withState { BrainEngineChoice.hasState(it.affinity) }
            if (!has) return@withLock store.current()
            fit(now) ?: store.current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: fitting the islands failed; the stored ones keep serving")
            store.current()
        }
    }

    private suspend fun fit(now: Long): IslandSet? {
        val started = System.nanoTime()
        val tracks = library.all(now)
        if (tracks.isEmpty()) return null
        // The graph: the persisted radio edges plus the track-artist edge of every library track.
        val builder = Graph.Builder()
        val edges = edgeDao.all()
        for (e in edges) builder.addEdge(e.src, e.dst, e.wQ16 / GraphEdgeEntity.UNIT)
        for (t in tracks) builder.addTrack(t.rid, t.aid)
        val table = FastRp.embed(builder.build())
        store.saveEmbeddings(table)
        // The listener's tracks by weight: decayed finished plays and the pseudo-counts of 4.4 (a liked song counts before it is played).
        val weights = brain.withState { st ->
            val out = HashMap<Long, DoubleArray>()
            for (t in tracks) {
                val k = st.affinity.counter(Family.TRACK, t.rid)
                val prior = st.affinity.prior(Family.TRACK, t.rid)
                val pos = (if (k == null) 0.0 else Affinity.pos(k, now)) + (if (prior == null) 0.0 else BrainMath.fromQ(prior.posQ))
                if (pos <= 0.0) continue
                val recent = if (k == null) 0.0 else Affinity.posRecent(k, now)
                out[t.rid] = doubleArrayOf(pos, recent)
            }
            out
        }
        val chosen = tracks.filter { weights.containsKey(it.rid) }
            .sortedWith { a, b ->
                val c = BrainMath.orderKey(weights[b.rid]!![0]).compareTo(BrainMath.orderKey(weights[a.rid]!![0]))
                if (c != 0) c else a.rid.compareTo(b.rid)
            }.take(Islands.MAX_TRACKS)
        val tagsByRid = HashMap<Long, Tags.Vector>()
        for (chunk in chosen.map { it.rid }.chunked(800)) {
            for (row in dnaDao.getAll(chunk)) {
                val v = Tags.decode(row.tags)
                if (!v.isEmpty) tagsByRid[row.rid] = v
            }
        }
        val input = chosen.map { t ->
            val w = weights[t.rid]!!
            Islands.Track(t.rid, t.aid, w[0], w[1], table.vector(t.rid), tagsByRid[t.rid])
        }
        val previous = store.current()?.islands.orEmpty()
        val fit = Islands.fit(input, previous, store.lastId())
        store.save(fit, now)
        store.saveTagShares(fit)
        states.put(BrainStateEntity(IslandStore.KEY_FIT_AT, now.toString().toByteArray(Charsets.UTF_8)))
        PerformanceMetrics.recordTiming("brain_island_fit", (System.nanoTime() - started) / 1_000_000)
        Timber.i("brain: %d islands from %d tracks over %d nodes", fit.islands.size, input.size, table.size)
        return store.current()
    }

    companion object {
        const val REFIT_AGE_MS = 7L * 24 * 60 * 60 * 1000
        const val REFIT_PLAYS = 200
    }
}

/** What the DNA and the embeddings know about the candidates of a pool (stage 2b): tags, popularity, an embedding. */
@Singleton
class CandidateDna @Inject constructor(
    private val store: IslandStore,
    private val dnaDao: TrackDnaDao,
    private val artistDnaDao: ArtistDnaDao,
) {
    class Known(val tags: Map<Long, Tags.Vector>, val embeddings: Embeddings?)

    /** [candidates] with an embedding, tags and popularity where they are known. Unknown stays unknown: nothing is guessed. */
    suspend fun enrich(candidates: List<Candidate>): Pair<List<Candidate>, Known> = withContext(Dispatchers.IO) {
        val rids = candidates.map { it.rid }
        val emb = store.embeddingsFor(rids)
        val tags = HashMap<Long, Tags.Vector>()
        val pop = HashMap<Long, Int>()
        try {
            for (chunk in rids.chunked(800)) {
                for (row in dnaDao.getAll(chunk)) {
                    val v = Tags.decode(row.tags)
                    if (!v.isEmpty) tags[row.rid] = v
                    if (row.popPermille >= 0) pop[row.rid] = row.popPermille
                }
            }
            // A track nothing placed takes its artist's genres at the weight of 3.4 (the artist fallback), or none.
            val needArtist = candidates.filter { !tags.containsKey(it.rid) && it.aid != 0L }.map { it.aid }.distinct()
            val artistTags = HashMap<Long, Tags.Vector>()
            for (chunk in needArtist.chunked(800)) {
                for (row in artistDnaDao.getAll(chunk)) {
                    val v = Tags.decode(row.tags)
                    if (!v.isEmpty) artistTags[row.aid] = Tags.fromArtist(v)
                }
            }
            for (c in candidates) if (!tags.containsKey(c.rid)) artistTags[c.aid]?.let { tags[c.rid] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "brain: reading DNA for a pool failed; the pool goes without")
        }
        val out = candidates.map { c ->
            val v = emb?.vector(c.rid)
            c.withDna(v, tags[c.rid], pop[c.rid] ?: -1)
        }
        out to Known(tags, emb)
    }
}
