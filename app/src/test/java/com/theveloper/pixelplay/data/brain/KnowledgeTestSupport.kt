package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.data.brain.source.KnowledgeHost
import com.theveloper.pixelplay.data.brain.source.KnowledgeHttp
import com.theveloper.pixelplay.data.brain.store.ArtistDnaDao
import com.theveloper.pixelplay.data.brain.store.ArtistDnaEntity
import com.theveloper.pixelplay.data.brain.store.DnaCoverage
import com.theveloper.pixelplay.data.brain.store.DnaFreshness
import com.theveloper.pixelplay.data.brain.store.SourceMissDao
import com.theveloper.pixelplay.data.brain.store.SourceMissEntity
import com.theveloper.pixelplay.data.brain.store.TrackDnaDao
import com.theveloper.pixelplay.data.brain.store.TrackDnaEntity
import java.io.File
import java.util.zip.GZIPInputStream

/** Fixtures cut from real responses (`tools/brain/cut_knowledge_fixtures.py`) and the real atlas asset. */
object KnowledgeFixtures {
    private fun file(relative: String): File = listOf(File(relative), File("app/$relative")).first { it.isFile }

    fun read(name: String): String = file("src/test/resources/knowledge/$name").readText(Charsets.UTF_8)

    val atlas: GenreAtlas by lazy {
        val gz = file("src/main/assets/genre-atlas-v1.json.gz").readBytes()
        GenreAtlas.parse(String(GZIPInputStream(gz.inputStream()).readBytes(), Charsets.UTF_8))
    }
}

/** An [AtlasProvider] over the real asset. */
class FixtureAtlas(private val value: GenreAtlas? = KnowledgeFixtures.atlas) : AtlasProvider {
    override suspend fun atlas(): GenreAtlas? = value
    override fun loadedOrNull(): GenreAtlas? = value
}

/**
 * A [KnowledgeHttp] answering from fixtures: the first route whose test matches the URL answers, no route answers 404, a host in
 * [down] is "not asked" (null), as when a source is switched off or its budget is spent.
 */
class FakeKnowledgeHttp : KnowledgeHttp {
    class Route(val host: KnowledgeHost, val matches: (String) -> Boolean, val body: String, val code: Int = 200)

    val routes = ArrayList<Route>()
    val requests = ArrayList<Pair<KnowledgeHost, String>>()
    val down = HashSet<KnowledgeHost>()
    val backedOff = ArrayList<Pair<KnowledgeHost, Long>>()

    fun route(host: KnowledgeHost, contains: String, fixture: String, code: Int = 200) {
        routes.add(Route(host, { it.contains(contains) }, KnowledgeFixtures.read(fixture), code))
    }

    fun routeText(host: KnowledgeHost, contains: String, text: String, code: Int = 200) {
        routes.add(Route(host, { it.contains(contains) }, text, code))
    }

    fun count(host: KnowledgeHost): Int = requests.count { it.first == host }

    private fun answer(host: KnowledgeHost, url: String, body: String? = null): KnowledgeHttp.Reply? {
        if (host in down) return null
        requests.add(host to url)
        val key = if (body != null) url + " " + body else url
        val r = routes.firstOrNull { it.host == host && it.matches(key) } ?: return KnowledgeHttp.Reply(404, null)
        return KnowledgeHttp.Reply(r.code, r.body)
    }

    override suspend fun get(host: KnowledgeHost, url: String, accept: String): KnowledgeHttp.Reply? = answer(host, url)

    override suspend fun post(host: KnowledgeHost, url: String, body: String, contentType: String, accept: String): KnowledgeHttp.Reply? =
        answer(host, url, body)

    override fun backOff(host: KnowledgeHost, forMs: Long) {
        backedOff.add(host to forMs)
    }
}

class FakeTrackDnaDao : TrackDnaDao {
    val rows = HashMap<Long, TrackDnaEntity>()
    override suspend fun upsert(row: TrackDnaEntity) { rows[row.rid] = row }
    override suspend fun upsertAll(rows: List<TrackDnaEntity>) { rows.forEach { this.rows[it.rid] = it } }
    override suspend fun get(rid: Long) = rows[rid]
    override suspend fun getAll(rids: List<Long>) = rids.mapNotNull { rows[it] }
    override suspend fun bySpotifyIds(spotifyIds: List<String>) = rows.values.filter { it.spotifyId in spotifyIds }
    override suspend fun knownSpotifyIds(spotifyIds: List<String>) = rows.values.mapNotNull { it.spotifyId }.filter { it in spotifyIds }
    override suspend fun freshnessOf(rids: List<Long>) = rids.mapNotNull { rows[it] }.map { DnaFreshness(it.rid, it.updatedAtMs, it.tagsAtMs, it.popAtMs, it.fetchedMask) }
    override suspend fun expired(tagsBefore: Long, popBefore: Long, retryBefore: Long, limit: Int) =
        rows.values.filter {
            (it.tagsAtMs > 0 && it.tagsAtMs < tagsBefore) || (it.popAtMs > 0 && it.popAtMs < popBefore) ||
                ((it.fetchedMask and 19) != 19 && it.updatedAtMs < retryBefore)
        }.sortedBy { it.updatedAtMs }.take(limit)
    override suspend fun page(after: Long, limit: Int) = rows.values.filter { it.rid > after }.sortedBy { it.rid }.take(limit)
    override suspend fun count() = rows.size
    override suspend fun newestUpdate() = rows.values.maxOfOrNull { it.updatedAtMs } ?: 0L
    override suspend fun coverage() = DnaCoverage(
        rows.size, rows.values.count { it.tags.isNotEmpty() }, rows.values.count { it.firstYear > 0 }, rows.values.count { it.popPermille >= 0 },
        rows.values.count { it.energy >= 0 }, rows.values.count { it.tempoBpmX10 > 0 },
    )
    override suspend fun countFetchedBy(bit: Int) = rows.values.count { it.fetchedMask and bit != 0 }
    override suspend fun clear() { rows.clear() }
}

class FakeArtistDnaDao : ArtistDnaDao {
    val rows = HashMap<Long, ArtistDnaEntity>()
    override suspend fun upsert(row: ArtistDnaEntity) { rows[row.aid] = row }
    override suspend fun get(aid: Long) = rows[aid]
    override suspend fun getAll(aids: List<Long>) = aids.mapNotNull { rows[it] }
    override suspend fun bySpotifyId(spotifyId: String) = rows.values.firstOrNull { it.spotifyId == spotifyId }
    override suspend fun count() = rows.size
    override suspend fun countWithTags() = rows.values.count { it.tags.isNotEmpty() }
    override suspend fun withTags() = rows.values.filter { it.tags.isNotEmpty() }
    override suspend fun clear() { rows.clear() }
}

class FakeSourceMissDao : SourceMissDao {
    val rows = HashMap<Pair<String, Int>, SourceMissEntity>()
    override suspend fun get(entity: String, source: Int) = rows[entity to source]
    override suspend fun put(row: SourceMissEntity) { rows[row.entity to row.source] = row }
    override suspend fun blocked(source: Int, nowMs: Long, entities: List<String>) =
        rows.values.filter { it.source == source && it.retryAfter > nowMs && it.entity in entities }.map { it.entity }
    override suspend fun forget(entity: String, source: Int) { rows.remove(entity to source) }
    override suspend fun countActive(nowMs: Long) = rows.values.count { it.retryAfter > nowMs }
    override suspend fun pruneOld(nowMs: Long) { rows.values.removeAll { it.retryAfter < nowMs - 15_552_000_000L } }
}
