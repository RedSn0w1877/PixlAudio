package com.theveloper.pixelplay.data.cloudstudio

import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A scripted [CloudHttp]: each call takes the next answer (a response or an exception) and records the request. */
internal class ScriptedHttp(vararg answers: Any) : CloudHttp {
    private val queue = ArrayDeque(answers.toList())
    val requests = mutableListOf<CloudHttpRequest>()

    override suspend fun send(request: CloudHttpRequest): CloudHttpResponse {
        requests += request
        return when (val next = queue.removeFirstOrNull() ?: error("no scripted answer for $request")) {
            is CloudHttpResponse -> next
            is Exception -> throw next
            else -> error("bad script entry")
        }
    }
}

internal fun response(status: Int, body: String = "", headers: Map<String, String> = emptyMap()) =
    CloudHttpResponse(status, headers, body.toByteArray())

class RunPodJobsClientTest {
    private var now = 1_000_000L
    private val sleeps = mutableListOf<Long>()

    private fun client(http: CloudHttp, endpointId: String = "ep123abc", key: String = "rpa_KEY") =
        RunPodJobsClient(http, endpointId, key, nowMs = { now }, sleep = { sleeps += it; now += it })

    private val minimalRequest = CloudJobRequest(CloudJobInput(jobKey = CloudFixtures.JOB_KEY), CloudJobPolicy(1, 2))

    @Test fun `run posts the body with the key and returns the job id`() = runBlocking {
        val http = ScriptedHttp(response(200, """{"id":"job-1","status":"IN_QUEUE"}"""))
        val job = client(http).run(minimalRequest)
        assertEquals("job-1", job.id)
        assertEquals(RunPodJobStatus.IN_QUEUE, job.typedStatus)
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.runpod.ai/v2/ep123abc/run", request.url)
        assertEquals("Bearer rpa_KEY", request.header("Authorization"))
        assertEquals("application/json", request.header("Content-Type"))
        assertTrue(request.body!!.toString(Charsets.UTF_8).contains(CloudFixtures.JOB_KEY))
        // The key never shows up when a request is printed.
        assertFalse("rpa_KEY" in request.toString())
    }

    @Test fun `runs are at least 100 ms apart`() = runBlocking {
        val http = ScriptedHttp(response(200, """{"id":"a"}"""), response(200, """{"id":"b"}"""))
        val client = client(http)
        client.run(minimalRequest)
        now += 30
        client.run(minimalRequest)
        assertEquals(listOf(70L), sleeps)
    }

    @Test fun `401 and 403 are the key, a 404 on the endpoint is the Endpoint ID`() = runBlocking {
        assertThrows(RunPodError.Unauthorized::class.java) { runBlocking { client(ScriptedHttp(response(401))).health() } }
        assertThrows(RunPodError.Unauthorized::class.java) { runBlocking { client(ScriptedHttp(response(403))).run(minimalRequest) } }
        assertThrows(RunPodError.EndpointNotFound::class.java) { runBlocking { client(ScriptedHttp(response(404))).health() } }
        Unit
    }

