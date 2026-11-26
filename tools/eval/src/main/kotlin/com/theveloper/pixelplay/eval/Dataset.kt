package com.theveloper.pixelplay.eval

import com.theveloper.pixelplay.brain.BrainHash
import com.theveloper.pixelplay.brain.BrainText
import com.theveloper.pixelplay.brain.EndReason
import com.theveloper.pixelplay.brain.GenreAtlas
import com.theveloper.pixelplay.brain.Origin
import com.theveloper.pixelplay.brain.PlayEvent
import com.theveloper.pixelplay.brain.StartReason
import com.theveloper.pixelplay.brain.Surface
import com.theveloper.pixelplay.brain.Tags
import java.io.File
import java.util.zip.GZIPInputStream

/** One listener, ready to replay: events in ascending id, the proxy label of each, and the tag vector of each track. */
class Listener(
    /** `u01`, `u02`, ...: never the ListenBrainz name outside the cache. */
    val id: String,
    val events: List<PlayEvent>,
    /** Per event: [FINISHED], [SKIPPED] or [UNKNOWN] (section "skip proxy" of tools/eval/README.md). */
    val label: IntArray,
    val tags: Map<Long, Tags.Vector>,
) {
    companion object {
        const val UNKNOWN = 0
        const val FINISHED = 1
        const val SKIPPED = 2
    }
}

/** A recording's metadata, trimmed from the ListenBrainz response. */
class RecMeta(val lengthMs: Int, val recTags: List<Tags.Raw>, val artistTags: List<Tags.Raw>)

object Dataset {
    const val MAX_LISTENS = 5000
    const val MIN_LISTENS = 2000
    const val SESSION_GAP_MS = 30L * 60 * 1000

    /** A next listen that starts this much before the track could have ended makes the track a skip (the proxy). */
    const val SKIP_MARGIN_MS = 30_000L

    fun loadAtlas(repoRoot: File): GenreAtlas {
        val gz = File(repoRoot, "app/src/main/assets/genre-atlas-v1.json.gz")
        return GenreAtlas.parse(String(GZIPInputStream(gz.inputStream()).readBytes(), Charsets.UTF_8))
    }

    // ── Choosing users ──────────────────────────────────────────────────────

    /**
     * Candidate users: the top listeners (ranks 2-12) of every third of the 300 most-listened artists sitewide, deduplicated, ordered by
     * a hash of the name (so the sample does not follow one artist's fan base), kept when their public listen count is at least
     * [MIN_LISTENS] + 500.
     */
    fun candidates(lb: Lb, cache: File, want: Int): List<String> {
        val file = File(cache, "candidates.txt")
        if (file.isFile) return file.readLines().filter { it.isNotBlank() }
        val artists = lb.sitewideArtists(300)
        val names = LinkedHashSet<String>()
        for ((i, a) in artists.withIndex()) {
            if (i % 3 != 0) continue
            lb.artistListeners(a).drop(1).take(11).forEach { names.add(it) }
        }
        val ordered = names.sortedBy { BrainHash.splitmix64(BrainHash.fnv1a64(it)) }
        val out = ArrayList<String>()
        for (u in ordered) {
            if (out.size >= want) break
            val n = lb.listenCount(u)
            if (n >= MIN_LISTENS + 500) out.add(u)
        }
        file.parentFile.mkdirs()
        file.writeText(out.joinToString("\n"))
        return out
    }

    // ── Listens to events ───────────────────────────────────────────────────

    private class Raw(
        val ts: Long, val recMbid: String?, val key: String, val artistKeys: List<String>, val durationMs: Int,
    )

    private fun rawOf(l: Map<String, Any?>): Raw? {
        val ts = (l["listened_at"] as? Long) ?: return null
        val tm = l["track_metadata"].obj() ?: return null
        val ai = tm["additional_info"].obj().orEmpty()
        val mm = tm["mbid_mapping"].obj()
        val rec = mm?.get("recording_mbid").str() ?: ai["recording_mbid"].str()?.takeIf { it.length == 36 }
        val msid = l["recording_msid"].str() ?: ai["recording_msid"].str()
        val artist = tm["artist_name"].str().orEmpty()
        val track = tm["track_name"].str().orEmpty()
        val key = when {
            rec != null -> "mb:$rec"
            msid != null -> "msid:$msid"
            else -> "name:" + BrainText.fold(artist) + "|" + BrainText.fold(track)
        }
        val aMbids = (mm?.get("artist_mbids").arr() ?: ai["artist_mbids"].arr()).orEmpty().mapNotNull { it.str() }
        val artistKeys = if (aMbids.isNotEmpty()) aMbids.map { "mba:$it" } else listOf("an:" + BrainText.fold(artist))
        val dur = ai["duration_ms"].num()?.toInt() ?: ai["duration"].num()?.let { (it * 1000.0).toInt() } ?: 0
        return Raw(ts * 1000L, rec, key, artistKeys, if (dur in 10_000..3_600_000) dur else 0)
    }

