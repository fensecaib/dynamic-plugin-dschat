package top.colter.dynamic.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DynamicAgentPluginConfigTest {
    @Test fun `unrelated live config changes preserve memory and chat config changes replace it`() = runBlocking {
        val directory = Files.createTempDirectory("agent-config-memory-")
        val parentJob = SupervisorJob()
        val hostScope = CoroutineScope(parentJob)
        var saves = 0
        val configService = Proxy.newProxyInstance(
            ConfigService::class.java.classLoader, arrayOf(ConfigService::class.java),
        ) { _, method, _ ->
            check(method.name == "save") { "Unexpected config call: ${method.name}" }
            saves++
            Unit
        } as ConfigService
        val context = PluginContext(
            pluginId = "config-memory-test",
            descriptor = PluginDescriptor(id = "config-memory-test", name = "test", version = "test", mainClass = "test", apiVersion = "3.0.0"),
            configService = configService, dataStore = unused(), scope = hostScope,
            taskScheduler = unused(), sourceUpdatePublisher = unused(), sourceStateStore = unused(), subscriptionQueryService = unused(),
            messagePublisher = unused(),
        )
        val plugin = DynamicAgentPlugin()
        val cache = CacheUtils(directory)
        fun field(name: String, value: Any) = DynamicAgentPlugin::class.java.getDeclaredField(name).apply {
            isAccessible = true
            set(plugin, value)
        }
        fun sessions() = DynamicAgentPlugin::class.java.getDeclaredField("sessionManager").apply {
            isAccessible = true
        }.get(plugin) as SessionManager
        field("context", context)
        field("dataDir", directory)
        field("config", AgentPluginConfig())
        field("workScope", RestartablePluginScope(hostScope))
        field("cacheUtils", cache)
        field("dota2Service", Dota2Service(directory.toFile(), cache))
        field("fontRegistry", FontRegistry())
        try {
            plugin.applyConfig(AgentPluginConfig())
            val original = sessions()
            val key = SessionManager.SessionKey("group", "player")
            original.buildMessages(key, "previous question", "system")
            original.saveToSession(key, "previous question", "previous answer")

            val base = plugin.currentConfig()
            val updates = listOf(
                base.copy(diagnostics = base.diagnostics.copy(enabled = false)),
                base.copy(image = base.image.copy(drawCacheRetentionHours = 24)),
                base.copy(dota2 = base.dota2.copy(enabled = false)),
                base.copy(api = base.api.copy(model = "custom-model")),
            )
            for (next in updates) {
                assertTrue(plugin.applyConfig(next).changed)
                assertSame(original, sessions())
                assertTrue(sessions().buildMessages(key, "next question", "system").any {
                    it.role == "assistant" && it.content == "previous answer"
                })
            }

            val next = plugin.currentConfig().let { it.copy(chat = it.chat.copy(memoryRounds = 3)) }
            plugin.applyConfig(next)
            assertNotSame(original, sessions())
            assertFalse(sessions().buildMessages(key, "next question", "system").any { it.role == "assistant" })
            assertEquals(6, saves)
        } finally {
            plugin.onStop()
            parentJob.cancelAndJoin()
            Files.delete(directory)
        }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unused host service was called: ${T::class.simpleName}.${method.name}")
    } as T
}
