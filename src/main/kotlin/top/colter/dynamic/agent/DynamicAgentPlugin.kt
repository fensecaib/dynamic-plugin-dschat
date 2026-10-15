package top.colter.dynamic.agent

import kotlinx.coroutines.*
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.command.CommandHandlers
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ChatConfig
import top.colter.dynamic.agent.config.agentConfigFormSpec
import top.colter.dynamic.agent.config.normalizeAgentConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.ds.safeModelLogLabel
import top.colter.dynamic.agent.listener.MessageListener
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.agent.util.AgentDiagnostics
import top.colter.dynamic.agent.util.AgentTrace
import top.colter.dynamic.agent.util.DrawCacheCleanup
import top.colter.dynamic.agent.util.RestartablePluginScope
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigurablePlugin
import top.colter.dynamic.core.plugin.CommandContributor
import top.colter.dynamic.core.plugin.IncomingMessageConsumerPlugin
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.Plugin
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.skiko.FontRegistry
import top.colter.skiko.Fonts
import top.colter.skiko.FontTypographySettings
import java.nio.file.Path

/** 宿主会缓存已注册命令；保留注册时的路径，执行时使用当前配置重建的处理器。 */
internal class LiveCommandHandler(
    override val spec: CommandSpec,
    private val scope: RestartablePluginScope? = null,
    private val current: () -> CommandHandler,
) : CommandHandler {
    override suspend fun handle(invocation: CommandInvocation) = if (scope == null) {
        current().handle(invocation)
    } else {
        scope.run { current().handle(invocation) }
    }
}