    private fun id64(key: String): Long {
        val h = BrainHash.fnv1a64(key) and Long.MAX_VALUE
        return if (h == 0L) 1L else h
    }

    /** Why a user is dropped, or null when kept. */
    class Built(val listener: Listener?, val dropped: String?, val recMbids: Set<String>)

    fun recMbidsOf(listens: List<Map<String, Any?>>): Set<String> = listens.mapNotNull { rawOf(it)?.recMbid }.toSet()

    /**
     * Turns one user's listens (any order) into play events, following the proxy in tools/eval/README.md:
     * - a listen whose next listen starts at least 30 s before the track's length is over is a SKIP heard for the gap
     *   (`endReason = next`, `playedMs = gap`); otherwise it was heard to the end (`trackDone`, `playedMs = length`);
     * - the last listen before a 30-minute gap, and a listen whose length is unknown and whose gap is 30 s or more, are UNKNOWN:
     *   heard, outcome unknown (`playedMs = -1`, rule 3 of 4.3, `r = 0.75`), and not labelled;
     * - a listen with unknown length and a gap under 30 s is a skip (`playedMs = gap`).
     */
    fun build(uid: String, listens: List<Map<String, Any?>>, meta: Map<String, RecMeta>, atlas: GenreAtlas): Built {
        val raws = listens.mapNotNull { rawOf(it) }.sortedWith(compareBy({ it.ts }, { it.key }))
        val dedup = ArrayList<Raw>(raws.size)
        for (r in raws) {
            val last = dedup.lastOrNull()
            if (last != null && last.ts == r.ts) continue // the same moment twice: a duplicate submission
            dedup.add(r)
        }
        val recs = dedup.mapNotNull { it.recMbid }.toSet()
        if (dedup.size < MIN_LISTENS) return Built(null, "under $MIN_LISTENS listens", recs)
        var tiny = 0
        for (i in 1 until dedup.size) if (dedup[i].ts - dedup[i - 1].ts <= 2000L) tiny++
        if (tiny > dedup.size / 20) return Built(null, "bulk-imported timestamps", recs)
        var known = 0
        for (r in dedup) if (r.durationMs > 0 || (r.recMbid != null && (meta[r.recMbid]?.lengthMs ?: 0) > 0)) known++
        if (known * 10 < dedup.size * 6) return Built(null, "track lengths unknown", recs)

        val events = ArrayList<PlayEvent>(dedup.size)
        val labels = IntArray(dedup.size)
        val tags = HashMap<Long, Tags.Vector>()
        val ids = HashMap<Long, String>()
        var sessionId = 0L
        @Suppress("VARIABLE_WITH_REDUNDANT_INITIALIZER") var prevStart = Long.MIN_VALUE
        var prevSkip = false
        var prevEndedSession = true
        var passive = 0
        for (i in dedup.indices) {
            val r = dedup[i]
            val m = r.recMbid?.let { meta[it] }
            val dur = if (r.durationMs > 0) r.durationMs else (m?.lengthMs ?: 0)
            val newSession = prevEndedSession
            val start = when {
                i == 0 || newSession -> StartReason.TAP
                prevSkip -> StartReason.NEXT
                else -> StartReason.AUTO_ADVANCE
            }
            if (i == 0 || newSession) sessionId = r.ts
            passive = if (start == StartReason.AUTO_ADVANCE) passive + 1 else 0
            val next = if (i + 1 < dedup.size) dedup[i + 1] else null
            val gap = if (next == null) Long.MAX_VALUE else next.ts - r.ts
            val endsSession = next == null || gap > SESSION_GAP_MS + dur
            val played: Int
            val end: Int
            val label: Int
            if (endsSession) {
                played = -1; end = EndReason.TRACK_DONE; label = Listener.UNKNOWN
            } else if (dur > 0) {
                if (gap < dur - SKIP_MARGIN_MS) {
                    played = gap.toInt().coerceAtLeast(0); end = EndReason.NEXT; label = Listener.SKIPPED
                } else {
                    played = dur; end = EndReason.TRACK_DONE; label = Listener.FINISHED
                }
            } else if (gap < SKIP_MARGIN_MS) {
                played = gap.toInt().coerceAtLeast(0); end = EndReason.NEXT; label = Listener.SKIPPED
            } else {
                played = -1; end = EndReason.TRACK_DONE; label = Listener.UNKNOWN
            }
            val rid = id64(r.key)
            val aids = r.artistKeys.map { id64(it) }.distinct()
            val eventId = PlayEvent.mintId(r.ts, Origin.LIVE, r.key) { ids[it] }
            ids[eventId] = PlayEvent.identityOf(Origin.LIVE, r.key)
            events.add(
                PlayEvent(
                    eventId = eventId, origin = Origin.LIVE, rid = rid, primaryAid = aids[0],
                    otherAids = aids.drop(1).take(PlayEvent.MAX_OTHER_AIDS).toLongArray(), keyAtWrite = r.key,
                    startedAtMs = r.ts, playedMs = played, durationMs = dur, startReason = start, endReason = end,
                    surface = Surface.UNKNOWN, sessionId = sessionId, posInSession = 0, passiveRun = passive.coerceAtMost(32767),
                ),
            )
            labels[i] = label
            if (m != null && rid !in tags) {
                val v = tagVector(atlas, m)
                if (!v.isEmpty) tags[rid] = v
            }
            prevStart = r.ts
            prevSkip = end == EndReason.NEXT
            prevEndedSession = endsSession
        }
        // Ids are minted from the start time, so ascending start is ascending id except on a collision: sort to be sure.
        val order = events.indices.sortedBy { events[it].eventId }
        return Built(Listener(uid, order.map { events[it] }, IntArray(order.size) { labels[order[it]] }, tags), null, recs)
    }