    @Test fun `a status 404 means the job expired, not a wrong endpoint`() {
        assertThrows(RunPodError.JobNotFound::class.java) {
            runBlocking { client(ScriptedHttp(response(404))).status("job-1") }
        }
        // An id that can't go in a URL path never reaches the network.
        val http = ScriptedHttp()
        assertThrows(RunPodError.JobNotFound::class.java) { runBlocking { client(http).status("../x") } }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `429 sets a Retry-After gate that fails later calls fast`() = runBlocking {
        val http = ScriptedHttp(response(429, headers = mapOf("Retry-After" to "20")))
        val client = client(http)
        val first = assertThrows(RunPodError.RateLimited::class.java) { runBlocking { client.health() } }
        assertEquals(20_000L, first.retryAfterMs)
        now += 5_000
        assertThrows(RunPodError.RateLimited::class.java) { runBlocking { client.health() } }
        assertEquals(1, http.requests.size)
        assertEquals(15_000L, client.retryAfterRemainingMs)
    }

    @Test fun `reads retry 5xx and dropped connections twice, run never retries`() = runBlocking {
        val reads = ScriptedHttp(response(502), IOException("reset"), response(200, CloudFixtures.phone("runpod.health.json")))
        val health = client(reads).health()
        assertEquals(3, health.inQueue)
        assertEquals(listOf(1_000L, 3_000L), sleeps)
        val exhausted = ScriptedHttp(response(500), response(500), response(503))
        assertThrows(RunPodError.Server::class.java) { runBlocking { client(exhausted).status("job-1") } }
        assertEquals(3, exhausted.requests.size)
        val run = ScriptedHttp(response(500))
        assertThrows(RunPodError.Server::class.java) { runBlocking { client(run).run(minimalRequest) } }
        assertEquals(1, run.requests.size)
    }

    @Test fun `network errors are redacted`() {
        val http = ScriptedHttp(IOException("failed https://x/y?X-Amz-Signature=abc Bearer rpa_KEY"))
        val error = assertThrows(RunPodError.Network::class.java) { runBlocking { client(http).run(minimalRequest) } }
        assertFalse("abc" in error.message!!)
        assertFalse("rpa_KEY" in error.message!!)
    }

    @Test fun `a missing or malformed endpoint never sends anything`() {
        val http = ScriptedHttp()
        assertThrows(RunPodError.NotConfigured::class.java) { runBlocking { client(http, endpointId = "https://x").health() } }
        assertThrows(RunPodError.NotConfigured::class.java) { runBlocking { client(http, key = " ").health() } }
        assertTrue(http.requests.isEmpty())
        assertTrue(RunPodJobsClient.isValidEndpointId("kjoa5h86wrpf2q"))
        assertFalse(RunPodJobsClient.isValidEndpointId("api.runpod.ai/v2/x"))
    }

    @Test fun `cancel returns the job's state afterwards`() = runBlocking {
        val http = ScriptedHttp(response(200, """{"id":"job-1","status":"CANCELLED"}"""))
        val reply = client(http).cancel("job-1")
        assertEquals("job-1", reply?.id)
        assertEquals(RunPodJobStatus.CANCELLED, reply?.typedStatus)
        val request = http.requests.single()
        assertEquals("POST", request.method)
        assertEquals("https://api.runpod.ai/v2/ep123abc/cancel/job-1", request.url)
        assertEquals("Bearer rpa_KEY", request.header("Authorization"))
    }

    @Test fun `a cancel that did not remove the job from the queue says so, and an unreadable reply is null`() = runBlocking {
        // The priority lane only trusts the word CANCELLED: a job that already began answers with where it is.
        val began = client(ScriptedHttp(response(200, """{"id":"job-1","status":"IN_PROGRESS"}"""))).cancel("job-1")
        assertEquals(RunPodJobStatus.IN_PROGRESS, began?.typedStatus)
        assertFalse(CloudPriorityLane.cancelConfirmed(began))
        assertNull(client(ScriptedHttp(response(200, ""))).cancel("job-1"))
        assertNull(client(ScriptedHttp(response(200, "not json"))).cancel("job-1"))
        assertFalse(CloudPriorityLane.cancelConfirmed(client(ScriptedHttp(response(200, "{}"))).cancel("job-1")))
    }

    @Test fun `cancels are at least 120 ms apart, one gate of their own`() = runBlocking {
        val http = ScriptedHttp(
            response(200, """{"id":"a","status":"CANCELLED"}"""), response(200, """{"id":"b","status":"CANCELLED"}"""),
            response(200, """{"id":"c","status":"CANCELLED"}"""),
        )
        val client = client(http)
        client.cancel("a")
        assertEquals(emptyList<Long>(), sleeps, "the first cancel waits for nothing")
        now += 30
        client.cancel("b")
        assertEquals(listOf(90L), sleeps)
        now += 500
        client.cancel("c")
        assertEquals(listOf(90L), sleeps, "after a long enough pause there is nothing to wait for")
        assertEquals(RunPodJobsClient.MINIMUM_CANCEL_INTERVAL_MS, CloudTiming.CANCEL_MIN_INTERVAL_MS)
    }

    @Test fun `a cancel of a job RunPod forgot is JobNotFound, and a bad id never leaves the phone`() {
        assertThrows(RunPodError.JobNotFound::class.java) {
            runBlocking { client(ScriptedHttp(response(404))).cancel("job-1") }
        }
        val http = ScriptedHttp()
        assertThrows(RunPodError.JobNotFound::class.java) { runBlocking { client(http).cancel("../x") } }
        assertTrue(http.requests.isEmpty())
    }

    @Test fun `selftest goes through runsync with a long timeout`() = runBlocking {
        val http = ScriptedHttp(response(200, CloudFixtures.phone("runpod.selftest.json")))
        val job = client(http).selftest("0.7.6 (11)")
        assertEquals("https://api.runpod.ai/v2/ep123abc/runsync", http.requests.single().url)
        assertEquals(150_000L, http.requests.single().timeoutMs)
        assertEquals("NVIDIA L4", job.selftest?.worker?.gpu)
        val report = CloudConnectionTest.selftestReport(object : RunPodJobsApi by FakeRunPodBase() {
            override suspend fun selftest(build: String) = job
        }, "b")
        assertTrue(report.check.ok)
        assertEquals("Worker 1.0.0 on NVIDIA L4", report.check.detail)
        assertNull(report.caps)
    }
}

/** Every call fails unless a test overrides it. */
internal open class FakeRunPodBase : RunPodJobsApi {
    override suspend fun run(request: CloudJobRequest): RunPodJob = error("unexpected run")
    override suspend fun status(jobId: String): RunPodJob = error("unexpected status")
    override suspend fun cancel(jobId: String): RunPodJob? = null
    override suspend fun health(): RunPodHealth = error("unexpected health")
    override suspend fun selftest(build: String): RunPodJob = error("unexpected selftest")
}

class CloudObjectClientTest {
    private val signer = S3Signer(S3Credentials("AKID", "SECRET"), S3Location("https://acct.r2.cloudflarestorage.com", "bucket"))

