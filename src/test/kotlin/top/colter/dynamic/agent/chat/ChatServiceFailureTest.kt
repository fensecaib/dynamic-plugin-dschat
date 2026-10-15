package top.colter.dynamic.agent.chat

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ApiConfig
import top.colter.dynamic.agent.config.ChatConfig
import top.colter.dynamic.agent.config.WebSearchConfig
import top.colter.dynamic.agent.ds.ApiCallException
import top.colter.dynamic.agent.ds.ChatMessage
import top.colter.dynamic.agent.ds.DeepSeekClient
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatServiceFailureTest {
    @Test fun `service failure preserves prior memory and successful recovery saves only the real answer`() = runBlocking<Unit> {
        val status = AtomicInteger(503)
        val requests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/chat") { exchange ->
                requests.incrementAndGet()
                exchange.requestBody.readBytes()
                val body = if (status.get() == 503) "raw-error secret-key" else
                    """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"恢复后的真实回答"}}]}"""
                val bytes = body.toByteArray(Charsets.UTF_8)
                exchange.sendResponseHeaders(status.get(), bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        try {
            val config = AgentPluginConfig(
                api = ApiConfig(apiKey = "local-test", apiUrl = "http://127.0.0.1:${server.address.port}/chat", maxRetries = 0),
                chat = ChatConfig(systemPrompt = "system", enableMemory = true),
                webSearch = WebSearchConfig(enabled = false),
            )
            val sessions = SessionManager(config.chat)
            val key = SessionManager.SessionKey("group", "user")
            sessions.buildMessages(key, "之前的问题", config.chat.systemPrompt)
            sessions.saveToSession(key, "之前的问题", "之前的回答")
            val client = DeepSeekClient(config.api.apiKey, config.api.apiUrl, maxRetries = 0)
            val service = ChatService(config, client, sessions)

            val failure = assertFailsWith<ApiCallException> { service.chat("失败请求的问题", key) }
            assertEquals("server_error", failure.category)
            assertEquals(1, requests.get())
            assertEquals(listOf(
                ChatMessage("system", "system"), ChatMessage("user", "之前的问题"),
                ChatMessage("assistant", "之前的回答"), ChatMessage("user", "检查记忆"),
            ), sessions.buildMessages(key, "检查记忆", "system"))

            status.set(200)
            assertEquals("恢复后的真实回答", service.chat("恢复后的问题", key).content)
            assertEquals(2, requests.get())
            assertEquals(listOf(
                ChatMessage("system", "system"), ChatMessage("user", "之前的问题"),
                ChatMessage("assistant", "之前的回答"), ChatMessage("user", "恢复后的问题"),
                ChatMessage("assistant", "恢复后的真实回答"), ChatMessage("user", "再次检查记忆"),
            ), sessions.buildMessages(key, "再次检查记忆", "system"))
        } finally {
            server.stop(0)
        }
    }
}