    /** The app's DnaAssembler path for ListenBrainz evidence: recording tags as source 1, artist tags as source 2, conf through a byte. */
    fun tagVector(atlas: GenreAtlas, m: RecMeta): Tags.Vector {
        val inputs = (Tags.inputs(atlas, Tags.SRC_RECORDING, null, m.recTags) + Tags.inputs(atlas, Tags.SRC_ARTIST, null, m.artistTags))
            .map { Tags.Input(it.source, it.atlasId, (it.conf * 255.0 + 0.5).toInt().coerceIn(0, 255) / 255.0) }
        return Tags.merge(atlas, inputs)
    }

    // ── Recording metadata store (trimmed; raw batches stay in cache/raw) ──

    fun loadMeta(cache: File): HashMap<String, RecMeta> {
        val f = File(cache, "recordings.tsv")
        val out = HashMap<String, RecMeta>()
        if (!f.isFile) return out
        f.forEachLine { line ->
            val p = line.split('\t')
            if (p.size == 4) out[p[0]] = RecMeta(p[1].toInt(), decodeTags(p[2]), decodeTags(p[3]))
        }
        return out
    }

    fun fetchMeta(lb: Lb, cache: File, store: HashMap<String, RecMeta>, mbids: Collection<String>) {
        val missing = mbids.filter { it !in store }
        if (missing.isEmpty()) return
        val got = lb.recordings(missing)
        val f = File(cache, "recordings.tsv")
        f.parentFile.mkdirs()
        f.appendText(buildString {
            for (id in missing) {
                val o = got[id]
                val len = (o?.get("recording").obj()?.get("length").num() ?: 0.0).toInt()
                val tag = o?.get("tag").obj()
                val rec = tag?.get("recording").arr().orEmpty().mapNotNull { rawTag(it) }
                val art = tag?.get("artist").arr().orEmpty().mapNotNull { rawTag(it) }
                val m = RecMeta(len, rec, art)
                store[id] = m
                append(id).append('\t').append(len).append('\t').append(encodeTags(rec)).append('\t').append(encodeTags(art)).append('\n')
            }
        })
    }

    private fun rawTag(v: Any?): Tags.Raw? {
        val o = v.obj() ?: return null
        val t = o["tag"].str() ?: return null
        return Tags.Raw(t, o["genre_mbid"].str().orEmpty(), (o["count"].num() ?: 0.0).toInt())
    }

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\u001E', ' ').replace('\u001F', ' ')
    private fun encodeTags(l: List<Tags.Raw>) = l.joinToString("\u001E") { clean(it.label) + "\u001F" + it.mbid + "\u001F" + it.votes }
    private fun decodeTags(s: String): List<Tags.Raw> = if (s.isEmpty()) emptyList() else s.split('\u001E').mapNotNull {
        val p = it.split('\u001F')
        if (p.size == 3) Tags.Raw(p[0], p[1], p[2].toIntOrNull() ?: 0) else null
    }
}
