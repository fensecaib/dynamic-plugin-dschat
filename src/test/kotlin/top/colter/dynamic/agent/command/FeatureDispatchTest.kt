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
import top.colter.dynamic.agent.ds.ChatRequest
import top.colter.dynamic.agent.util.HttpUtils
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext
import kotlin.test.*

class FeatureDispatchTest {
    private val analysisCommands = listOf("战报", "深度战报", "分析", "个人详情", "深度个人详情")

    @Test fun `host Pro handler sends Pro model and default handler keeps configured model`() = runBlocking {
        Fixture(executeTasks = true).use { f ->
            val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test", model = "custom-default"))
            val handlers = f.handlers(config)
            f.command(handlers, "dspro", listOf("Pro问题"))
            withTimeout(10000) { while (f.sent.size < 1) delay(10) }
            assertEquals("deepseek-v4-pro", f.requests.single().model)
            f.command(handlers, "ds", listOf("普通追问"))
            withTimeout(10000) { while (f.sent.size < 2) delay(10) }
            assertEquals("custom-default", f.requests.last().model)
            assertEquals(listOf("Pro问题", "测试回答", "普通追问"), f.requests.last().messages.filter { it.role != "system" }.map { it.content })
            f.command(handlers, "dspro", listOf("clear"))
            withTimeout(10000) { while (f.sent.size < 3) delay(10) }
            assertEquals(2, f.requests.size)
            f.command(handlers, "ds", listOf("新问题"))
            withTimeout(10000) { while (f.sent.size < 4) delay(10) }
            assertEquals(listOf("新问题"), f.requests.last().messages.filter { it.role == "user" }.map { it.content })
        }
    }