    @Test fun `head, get, put and delete map the bucket's answers`() = runBlocking {
        val http = ScriptedHttp(
            response(200, headers = mapOf("Content-Length" to "42")),
            response(404),
            response(404, "<Error><Code>NoSuchBucket</Code></Error>"),
            response(200, "hello"),
            response(403),
            response(404),
            response(204),
        )
        val client = CloudObjectClient(http, signer) { 1_700_000_000 }
        assertEquals(42L, client.head("out/a"))
        assertNull(client.head("out/b"))
        assertThrows(CloudStorageError.BucketNotFound::class.java) { runBlocking { client.get("x") } }
        assertEquals("hello", client.get("y")!!.toString(Charsets.UTF_8))
        assertThrows(CloudStorageError.Unauthorized::class.java) { runBlocking { client.put("z", byteArrayOf(1), "a/b") } }
        client.delete("missing") // 404 on delete is fine
        client.delete("present")
        assertEquals("HEAD", http.requests[0].method)
        assertEquals("a/b", http.requests[4].header("Content-Type"))
        // Presigned, never an Authorization header.
        assertTrue(http.requests.all { it.header("Authorization") == null && "X-Amz-Signature=" in it.url })
    }

    @Test fun `list follows continuation tokens`() = runBlocking {
        val page1 = "<ListBucketResult><IsTruncated>true</IsTruncated><NextContinuationToken>tok</NextContinuationToken>" +
            "<CommonPrefixes><Prefix>out/a/</Prefix></CommonPrefixes></ListBucketResult>"
        val page2 = "<ListBucketResult><IsTruncated>false</IsTruncated>" +
            "<CommonPrefixes><Prefix>out/b/</Prefix></CommonPrefixes></ListBucketResult>"
        val http = ScriptedHttp(response(200, page1), response(200, page2))
        val list = CloudObjectClient(http, signer).list("out/", "/")
        assertEquals(listOf("out/a/", "out/b/"), list.commonPrefixes)
        assertTrue("continuation-token=tok" in http.requests[1].url)
    }

    @Test fun `network failures are redacted storage errors`() {
        val http = ScriptedHttp(IOException("timeout on https://acct/bucket/k?X-Amz-Signature=deadbeef"))
        val error = assertThrows(CloudStorageError.Network::class.java) {
            runBlocking { CloudObjectClient(http, signer).get("k") }
        }
        assertFalse("deadbeef" in error.message!!)
    }

    @Test fun `the storage probe reports each step`() = runBlocking {
        val ok = CloudConnectionTest.checkStorage(
            CloudObjectClient(ScriptedHttp(response(200), response(200, headers = mapOf("Content-Length" to "1")), response(204)), signer),
            "probe-id"
        )
        assertTrue(ok.ok)
        val refused = CloudConnectionTest.checkStorage(CloudObjectClient(ScriptedHttp(response(403)), signer), "p")
        assertFalse(refused.ok)
        assertTrue(refused.message.startsWith("Storage refused the key when trying to write a test file"))
        val noDelete = CloudConnectionTest.checkStorage(
            CloudObjectClient(ScriptedHttp(response(200), response(200), response(403)), signer), "p"
        )
        assertTrue(noDelete.message.endsWith("The token needs Object Read & Write, which includes deleting."))
        assertFalse(CloudConnectionTest.checkStorage(null, "p").ok)
    }
}
