package top.colter.dynamic.agent.chat

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ChatConfig
import top.colter.dynamic.agent.config.WebSearchConfig
import top.colter.dynamic.agent.ds.ChatChoice
import top.colter.dynamic.agent.ds.ChatMessage
import top.colter.dynamic.agent.ds.ChatRequest
import top.colter.dynamic.agent.ds.ChatResponse
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.util.HttpUtils
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals

class ChatMemoryPolicyTest {
    private val key = SessionManager.SessionKey("group", "user")

    @Test fun `existing service follows newly enabled memory policy`() = runBlocking<Unit> {
        Endpoint { request -> "reply:${request.messages.last().content}" }.use { endpoint ->
            val config = AgentPluginConfig(
                chat = ChatConfig(systemPrompt = "system", enableMemory = false),
                webSearch = WebSearchConfig(enabled = false),
            )
            val sessions = SessionManager(config.chat)
            val service = ChatService(config, endpoint.client(), sessions)

            sessions.updateConfig(config.chat.copy(enableMemory = true))
            assertEquals("reply:first", service.chat("first", key).content)
            assertEquals("reply:second", service.chat("second", key).content)

            assertEquals(2, endpoint.requests.size)
            assertEquals(listOf("system", "first"), endpoint.requests.first().messages.map { it.content })
            assertEquals(listOf("system", "first", "reply:first", "second"),
                endpoint.requests.last().messages.map { it.content })
            assertEquals(listOf("system", "first", "reply:first", "second", "reply:second", "next"),
                sessions.buildMessages(key, "next", "system").map { it.content })
        }
    }

    @Test fun `memory disable and reenable invalidate an old in flight service request`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        Endpoint { request ->
            val prompt = request.messages.last().content
            if (prompt == "old question") {
                entered.complete(Unit)
                release.await()
            }
            "reply:$prompt"
        }.use { endpoint ->
            try {
                val config = AgentPluginConfig(
                    chat = ChatConfig(systemPrompt = "system"),
                    webSearch = WebSearchConfig(enabled = false),
                )
                val sessions = SessionManager(config.chat)
                val service = ChatService(config, endpoint.client(), sessions)
                coroutineScope {
                    val old = async { service.chat("old question", key) }
                    try {
                        withTimeout(10_000) { entered.await() }
                        sessions.updateConfig(config.chat.copy(enableMemory = false))
                        sessions.updateConfig(config.chat)

                        assertEquals("reply:new question", service.chat("new question", key).content)
                        release.countDown()
                        assertEquals("reply:old question", withTimeout(10_000) { old.await() }.content)

                        val newRequest = endpoint.requests.single { it.messages.last().content == "new question" }
                        assertEquals(listOf("system", "new question"), newRequest.messages.map { it.content })
                        assertEquals(listOf("system", "new question", "reply:new question", "next"),
                            sessions.buildMessages(key, "next", "system").map { it.content })
                    } finally {
                        release.countDown()
                        old.cancelAndJoin()
                    }
                }
            } finally {
                release.countDown()
            }
        }
    }

    private class Endpoint(answerFor: (ChatRequest) -> String) : AutoCloseable {
        val requests = CopyOnWriteArrayList<ChatRequest>()
        private val executor = Executors.newCachedThreadPool()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = this@Endpoint.executor
            createContext("/chat") { exchange ->
                val request = HttpUtils.json.decodeFromString(
                    ChatRequest.serializer(), exchange.requestBody.readBytes().toString(Charsets.UTF_8),
                )
                requests.add(request)
                val response = ChatResponse(choices = listOf(ChatChoice("stop", ChatMessage("assistant", answerFor(request)))))
                val bytes = HttpUtils.json.encodeToString(ChatResponse.serializer(), response).toByteArray(Charsets.UTF_8)
                exchange.responseHeaders.set("Content-Type", "application/json; charset=utf-8")
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }

        fun client() = DeepSeekClient("local-test", "http://127.0.0.1:${server.address.port}/chat", maxRetries = 0)

        override fun close() {
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
