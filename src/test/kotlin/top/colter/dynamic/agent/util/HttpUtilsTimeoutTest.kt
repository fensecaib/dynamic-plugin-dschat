package top.colter.dynamic.agent.util

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import java.net.InetSocketAddress
import java.net.http.HttpTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class HttpUtilsTimeoutTest {
    private class StalledResponse : AutoCloseable {
        val headersSent = CountDownLatch(1)
        private val release = CountDownLatch(1)
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@StalledResponse.executor
            createContext("/stall") { exchange ->
                try {
                    exchange.requestBody.readBytes()
                    exchange.sendResponseHeaders(200, 0)
                    exchange.responseBody.write(' '.code)
                    exchange.responseBody.flush()
                    headersSent.countDown()
                    release.await(10, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    // The caller deliberately closes the connection before the body completes.
                } finally {
                    exchange.close()
                }
            }
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/stall"

        override fun close() {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun `GET POST and bytes deadlines cover a body stalled after headers`() = runBlocking<Unit> {
        val requests: List<suspend (String) -> Unit> = listOf(
            { HttpUtils.httpGetAsync(it, timeoutSec = 1) },
            { HttpUtils.httpPostAsync(it, "{}", timeoutSec = 1) },
            { HttpUtils.httpGetBytesAsync(it, timeoutSec = 1) },
        )
        for (request in requests) StalledResponse().use { fixture ->
            withTimeout(5_000) {
                assertFailsWith<HttpTimeoutException> { request(fixture.url) }
            }
            assertEquals(0L, fixture.headersSent.count, "The deadline must include an already-started response body")
        }
    }

    @Test fun `caller cancellation stays cancellation rather than a network timeout`() = runBlocking<Unit> {
        StalledResponse().use { fixture ->
            val request = async { HttpUtils.httpGetAsync(fixture.url, timeoutSec = 30) }
            assertTrue(withContext(Dispatchers.IO) { fixture.headersSent.await(3, TimeUnit.SECONDS) })
            request.cancel()
            assertFailsWith<CancellationException> { request.await() }
        }
    }
}
