package com.theveloper.pixelplay.data.stream

import com.google.common.truth.Truth.assertThat
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

class CloudStreamProxyLifecycleTest {

    private class FakeProxy : CloudStreamProxy<String>(OkHttpClient()) {
        override val allowedHostSuffixes = setOf("example.com")
        override val cacheExpirationMs = 60_000L
        override val proxyTag = "FakeProxy"
        override val routePath = "/fake/{id}"
        override val routeParamName = "id"
        override val uriScheme = "fake"
        override val routePrefix = "/fake"
        override fun parseRouteParam(value: String): String? = value.takeIf { it.isNotBlank() }
        override fun validateId(id: String) = id.isNotBlank()
        override fun formatIdForUrl(id: String) = id
        override suspend fun resolveStreamUrl(id: String): String? = null
    }

    private fun portOf(proxy: FakeProxy): Int =
        proxy.getProxyUrl("x").substringAfter("127.0.0.1:").substringBefore('/').toInt()

    @Test
    fun `stop never leaves a server running when it lands while start is binding`() {
        val proxy = FakeProxy()
        repeat(40) {
            proxy.start()
            proxy.stop()
        }
        // Whatever interleaving happened, a stop() that came last wins.
        Thread.sleep(300)
        assertThat(proxy.isReady()).isFalse()
    }

    @Test
    fun `stop returns promptly even while a client connection is still open`() {
        val proxy = FakeProxy()
        try {
            assertThat(runBlocking { proxy.ensureReady(10_000L) }).isTrue()
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", portOf(proxy)), 2_000)
                // A partial request keeps the connection open server-side.
                socket.getOutputStream().write("GET /fake/x HT".toByteArray())
                socket.getOutputStream().flush()
                Thread.sleep(100)

                val startedAt = System.nanoTime()
                proxy.stop()
                val tookMs = (System.nanoTime() - startedAt) / 1_000_000
                assertThat(proxy.isReady()).isFalse()
                assertThat(tookMs).isLessThan(500L)
            }
        } finally {
            proxy.stop()
        }
    }

    @Test
    fun `the proxy can be started again after a stop`() {
        val proxy = FakeProxy()
        try {
            assertThat(runBlocking { proxy.ensureReady(10_000L) }).isTrue()
            proxy.stop()
            assertThat(proxy.isReady()).isFalse()
            assertThat(runBlocking { proxy.ensureReady(10_000L) }).isTrue()
        } finally {
            proxy.stop()
        }
    }
}
