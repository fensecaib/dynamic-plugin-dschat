package top.colter.dynamic.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ApiConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.agent.util.RestartablePluginScope
import top.colter.dynamic.core.config.ConfigService
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.skiko.FontRegistry
import java.lang.reflect.Proxy
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicAgentPluginConfigTest {
    @Test fun `display memory and web search changes reuse the api client`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val original = fixture.field("dsClient")
        val base = plugin.currentConfig()
        val updates = listOf(
            base.copy(chat = base.chat.copy(textReplyThreshold = 600)),
            base.copy(chat = base.chat.copy(enableMemory = false)),
            base.copy(chat = base.chat.copy(memoryRounds = 3, maxContextTokens = 1024)),
            base.copy(webSearch = base.webSearch.copy(enabled = false)),
            base.copy(webSearch = base.webSearch.copy(fallbackEnabled = true)),
            base.copy(webSearch = base.webSearch.copy(officialApiKey = "synthetic-search-key")),
            base.copy(image = base.image.copy(factor = 0.8f)),
        )
        for (next in updates) {
            plugin.applyConfig(next)
            assertSame(original, fixture.field("dsClient"))
            for (name in listOf("chatService", "commandHandlers", "messageListener")) {
                assertSame(original, fixture.serviceClient(name), name)
                assertEquals(next, fixture.serviceConfig(name), name)
            }
        }
    }

    @Test fun `api changes replace the client used by all active services`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val updates = listOf<(ApiConfig) -> ApiConfig>(
            { it.copy(apiKey = "synthetic-api-key") },
            { it.copy(apiUrl = "https://example.invalid/chat/completions") },
            { it.copy(requestTimeoutSeconds = 30) },
            { it.copy(maxRetries = 0) },
            { it.copy(model = "custom-model") },
        )
        for (update in updates) {
            val api = update(plugin.currentConfig().api)
            val previous = fixture.field("dsClient")
            plugin.applyConfig(plugin.currentConfig().copy(api = api))
            val current = fixture.field("dsClient")
            assertNotSame(previous, current)
            for (name in listOf("chatService", "commandHandlers", "messageListener")) {
                assertSame(current, fixture.serviceClient(name), name)
                assertEquals(api, fixture.serviceConfig(name).api, name)
            }
            plugin.applyConfig(plugin.currentConfig())
            assertSame(current, fixture.field("dsClient"))
        }
    }

    @Test fun `live config changes preserve the session manager and retained history`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val original = fixture.sessions()
        val key = SessionManager.SessionKey("group", "player")
        original.buildMessages(key, "previous question", "system")
        original.saveToSession(key, "previous question", "previous answer")

        val base = plugin.currentConfig()
        val updates = listOf(
            base.copy(diagnostics = base.diagnostics.copy(enabled = false)),
            base.copy(image = base.image.copy(drawCacheRetentionHours = 24)),
            base.copy(dota2 = base.dota2.copy(enabled = false)),
            base.copy(api = base.api.copy(model = "custom-model")),
            base.copy(trigger = base.trigger.copy(enableAtTrigger = false)),
            base.copy(trigger = base.trigger.copy(enableAtTrigger = true)),
        )
        for (next in updates) {
            val result = plugin.applyConfig(next)
            assertTrue(result.changed)
            assertFalse(result.restartRequired)
            assertEquals(next.trigger, plugin.currentConfig().trigger)
            assertSame(original, fixture.sessions())
            assertTrue(fixture.sessions().buildMessages(key, "next question", "system").any {
                it.role == "assistant" && it.content == "previous answer"
            })
        }

        val prefixUpdate = plugin.currentConfig().let { it.copy(trigger = it.trigger.copy(triggerPrefix = "/ask")) }
        assertTrue(plugin.applyConfig(prefixUpdate).restartRequired)
        assertSame(original, fixture.sessions())

        val next = plugin.currentConfig().let { it.copy(chat = it.chat.copy(memoryRounds = 3)) }
        assertTrue(plugin.applyConfig(next).restartRequired)
        assertSame(original, fixture.sessions())
        assertTrue(fixture.sessions().buildMessages(key, "next question", "system").any {
            it.role == "assistant" && it.content == "previous answer"
        })
        assertEquals(9, fixture.saves)
    }

    @Test fun `chat presentation and prompt changes preserve conversation history`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val sessions = fixture.sessions()
        val key = SessionManager.SessionKey("group", "player")
        sessions.buildMessages(key, "previous question", "system")
        sessions.saveToSession(key, "previous question", "previous answer")

        val settings = listOf(
            plugin.currentConfig().chat.copy(textReplyThreshold = 600),
            plugin.currentConfig().chat.copy(maxTokens = 2048),
            plugin.currentConfig().chat.copy(systemPrompt = "updated system prompt"),
        )
        for (chat in settings) {
            assertFalse(plugin.applyConfig(plugin.currentConfig().copy(chat = chat)).restartRequired)
            assertSame(sessions, fixture.sessions())
            val messages = sessions.buildMessages(key, "next question", chat.systemPrompt)
            assertEquals(chat.systemPrompt, messages.first().content)
            assertEquals(listOf("previous question", "previous answer"), messages.drop(1).dropLast(1).map { it.content })
            for (name in listOf("chatService", "commandHandlers", "messageListener")) {
                assertEquals(chat, fixture.serviceConfig(name).chat, name)
            }
        }
    }

    @Test fun `reducing memory rounds trims every conversation without replacing the manager`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val sessions = fixture.sessions()
        val keys = listOf(SessionManager.SessionKey("group", "first"), SessionManager.SessionKey("group", "second"))
        for (key in keys) {
            sessions.buildMessages(key, "start", "system")
            for (round in 1..6) sessions.saveToSession(key, "question $round", "answer $round")
        }

        val next = plugin.currentConfig().let { it.copy(chat = it.chat.copy(memoryRounds = 3)) }
        assertFalse(plugin.applyConfig(next).restartRequired)
        assertSame(sessions, fixture.sessions())
        for (key in keys) {
            val history = sessions.buildMessages(key, "next", "system").drop(1).dropLast(1)
            assertEquals((4..6).flatMap { listOf("question $it", "answer $it") }, history.map { it.content })
        }
    }

    @Test fun `reducing context budget trims complete old rounds in place`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val sessions = fixture.sessions()
        val key = SessionManager.SessionKey("group", "player")
        sessions.buildMessages(key, "start", "system")
        val rounds = (1..3).map { "question $it ${"q".repeat(250)}" to "answer $it ${"a".repeat(250)}" }
        for ((question, answer) in rounds) sessions.saveToSession(key, question, answer)

        val next = plugin.currentConfig().let { it.copy(chat = it.chat.copy(maxContextTokens = 1024)) }
        assertFalse(plugin.applyConfig(next).restartRequired)
        assertSame(sessions, fixture.sessions())
        val history = sessions.buildMessages(key, "next", "system").drop(1).dropLast(1)
        assertEquals(listOf(rounds.last().first, rounds.last().second), history.map { it.content })
        assertEquals(listOf("user", "assistant"), history.map { it.role })
    }

    @Test fun `disabling memory clears history permanently including pending turns`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val sessions = fixture.sessions()
        val key = SessionManager.SessionKey("group", "player")
        sessions.buildMessages(key, "previous question", "system")
        sessions.saveToSession(key, "previous question", "previous answer")
        val pending = sessions.beginTurn(key, "pending question", "system")

        val disabled = plugin.currentConfig().let { it.copy(chat = it.chat.copy(enableMemory = false)) }
        plugin.applyConfig(disabled)
        assertSame(sessions, fixture.sessions())
        sessions.saveToSession(key, "disabled question", "disabled answer")
        assertEquals(2, sessions.buildMessages(key, "disabled next", "system").size)

        plugin.applyConfig(disabled.copy(chat = disabled.chat.copy(enableMemory = true)))
        assertSame(sessions, fixture.sessions())
        sessions.buildMessages(key, "new question", "system")
        sessions.saveTurn(key, pending, "pending question", "pending answer")
        assertEquals(2, sessions.buildMessages(key, "next", "system").size)
    }

    @Test fun `failed saves leave the config and active services untouched`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val previous = plugin.currentConfig()
        val serviceFields = listOf("sessionManager", "chatService", "dsClient", "commandHandlers", "messageListener")
        val originalServices = serviceFields.associateWith(fixture::field)
        val key = SessionManager.SessionKey("group", "player")
        fixture.sessions().buildMessages(key, "previous question", "system")
        fixture.sessions().saveToSession(key, "previous question", "previous answer")
        val next = previous.copy(
            api = previous.api.copy(model = "another-model", apiKey = "synthetic-api-key", requestTimeoutSeconds = 30),
            chat = previous.chat.copy(enableMemory = false, memoryRounds = 3),
            trigger = previous.trigger.copy(triggerPrefix = "/ask"),
        )

        fixture.rejectSaves = true
        assertFailsWith<IllegalStateException> { plugin.applyConfig(next) }

        assertSame(previous, plugin.currentConfig())
        for ((name, original) in originalServices) assertSame(original, fixture.field(name), name)
        assertEquals(listOf("previous question", "previous answer"),
            fixture.sessions().buildMessages(key, "next", "system").drop(1).dropLast(1).map { it.content })
        fixture.assertRuntimePrefix("/ds")
        assertEquals(1, fixture.saves)

        fixture.rejectSaves = false
        assertFalse(plugin.applyConfig(previous).restartRequired)
        assertSame(originalServices.getValue("dsClient"), fixture.field("dsClient"))
    }

    @Test fun `saved prefixes stay pending until restart while feature toggles apply immediately`() = withFixture { fixture ->
        val plugin = fixture.plugin
        val originalSessions = fixture.sessions()
        val pending = plugin.currentConfig().let { it.copy(trigger = it.trigger.copy(triggerPrefix = "/ask")) }
        assertTrue(plugin.applyConfig(pending).restartRequired)
        assertEquals("/ask", plugin.currentConfig().trigger.triggerPrefix)
        fixture.assertRuntimePrefix("/ds")

        val disabled = pending.copy(trigger = pending.trigger.copy(enableCommandTrigger = false, enableAtTrigger = false))
        assertTrue(plugin.applyConfig(disabled).restartRequired)
        fixture.assertRuntimePrefix("/ds")
        for (name in listOf("commandHandlers", "messageListener")) {
            assertFalse(fixture.serviceConfig(name).trigger.enableCommandTrigger)
            assertFalse(fixture.serviceConfig(name).trigger.enableAtTrigger)
        }

        val restored = disabled.copy(trigger = disabled.trigger.copy(triggerPrefix = "/ds"))
        assertFalse(plugin.applyConfig(restored).restartRequired)
        fixture.assertRuntimePrefix("/ds")

        assertTrue(plugin.applyConfig(disabled).restartRequired)
        plugin.onStop()
        plugin.onStart()
        fixture.assertRuntimePrefix("/ask")
        assertSame(originalSessions, fixture.sessions())
        val afterRestart = disabled.copy(diagnostics = disabled.diagnostics.copy(enabled = false))
        assertFalse(plugin.applyConfig(afterRestart).restartRequired)
        fixture.assertRuntimePrefix("/ask")
    }

    @Test fun `reserved prefix save preserves active paths until restart and registers unique commands`() = withFixture { fixture ->
        val plugin = fixture.plugin
        plugin.applyConfig(plugin.currentConfig().let { it.copy(trigger = it.trigger.copy(triggerPrefix = "/ask")) })
        plugin.onStop()
        plugin.onStart()
        fixture.assertRuntimePrefix("/ask")
        val sessions = fixture.sessions()
        val result = plugin.applyConfig(plugin.currentConfig().let { it.copy(trigger = it.trigger.copy(triggerPrefix = "/DSPRO")) })
        assertTrue(result.restartRequired)
        assertContains(result.message, "保留入口")
        assertEquals("/ds", plugin.currentConfig().trigger.triggerPrefix)
        fixture.assertRuntimePrefix("/ask")
        plugin.onStop()
        plugin.onStart()
        fixture.assertRuntimePrefix("/ds")
        assertSame(sessions, fixture.sessions())
        val paths = plugin.commandHandlers().flatMap { listOf(it.spec.path) + it.spec.aliases }.map { it.map(String::lowercase) }
        assertEquals(paths.size, paths.distinct().size)
        assertEquals(1, paths.count { it == listOf("dspro") })
        assertFalse(paths.contains(listOf("pro")))
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try {
            block(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        private val directory = Files.createTempDirectory("agent-config-memory-")
        private val parentJob = SupervisorJob()
        private val hostScope = CoroutineScope(parentJob)
        var saves = 0
        var rejectSaves = false
        private val configService = Proxy.newProxyInstance(
            ConfigService::class.java.classLoader, arrayOf(ConfigService::class.java),
        ) { _, method, _ ->
            check(method.name == "save") { "Unexpected config call: ${method.name}" }
            check(!rejectSaves) { "Simulated storage failure" }
            saves++
            Unit
        } as ConfigService
        private val context = PluginContext(
            pluginId = "config-memory-test",
            descriptor = PluginDescriptor(id = "config-memory-test", name = "test", version = "test", mainClass = "test"),
            configService = configService, dataStore = unused(), scope = hostScope,
            taskScheduler = unused(), sourceUpdatePublisher = unused(), sourceStateStore = unused(), subscriptionQueryService = unused(),
            messagePublisher = unused(),
        )
        val plugin = DynamicAgentPlugin()

        init {
            val cache = CacheUtils(directory)
            setField("context", context)
            setField("dataDir", directory)
            setField("config", AgentPluginConfig())
            setField("workScope", RestartablePluginScope(hostScope))
            setField("cacheUtils", cache)
            setField("dota2Service", Dota2Service(directory.toFile(), cache))
            setField("fontRegistry", FontRegistry())
            plugin.applyConfig(AgentPluginConfig())
        }

        fun field(name: String): Any = DynamicAgentPlugin::class.java.getDeclaredField(name).apply {
            isAccessible = true
        }.get(plugin)

        private fun setField(name: String, value: Any) = DynamicAgentPlugin::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(plugin, value)
        }

        fun sessions() = field("sessionManager") as SessionManager

        fun serviceConfig(name: String): AgentPluginConfig = field(name).let { service ->
            service.javaClass.getDeclaredField("config").apply { isAccessible = true }.get(service) as AgentPluginConfig
        }

        fun serviceClient(name: String): Any = field(name).let { service ->
            service.javaClass.getDeclaredField("dsClient").apply { isAccessible = true }.get(service)
        }

        fun assertRuntimePrefix(prefix: String) {
            assertEquals(listOf(prefix.removePrefix("/")), plugin.commandHandlers().first().spec.path)
            assertEquals(prefix, serviceConfig("commandHandlers").trigger.triggerPrefix)
            assertEquals(prefix, serviceConfig("messageListener").trigger.triggerPrefix)
        }

        suspend fun close() {
            plugin.onStop()
            parentJob.cancelAndJoin()
            Files.delete(directory)
        }
    }

    companion object {
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unused host service was called: ${T::class.simpleName}.${method.name}")
        } as T
    }
}
