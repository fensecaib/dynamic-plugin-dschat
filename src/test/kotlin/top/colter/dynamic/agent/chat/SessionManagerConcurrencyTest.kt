package top.colter.dynamic.agent.chat

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import top.colter.dynamic.agent.config.*
import top.colter.dynamic.agent.ds.DeepSeekClient
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import kotlin.test.*

class SessionManagerConcurrencyTest {
    private val key = SessionManager.SessionKey("group", "user")

    @Test fun `simultaneous turns retain complete user assistant pairs`() = runBlocking<Unit> {
        val sessions = SessionManager(ChatConfig(memoryRounds = 1000, maxContextTokens = 1_000_000))
        coroutineScope {
            (0 until 16).map { worker -> async(Dispatchers.Default) {
                repeat(40) { index ->
                    val prompt = "$worker-$index"
                    val turn = sessions.beginTurn(key, prompt, "system")
                    assertEquals("user", turn.messages.last().role)
                    yield()
                    sessions.saveTurn(key, turn, prompt, "reply:$prompt")
                }
            } }.awaitAll()
        }
        val history = sessions.buildMessages(key, "next", "system").drop(1).dropLast(1)
        assertEquals(16 * 40 * 2, history.size)
        history.chunked(2).forEach { pair ->
            assertEquals(listOf("user", "assistant"), pair.map { it.role })
            assertEquals("reply:${pair[0].content}", pair[1].content)
        }
    }

    @Test fun `late model reply cannot repopulate conversation after clear`() = runBlocking<Unit> {
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val executor = Executors.newCachedThreadPool()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/chat") { exchange ->
                val old = exchange.requestBody.readBytes().toString(Charsets.UTF_8).contains("旧问题")
                if (old) { entered.complete(Unit); release.await() }
                val answer = if (old) "旧回答" else "新回答"
                val bytes = """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"$answer"}}]}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        try {
            val config = AgentPluginConfig(webSearch = WebSearchConfig(enabled = false))
            val sessions = SessionManager(config.chat)
            val client = DeepSeekClient("test", "http://127.0.0.1:${server.address.port}/chat", maxRetries = 0)
            val service = ChatService(config, client, sessions)
            coroutineScope {
                val old = async { service.chat("旧问题", key) }
                try {
                    withTimeout(5000) { entered.await() }
                    sessions.clear(key)
                    assertEquals("新回答", service.chat("新问题", key).content)
                    release.countDown()
                    assertEquals("旧回答", withTimeout(5000) { old.await() }.content)
                    val history = sessions.buildMessages(key, "next", "system")
                    assertEquals(listOf("system", "新问题", "新回答", "next"), history.map { it.content })
                } finally {
                    release.countDown()
                    old.cancelAndJoin()
                }
            }
        } finally {
            release.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }

    @Test fun `round and token trimming remove complete pairs`() {
        for (config in listOf(ChatConfig(memoryRounds = 2), ChatConfig(memoryRounds = 10, maxContextTokens = 12))) {
            val sessions = SessionManager(config)
            repeat(3) { n ->
                sessions.buildMessages(key, "u$n", "system")
                sessions.saveToSession(key, "u$n", "a$n")
            }
            assertEquals(listOf("system", "u1", "a1", "u2", "a2", "next"),
                sessions.buildMessages(key, "next", "system").map { it.content })
        }
    }

    @Test fun `expired conversation does not accept an older turn`() {
        var time = 0L
        val sessions = SessionManager(ChatConfig(memoryTtlMinutes = 1), now = { time })
        val old = sessions.beginTurn(key, "old", "system")
        time = 120_000L
        val fresh = sessions.beginTurn(key, "new", "system")
        sessions.saveTurn(key, old, "old", "old reply")
        sessions.saveTurn(key, fresh, "new", "new reply")
        assertEquals(listOf("system", "new", "new reply", "next"), sessions.buildMessages(key, "next", "system").map { it.content })
    }
}