class DynamicAgentPlugin : Plugin, IncomingMessageConsumerPlugin, CommandContributor,
    ConfigurablePlugin<AgentPluginConfig> {

    private lateinit var context: PluginContext
    private lateinit var dataDir: Path
    @Volatile private lateinit var config: AgentPluginConfig
    private lateinit var dsClient: DeepSeekClient
    private var grokClient: DeepSeekClient? = null
    private lateinit var sessionManager: SessionManager
    private var sessionConfig: ChatConfig? = null
    private lateinit var chatService: ChatService
    private lateinit var dota2Service: Dota2Service
    private lateinit var cacheUtils: CacheUtils
    @Volatile private lateinit var messageListener: MessageListener
    @Volatile private lateinit var commandHandlers: CommandHandlers
    private lateinit var fontRegistry: FontRegistry
    private lateinit var workScope: RestartablePluginScope
    private var drawCleanupJob: Job? = null

    override val configId: String = "dynamic-agent"
    override val configName: String = "\u591a\u529f\u80fdAgent\u63d2\u4ef6"
    override val configDescription: String = "DeepSeek AI\u5bf9\u8bdd / Grok AI\u5bf9\u8bdd / Dota2\u6218\u62a5 / \u8054\u7f51\u641c\u7d22"
    override val configClass = AgentPluginConfig::class

    override val configFormSpec = agentConfigFormSpec

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        this.workScope = RestartablePluginScope(context.scope)
        this.dataDir = context.dataStore.dataDir
        this.cacheUtils = CacheUtils(dataDir)
        this.fontRegistry = Fonts.default

        loadFonts()

        val loaded = context.configService.loadOrCreate(configId, AgentPluginConfig::class, defaultProvider = { AgentPluginConfig() })
        this.config = normalizeAgentConfig(loaded)
        if (config != loaded) context.configService.save(configId, config)

        this.dota2Service = Dota2Service(dataDir.toFile(), cacheUtils)
        this.dota2Service.init()

        rebuildServices()
        withContext(AgentTrace.create("plugin_load", "internal", config.diagnostics)) {
            AgentDiagnostics.event("agent_config", "loaded", mapOf("model" to safeModelLogLabel(config.api.model, config.api.apiKey), "model_migrated" to (loaded.api.model != config.api.model)))
        }
    }

    override suspend fun onStart() {
        workScope.start()
        dota2Service.init()
        if (drawCleanupJob?.isActive != true) {
            drawCleanupJob = workScope.launch {
                while (isActive) {
                    val cleanupConfig = config
                    val retentionHours = cleanupConfig.image.drawCacheRetentionHours.coerceIn(1, 8760).toLong()
                    withContext(AgentTrace.create("cache_cleanup", "internal", cleanupConfig.diagnostics)) {
                        try {
                            val result = AgentDiagnostics.stage("draw_cleanup") {
                                runInterruptible(Dispatchers.IO) { DrawCacheCleanup.cleanup(dataDir, retentionHours = retentionHours) }
                            }
                            AgentDiagnostics.event("agent_cache", "completed", mapOf(
                                "retention_hours" to retentionHours, "deleted_files" to result.deletedFiles,
                                "deleted_bytes" to result.deletedBytes, "failed_entries" to result.failedEntries,
                                "skipped_entries" to result.skippedEntries,
                            ), warning = result.failedEntries > 0)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            AgentDiagnostics.event("agent_cache", "failed", mapOf("error_type" to e.javaClass.simpleName), warning = true)
                        }
                    }
                    delay(6 * 60 * 60 * 1000L)
                }
            }
        }
    }

    override suspend fun onStop() {
        workScope.stop()
        drawCleanupJob = null
    }

    override suspend fun onUnload() { onStop() }

    private fun loadFonts() {
        try {
            val fontsDir = dataDir.resolve("fonts")
            if (fontsDir.toFile().isDirectory) {
                fontsDir.toFile().listFiles()?.filter { it.extension.lowercase() in listOf("ttf", "otf", "ttc") }
                    ?.forEach { fontRegistry.loadTextFallbackTypeface(it.absolutePath) }
            }
            val fontFile = dataDir.resolve("font.ttf").toFile()
            if (fontFile.exists()) {
                fontRegistry.loadTextTypeface(fontFile.absolutePath)
            }
        } catch (_: Exception) {}
    }

    private fun rebuildServices() {
        val workContext = context.copy(scope = workScope)
        this.dsClient = DeepSeekClient(config.api.apiKey, config.api.apiUrl,
            requestTimeoutSeconds = config.api.requestTimeoutSeconds.toLong(), maxRetries = config.api.maxRetries)
        this.grokClient = if (config.grok.enabled && config.grok.apiKey.isNotBlank()) {
            DeepSeekClient(config.grok.apiKey, config.grok.apiUrl,
                requestTimeoutSeconds = config.grok.requestTimeoutSeconds.toLong(), maxRetries = config.grok.maxRetries)
        } else null
        // Host settings are applied live; unrelated changes must preserve active conversations.
        if (!::sessionManager.isInitialized || sessionConfig != config.chat) {
            this.sessionManager = SessionManager(config.chat)
            this.sessionConfig = config.chat
        }
        this.chatService = ChatService(config, dsClient, sessionManager)

        this.messageListener = MessageListener(
            config = config, chatService = chatService, sessionManager = sessionManager,
            dota2Service = dota2Service, dsClient = dsClient, grokClient = grokClient,
            imageConfig = config.image, pluginContext = workContext, dataDir = dataDir,
            cacheUtils = cacheUtils, fontRegistry = fontRegistry,
        )

        this.commandHandlers = CommandHandlers(
            config = config, chatService = chatService, sessionManager = sessionManager,
            dota2Service = dota2Service, dsClient = dsClient, grokClient = grokClient,
            imageConfig = config.image, pluginContext = workContext, cacheUtils = cacheUtils,
            fontRegistry = fontRegistry,
        )
    }

    override fun currentConfig(): AgentPluginConfig = config

    override fun applyConfig(next: AgentPluginConfig): ConfigApplyResult {
        val prefixChanged = config.trigger.triggerPrefix != next.trigger.triggerPrefix ||
            config.grok.triggerPrefix != next.grok.triggerPrefix
        val migrated = normalizeAgentConfig(next)
        val normalized = if (migrated.grok.useAsAtTrigger && migrated.trigger.enableAtTrigger) {
            migrated.copy(trigger = migrated.trigger.copy(enableAtTrigger = false))
        } else migrated
        this.config = normalized
        context.configService.save(configId, normalized)
        rebuildServices()
        return ConfigApplyResult(
            changed = true,
            restartRequired = prefixChanged,
            message = buildList {
                if (next.grok.useAsAtTrigger && next.trigger.enableAtTrigger) {
                    add("Grok @Bot 触发已开启，已自动关闭 DeepSeek @Bot 触发")
                }
                if (prefixChanged) add("功能开关已生效；命令前缀修改需重启插件后生效")
                if (next.api.model != normalized.api.model) add("已将官方 Flash 模型更新为 deepseek-flash")
            }.joinToString("；")
        )
    }

    override fun commandHandlers(): Collection<CommandHandler> = commandHandlers.all().mapIndexed { index, handler ->
        LiveCommandHandler(handler.spec, workScope) { commandHandlers.all()[index] }
    }

    override suspend fun onIncomingMessage(context: IncomingMessageDispatchContext) {
        workScope.run { messageListener.handle(context) }
    }
}
