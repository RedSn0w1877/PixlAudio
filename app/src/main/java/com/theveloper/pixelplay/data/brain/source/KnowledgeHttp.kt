package com.theveloper.pixelplay.data.brain.source

import com.theveloper.pixelplay.data.brain.BrainClock
import com.theveloper.pixelplay.di.KnowledgeOkHttpClient
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber

/**
 * The one door every open-data request goes through (docs/brain-spec.md, 3.7): the host's off switch (local and remote), its daily
 * budget and request ledger, its polite spacing, and its back-off. A source client never talks to the network any other way, so
 * none of these rules can be bypassed.
 *
 * A reply of `null` means "not asked": the source is switched off, its budget for the day is spent, it is in back-off, or there is
 * no network. The caller treats that as "try later" and records nothing.
 */
interface KnowledgeHttp {
    class Reply(val code: Int, val body: String?, val retryAfterSec: Int = 0) {
        val ok: Boolean get() = code in 200..299
    }

    suspend fun get(host: KnowledgeHost, url: String, accept: String = "application/json"): Reply?

    suspend fun post(host: KnowledgeHost, url: String, body: String, contentType: String = "application/json", accept: String = "application/json"): Reply?

    /** A source says it was told to slow down (Deezer's quota error arrives as HTTP 200): no request for [forMs]. */
    fun backOff(host: KnowledgeHost, forMs: Long)
}

/** Whether an open-data source may be asked right now: its local switch is on and the remote config does not switch it off. */
fun interface KnowledgeSwitch {
    suspend fun sourceAllowed(host: KnowledgeHost): Boolean
}

/** One token bucket and ledger per host, built from the spec's ceilings (3.7). */
@Singleton
class KnowledgeBudgets @Inject constructor(
    private val ledger: KnowledgeLedger,
    private val clock: BrainClock,
) {
    private val budgets = HashMap<KnowledgeHost, HostBudget>()

    @Synchronized
    fun of(host: KnowledgeHost): HostBudget = budgets.getOrPut(host) {
        HostBudget(
            capacity = host.dailyCeiling,
            refillIntervalMs = HostBudget.DAY_MS / host.dailyCeiling,
            maxParallel = 1,
            clock = clock::nowMs,
            onSpend = { ledger.record(host, it) },
            dayHasRoom = { ledger.hasRoom(host) },
        )
    }
}

@Singleton
class OkHttpKnowledgeHttp @Inject constructor(
    @KnowledgeOkHttpClient private val client: OkHttpClient,
    private val budgets: KnowledgeBudgets,
    private val switch: KnowledgeSwitch,
    private val clock: BrainClock,
) : KnowledgeHttp {
    private val pacing = HashMap<KnowledgeHost, Mutex>()
    private val nextAtMs = HashMap<KnowledgeHost, Long>()
    private val blockedUntilMs = HashMap<KnowledgeHost, Long>()
    private val failures = HashMap<KnowledgeHost, Int>()

    @Synchronized
    private fun mutex(host: KnowledgeHost): Mutex = pacing.getOrPut(host) { Mutex() }

    override suspend fun get(host: KnowledgeHost, url: String, accept: String): KnowledgeHttp.Reply? =
        run(host, Request.Builder().url(url).header("Accept", accept).get().build())

    override suspend fun post(host: KnowledgeHost, url: String, body: String, contentType: String, accept: String): KnowledgeHttp.Reply? =
        run(host, Request.Builder().url(url).header("Accept", accept).post(body.toRequestBody(contentType.toMediaType())).build())

    @Synchronized
    override fun backOff(host: KnowledgeHost, forMs: Long) {
        blockedUntilMs[host] = maxOf(blockedUntilMs[host] ?: 0L, clock.nowMs() + forMs)
    }

    private suspend fun run(host: KnowledgeHost, request: Request): KnowledgeHttp.Reply? {
        if (!switch.sourceAllowed(host)) return null
        if (clock.nowMs() < synchronized(this) { blockedUntilMs[host] ?: 0L }) return null
        return budgets.of(host).withPermit {
            mutex(host).withLock {
                // Spacing: the polite rate of the host, measured from the start of the last request.
                val wait = synchronized(this) { nextAtMs[host] ?: 0L } - clock.nowMs()
                if (wait > 0) delay(wait)
                synchronized(this) { nextAtMs[host] = clock.nowMs() + host.minIntervalMs }
                // The switch is asked again after the wait: flipping it stops the very next request.
                if (!switch.sourceAllowed(host)) return@withLock null
                withContext(Dispatchers.IO) {
                    try {
                        client.newCall(request).execute().use { response ->
                            val text = response.body?.string()
                            val retry = response.header("Retry-After")?.toIntOrNull() ?: 0
                            noteOutcome(host, response.code, retry)
                            KnowledgeHttp.Reply(response.code, text, retry)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Timber.d(e, "brain: %s request failed", host.key)
                        noteOutcome(host, -1, 0)
                        null
                    }
                }
            }
        }
    }

    /** 429, 502, 503, 504 (and iTunes' 403) back the host off, exponentially with jitter, honouring Retry-After. */
    @Synchronized
    private fun noteOutcome(host: KnowledgeHost, code: Int, retryAfterSec: Int) {
        val throttled = code == 429 || code == 502 || code == 503 || code == 504 || (host == KnowledgeHost.ITUNES && code == 403)
        if (!throttled && code != -1) {
            failures[host] = 0
            return
        }
        val n = (failures[host] ?: 0) + 1
        failures[host] = n
        val base = minOf(BACKOFF_CAP_MS, BACKOFF_FIRST_MS shl minOf(n - 1, 8))
        val jitter = (clock.nowMs() % 1000L)
        val wait = maxOf(base + jitter, retryAfterSec * 1000L)
        blockedUntilMs[host] = clock.nowMs() + wait
    }

    private companion object {
        const val BACKOFF_FIRST_MS = 5_000L
        const val BACKOFF_CAP_MS = 10L * 60_000L
    }
}

/** What the app calls itself to the open-data hosts. The URL is the public project page MusicBrainz and Wikimedia ask a client to give. */
object KnowledgeUserAgent {
    const val PROJECT_URL = "https://github.com/RedSn0w1877/PixlAudio"
}
