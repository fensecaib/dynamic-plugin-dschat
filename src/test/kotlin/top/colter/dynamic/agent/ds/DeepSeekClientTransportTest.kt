package top.colter.dynamic.agent.ds

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import top.colter.dynamic.agent.util.HttpUtils
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class DeepSeekClientTransportTest {
    @Test fun `request payload stays exact and server errors still retry`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        val bodies = java.util.concurrent.CopyOnWriteArrayList<String>()
        val headers = java.util.concurrent.CopyOnWriteArrayList<String>()
        server.createContext("/chat") { exchange ->
            bodies.add(exchange.requestBody.readBytes().toString(Charsets.UTF_8))
            headers.add(exchange.requestHeaders.getFirst("Authorization"))
            val attempt = attempts.incrementAndGet()
            val body = if (attempt == 1) "temporary error" else """{"choices":[{"message":{"role":"assistant","content":"完整分析文本"}}]}"""
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(if (attempt == 1) 503 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val request = ChatRequest("unchanged-model", listOf(ChatMessage("system", "原始提示词"), ChatMessage("user", "原始比赛数据")), ThinkingConfig("enabled"), maxTokens=16384)
            val client = DeepSeekClient("local-test-token", "http://127.0.0.1:${server.address.port}/chat")
            val response = client.chat(request).getOrThrow()
            assertEquals("完整分析文本", response.choices.single().message!!.content)
            assertEquals(2, attempts.get())
            bodies.forEach { assertEquals(HttpUtils.json.encodeToString(ChatRequest.serializer(), request), it) }
            assertEquals(listOf("Bearer local-test-token", "Bearer local-test-token"), headers)
        } finally { server.stop(0) }
    }

    @Test fun `cancellation interrupts waiting request without retry`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        val entered = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = AtomicInteger()
        server.executor = executor
        server.createContext("/chat") { exchange ->
            attempts.incrementAndGet(); entered.complete(Unit)
            try { release.await() } finally { exchange.close() }
        }
        server.start()
        try {
            val client = DeepSeekClient("local-test", "http://127.0.0.1:${server.address.port}/chat")
            val task = launch { client.chat(ChatRequest("test", emptyList())) }
            withTimeout(5000) { entered.await() }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(1, attempts.get())
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
