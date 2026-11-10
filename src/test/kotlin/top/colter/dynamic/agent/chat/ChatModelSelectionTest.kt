package top.colter.dynamic.agent.chat

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.agent.ResearchBackend
import top.colter.dynamic.agent.agent.SearchAgent
import top.colter.dynamic.agent.agent.WebPageResponse
import top.colter.dynamic.agent.agent.WebSearchResponse
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ApiConfig
import top.colter.dynamic.agent.ds.*
import top.colter.dynamic.agent.util.HttpUtils
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class ChatModelSelectionTest {
    @Test fun `pro selection covers search followup and preserves shared memory without changing default`() = runBlocking {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        val searches = CopyOnWriteArrayList<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chat") { exchange ->
                requests += HttpUtils.json.decodeFromString(ChatRequest.serializer(), exchange.requestBody.readBytes().toString(Charsets.UTF_8))
                val message = if (requests.size == 2) ChatMessage("assistant", toolCalls = listOf(
                    ToolCall("search-1", function = FunctionCall("web_search", """{"query":"测试问题"}""")),
                )) else ChatMessage("assistant", "回答${requests.size}")
                val response = ChatResponse(choices = listOf(ChatChoice(if (requests.size == 2) "tool_calls" else "stop", message)))
                val bytes = HttpUtils.json.encodeToString(ChatResponse.serializer(), response).toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        try {
            val config = AgentPluginConfig(api = ApiConfig(model = "custom-default"))
            val sessions = SessionManager(config.chat)
            val backend = object : ResearchBackend {
                override suspend fun search(query: String, originalQuestion: String): WebSearchResponse {
                    searches += query
                    return WebSearchResponse(emptyList())
                }
                override suspend fun fetch(url: String): WebPageResponse = error("Unexpected fetch")
            }
            val client = DeepSeekClient("local-test", "http://127.0.0.1:${server.address.port}/chat", maxRetries = 0)
            val service = ChatService(config, client, sessions) {
                SearchAgent(it.webSearch, apiKey = "local-test", backend = backend)
            }
            val key = SessionManager.SessionKey("group", "user")
            assertEquals("回答1", service.chat("普通问题", key).content)
            assertEquals("回答3", service.chat("联网追问", key, PRO_CHAT_MODEL).content)
            assertEquals("回答4", service.chat("继续", key).content)
            assertEquals(listOf("custom-default", "deepseek-v4-pro", "deepseek-v4-pro", "custom-default"), requests.map { it.model })
            assertEquals(listOf("测试问题"), searches.toList())
            assertTrue(requests[1].tools.orEmpty().any { it.function.name == "web_search" })
            assertEquals("search-1", requests[2].messages.single { it.role == "tool" }.toolCallId)
            assertEquals(listOf("普通问题", "回答1", "联网追问", "回答3", "继续"),
                requests.last().messages.filter { it.role != "system" }.map { it.content })
        } finally {
            server.stop(0)
        }
    }
}
