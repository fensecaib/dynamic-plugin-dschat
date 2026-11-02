package top.colter.dynamic.agent.ds

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import top.colter.dynamic.agent.util.AgentTrace
import top.colter.dynamic.agent.util.HttpUtils
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class DeepSeekClientTransportTest {
    private val validAnswer = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"完整分析文本"}}]}"""

    @Test fun `authenticated requests never follow redirects to another endpoint`() = runBlocking<Unit> {
        val destination = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirectedRequests = AtomicInteger()
        destination.createContext("/other") { exchange ->
            redirectedRequests.incrementAndGet()
            exchange.requestBody.readBytes()
            val bytes = validAnswer.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        destination.start()
        val origin = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        val redirectStatus = AtomicInteger(307)
        origin.createContext("/chat") { exchange ->
            attempts.incrementAndGet()
            exchange.requestBody.readBytes()
            exchange.responseHeaders.set("Location", "http://127.0.0.1:${destination.address.port}/other")
            exchange.sendResponseHeaders(redirectStatus.get(), -1)
            exchange.close()
        }
        origin.start()
        try {
            val client = DeepSeekClient("local-redirect-test-token", "http://127.0.0.1:${origin.address.port}/chat")
            for (status in listOf(301, 302, 303, 307, 308)) {
                redirectStatus.set(status)
                val before = attempts.get()
                val result = client.chat(ChatRequest("test", listOf(ChatMessage("user", "private local fixture"))))
                assertEquals(0, redirectedRequests.get(), "HTTP $status must not forward credentials or conversation data")
                val failure = assertIs<ApiCallException>(result.exceptionOrNull())
                assertEquals("redirect_rejected", failure.category)
                assertEquals(status, failure.statusCode)
                assertEquals(before + 1, attempts.get())
                assertFalse(failure.message.orEmpty().contains("/other"))
            }
        } finally {
            origin.stop(0)
            destination.stop(0)
        }
    }

    @Test fun `missing credentials never sends a request or retries`() = runBlocking {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        server.createContext("/chat") { exchange ->
            attempts.incrementAndGet()
            exchange.sendResponseHeaders(401, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = DeepSeekClient("   ", "http://127.0.0.1:${server.address.port}/chat")
            val result = withTimeout(1000) { client.chat(ChatRequest("test", emptyList())) }
            assertTrue(result.isFailure)
            assertContains(result.exceptionOrNull()!!.message!!, "未配置 API Key")
            assertEquals(0, attempts.get())
        } finally { server.stop(0) }
    }

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

    @Test fun `HTTP failures have safe categories and client failures never retry`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        val status = AtomicInteger(401)
        val secret = "secret-local-test-api-key"
        server.createContext("/chat") { exchange ->
            attempts.incrementAndGet()
            exchange.requestBody.readBytes()
            val bytes = """{"error":{"message":"$secret prompt-sensitive response-sensitive"}}""".toByteArray()
            exchange.sendResponseHeaders(status.get(), bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val client = DeepSeekClient(secret, "http://127.0.0.1:${server.address.port}/chat?key=$secret")
            val logs = mutableListOf<String>()
            for ((httpStatus, category) in listOf(401 to "unauthorized", 402 to "payment_required", 429 to "rate_limited", 404 to "http_error")) {
                status.set(httpStatus)
                val before = attempts.get()
                val result = withContext(AgentTrace("safe-http-$httpStatus", sink = logs::add)) {
                    client.chat(ChatRequest("deepseek-flash", listOf(ChatMessage("user", "prompt-sensitive"))))
                }
                val failure = assertIs<ApiCallException>(result.exceptionOrNull())
                assertEquals(category, failure.category)
                assertEquals(httpStatus, failure.statusCode)
                assertNull(failure.cause)
                assertFalse(failure.toString().contains(secret))
                assertEquals(before + 1, attempts.get())
                assertTrue(logs.any { "trace_id=safe-http-$httpStatus" in it && "http_status=$httpStatus" in it && "category=$category" in it })
            }
            val text = logs.joinToString("\n")
            listOf(secret, "prompt-sensitive", "response-sensitive", "Bearer", "?key=", "/chat").forEach { assertFalse(it in text) }
        } finally { server.stop(0) }
    }

    @Test fun `response validation rejects empty error reasoning-only and truncated responses without retry`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        val responseBody = AtomicReference("{}")
        server.createContext("/chat") { exchange ->
            attempts.incrementAndGet()
            exchange.requestBody.readBytes()
            val bytes = responseBody.get().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val cases = listOf(
                "{}" to "empty_choices",
                "{\"choices\":[]}" to "empty_choices",
                "   \n\r\n" to "empty_response",
                "{\"choices\":" to "response_json",
                "[]" to "response_json",
                """{"error":{"message":"secret-response"}}""" to "api_error",
                """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"   "}}]}""" to "empty_content",
                """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":null,"reasoning_content":"secret-response"}}]}""" to "empty_content",
                """{"choices":[{"finish_reason":"stop"}]}""" to "empty_content",
                """{"choices":[{"finish_reason":"length","message":{"role":"assistant","content":"partial-secret-response"}}]}""" to "response_incomplete",
                """{"choices":[{"finish_reason":"content_filter","message":{"role":"assistant","content":"partial-secret-response"}}]}""" to "response_incomplete",
                """{"choices":[{"finish_reason":"secret-response","message":{"role":"assistant","content":"partial-secret-response"}}]}""" to "response_incomplete",
                """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[]}}]}""" to "invalid_tool_calls",
                """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"","function":{"name":"search","arguments":"{}"}}]}}]}""" to "invalid_tool_calls",
                """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","tool_calls":[{"id":"call-1","function":{"name":"search","arguments":"not-json"}}]}}]}""" to "invalid_tool_calls",
            )
            val client = DeepSeekClient("secret-key", "http://127.0.0.1:${server.address.port}/chat")
            val logs = mutableListOf<String>()
            for ((body, category) in cases) {
                responseBody.set(body)
                val before = attempts.get()
                val result = withContext(AgentTrace("response-check", sink = logs::add)) {
                    client.chat(ChatRequest("test", emptyList()))
                }
                val failure = assertIs<ApiCallException>(result.exceptionOrNull())
                assertEquals(category, failure.category, body)
                assertEquals(200, failure.statusCode)
                assertNull(failure.cause)
                assertFalse("secret-response" in failure.toString())
                assertEquals(before + 1, attempts.get())
            }
            val text = logs.joinToString("\n")
            assertFalse("secret-response" in text)
            assertTrue(logs.any { "category=response_incomplete" in it && "finish_reason=length" in it })
            assertTrue(logs.any { "category=response_incomplete" in it && "finish_reason=other" in it })
        } finally { server.stop(0) }
    }

    @Test fun `blank keepalive padding and reasoning field preserve final answer and usage logs`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            exchange.requestBody.readBytes()
            val bytes = ("\n \n" + """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","reasoning_content":"secret-reasoning","content":"secret-answer"}}],"usage":{"prompt_tokens":10,"completion_tokens":20,"total_tokens":30}}""").toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val logs = mutableListOf<String>()
            val response = withContext(AgentTrace("padding-check", sink = logs::add)) {
                DeepSeekClient("secret-key", "http://127.0.0.1:${server.address.port}/chat")
                    .chat(ChatRequest("deepseek-flash", listOf(ChatMessage("user", "secret-prompt")), ThinkingConfig("enabled"))).getOrThrow()
            }
            assertEquals("secret-answer", response.choices.first().message!!.content)
            assertEquals(30, response.usage!!.totalTokens)
            assertTrue(logs.all { "trace_id=padding-check" in it && "event=agent_ai" in it })
            assertTrue(logs.any { "status=start" in it && "model=deepseek-flash" in it && "thinking=enabled" in it && "timeout_seconds=120" in it })
            assertTrue(logs.any { "status=success" in it && "finish_reason=stop" in it && "prompt_tokens=10" in it && "completion_tokens=20" in it && "total_tokens=30" in it })
            val text = logs.joinToString("\n")
            listOf("secret-key", "secret-prompt", "secret-reasoning", "secret-answer").forEach { assertFalse(it in text) }
        } finally { server.stop(0) }
    }

    @Test fun `valid tool calls allow null content`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/chat") { exchange ->
            exchange.requestBody.readBytes()
            val bytes = """{"choices":[{"finish_reason":"tool_calls","message":{"role":"assistant","content":null,"tool_calls":[{"id":"call-1","type":"function","function":{"name":"search","arguments":"{}"}}]}}]}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val response = DeepSeekClient("test", "http://127.0.0.1:${server.address.port}/chat")
                .chat(ChatRequest("test", emptyList())).getOrThrow()
            assertNull(response.choices.first().message!!.content)
            assertEquals("call-1", response.choices.first().message!!.toolCalls!!.single().id)
        } finally { server.stop(0) }
    }

    @Test fun `server retry limit is configurable and per-call override wins`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val attempts = AtomicInteger()
        server.createContext("/chat") { exchange ->
            attempts.incrementAndGet()
            exchange.requestBody.readBytes()
            exchange.sendResponseHeaders(503, -1)
            exchange.close()
        }
        server.start()
        try {
            val client = DeepSeekClient("test", "http://127.0.0.1:${server.address.port}/chat", maxRetries = 1)
            val logs = mutableListOf<String>()
            val result = withContext(AgentTrace("retry-check", sink = logs::add)) { client.chat(ChatRequest("test", emptyList())) }
            assertEquals("server_error", assertIs<ApiCallException>(result.exceptionOrNull()).category)
            assertEquals(2, attempts.get())
            assertTrue(logs.any { "status=retry" in it && "attempt=2" in it && "total_attempts=2" in it && "http_status=503" in it })
            client.chat(ChatRequest("test", emptyList()), retries = 0)
            assertEquals(3, attempts.get())
        } finally { server.stop(0) }
    }

    @Test fun `timeout includes a stalled body after headers and can retry`() = runBlocking<Unit> {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newCachedThreadPool()
        val release = java.util.concurrent.CountDownLatch(1)
        val attempts = AtomicInteger()
        server.executor = executor
        server.createContext("/chat") { exchange ->
            exchange.requestBody.readBytes()
            if (attempts.incrementAndGet() == 1) {
                exchange.sendResponseHeaders(200, 0)
                try {
                    exchange.responseBody.write(" \n".toByteArray())
                    exchange.responseBody.flush()
                    release.await()
                } finally { exchange.close() }
            } else {
                val bytes = validAnswer.toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
        }
        server.start()
        try {
            val logs = mutableListOf<String>()
            val result = withTimeout(6000) {
                withContext(AgentTrace("timeout-check", sink = logs::add)) {
                    DeepSeekClient("test", "http://127.0.0.1:${server.address.port}/chat", requestTimeoutSeconds = 1, maxRetries = 1)
                        .chat(ChatRequest("test", emptyList()))
                }
            }
            assertTrue(result.isSuccess)
            assertEquals(2, attempts.get())
            assertTrue(logs.any { "category=network_timeout" in it && "timeout_seconds=1" in it && "status=failed" in it })
            assertTrue(logs.any { "status=success" in it && "attempt=2" in it })
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }

    @Test fun `invalid URL and model log labels never expose credentials`() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        val secret = "very-secret-test-key"
        for ((url, category) in listOf(" " to "missing_api_url", "invalid:$secret" to "invalid_api_url", "file:///secret" to "invalid_api_url")) {
            val result = withContext(AgentTrace("config-check", sink = logs::add)) {
                DeepSeekClient(secret, url).chat(ChatRequest(secret, emptyList()))
            }
            val failure = assertIs<ApiCallException>(result.exceptionOrNull())
            assertEquals(category, failure.category)
            assertFalse(secret in failure.toString())
        }
        assertFalse(secret in logs.joinToString("\n"))
        assertEquals("custom", safeModelLogLabel("deepseek-flash\nAuthorization=$secret", "other-key"))
        assertEquals("custom", safeModelLogLabel("https://api.example/chat?key=$secret", "other-key"))
        assertEquals("redacted", safeModelLogLabel("prefix-$secret", secret))
        assertEquals("deepseek-flash", safeModelLogLabel("deepseek-flash", secret))
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
            val logs = java.util.concurrent.CopyOnWriteArrayList<String>()
            val task = launch(AgentTrace("cancel-check", sink = logs::add)) { client.chat(ChatRequest("test", emptyList())) }
            withTimeout(5000) { entered.await() }
            withTimeout(3000) { task.cancelAndJoin() }
            assertTrue(task.isCancelled)
            assertEquals(1, attempts.get())
            assertTrue(logs.any { "status=cancelled" in it && "trace_id=cancel-check" in it })
            assertFalse(logs.any { "status=retry" in it || "status=failed" in it })
        } finally { release.countDown(); server.stop(0); executor.shutdownNow() }
    }
}
