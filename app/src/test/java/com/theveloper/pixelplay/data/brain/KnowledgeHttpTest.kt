package com.theveloper.pixelplay.data.brain

import com.theveloper.pixelplay.data.brain.source.KnowledgeBudgets
import com.theveloper.pixelplay.data.brain.source.KnowledgeHost
import com.theveloper.pixelplay.data.brain.source.KnowledgeLedger
import com.theveloper.pixelplay.data.brain.source.KnowledgeSwitch
import com.theveloper.pixelplay.data.brain.source.OkHttpKnowledgeHttp
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The one door every open-data request goes through (docs/brain-spec.md, 3.7): switches, budget, ledger, spacing, back-off. */
class KnowledgeHttpTest {
    private var now = 1_795_000_000_000L
    private val clock = BrainClock { now }
    private val states = FakeBrainStateDao()
    private val ledger = KnowledgeLedger(states, clock)
    private val budgets = KnowledgeBudgets(ledger, clock)
    private val calls = AtomicInteger()
    private val allowed = HashSet<KnowledgeHost>(KnowledgeHost.entries)
    private var code = 200
    private val userAgents = ArrayList<String?>()

    private val switch = KnowledgeSwitch { host -> host in allowed }

    private val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        calls.incrementAndGet()
        userAgents.add(chain.request().header("User-Agent"))
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("x")
            .body("""{"ok":true}""".toResponseBody("application/json".toMediaType())).build()
    }).build()

    private fun http() = OkHttpKnowledgeHttp(client, budgets, switch, clock)

    @Test
    fun `a switched off source is never asked, and flipping it stops the very next request`() = runTest {
        val h = http()
        assertNotNull(h.get(KnowledgeHost.ITUNES, "https://itunes.apple.com/search?term=a"))
        assertEquals(1, calls.get())
        allowed.remove(KnowledgeHost.ITUNES)
        assertNull(h.get(KnowledgeHost.ITUNES, "https://itunes.apple.com/search?term=b"))
        assertEquals(1, calls.get(), "nothing went out")
        assertNotNull(h.get(KnowledgeHost.DEEZER, "https://api.deezer.com/track/1"), "the other sources are not affected")
    }

    @Test
    fun `every request is counted in the ledger and the ceiling of the day is a hard stop`() = runTest {
        val h = http()
        repeat(KnowledgeHost.WIKIDATA.dailyCeiling) { assertNotNull(h.post(KnowledgeHost.WIKIDATA, "https://query.wikidata.org/sparql", "query=x")) }
        assertNull(h.post(KnowledgeHost.WIKIDATA, "https://query.wikidata.org/sparql", "query=x"), "the fourth query of the day is refused")
        assertEquals(3, calls.get())
        assertEquals(3, ledger.countSince(KnowledgeHost.WIKIDATA, now - 86_400_000L))
        // A new process: the ledger remembers, so the budget of a fresh object still refuses.
        var settled = false
        val deadline = System.currentTimeMillis() + 5_000L
        while (!settled && System.currentTimeMillis() < deadline) {
            settled = KnowledgeLedger(states, clock).countSince(KnowledgeHost.WIKIDATA, now - 86_400_000L) == 3
            if (!settled) Thread.sleep(10)
        }
        assertTrue(settled)
        val restarted = OkHttpKnowledgeHttp(client, KnowledgeBudgets(KnowledgeLedger(states, clock), clock), switch, clock)
        assertNull(restarted.post(KnowledgeHost.WIKIDATA, "https://query.wikidata.org/sparql", "query=x"))
        // A day later there is room again.
        now += 86_400_000L + 1
        assertNotNull(restarted.post(KnowledgeHost.WIKIDATA, "https://query.wikidata.org/sparql", "query=x"))
    }

    @Test
    fun `requests to one host are spaced by its polite interval`() = runTest {
        val h = http()
        val start = currentTime
        h.get(KnowledgeHost.MUSICBRAINZ, "https://musicbrainz.org/ws/2/a")
        h.get(KnowledgeHost.MUSICBRAINZ, "https://musicbrainz.org/ws/2/b")
        assertTrue(currentTime - start >= KnowledgeHost.MUSICBRAINZ.minIntervalMs, "waited ${currentTime - start} ms")
    }

    @Test
    fun `a 503 backs the host off, and the next request is not asked until it passes`() = runTest {
        val h = http()
        code = 503
        val reply = h.get(KnowledgeHost.MUSICBRAINZ, "https://musicbrainz.org/ws/2/a")
        assertEquals(503, reply!!.code)
        assertNull(h.get(KnowledgeHost.MUSICBRAINZ, "https://musicbrainz.org/ws/2/b"), "in back-off")
        assertEquals(1, calls.get())
        assertNotNull(h.get(KnowledgeHost.DEEZER, "https://api.deezer.com/track/1"), "another host is not held")
        code = 200
        now += 11L * 60 * 1000
        assertNotNull(h.get(KnowledgeHost.MUSICBRAINZ, "https://musicbrainz.org/ws/2/c"))
    }

    @Test
    fun `a source can ask for a back-off by itself, as Deezer does on its quota error`() = runTest {
        val h = http()
        h.backOff(KnowledgeHost.DEEZER, 60_000L)
        assertNull(h.get(KnowledgeHost.DEEZER, "https://api.deezer.com/track/1"))
        now += 61_000L
        assertNotNull(h.get(KnowledgeHost.DEEZER, "https://api.deezer.com/track/1"))
    }
}
