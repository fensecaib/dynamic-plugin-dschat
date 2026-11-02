package top.colter.dynamic.agent.chat

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.agent.ResearchBackend
import top.colter.dynamic.agent.agent.SearchAgent
import top.colter.dynamic.agent.agent.WebPageResponse
import top.colter.dynamic.agent.agent.WebSearchResponse
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ChatConfig
import top.colter.dynamic.agent.config.WebSearchConfig
import top.colter.dynamic.agent.ds.*
import top.colter.dynamic.agent.util.HttpUtils
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

class ChatServiceUrlTest {
    private val key = SessionManager.SessionKey("group", "user")

    @Test fun `URL message reaches model and memory unchanged without prefetch or failure instruction`() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        val pageReads = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/page") { exchange ->
                pageReads.incrementAndGet()
                exchange.respond("page content", "text/plain")
            }
            createContext("/chat") { exchange ->
                requests.add(HttpUtils.json.decodeFromString(ChatRequest.serializer(), exchange.requestBody.readBytes().toString(Charsets.UTF_8)))
                exchange.respond(answer("收到链接"))
            }
            start()
        }
        try {
            val config = AgentPluginConfig(chat = ChatConfig(systemPrompt = "system"), webSearch = WebSearchConfig(enabled = true))
            val sessions = SessionManager(config.chat)
            val client = DeepSeekClient("local-test", "http://127.0.0.1:${server.address.port}/chat", maxRetries = 0)
            val service = ChatService(config, client, sessions)
            val url = "http://127.0.0.1:${server.address.port}/page"
            val prompt = "阅读 [$url]($url) 并解释。"

            assertEquals("收到链接", service.chat(prompt, key).content)

            assertEquals(0, pageReads.get())
            assertEquals(1, requests.size)
            assertEquals(prompt, requests.single().messages.single { it.role == "user" }.content)
            assertEquals(listOf("current_time", "web_search", "web_fetch"), requests.single().tools?.map { it.function.name })
            assertEquals(listOf("system", prompt, "收到链接", "next"), sessions.buildMessages(key, "next", "system").map { it.content })
        } finally {
            server.stop(0)
        }
    }

    @Test fun `URL tool reads page once and returns receipt without storing page body in memory`() = runBlocking<Unit> {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        val events = CopyOnWriteArrayList<String>()
        val marker = "本页独有的正文资料"
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val url = "http://127.0.0.1:${server.address.port}/page"
        server.apply {
            createContext("/chat") { exchange ->
                events.add("model")
                requests.add(HttpUtils.json.decodeFromString(ChatRequest.serializer(), exchange.requestBody.readBytes().toString(Charsets.UTF_8)))
                val response = if (requests.size == 1) {
                    ChatResponse(choices = listOf(ChatChoice("tool_calls", ChatMessage("assistant", toolCalls = listOf(
                        ToolCall("page-call", function = FunctionCall("web_fetch", """{"url":"$url"}""")),
                    )))))
                } else {
                    ChatResponse(choices = listOf(ChatChoice("stop", ChatMessage("assistant", "依据正文回答"))))
                }
                exchange.respond(HttpUtils.json.encodeToString(ChatResponse.serializer(), response))
            }
            start()
        }
        try {
            val config = AgentPluginConfig(chat = ChatConfig(systemPrompt = "system"), webSearch = WebSearchConfig(enabled = true))
            val sessions = SessionManager(config.chat)
            val client = DeepSeekClient("local-test", "http://127.0.0.1:${server.address.port}/chat", maxRetries = 0)
            val backend = object : ResearchBackend {
                override suspend fun search(query: String, originalQuestion: String): WebSearchResponse =
                    error("URL reading does not require a search")

                override suspend fun fetch(url: String): WebPageResponse {
                    assertEquals("http://127.0.0.1:${server.address.port}/page", url)
                    events.add("page")
                    return WebPageResponse("测试页面", marker)
                }
            }
            val service = ChatService(config, client, sessions) {
                SearchAgent(it.webSearch, apiKey = "local-web-key", backend = backend)
            }
            val prompt = "阅读并总结 $url"

            assertEquals("依据正文回答", service.chat(prompt, key).content)

            assertEquals(listOf("model", "page", "model"), events.toList())
            assertEquals(2, requests.size)
            assertEquals(prompt, requests.first().messages.single { it.role == "user" }.content)
            val tool = requests.last().messages.single { it.role == "tool" }
            assertEquals("page-call", tool.toolCallId)
            assertEquals("web_fetch", tool.name)
            assertContains(tool.content.orEmpty(), marker)
            assertContains(tool.content.orEmpty(), "来源: $url")
            assertEquals(listOf("system", prompt, "依据正文回答", "next"), sessions.buildMessages(key, "next", "system").map { it.content })
        } finally {
            server.stop(0)
        }
    }

    private fun answer(content: String) = HttpUtils.json.encodeToString(ChatResponse.serializer(),
        ChatResponse(choices = listOf(ChatChoice("stop", ChatMessage("assistant", content)))))

    private fun HttpExchange.respond(body: String, contentType: String = "application/json; charset=utf-8") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        responseHeaders.set("Content-Type", contentType)
        sendResponseHeaders(200, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
        close()
    }
}
