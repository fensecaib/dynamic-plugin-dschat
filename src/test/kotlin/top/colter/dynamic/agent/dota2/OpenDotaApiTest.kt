package top.colter.dynamic.agent.dota2

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.agent.util.CacheUtils
import java.net.InetSocketAddress
import java.net.ConnectException
import java.net.http.HttpTimeoutException
import java.nio.file.Files
import kotlin.test.*

class OpenDotaApiTest {
    private fun withApi(status: Int, body: String, beforeResponse: () -> Unit = {}, test: suspend (Dota2Service) -> Unit) = runBlocking {
        val dir = Files.createTempDirectory("opendota-errors-")
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            beforeResponse()
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            test(Dota2Service(dir.toFile(), CacheUtils(dir), "http://127.0.0.1:${server.address.port}"))
        } finally {
            server.stop(0)
            Files.delete(dir)
        }
    }

    @Test fun `service failures retain cause across player history match and overview queries`() {
        for ((status, reason) in mapOf(522 to "源服务器超时", 524 to "响应超时", 503 to "服务端或网关异常", 429 to "限流", 403 to "拒绝访问")) {
            withApi(status, "private upstream error details") { service ->
                val calls: List<suspend () -> Any?> = listOf(
                    { service.validatePlayer(176496411) },
                    { service.getRecentMatches(176496411, 10) },
                    { service.getMatchDetail(8988982914) },
                    { service.getOverviewSnapshot(176496411) },
                )
                for (call in calls) {
                    val error = assertFailsWith<OpenDotaApiException> {
                        service.reportTasks.runIfIdle(onBusy = { error("slot leaked") }) { call() }
                    }
                    assertContains(error.message.orEmpty(), "OpenDota 服务不可用")
                    assertContains(error.message.orEmpty(), "HTTP $status")
                    assertContains(error.message.orEmpty(), reason)
                    assertFalse(error.message.orEmpty().contains("private upstream"))
                    assertEquals("released", service.reportTasks.runIfIdle(onBusy = { "busy" }) { "released" })
                }
            }
        }
    }

    @Test fun `missing records remain distinct from an outage`() {
        withApi(404, "not found") { service ->
            assertNull(service.validatePlayer(176496411))
            assertNull(service.getRecentMatches(176496411))
            assertNull(service.getMatchDetail(8988982914))
        }
        withApi(200, "[]") { assertTrue(it.getRecentMatches(176496411)!!.isEmpty()) }
        withApi(200, "{\"profile\":null}") { assertNull(it.validatePlayer(176496411)) }
        withApi(200, "{\"profile\":{\"personaname\":\"test\"}}") {
            assertEquals("test", it.validatePlayer(176496411))
        }
    }

    @Test fun `invalid upstream responses are not treated as missing matches`() {
        for (body in listOf("<html>upstream unavailable</html>", "{}", "[null]", "[{\"match_id\":{}}]", "[{\"match_id\":1,\"start_time\":{}}]")) {
            withApi(200, body) { service ->
                assertContains(assertFailsWith<OpenDotaApiException> {
                    service.getRecentMatches(176496411)
                }.message.orEmpty(), "数据格式异常")
            }
        }
        withApi(200, "{\"error\":\"internal details\"}") { service ->
            assertFailsWith<OpenDotaApiException> { service.validatePlayer(176496411) }
            assertFailsWith<OpenDotaApiException> { service.getMatchDetail(8988982914) }
        }
        withApi(200, "{\"profile\":{\"personaname\":123}}") {
            assertFailsWith<OpenDotaApiException> { it.validatePlayer(176496411) }
        }
        withApi(200, "{\"match_id\":123}") {
            assertFailsWith<OpenDotaApiException> { it.getMatchDetail(8988982914) }
            assertNotNull(it.getMatchDetail(123))
        }
    }

    @Test fun `cancelling a pending request is not converted into an outage and releases slot`() {
        val started = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        withApi(200, "[]", beforeResponse = {
            started.complete(Unit)
            release.await(5, java.util.concurrent.TimeUnit.SECONDS)
        }) { service ->
            kotlinx.coroutines.coroutineScope {
                val request = launch {
                    service.reportTasks.runIfIdle(onBusy = { error("busy") }) {
                        service.getRecentMatches(176496411)
                    }
                }
                try {
                    withTimeout(3000) { started.await(); request.cancelAndJoin() }
                    assertTrue(request.isCancelled)
                    assertEquals("released", service.reportTasks.runIfIdle(onBusy = { "busy" }) { "released" })
                } finally {
                    release.countDown()
                    request.cancelAndJoin()
                }
            }
        }
    }

    @Test fun `network errors explain uncertainty without leaking raw exception details`() {
        assertContains(openDotaNetworkFailure(HttpTimeoutException("secret")).message.orEmpty(), "请求超时")
        val error = openDotaNetworkFailure(ConnectException("secret"))
        assertContains(error.message.orEmpty(), "宿主网络")
        assertFalse(error.message.orEmpty().contains("secret"))
    }
}