    @Test fun `mentioned pro reaches only addressed bot and resets model on next ordinary mention`() = runBlocking {
        Fixture(executeTasks = true).use { f ->
            val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"),
                trigger = f.config.trigger.copy(enableCommandTrigger = false))
            val listener = f.listener(config)
            val segments = listOf(IncomingMessageSegment.Mention("bot"), IncomingMessageSegment.Text(" /Pro　查一下 https://example.com"))
            val message = f.incoming("@bot /Pro　查一下 https://example.com", mention = true, segments = segments)
                .copy(intent = IncomingMessageIntent.Command)
            listener.handle(f.incoming(message.rawText, mention = true, botId = "other-bot", segments = segments)
                .copy(intent = IncomingMessageIntent.Command))
            listener.handle(f.incoming("/pro 问题"))
            assertEquals(0, f.dispatcher.launches)
            listener.handle(message)
            withTimeout(10000) { while (f.sent.size < 1) delay(10) }
            assertEquals("deepseek-v4-pro", f.requests.single().model)
            assertEquals("查一下 https://example.com", f.requests.single().messages.last().content)
            assertTrue(f.requests.single().tools.orEmpty().any { it.function.name == "web_search" })
            listener.handle(f.incoming("继续", mention = true))
            withTimeout(10000) { while (f.sent.size < 2) delay(10) }
            assertEquals(config.api.model, f.requests.last().model)
            assertEquals(listOf("查一下 https://example.com", "测试回答", "继续"),
                f.requests.last().messages.filter { it.role != "system" }.map { it.content })
            listener.handle(f.incoming("/pro 再问", mention = true))
            withTimeout(10000) { while (f.sent.size < 3) delay(10) }
            assertEquals("deepseek-v4-pro", f.requests.last().model)
            assertEquals("再问", f.requests.last().messages.last().content)
            listener.handle(f.incoming("problem statement", mention = true))
            withTimeout(10000) { while (f.sent.size < 4) delay(10) }
            assertEquals(config.api.model, f.requests.last().model)
            assertEquals("problem statement", f.requests.last().messages.last().content)
            listener.handle(f.incoming("pro 普通文字", mention = true))
            withTimeout(10000) { while (f.sent.size < 5) delay(10) }
            assertEquals(config.api.model, f.requests.last().model)
            assertEquals("pro 普通文字", f.requests.last().messages.last().content)
        }
    }

    @Test fun `mention pro respects mention switch and empty question never starts model`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"))
        f.listener(config).handle(f.incoming("/pro　", mention = true))
        assertEquals(listOf("用法: @Bot /pro <问题>"), f.sent)
        f.sent.clear()
        for (disabled in listOf(
            config.copy(trigger = config.trigger.copy(enableAtTrigger = false)),
            config.copy(chat = config.chat.copy(enabled = false)),
            f.config,
        )) {
            for (intent in listOf(IncomingMessageIntent.PlainText, IncomingMessageIntent.Command)) {
                f.listener(disabled).handle(f.incoming("/pro 问题", mention = true).copy(intent = intent))
            }
        }
        assertTrue(f.sent.isEmpty())
        f.assertNoWork()
    }

    @Test fun `host command entry rejects paused features before any background work or requests`() = withFixture { f ->
        val paused = f.config.copy(chat = ChatConfig(enabled = false), dota2 = Dota2Config(enabled = false))
        val handlers = f.handlers(paused)
        assertEquals(listOf("ds", "dspro", "dota", "dota绑定", "dota历史", "dota战报", "dota深报", "dota分析", "dota详情", "dota深详", "dota帮助"),
            handlers.map { it.spec.path.single() })
        for ((name, args) in listOf("ds" to listOf("https://example.com"), "dspro" to listOf("https://example.com"), "dota" to listOf("绑定", "100000001"))) {
            assertContains(f.command(handlers, name, args).rejectedText(), "暂停")
        }
        val listener = f.listener(paused)
        for (text in listOf("/ds https://example.com", "/dspro https://example.com", "/dota 绑定 100000001")) {
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
        assertContains(f.command(handlers, "dspro", listOf("https://example.com")).rejectedText(), "DeepSeek API Key")
        // Occupying the shared slot proves configuration is checked before the busy response too.
        f.dota.reportTasks.runIfIdle(onBusy = { fail("unexpected occupied slot") }) {
            for (command in analysisCommands) {
                val result = f.command(handlers, "dota", listOf(command, "9044880093"))
                assertContains(result.rejectedText(), "DeepSeek API Key", message = command)
            }
        }
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
        assertContains(f.command(handlers, "dota", listOf("绑定", "100000001")).text(), "已绑定")
        assertEquals(100000001L, f.dota.getBinding("user"))
        assertContains(f.command(handlers, "dota", listOf("历史")).text(), "未找到对局记录")
        val listener = f.listener(config)
        listener.handle(f.incoming("/dota 绑定 100000001"))
        listener.handle(f.incoming("/d2 历史"))
        assertEquals(4, f.paths.size)
        assertTrue(f.paths.all { it.startsWith("/opendota/players/") })
        assertEquals(0, f.dispatcher.launches)
        assertEquals(listOf("已绑定: Test Player (ID=100000001)", "未找到对局记录"), f.sent)
    }

    @Test fun `Dota subfeature pauses take precedence even when API is configured`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"), dota2 = Dota2Config(enableBinding = false, enableHistory = false, enableAnalysis = false))
        val handlers = f.handlers(config)
        val listener = f.listener(config)
        for (command in listOf("绑定", "历史") + analysisCommands) {
            assertContains(f.command(handlers, "dota", listOf(command, "100000001")).rejectedText(), "暂停", message = command)
            f.sent.clear()
            listener.handle(f.incoming("/dota $command 100000001"))
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
                val legacy = f.command(handlers, "dota", listOf(shortcut.command, "100000001")).rejectedText()
                assertEquals(legacy, f.command(handlers, shortcut.token, listOf("100000001")).rejectedText(), shortcut.token)
                for (text in listOf("/dota ${shortcut.command} 100000001", "/${shortcut.token} 100000001")) {
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
            val args = if (command == "绑定") listOf("100000001") else emptyList()
            val legacy = f.command(handlers, "dota", listOf(command) + args).text()
            assertEquals(legacy, f.command(handlers, shortcut, args).text())
            for (text in listOf("/dota $command ${args.joinToString(" ")}", "/$shortcut ${args.joinToString(" ")}")) {
                f.sent.clear()
                listener.handle(f.incoming(text))
                assertEquals(listOf(legacy), f.sent)
            }
        }
        assertEquals(100000001L, f.dota.getBinding("user"))
        assertEquals(8, f.paths.size)
        assertTrue(f.paths.all { it.startsWith("/opendota/players/") })
        assertEquals(0, f.dispatcher.launches)
    }

    @Test fun `full analysis rejects invalid ids explicitly in both command forms and listener`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"))
        f.dota.setBinding("user", 100000001)
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
            f.dota.setBinding("user", 100000001)
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

    @Test fun `custom DeepSeek command and mention switches are independent and Dota can stay paused`() = withFixture { f ->
        val enabled = f.config.copy(
            api = f.config.api.copy(apiKey = "local-test"),
            trigger = f.config.trigger.copy(triggerPrefix = "/ask"),
            dota2 = Dota2Config(enabled = false),
        )
        val onlyCommand = enabled.copy(trigger = enabled.trigger.copy(enableAtTrigger = false))
        val listener = f.listener(onlyCommand)
        listener.handle(f.incoming("https://example.com", mention = true))
        listener.handle(f.incoming("/ds 问题"))
        listener.handle(f.incoming("/asker 问题"))
        assertEquals(0, f.dispatcher.launches)
        listener.handle(f.incoming("/ask 问题"))
        assertEquals(1, f.dispatcher.launches)
        val handlers = f.handlers(onlyCommand)
        assertEquals(listOf(listOf("chat")), handlers.single { it.spec.path == listOf("ask") }.spec.aliases)
        f.command(handlers, "ask", listOf("问题"))
        assertEquals(2, f.dispatcher.launches)
        assertContains(f.command(handlers, "dota", listOf("历史")).rejectedText(), "暂停")

        val onlyAt = enabled.copy(trigger = enabled.trigger.copy(enableAtTrigger = true, enableCommandTrigger = false))
        assertContains(f.command(f.handlers(onlyAt), "ask", listOf("clear")).rejectedText(), "命令对话已暂停")
        f.listener(onlyAt).handle(f.incoming("/ask 问题"))
        assertEquals(2, f.dispatcher.launches)
        f.listener(onlyAt).handle(f.incoming("问题", mention = true))
        assertEquals(3, f.dispatcher.launches)
        assertTrue(f.paths.isEmpty())
    }

    @Test fun `pro command stays available with custom prefix and obeys command switch`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"),
            trigger = f.config.trigger.copy(triggerPrefix = "/ask", enableAtTrigger = false))
        val listener = f.listener(config)
        listener.handle(f.incoming("/dspro"))
        assertEquals(listOf("用法: /dspro <问题>"), f.sent)
        f.sent.clear()
        listener.handle(f.incoming("/dspro clear"))
        assertContains(f.sent.single(), "上下文已清空")
        listener.handle(f.incoming("/dsproextra 问题"))
        assertEquals(0, f.dispatcher.launches)
        listener.handle(f.incoming("/dspro 问题"))
        f.command(f.handlers(config), "dspro", listOf("问题"))
        assertEquals(2, f.dispatcher.launches)
        val disabled = config.copy(trigger = config.trigger.copy(enableCommandTrigger = false))
        assertContains(f.command(f.handlers(disabled), "dspro", listOf("问题")).rejectedText(), "命令对话已暂停")
        f.sent.clear()
        f.listener(disabled).handle(f.incoming("/dspro 问题", mention = true))
        assertContains(f.sent.single(), "命令对话已暂停")
        assertEquals(2, f.dispatcher.launches)
        assertTrue(f.paths.isEmpty())
    }

    @Test fun `removed provider has no registered command and old messages do not start chat`() = withFixture { f ->
        val config = f.config.copy(api = f.config.api.copy(apiKey = "local-test"))
        val handlers = f.handlers(config)
        assertFalse(handlers.any { it.spec.path == listOf("grok") || listOf("grok") in it.spec.aliases })
        val listener = f.listener(config)
        for (text in listOf("/grok", "/grok 问题", "/grok clear")) {
            listener.handle(f.incoming(text))
        }
        assertTrue(f.sent.isEmpty())
        f.assertNoWork()
    }

    @Test fun `previously registered host handlers follow newly applied feature settings`() = withFixture { f ->
        var current = f.handlers(f.config).associateBy { it.spec.path.single() }
        val registered = current.mapValues { (name, handler) -> LiveCommandHandler(handler.spec) { current.getValue(name) } }
        val oldDs = registered.getValue("ds")
        val oldDota = registered.getValue("dota")
        assertContains(oldDs.handle(f.invocation("ds", listOf("问题"))).rejectedText(), "DeepSeek API Key")

        val enabled = f.config.copy(api = f.config.api.copy(apiKey = "local-test"))
        current = f.handlers(enabled).associateBy { it.spec.path.single() }
        oldDs.handle(f.invocation("ds", listOf("问题")))
        assertEquals(1, f.dispatcher.launches)

        current = f.handlers(enabled.copy(chat = enabled.chat.copy(enabled = false), dota2 = enabled.dota2.copy(enabled = false))).associateBy { it.spec.path.single() }
        assertContains(oldDs.handle(f.invocation("ds", listOf("问题"))).rejectedText(), "暂停")
        assertContains(oldDota.handle(f.invocation("dota", listOf("绑定", "100000001"))).rejectedText(), "暂停")
        assertEquals(1, f.dispatcher.launches)
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

    /** Admission-only by default; integration tests opt into executing against the local HTTP fixture. */
    private class RecordingDispatcher(private val executeTasks: Boolean = false) : CoroutineDispatcher() {
        private val dispatchCount = AtomicInteger()
        val launches: Int get() = dispatchCount.get()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatchCount.incrementAndGet()
            if (executeTasks) Dispatchers.IO.dispatch(context, block)
        }
    }

    private class Fixture(executeTasks: Boolean = false) : AutoCloseable {
        val paths = CopyOnWriteArrayList<String>()
        val requests = CopyOnWriteArrayList<ChatRequest>()
        val sent = CopyOnWriteArrayList<String>()
        val sentContent = CopyOnWriteArrayList<MessageContent>()
        val dispatcher = RecordingDispatcher(executeTasks)
        private val job = SupervisorJob()
        private val dir = Files.createTempDirectory("feature-dispatch-")
        private var modelStatus = 200
        private var globalIcon: Image? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                val path = exchange.requestURI.path
                paths += path
                val requestBody = exchange.requestBody.readBytes().toString(Charsets.UTF_8)
                if (path == "/model") requests += HttpUtils.json.decodeFromString(ChatRequest.serializer(), requestBody)
                val body = when {
                    path == "/model" && modelStatus == 200 -> """{"choices":[{"finish_reason":"stop","message":{"role":"assistant","content":"测试回答"}}]}"""
                    path == "/model" -> "private-upstream-body"
                    path.startsWith("/opendota/matches/") -> """{"match_id":9044880093,"radiant_win":true,"players":[{"account_id":100000001,"hero_id":0,"isRadiant":true},{"account_id":2,"hero_id":0,"isRadiant":false}]}"""
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
            descriptor = PluginDescriptor(id = "feature-test", name = "test", version = "test", mainClass = "test"),
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
            return CommandHandlers(config, ChatService(config, ds, sessions), sessions, dota, ds, config.image, context, cache, fonts).all()
        }

        fun listener(config: AgentPluginConfig): MessageListener {
            val sessions = SessionManager(config.chat)
            val ds = DeepSeekClient(config.api.apiKey, config.api.apiUrl, maxRetries = config.api.maxRetries)
            return MessageListener(config, ChatService(config, ds, sessions), sessions, dota, ds, config.image, context, cache, fonts)
        }

        suspend fun command(handlers: List<CommandHandler>, name: String, args: List<String>) = handlers.single { it.spec.path == listOf(name) }.handle(invocation(name, args))

        fun invocation(name: String, args: List<String>) = CommandInvocation(
            sourcePlugin = "test", context = CommandContext(target, "user"), rawText = "/$name ${args.joinToString(" ")}",
            traceId = "trace", replyToMessageId = "reply", tokens = listOf(name) + args, matchedPath = listOf(name), args = args, role = CommandRole.USER,
        )

        fun incoming(text: String, mention: Boolean = false, botId: String = "bot", segments: List<IncomingMessageSegment> = emptyList()): IncomingMessageDispatchContext {
            val message = IncomingMessage(platformId = PlatformId.of("qq"), target = target, senderId = "user", botAccountId = botId, text = text, mentions = if (mention) setOf("bot") else emptySet(), segments = segments)
            return IncomingMessageDispatchContext(message = message, sourcePlugin = "test", traceId = "trace", replyToMessageId = "reply", commandContext = CommandContext(target, "user", botId, message.mentions), rawText = text)
        }

        fun assertNoWork() {
            assertEquals(0, dispatcher.launches, "Rejected input must not create a background task")
            assertTrue(paths.isEmpty(), "Rejected input must not query the model or OpenDota: $paths")
        }

        fun prepareAnalysisFailure(status: Int): AgentPluginConfig {
            modelStatus = status
            dota.setBinding("user", 100000001)
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
