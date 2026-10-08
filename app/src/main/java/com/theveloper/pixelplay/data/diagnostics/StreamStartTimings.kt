package com.theveloper.pixelplay.data.diagnostics

import android.os.SystemClock
import timber.log.Timber
import java.util.ArrayDeque

/**
 * Streaming speed, step 1 (R12): measure before changing anything.
 *
 * One record per streamed-song start, from the tap (or skip / auto-advance) to the first
 * audible frame, split into the steps that can be slow:
 * - request→transition: the UI building the item until the player switches to it;
 * - dataSpec: ExoPlayer's resolver (local-copy lookup, proxy start);
 * - url: the proxy's URL lookup, i.e. Spotify→YouTube matching plus the InnerTube client,
 *   and whether the URL carried `n` (the base.js question iOS also asks);
 * - upstream TTFB: googlevideo's answer to the proxy's first ranged GET;
 * - player first byte: the first byte ExoPlayer actually read from the proxy. Its distance
 *   from the proxy's first upstream byte is the "flush gap" (the 1 MiB wait this batch fixes);
 * - ready / playing: STATE_READY and the first onIsPlayingChanged(true).
 *
 * The "Test playback" card and logcat (`adb logcat -s StreamStart`) show the last
 * [MAX_RECORDS] lines. Aggregates go to [PerformanceMetrics] under `stream_*` names and never
 * carry a track id (privacy rule in docs/performance-report.md).
 *
 * A process-wide `object`, like PerformanceMetrics: the engine (main and loader threads), the
 * proxy (IO threads) and the UI tap all report here without plumbing. Every hook is O(1)
 * under one short lock and does no IO; ids are Spotify track ids.
 */
object StreamStartTimings {

    enum class Kind { TAP, SKIP, AUTO, REPEAT }

    const val MAX_RECORDS = 8

    /** A start still open after this long was abandoned (paused before it played, an error). */
    internal const val ABANDON_AFTER_MS = 60_000L
    /** A rebuffer this soon after a start counts against that start (bufferForPlayback too low?). */
    internal const val EARLY_REBUFFER_WINDOW_MS = 10_000L
    /** A UI request older than this belongs to an earlier tap, not to this start. */
    internal const val REQUEST_MATCH_WINDOW_MS = 10_000L
    /** Play pressed this long after the item was set (a restored queue) is reported apart. */
    internal const val LATE_PLAY_THRESHOLD_MS = 1_000L
    private const val MAX_PRELOAD_IDS = 16
    private const val TAG = "StreamStart"

    /** Elapsed-realtime clock in ms. Swapped in JVM tests, where SystemClock returns 0. */
    @Volatile
    var clock: () -> Long = { SystemClock.elapsedRealtime() }

