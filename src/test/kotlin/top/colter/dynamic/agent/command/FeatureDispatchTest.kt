package top.colter.dynamic.agent.command

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.*
import top.colter.dynamic.agent.LiveCommandHandler
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.*
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.dota2.dotaCommandHelp
import top.colter.dynamic.agent.dota2.dotaShortcuts
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.listener.MessageListener
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.data.*
import top.colter.dynamic.core.plugin.*
import top.colter.skiko.FontRegistry
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.lang.reflect.Proxy
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class FeatureDispatchTest {
    private val analysisCommands = listOf("战报", "深度战报", "分析", "个人详情", "深度个人详情")

    @Test fun `host command entry rejects paused features before any background work or requests`() = withFixture { f ->
        val paused = f.config.copy(chat = ChatConfig(enabled = false), dota2 = Dota2Config(enabled = false))
        val handlers = f.handlers(paused)
        assertEquals(listOf("ds", "grok", "dota", "dota绑定", "dota历史", "dota战报", "dota深报", "dota分析", "dota详情", "dota深详", "dota帮助"),
            handlers.map { it.spec.path.single() })
        for ((name, args) in listOf("ds" to listOf("https://example.com"), "grok" to listOf("问题"), "dota" to listOf("绑定", "176496411"))) {
            assertContains(f.command(handlers, name, args).rejectedText(), "暂停")
        }
        val listener = f.listener(paused)
        for (text in listOf("/ds https://example.com", "/grok 问题", "/dota 绑定 176496411")) {
            f.sent.clear()
            listener.handle(f.incoming(text))
            assertContains(f.sent.single(), "暂停")
        }
        f.sent.clear()
        listener.handle(f.incoming("https://example.com", mention = true))
        assertTrue(f.sent.isEmpty())
        f.assertNoWork()
    }

    @Test fun `missing DeepSeek blocks all analysis commands before binding checks and report slots`() = withFixture { f ->
        val handlers = f.handlers(f.config)
        assertContains(f.command(handlers, "ds", listOf("https://example.com")).rejectedText(), "DeepSeek API Key")
        // Occupying the shared slot proves configuration is checked before the busy response too.
        f.dota.reportTasks.runIfIdle(onBusy = { fail("unexpected occupied slot") }) {
            for (command in analysisCommands) {
                val result = f.command(handlers, "dota", listOf(command, "9044880093"))
                assertContains(result.rejectedText(), "DeepSeek API Key", message = command)
            }
        }
        assertContains(f.command(handlers, "grok", listOf("问题")).rejectedText(), "Grok")
        f.assertNoWork()
    }

    @Test fun `listener rejects unavailable commands explicitly and ignores unavailable mentions`() = withFixture { f ->
        val listener = f.listener(f.config)
        listener.handle(f.incoming("/ds https://example.com"))
        assertContains(f.sent.single(), "DeepSeek API Key")
        f.sent.clear()
        listener.handle(f.incoming("/chat clear"))
        assertContains(f.sent.single(), "DeepSeek API Key")
        for (command in analysisCommands) {
            f.sent.clear()
            listener.handle(f.incoming("/dota $command 9044880093"))
            assertContains(f.sent.single(), "DeepSeek API Key", message = command)
        }
        f.sent.clear()
        listener.handle(f.incoming("/d2 战报 9044880093"))
        assertContains(f.sent.single(), "DeepSeek API Key")
        f.sent.clear()
        listener.handle(f.incoming("https://example.com", mention = true))
        assertTrue(f.sent.isEmpty())
        f.assertNoWork()
    }

    @Test fun `Dota binding and history work with chat paused and no DeepSeek credentials`() = withFixture { f ->
        val config = f.config.copy(chat = ChatConfig(enabled = false))
        val handlers = f.handlers(config)
        assertContains(f.command(handlers, "dota", listOf("绑定", "176496411")).text(), "已绑定")
        assertEquals(176496411L, f.dota.getBinding("user"))
        assertContains(f.command(handlers, "dota", listOf("历史")).text(), "未找到对局记录")
        val listener = f.listener(config)
        listener.handle(f.incoming("/dota 绑定 176496411"))
        listener.handle(f.incoming("/d2 历史"))
        assertEquals(4, f.paths.size)
        assertTrue(f.paths.all { it.startsWith("/opendota/players/") })
        assertEquals(0, f.dispatcher.launches)
        assertEquals(listOf("已绑定: Test Player (ID=176496411)", "未找到对局记录"), f.sent)
    }

    @Test fun `Dota subfeature pauses take precedence even when API is configured`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"), dota2 = Dota2Config(enableBinding = false, enableHistory = false, enableAnalysis = false))
        val handlers = f.handlers(config)
        val listener = f.listener(config)
        for (command in listOf("绑定", "历史") + analysisCommands) {
            assertContains(f.command(handlers, "dota", listOf(command, "176496411")).rejectedText(), "暂停", message = command)
            f.sent.clear()
            listener.handle(f.incoming("/dota $command 176496411"))
            assertContains(f.sent.single(), "暂停", message = command)
        }
        f.assertNoWork()
    }

    @Test fun `shortcut and legacy commands enforce the same feature switches and credentials`() = withFixture { f ->
        val cases = listOf(
            f.config.copy(dota2 = Dota2Config(enabled = false)) to dotaShortcuts,
            f.config.copy(dota2 = Dota2Config(enableBinding = false, enableHistory = false, enableAnalysis = false)) to dotaShortcuts.filter { it.command != "帮助" },
            f.config to dotaShortcuts.filter { it.command in analysisCommands },
        )
        for ((config, shortcuts) in cases) {
            val handlers = f.handlers(config)
            val listener = f.listener(config)
            for (shortcut in shortcuts) {
                val legacy = f.command(handlers, "dota", listOf(shortcut.command, "176496411")).rejectedText()
                assertEquals(legacy, f.command(handlers, shortcut.token, listOf("176496411")).rejectedText(), shortcut.token)
                for (text in listOf("/dota ${shortcut.command} 176496411", "/${shortcut.token} 176496411")) {
                    f.sent.clear()
                    listener.handle(f.incoming(text))
                    assertEquals(listOf(legacy), f.sent, text)
                }
            }
        }
        f.assertNoWork()
    }

    @Test fun `shortcut binding and history keep working without AI and help needs no binding`() = withFixture { f ->
        val handlers = f.handlers(f.config)
        val listener = f.listener(f.config)
        assertEquals(dotaCommandHelp, f.command(handlers, "dota帮助", emptyList()).text())
        assertEquals(dotaCommandHelp, f.command(handlers, "dota", listOf("帮助")).text())
        listener.handle(f.incoming("/dota帮助"))
        assertEquals(listOf(dotaCommandHelp), f.sent)
        f.sent.clear()
        assertTrue(f.paths.isEmpty())
        for ((shortcut, command) in listOf("dota绑定" to "绑定", "dota历史" to "历史")) {
            val args = if (command == "绑定") listOf("176496411") else emptyList()
            val legacy = f.command(handlers, "dota", listOf(command) + args).text()
            assertEquals(legacy, f.command(handlers, shortcut, args).text())
            for (text in listOf("/dota $command ${args.joinToString(" ")}", "/$shortcut ${args.joinToString(" ")}")) {
                f.sent.clear()
                listener.handle(f.incoming(text))
                assertEquals(listOf(legacy), f.sent)
            }
        }
        assertEquals(176496411L, f.dota.getBinding("user"))
        assertEquals(8, f.paths.size)
        assertTrue(f.paths.all { it.startsWith("/opendota/players/") })
        assertEquals(0, f.dispatcher.launches)
    }

    @Test fun `full analysis rejects invalid ids explicitly in both command forms and listener`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"))
        f.dota.setBinding("user", 176496411)
        val handlers = f.handlers(config)
        val listener = f.listener(config)
        for (argument in listOf("abc", "-1", "0")) {
            assertContains(f.command(handlers, "dota", listOf("分析", argument)).rejectedText(), "无效比赛ID")
            assertContains(f.command(handlers, "dota分析", listOf(argument)).rejectedText(), "无效比赛ID")
            for (text in listOf("/dota 分析 $argument", "/dota分析 $argument")) {
                f.sent.clear()
                listener.handle(f.incoming(text))
                assertEquals(listOf("无效比赛ID"), f.sent)
            }
        }
        f.assertNoWork()
    }

    @Test fun `full analysis checks binding then shares occupied report admission in both entry points`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"))
        val handlers = f.handlers(config)
        val listener = f.listener(config)
        f.dota.reportTasks.runIfIdle(onBusy = { fail("unexpected occupied slot") }) {
            assertContains(f.command(handlers, "dota分析", listOf("9044880093")).rejectedText(), "先绑定")
            listener.handle(f.incoming("/dota分析 9044880093"))
            assertContains(f.sent.single(), "先绑定")
            f.dota.setBinding("user", 176496411)
            assertContains(f.command(handlers, "dota", listOf("分析", "9044880093")).rejectedText(), "报告生成中")
            assertContains(f.command(handlers, "dota分析", listOf("9044880093")).rejectedText(), "报告生成中")
            for (text in listOf("/dota 分析 9044880093", "/dota分析 9044880093")) {
                f.sent.clear()
                listener.handle(f.incoming(text))
                assertContains(f.sent.single(), "报告生成中")
            }
        }
        f.assertNoWork()
    }

    @Test fun `provider failures clearly reject analysis from host and listener without producing images`() = withFixture { f ->
        for (status in listOf(401, 503)) {
            val config = f.prepareAnalysisFailure(status)
            val handlers = f.handlers(config)
            f.sent.clear()
            f.sentContent.clear()
            val result = withTimeout(10000) { f.command(handlers, "dota战报", listOf("比赛", "9044880093")) }
            assertContains(result.rejectedText(), "AI 分析暂不可用")
            assertFalse(result.text().contains("private-upstream-body"))
            assertTrue(result.reply.flatMap { it.content }.all { it is MessageContent.Text })
            assertEquals(1, f.sent.size) // Progress was sent, but no terminal image was prepared.
            f.sent.clear()
            withTimeout(10000) { f.listener(config).handle(f.incoming("/dota战报 比赛 9044880093")) }
            assertEquals(2, f.sent.size)
            assertContains(f.sent.last(), "AI 分析暂不可用")
            assertFalse(f.sent.last().contains("private-upstream-body"))
            assertTrue(f.sentContent.all { it is MessageContent.Text })
        }
        assertEquals(4, f.paths.count { it == "/model" })
        assertEquals(4, f.paths.count { it == "/opendota/matches/9044880093" })
        assertEquals(8, f.paths.size)
    }

    @Test fun `DeepSeek command and mention switches are independent and Dota can stay paused`() = withFixture { f ->
        val enabled = f.config.copy(api = f.config.api.copy(apiKey = "local-test"), dota2 = Dota2Config(enabled = false))
        val onlyCommand = enabled.copy(trigger = enabled.trigger.copy(enableAtTrigger = false))
        val listener = f.listener(onlyCommand)
        listener.handle(f.incoming("https://example.com", mention = true))
        assertEquals(0, f.dispatcher.launches)
        listener.handle(f.incoming("/ds 问题"))
        assertEquals(1, f.dispatcher.launches)
        assertContains(f.command(f.handlers(onlyCommand), "dota", listOf("历史")).rejectedText(), "暂停")

        val onlyAt = enabled.copy(trigger = enabled.trigger.copy(enableAtTrigger = true, enableCommandTrigger = false))
        assertContains(f.command(f.handlers(onlyAt), "ds", listOf("clear")).rejectedText(), "命令对话已暂停")
        f.listener(onlyAt).handle(f.incoming("/ds 问题"))
        assertEquals(1, f.dispatcher.launches)
        f.listener(onlyAt).handle(f.incoming("问题", mention = true))
        assertEquals(2, f.dispatcher.launches)
        assertTrue(f.paths.isEmpty())
    }

    @Test fun `Grok uses its own credentials and command switch without requiring DeepSeek`() = withFixture { f ->
        val grok = f.config.copy(grok = GrokConfig(enabled = true, apiKey = "local-grok", apiUrl = f.modelUrl, useAsAtTrigger = true, enableCommandTrigger = false))
        val handlers = f.handlers(grok)
        assertContains(f.command(handlers, "grok", listOf("问题")).rejectedText(), "命令对话已暂停")
        val listener = f.listener(grok)
        listener.handle(f.incoming("/grok 问题"))
        assertEquals(0, f.dispatcher.launches)
        listener.handle(f.incoming("问题", mention = true))
        assertEquals(1, f.dispatcher.launches)

        val commandEnabled = grok.copy(grok = grok.grok.copy(enableCommandTrigger = true, useAsAtTrigger = false))
        f.command(f.handlers(commandEnabled), "grok", listOf("问题"))
        assertEquals(2, f.dispatcher.launches)
        f.listener(commandEnabled).handle(f.incoming("问题", mention = true))
        assertEquals(2, f.dispatcher.launches) // DS remains unavailable; Grok is not selected for @.

        f.sent.clear()
        val noGrokKey = grok.copy(grok = grok.grok.copy(apiKey = "", enableCommandTrigger = true))
        f.listener(noGrokKey).handle(f.incoming("/grok 问题"))
        assertContains(f.sent.single(), "Grok API Key")
        f.listener(noGrokKey).handle(f.incoming("问题", mention = true))
        assertEquals(2, f.dispatcher.launches)
        assertTrue(f.paths.isEmpty())
    }

    @Test fun `previously registered host handlers follow newly applied feature settings`() = withFixture { f ->
        var current = f.handlers(f.config).associateBy { it.spec.path.single() }
        val registered = current.mapValues { (name, handler) -> LiveCommandHandler(handler.spec) { current.getValue(name) } }
        val oldDs = registered.getValue("ds")
        val oldGrok = registered.getValue("grok")
        val oldDota = registered.getValue("dota")
        assertContains(oldDs.handle(f.invocation("ds", listOf("问题"))).rejectedText(), "DeepSeek API Key")

        val enabled = f.config.copy(api = f.config.api.copy(apiKey = "local-test"), grok = GrokConfig(enabled = true, apiKey = "local-grok", apiUrl = f.modelUrl))
        current = f.handlers(enabled).associateBy { it.spec.path.single() }
        oldDs.handle(f.invocation("ds", listOf("问题")))
        oldGrok.handle(f.invocation("grok", listOf("问题")))
        assertEquals(2, f.dispatcher.launches)

        current = f.handlers(enabled.copy(chat = enabled.chat.copy(enabled = false), dota2 = enabled.dota2.copy(enabled = false))).associateBy { it.spec.path.single() }
        assertContains(oldDs.handle(f.invocation("ds", listOf("问题"))).rejectedText(), "暂停")
        assertContains(oldGrok.handle(f.invocation("grok", listOf("问题"))).rejectedText(), "暂停")
        assertContains(oldDota.handle(f.invocation("dota", listOf("绑定", "176496411"))).rejectedText(), "暂停")
        assertEquals(2, f.dispatcher.launches)
        assertTrue(f.paths.isEmpty())
    }

    private fun CommandExecutionResult.text() = reply.flatMap { it.content }.joinToString("\n") { it.fallbackText }
    private fun CommandExecutionResult.rejectedText(): String {
        assertEquals(CommandStatus.REJECTED, status)
        return text()
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        Fixture().use { block(it) }
    }

    /** A launch is observable immediately, while its body cannot accidentally call external services. */
    private class RecordingDispatcher : CoroutineDispatcher() {
        var launches = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) { launches++ }
    }

    private class Fixture : AutoCloseable {
        val paths = CopyOnWriteArrayList<String>()
        val sent = mutableListOf<String>()
        val sentContent = mutableListOf<MessageContent>()
        val dispatcher = RecordingDispatcher()
        private val job = SupervisorJob()
        private val dir = Files.createTempDirectory("feature-dispatch-")
        private var modelStatus = 200
        private var globalIcon: Image? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                paths += path
                exchange.requestBody.readBytes()
                val body = when {
                    path == "/model" -> "private-upstream-body"
                    path.startsWith("/opendota/matches/") -> """{"match_id":9044880093,"radiant_win":true,"players":[{"account_id":176496411,"hero_id":0,"isRadiant":true},{"account_id":2,"hero_id":0,"isRadiant":false}]}"""
                    path.endsWith("/recentMatches") -> "[]"
                    else -> """{"profile":{"personaname":"Test Player"}}"""
                }
                val bytes = body.toByteArray()
                exchange.sendResponseHeaders(if (path == "/model") modelStatus else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val modelUrl = "http://127.0.0.1:${server.address.port}/model"
        val config = AgentPluginConfig(api = ApiConfig(apiUrl = modelUrl))
        private val cache = CacheUtils(dir)
        val dota = Dota2Service(dir.toFile(), cache, "http://127.0.0.1:${server.address.port}/opendota")
        private val fonts = FontRegistry()
        private val target = TargetAddress.of("qq", TargetKind.GROUP, "group")
        private val context = PluginContext(
            pluginId = "feature-test",
            descriptor = PluginDescriptor(id = "feature-test", name = "test", version = "test", mainClass = "test", apiVersion = "3.0.0"),
            configService = unused(), dataStore = unused(), scope = CoroutineScope(job + dispatcher),
            taskScheduler = unused(), sourceUpdatePublisher = unused(), sourceStateStore = unused(), subscriptionQueryService = unused(),
            messagePublisher = object : PluginMessagePublisher {
                override suspend fun sendBatches(targets: List<TargetAddress>, batches: List<MessageBatch>, renderVariant: String?, options: PluginMessagePublishOptions): PluginMessagePublishResult {
                    sentContent += batches.flatMap { it.content }
                    sent += batches.flatMap { it.content }.map { it.fallbackText }
                    return PluginMessagePublishResult(accepted = true)
                }
            },
        )

        fun handlers(config: AgentPluginConfig): List<CommandHandler> {
            val sessions = SessionManager(config.chat)
            val ds = DeepSeekClient(config.api.apiKey, config.api.apiUrl, maxRetries = config.api.maxRetries)
            val grok = config.grok.takeIf { it.enabled && it.apiKey.isNotBlank() }?.let { DeepSeekClient(it.apiKey, it.apiUrl) }
            return CommandHandlers(config, ChatService(config, ds, sessions), sessions, dota, ds, grok, config.image, context, cache, fonts).all()
        }

        fun listener(config: AgentPluginConfig): MessageListener {
            val sessions = SessionManager(config.chat)
            val ds = DeepSeekClient(config.api.apiKey, config.api.apiUrl, maxRetries = config.api.maxRetries)
            val grok = config.grok.takeIf { it.enabled && it.apiKey.isNotBlank() }?.let { DeepSeekClient(it.apiKey, it.apiUrl) }
            return MessageListener(config, ChatService(config, ds, sessions), sessions, dota, ds, grok, config.image, context, dir, cache, fonts)
        }

        suspend fun command(handlers: List<CommandHandler>, name: String, args: List<String>) = handlers.single { it.spec.path == listOf(name) }.handle(invocation(name, args))

        fun invocation(name: String, args: List<String>) = CommandInvocation(
            sourcePlugin = "test", context = CommandContext(target, "user"), rawText = "/$name ${args.joinToString(" ")}",
            traceId = "trace", replyToMessageId = "reply", tokens = listOf(name) + args, matchedPath = listOf(name), args = args, role = CommandRole.USER,
        )

        fun incoming(text: String, mention: Boolean = false): IncomingMessageDispatchContext {
            val message = IncomingMessage(platformId = PlatformId.of("qq"), target = target, senderId = "user", botAccountId = "bot", text = text, mentions = if (mention) setOf("bot") else emptySet())
            return IncomingMessageDispatchContext(message = message, sourcePlugin = "test", traceId = "trace", replyToMessageId = "reply", commandContext = CommandContext(target, "user", "bot", message.mentions), rawText = text)
        }

        fun assertNoWork() {
            assertEquals(0, dispatcher.launches, "Rejected input must not create a background task")
            assertTrue(paths.isEmpty(), "Rejected input must not query the model or OpenDota: $paths")
        }

        fun prepareAnalysisFailure(status: Int): AgentPluginConfig {
            modelStatus = status
            dota.setBinding("user", 176496411)
            fun field(name: String, value: Any) = Dota2Service::class.java.getDeclaredField(name).apply { isAccessible = true }.set(dota, value)
            field("heroConstantsLoaded", true)
            field("itemConstantsLoaded", true)
            val icon = globalIcon ?: Surface.makeRasterN32Premul(1, 1).use { it.makeImageSnapshot() }.also { globalIcon = it }
            listOf("aghsScepterYes", "aghsScepterNo", "aghsShardYes", "aghsShardNo").forEach { field(it, icon) }
            return config.copy(api = config.api.copy(apiKey = "local-test", maxRetries = 0))
        }

        override fun close() {
            job.cancel()
            server.stop(0)
            globalIcon?.close()
            check(dir.toAbsolutePath().normalize().startsWith(java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize()))
            check(dir.fileName.toString().startsWith("feature-dispatch-"))
            dir.toFile().deleteRecursively()
        }
    }

    private companion object {
        private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            error("Unused host service was called: ${T::class.simpleName}.${method.name}")
        } as T
    }
}
