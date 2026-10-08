package com.theveloper.pixelplay.data.diagnostics

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class StreamStartTimingsTest {

    private var now = 10_000L

    @BeforeEach
    fun setUp() {
        StreamStartTimings.resetForTest()
        PerformanceMetrics.resetForTest()
        StreamStartTimings.clock = { now }
    }

    @AfterEach
    fun tearDown() {
        StreamStartTimings.resetForTest()
        PerformanceMetrics.resetForTest()
    }

    private fun at(ms: Long) { now = ms }

    @Test
    fun `a tap through the proxy builds the expected line`() {
        at(10_000); StreamStartTimings.requested("trackA")
        at(10_040); StreamStartTimings.begin("trackA", StreamStartTimings.Kind.TAP)
        StreamStartTimings.dataSpecResolved("trackA", durationMs = 12, localCopy = false)
        assertTrue(StreamStartTimings.proxyRequest("trackA", isDownload = false))
        StreamStartTimings.urlResolved("trackA", matchMs = null, resolveMs = 610, strategy = "VISIONOS", nParam = false, nTransformMs = null)
        StreamStartTimings.proxyUrlReady("trackA", durationMs = 620, cached = false)
        StreamStartTimings.proxyUpstreamAnswered("trackA", ttfbMs = 140)
        at(10_900); StreamStartTimings.proxyFirstBodyByte("trackA")
        at(10_903); StreamStartTimings.playerBytes("trackA", 65_536)
        at(11_500); StreamStartTimings.ready()
        at(11_530); StreamStartTimings.playing()

        val record = StreamStartTimings.recentRecords().single()
        assertEquals(1_490L, record.totalMs)
        assertEquals(40L, record.requestLeadMs)
        assertEquals(863L, record.playerFirstByteMs)
        assertEquals(3L, record.flushGapMs)
        assertEquals(1_460L, record.readyMs)
        assertEquals(
            "TAP 1490 ms: request→transition 40, dataSpec 12, url 620 (resolve 610 VISIONOS, n: no), " +
                "upstream TTFB 140, player first byte 863, flush gap 3, ready 1460, playing +30; " +
                "proxy 1 player, 0 retries, preload: no",
            StreamStartTimings.lastLine()
        )
    }

    @Test
    fun `only the first player request records marks, others are counted`() {
        StreamStartTimings.begin("trackA", StreamStartTimings.Kind.SKIP)
        assertTrue(StreamStartTimings.proxyRequest("trackA", isDownload = false))
        assertFalse(StreamStartTimings.proxyRequest("trackA", isDownload = false))
        assertFalse(StreamStartTimings.proxyRequest("trackA", isDownload = true))
        assertFalse(StreamStartTimings.proxyRequest("trackB", isDownload = false))
        StreamStartTimings.proxyRetry("trackA")
        StreamStartTimings.playing()

        val record = StreamStartTimings.recentRecords().single()
        assertEquals(2, record.playerRequests)
        assertEquals(1, record.downloadRequests)
        assertEquals(1, record.backgroundRequests)
        assertEquals(1, record.retries)
        assertTrue(record.line().contains("proxy 2 player + 1 download + 1 background, 1 retry"))
    }

    @Test
    fun `requests with no open start are background and record nothing`() {
        assertFalse(StreamStartTimings.proxyRequest("trackA", isDownload = false))
        StreamStartTimings.proxyUrlReady("trackA", 5, cached = true)
        StreamStartTimings.playing()
        assertTrue(StreamStartTimings.recentRecords().isEmpty())
    }

    @Test
    fun `bytes read before the start are a preload hit`() {
        StreamStartTimings.begin("current", StreamStartTimings.Kind.TAP)
        StreamStartTimings.playing()
        // The current song keeps loading: not a preload. The next one is preloaded.
        StreamStartTimings.playerBytes("current", 500_000)
        StreamStartTimings.playerBytes("next", 300_000)
        StreamStartTimings.playerBytes("next", 212_288)
        StreamStartTimings.proxyBytesServed("current", 65_536)
        StreamStartTimings.proxyBytesServed("next", 1_048_576)

        at(20_000); StreamStartTimings.begin("next", StreamStartTimings.Kind.SKIP)
        at(20_120); StreamStartTimings.ready()
        at(20_130); StreamStartTimings.playing()

        val record = StreamStartTimings.recentRecords().first()
        assertTrue(record.preloadHit)
        assertEquals(512_288L, record.preloadedBytes)
        assertTrue(record.line().startsWith("SKIP 130 ms"))
        assertEquals(1_048_576L, record.preloadFetchedBytes)
        assertTrue(record.line().contains("preload: yes (500 KiB read, 1024 KiB fetched)"))
        assertFalse(StreamStartTimings.recentRecords().last().preloadHit)
    }

    @Test
    fun `a seamless hand-over finishes at once`() {
        StreamStartTimings.begin("next", StreamStartTimings.Kind.AUTO, alreadyPlaying = true)
        val record = StreamStartTimings.recentRecords().single()
        assertEquals(StreamStartTimings.Kind.AUTO, record.kind)
        assertEquals(0L, record.totalMs)
    }

    @Test
    fun `a local song cancels the open start`() {
        StreamStartTimings.begin("trackA", StreamStartTimings.Kind.TAP)
        StreamStartTimings.cancel()
        StreamStartTimings.playing()
        assertTrue(StreamStartTimings.recentRecords().isEmpty())
    }

    @Test
    fun `an abandoned start is dropped`() {
        StreamStartTimings.begin("trackA", StreamStartTimings.Kind.TAP)
        at(now + StreamStartTimings.ABANDON_AFTER_MS + 1)
        StreamStartTimings.playing()
        assertTrue(StreamStartTimings.recentRecords().isEmpty())
    }

    @Test
    fun `an old ui request is not this tap`() {
        StreamStartTimings.requested("trackA")
        at(now + StreamStartTimings.REQUEST_MATCH_WINDOW_MS + 1)
        StreamStartTimings.begin("trackA", StreamStartTimings.Kind.TAP)
        StreamStartTimings.playing()
        assertNull(StreamStartTimings.recentRecords().single().requestLeadMs)
    }

    @Test
    fun `early rebuffer marks the last start once, seeks do not count`() {
        StreamStartTimings.begin("trackA", StreamStartTimings.Kind.TAP)
        StreamStartTimings.playing()
        at(now + 2_000); StreamStartTimings.buffering(postSeek = true)
        assertFalse(StreamStartTimings.recentRecords().single().rebufferedEarly)
        at(now + 1_000); StreamStartTimings.buffering(postSeek = false)
        assertTrue(StreamStartTimings.recentRecords().single().rebufferedEarly)
        assertTrue(StreamStartTimings.lastLine()!!.contains("rebuffered within 10 s"))
    }

    @Test
    fun `a rebuffer after the window is not an early one`() {
        StreamStartTimings.begin("trackA", StreamStartTimings.Kind.TAP)
        StreamStartTimings.playing()
        at(now + StreamStartTimings.EARLY_REBUFFER_WINDOW_MS + 1)
        StreamStartTimings.buffering(postSeek = false)
        assertFalse(StreamStartTimings.recentRecords().single().rebufferedEarly)
    }

    @Test
    fun `the ring keeps the newest eight, newest first`() {
        repeat(10) { index ->
            at(30_000L + index * 1_000L)
            StreamStartTimings.begin("track$index", StreamStartTimings.Kind.TAP)
            at(30_000L + index * 1_000L + index)
            StreamStartTimings.playing()
        }
        val records = StreamStartTimings.recentRecords()
        assertEquals(StreamStartTimings.MAX_RECORDS, records.size)
        assertEquals(9L, records.first().totalMs)
        assertEquals(2L, records.last().totalMs)
        assertEquals(StreamStartTimings.MAX_RECORDS, StreamStartTimings.recentLines().size)
    }

    @Test
    fun `aggregates carry timing names only, never a track id`() {
        StreamStartTimings.begin("SecretTrackId", StreamStartTimings.Kind.TAP)
        StreamStartTimings.urlResolved("SecretTrackId", matchMs = 300, resolveMs = 500, strategy = "VISIONOS", nParam = true, nTransformMs = 20)
        at(now + 900); StreamStartTimings.playing()

        val timings = PerformanceMetrics.snapshot().timings
        assertNotNull(timings[PerformanceMetrics.Timings.STREAM_START_TAP])
        assertEquals(500.0, timings[PerformanceMetrics.Timings.STREAM_RESOLVE]!!.lastMs)
        assertEquals(300.0, timings[PerformanceMetrics.Timings.STREAM_MATCH]!!.lastMs)
        assertTrue(timings.keys.none { it.contains("SecretTrackId") })
        assertTrue(StreamStartTimings.lastLine()!!.contains("n: yes 20 ms"))
    }

    @Test
    fun `a start that waited for the play button keeps its line but not the aggregate`() {
        // A restored queue: the item is set at launch, play is pressed 20 s later.
        StreamStartTimings.begin("trackA", StreamStartTimings.Kind.TAP)
        at(now + 20_000); StreamStartTimings.playRequested()
        at(now + 700); StreamStartTimings.playing()

        assertTrue(StreamStartTimings.lastLine()!!.startsWith("TAP 20700 ms: play pressed after 20000"))
        assertNull(PerformanceMetrics.snapshot().timings[PerformanceMetrics.Timings.STREAM_START_TAP])
    }

    @Test
    fun `a crossfade hand-over makes the incoming song current and is not a timed start`() {
        StreamStartTimings.begin("outgoing", StreamStartTimings.Kind.TAP)
        StreamStartTimings.playing()
        // The crossfade deck loads the incoming song before the swap: counted like a preload.
        StreamStartTimings.playerBytes("incoming", 300_000)
        StreamStartTimings.begin("incoming", StreamStartTimings.Kind.CROSSFADE, alreadyPlaying = true)
        // From now on its loading belongs to the song that plays, not to a later "preload".
        StreamStartTimings.playerBytes("incoming", 700_000)

        val record = StreamStartTimings.recentRecords().first()
        assertEquals(StreamStartTimings.Kind.CROSSFADE, record.kind)
        assertEquals(0L, record.totalMs)
        assertEquals(300_000L, record.preloadedBytes)
        assertTrue(StreamStartTimings.lastLine()!!.startsWith("CROSSFADE 0 ms"))
        assertNull(PerformanceMetrics.snapshot().timings[PerformanceMetrics.Timings.STREAM_START_AUTO])

        // Coming back to it later starts clean: no leftover "preload" from when it played.
        StreamStartTimings.begin("other", StreamStartTimings.Kind.SKIP)
        StreamStartTimings.playing()
        StreamStartTimings.begin("incoming", StreamStartTimings.Kind.SKIP)
        StreamStartTimings.playing()
        assertFalse(StreamStartTimings.recentRecords().first().preloadHit)
    }
}