    /** One finished start. Durations in ms; the step marks are offsets from the start. */
    data class Record(
        val kind: Kind,
        val totalMs: Long,
        val requestLeadMs: Long? = null,
        val playPressedAfterMs: Long? = null,
        val dataSpecMs: Long? = null,
        val fromLocalCopy: Boolean = false,
        val urlMs: Long? = null,
        val urlCached: Boolean = false,
        val matchMs: Long? = null,
        val resolveMs: Long? = null,
        val strategy: String? = null,
        val nParam: Boolean? = null,
        val nTransformMs: Long? = null,
        val upstreamTtfbMs: Long? = null,
        val playerFirstByteMs: Long? = null,
        val flushGapMs: Long? = null,
        val readyMs: Long? = null,
        val playingAfterReadyMs: Long? = null,
        val playerRequests: Int = 0,
        val downloadRequests: Int = 0,
        val backgroundRequests: Int = 0,
        val retries: Int = 0,
        val preloadedBytes: Long = 0L,
        val rebufferedEarly: Boolean = false,
        val error: String? = null
    ) {
        /** ExoPlayer had already read this song's first bytes before it started (preloading). */
        val preloadHit: Boolean get() = preloadedBytes > 0L

        /**
         * e.g. `TAP 1840 ms: request→transition 40, dataSpec 12, url 620 (match 0, resolve 610
         * VISIONOS, n: no), upstream TTFB 140, player first byte 1210, flush gap 3, ready 1270,
         * playing +30; proxy 1 player + 1 download, 0 retries, preload: no`
         */
        fun line(): String = buildString {
            append(kind.name).append(' ').append(totalMs).append(" ms")
            val parts = ArrayList<String>(12)
            requestLeadMs?.let { parts += "request→transition $it" }
            playPressedAfterMs?.let { parts += "play pressed after $it" }
            dataSpecMs?.let { parts += if (fromLocalCopy) "dataSpec $it (local copy)" else "dataSpec $it" }
            // The resolve marks can arrive without the proxy's URL time (a warm-up resolving
            // the same song at that moment), so either one shows the part.
            if (urlMs != null || resolveMs != null) parts += urlPart(urlMs)
            upstreamTtfbMs?.let { parts += "upstream TTFB $it" }
            playerFirstByteMs?.let { parts += "player first byte $it" }
            flushGapMs?.let { parts += "flush gap $it" }
            readyMs?.let { parts += "ready $it" }
            playingAfterReadyMs?.let { parts += "playing +$it" }
            if (parts.isNotEmpty()) append(": ").append(parts.joinToString(", "))
            append("; proxy ").append(playerRequests).append(" player")
            if (downloadRequests > 0) append(" + ").append(downloadRequests).append(" download")
            if (backgroundRequests > 0) append(" + ").append(backgroundRequests).append(" background")
            append(", ").append(retries).append(if (retries == 1) " retry" else " retries")
            append(", preload: ")
            append(if (preloadHit) "yes (${preloadedBytes / 1024} KiB)" else "no")
            if (rebufferedEarly) append(", rebuffered within 10 s")
            error?.let { append(", error: ").append(it) }
        }

        private fun urlPart(ms: Long?): String {
            if (urlCached) return "url cached"
            val inner = ArrayList<String>(3)
            matchMs?.let { inner += "match $it" }
            resolveMs?.let { inner += if (strategy != null) "resolve $it $strategy" else "resolve $it" }
            nParam?.let { hasN ->
                inner += when {
                    !hasN -> "n: no"
                    nTransformMs != null -> "n: yes $nTransformMs ms"
                    else -> "n: yes"
                }
            }
            val label = if (ms != null) "url $ms" else "url"
            return if (inner.isEmpty()) label else "$label (${inner.joinToString(", ")})"
        }
    }

    private class Open(
        val id: String,
        val kind: Kind,
        val beganAt: Long,
        val requestLeadMs: Long?,
        val preloadedBytes: Long
    ) {
        var playRequestedAt = -1L
        var dataSpecMs: Long? = null
        var fromLocalCopy = false
        var urlMs: Long? = null
        var urlCached = false
        var matchMs: Long? = null
        var resolveMs: Long? = null
        var strategy: String? = null
        var nParam: Boolean? = null
        var nTransformMs: Long? = null
        var upstreamTtfbMs: Long? = null
        var upstreamFirstByteAt = -1L
        var playerFirstByteAt = -1L
        var readyAt = -1L
        var playerRequests = 0
        var downloadRequests = 0
        var backgroundRequests = 0
        var retries = 0
        var error: String? = null
    }

    private val lock = Any()
    private var open: Open? = null
    private var currentId: String? = null
    private var requestedId: String? = null
    private var requestedAt = 0L
    private var lastFinishedAt = -1L
    private val preloadBytes = HashMap<String, Long>()
    private val records = ArrayDeque<Record>(MAX_RECORDS)

    // ─── UI ────────────────────────────────────────────────────────────────

    /** The UI is about to hand this streamed song to the player (a tap). */
    fun requested(id: String) {
        synchronized(lock) {
            requestedId = id
            requestedAt = clock()
        }
    }

    // ─── Player (DualPlayerEngine) ─────────────────────────────────────────

    /**
     * The master player switched to a streamed song. [alreadyPlaying]: a seamless hand-over
     * (gapless or a preloaded item already audible), which finishes the start right away.
     */
    fun begin(id: String, kind: Kind, alreadyPlaying: Boolean = false) {
        val finished = synchronized(lock) {
            val now = clock()
            val lead = if (kind == Kind.TAP && requestedId == id && now - requestedAt in 0..REQUEST_MATCH_WINDOW_MS) {
                now - requestedAt
            } else {
                null
            }
            requestedId = null
            lastFinishedAt = -1L
            currentId = id
            val started = Open(id, kind, now, lead, preloadBytes.remove(id) ?: 0L)
            if (alreadyPlaying) {
                started.readyAt = now
                open = null
                finishLocked(started, now)
            } else {
                open = started
                null
            }
        }
        finished?.let(::publish)
    }

