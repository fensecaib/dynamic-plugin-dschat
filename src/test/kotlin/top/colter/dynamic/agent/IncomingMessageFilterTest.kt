package top.colter.dynamic.agent

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ApiConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.listener.MessageListener
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.data.CommandContext
import top.colter.dynamic.core.data.IncomingMessage
import top.colter.dynamic.core.data.PlatformId
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.TargetKind
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginDescriptor
import top.colter.skiko.FontRegistry
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IncomingMessageFilterTest {
    private val target = TargetAddress.of("qq", TargetKind.GROUP, "group")

    @Test fun `links and commands reach listener for selective mention admission`() {
        val filter = DynamicAgentPlugin().incomingMessageFilter
        assertTrue(filter.matches(incoming(IncomingMessageIntent.LinkText)))
        assertTrue(filter.matches(incoming(IncomingMessageIntent.PlainText)))
        assertTrue(filter.matches(incoming(IncomingMessageIntent.Command, text = "/pro 问题", mention = true)))
    }

    @Test fun `only mentioned links start chat after passing the real plugin filter`() = runBlocking {
        // Observe admission without running a model request, rendering or filesystem work.
        val dispatcher = RecordingDispatcher()
        val job = SupervisorJob()
        try {
            val config = AgentPluginConfig(api = ApiConfig(apiKey = "local-test"))
            val sessions = SessionManager(config.chat)
            val client = DeepSeekClient(config.api.apiKey, config.api.apiUrl)
            val unusedDir = Path.of("build", "unused-filter-test")
            val cache = CacheUtils(unusedDir)
            val context = PluginContext(
                pluginId = "filter-test",
                descriptor = PluginDescriptor(id = "filter-test", name = "test", version = "test", mainClass = "test", apiVersion = "4.0.0"),
                configService = unused(), dataStore = unused(), scope = CoroutineScope(job + dispatcher),
                taskScheduler = unused(), sourceUpdatePublisher = unused(), sourceStateStore = unused(),
                subscriptionQueryService = unused(), messagePublisher = unused(),
            )
            val listener = MessageListener(config, ChatService(config, client, sessions), sessions,
                Dota2Service(unusedDir.toFile(), cache), client, config.image, context, cache, FontRegistry())
            val filter = DynamicAgentPlugin().incomingMessageFilter
            suspend fun dispatch(message: IncomingMessageDispatchContext) {
                if (filter.matches(message)) listener.handle(message)
            }

            dispatch(incoming(IncomingMessageIntent.LinkText, mention = false))
            assertEquals(0, dispatcher.launches, "An unmentioned link must not start AI chat")
            dispatch(incoming(IncomingMessageIntent.Command, text = "/ds https://example.com", mention = true))
            assertEquals(0, dispatcher.launches, "Commands are handled by the registered host handler")
            dispatch(incoming(IncomingMessageIntent.LinkText, mention = true))
            assertEquals(1, dispatcher.launches, "A mentioned link must reach chat admission")
            dispatch(incoming(IncomingMessageIntent.Command, text = "/pro 问题"))
            dispatch(incoming(IncomingMessageIntent.Command, text = "/dota 帮助", mention = true))
            dispatch(incoming(IncomingMessageIntent.Command, text = "/dspro 问题", mention = true))
            dispatch(incoming(IncomingMessageIntent.Command, text = "/proextra 问题", mention = true))
            assertEquals(1, dispatcher.launches, "Other commands and unmentioned /pro must not start chat")
            dispatch(incoming(IncomingMessageIntent.Command, text = "/pro 问题", mention = true))
            assertEquals(2, dispatcher.launches, "Mentioned /pro must work even when classified as Command")
        } finally {
            job.cancel()
        }
    }

    private fun incoming(intent: IncomingMessageIntent, mention: Boolean = false, text: String = "请总结 https://example.com/article"): IncomingMessageDispatchContext {
        val message = IncomingMessage(platformId = PlatformId.of("qq"), target = target, senderId = "user",
            botAccountId = "bot", text = text, mentions = if (mention) setOf("bot") else emptySet())
        return IncomingMessageDispatchContext(message = message, commandContext = CommandContext(target, "user", "bot", message.mentions),
            rawText = text, intent = intent)
    }

    private class RecordingDispatcher : CoroutineDispatcher() {
        var launches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) { launches++ }
    }

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unexpected host call: ${T::class.simpleName}.${method.name}")
    } as T
}