    /** The master player switched to a song that is not streamed: nothing to time. */
    fun cancel() {
        synchronized(lock) {
            open = null
            currentId = null
            lastFinishedAt = -1L
        }
    }

    /** playWhenReady became true (a restored queue may sit prepared until the user presses play). */
    fun playRequested() {
        synchronized(lock) {
            val o = currentLocked(clock()) ?: return
            if (o.playRequestedAt < 0) o.playRequestedAt = clock()
        }
    }

    /** ExoPlayer's resolver mapped `spotify://id` to a proxy URL or a local copy. */
    fun dataSpecResolved(id: String, durationMs: Long, localCopy: Boolean) {
        synchronized(lock) {
            val o = openFor(id) ?: return
            if (o.dataSpecMs != null) return
            o.dataSpecMs = durationMs
            o.fromLocalCopy = localCopy
        }
    }

    /**
     * ExoPlayer read [bytes] of [id] from the proxy. The first call after [begin] is the
     * player's first byte; bytes for a song nobody is starting are ExoPlayer preloading it.
     */
    fun playerBytes(id: String, bytes: Int) {
        synchronized(lock) {
            val o = currentLocked(clock())
            if (o != null && o.id == id) {
                if (o.playerFirstByteAt < 0) o.playerFirstByteAt = clock()
                return
            }
            // The song already playing keeps loading after its start finished; not a preload.
            if (id == currentId) return
            if (preloadBytes.size >= MAX_PRELOAD_IDS && id !in preloadBytes) preloadBytes.clear()
            preloadBytes[id] = (preloadBytes[id] ?: 0L) + bytes
        }
    }

    fun ready() {
        synchronized(lock) {
            val o = currentLocked(clock()) ?: return
            if (o.readyAt < 0) o.readyAt = clock()
        }
    }

    /** First onIsPlayingChanged(true) after [begin]: the start is over. */
    fun playing() {
        val finished = synchronized(lock) {
            val now = clock()
            val o = currentLocked(now) ?: return
            open = null
            finishLocked(o, now)
        }
        publish(finished)
    }

    /** STATE_BUFFERING on the master player; [postSeek] rebuffers are the seek's, not the start's. */
    fun buffering(postSeek: Boolean) {
        synchronized(lock) {
            if (postSeek || open != null || lastFinishedAt < 0) return
            if (clock() - lastFinishedAt > EARLY_REBUFFER_WINDOW_MS) return
            lastFinishedAt = -1L
            val last = records.pollLast() ?: return
            records.addLast(last.copy(rebufferedEarly = true))
        }
    }

    // ─── Proxy (CloudStreamProxy / SpotifyStreamProxy) ─────────────────────

    /**
     * A request reached the local proxy. Returns true when it is the player's first request
     * for the song being started; only then are the request's step marks worth recording.
     */
    fun proxyRequest(id: String, isDownload: Boolean): Boolean {
        synchronized(lock) {
            val o = currentLocked(clock()) ?: return false
            return when {
                isDownload -> { o.downloadRequests++; false }
                o.id == id -> { o.playerRequests++; o.playerRequests == 1 }
                else -> { o.backgroundRequests++; false }
            }
        }
    }

    fun proxyUrlReady(id: String, durationMs: Long, cached: Boolean) {
        synchronized(lock) {
            val o = openFor(id) ?: return
            if (o.urlMs != null) return
            o.urlMs = durationMs
            o.urlCached = cached
        }
    }

    /** SpotifyStreamProxy resolved [id]'s URL ([matchMs] only when it had to match on the spot). */
    fun urlResolved(
        id: String,
        matchMs: Long?,
        resolveMs: Long,
        strategy: String?,
        nParam: Boolean?,
        nTransformMs: Long?
    ) {
        synchronized(lock) {
            val o = openFor(id) ?: return
            if (o.resolveMs != null) return
            o.matchMs = matchMs
            o.resolveMs = resolveMs
            o.strategy = strategy
            o.nParam = nParam
            o.nTransformMs = nTransformMs
        }
    }

    fun proxyUpstreamAnswered(id: String, ttfbMs: Long) {
        synchronized(lock) {
            val o = openFor(id) ?: return
            if (o.upstreamTtfbMs == null) o.upstreamTtfbMs = ttfbMs
        }
    }

    /** The proxy wrote its first upstream bytes towards the player. */
    fun proxyFirstBodyByte(id: String) {
        synchronized(lock) {
            val o = openFor(id) ?: return
            if (o.upstreamFirstByteAt < 0) o.upstreamFirstByteAt = clock()
        }
    }

    fun proxyRetry(id: String) {
        synchronized(lock) { openFor(id)?.let { it.retries++ } }
    }

    fun proxyFailed(id: String, reason: String) {
        synchronized(lock) {
            val o = openFor(id) ?: return
            if (o.error == null) o.error = reason.take(120)
        }
    }

    // ─── Readers ───────────────────────────────────────────────────────────

    /** Newest first, at most [MAX_RECORDS]. */
    fun recentLines(): List<String> = synchronized(lock) { records.toList().asReversed().map { it.line() } }

    fun lastLine(): String? = synchronized(lock) { records.peekLast()?.line() }

    internal fun recentRecords(): List<Record> = synchronized(lock) { records.toList().asReversed() }

    internal fun resetForTest() {
        synchronized(lock) {
            open = null
            currentId = null
            requestedId = null
            requestedAt = 0L
            lastFinishedAt = -1L
            preloadBytes.clear()
            records.clear()
        }
    }

    // ─── Internals ─────────────────────────────────────────────────────────

    private fun currentLocked(now: Long): Open? {
        val o = open ?: return null
        if (now - o.beganAt > ABANDON_AFTER_MS) {
            open = null
            return null
        }
        return o
    }

    private fun openFor(id: String): Open? = currentLocked(clock())?.takeIf { it.id == id }

    private fun finishLocked(o: Open, now: Long): Record {
        val origin = o.beganAt
        fun offset(at: Long): Long? = if (at >= 0) at - origin else null
        val record = Record(
            kind = o.kind,
            totalMs = now - origin,
            requestLeadMs = o.requestLeadMs,
            playPressedAfterMs = (o.playRequestedAt - origin).takeIf { o.playRequestedAt >= 0 && it > LATE_PLAY_THRESHOLD_MS },
            dataSpecMs = o.dataSpecMs,
            fromLocalCopy = o.fromLocalCopy,
            urlMs = o.urlMs,
            urlCached = o.urlCached,
            matchMs = o.matchMs,
            resolveMs = o.resolveMs,
            strategy = o.strategy,
            nParam = o.nParam,
            nTransformMs = o.nTransformMs,
            upstreamTtfbMs = o.upstreamTtfbMs,
            playerFirstByteMs = offset(o.playerFirstByteAt),
            flushGapMs = if (o.playerFirstByteAt >= 0 && o.upstreamFirstByteAt >= 0) {
                (o.playerFirstByteAt - o.upstreamFirstByteAt).coerceAtLeast(0L)
            } else {
                null
            },
            readyMs = offset(o.readyAt),
            playingAfterReadyMs = if (o.readyAt >= 0) now - o.readyAt else null,
            playerRequests = o.playerRequests,
            downloadRequests = o.downloadRequests,
            backgroundRequests = o.backgroundRequests,
            retries = o.retries,
            preloadedBytes = o.preloadedBytes,
            error = o.error
        )
        if (records.size >= MAX_RECORDS) records.pollFirst()
        records.addLast(record)
        lastFinishedAt = now
        return record
    }

    /** Outside the lock: aggregates (no ids) and the log line. */
    private fun publish(record: Record) {
        PerformanceMetrics.recordTiming(
            when (record.kind) {
                Kind.TAP -> PerformanceMetrics.Timings.STREAM_START_TAP
                Kind.SKIP -> PerformanceMetrics.Timings.STREAM_START_SKIP
                Kind.AUTO, Kind.REPEAT -> PerformanceMetrics.Timings.STREAM_START_AUTO
            },
            record.totalMs
        )
        record.resolveMs?.let { PerformanceMetrics.recordTiming(PerformanceMetrics.Timings.STREAM_RESOLVE, it) }
        record.matchMs?.let { PerformanceMetrics.recordTiming(PerformanceMetrics.Timings.STREAM_MATCH, it) }
        record.upstreamTtfbMs?.let { PerformanceMetrics.recordTiming(PerformanceMetrics.Timings.STREAM_UPSTREAM_TTFB, it) }
        record.playerFirstByteMs?.let { PerformanceMetrics.recordTiming(PerformanceMetrics.Timings.STREAM_PLAYER_FIRST_BYTE, it) }
        record.flushGapMs?.let { PerformanceMetrics.recordTiming(PerformanceMetrics.Timings.STREAM_PROXY_FLUSH_GAP, it) }
        Timber.tag(TAG).d("%s", record.line())
    }
}
