package top.colter.dynamic.agent

import kotlinx.coroutines.*
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.command.CommandHandlers
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ApiConfig
import top.colter.dynamic.agent.config.agentConfigFormSpec
import top.colter.dynamic.agent.config.agentConfigMigrations
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
import top.colter.dynamic.core.plugin.IncomingMessageFilter
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

    // Mentioned URLs need the listener; commands remain owned by the host command dispatcher.
    override val incomingMessageFilter = IncomingMessageFilter(receiveLinkMessages = true)

    private lateinit var context: PluginContext
    private lateinit var dataDir: Path
    @Volatile private lateinit var config: AgentPluginConfig
    private var activeCommandPrefix: String? = null
    private lateinit var dsClient: DeepSeekClient
    private var lastClientConfig: ApiConfig? = null
    private lateinit var sessionManager: SessionManager
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
    override val configDescription: String = "DeepSeek AI\u5bf9\u8bdd / Dota2\u6218\u62a5 / \u8054\u7f51\u641c\u7d22"
    override val configClass = AgentPluginConfig::class

    override val configFormSpec = agentConfigFormSpec

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        this.workScope = RestartablePluginScope(context.scope)
        this.dataDir = context.dataStore.dataDir
        this.cacheUtils = CacheUtils(dataDir)
        this.fontRegistry = Fonts.default

        loadFonts()

        val loaded = context.configService.loadOrCreate(
            configId, AgentPluginConfig::class, migrations = agentConfigMigrations,
            defaultProvider = { AgentPluginConfig() },
        )
        this.config = normalizeAgentConfig(loaded)
        if (config != loaded) context.configService.save(configId, config)
        this.activeCommandPrefix = config.trigger.triggerPrefix

        this.dota2Service = Dota2Service(dataDir.toFile(), cacheUtils)
        this.dota2Service.init()

        rebuildServices()
        withContext(AgentTrace.create("plugin_load", "internal", config.diagnostics)) {
            AgentDiagnostics.event("agent_config", "loaded", mapOf("model" to safeModelLogLabel(config.api.model, config.api.apiKey), "model_migrated" to (loaded.api.model != config.api.model)))
        }
    }

    override suspend fun onStart() {
        // The host registers command paths immediately after this hook returns.
        if (activeCommandPrefix != config.trigger.triggerPrefix) {
            activeCommandPrefix = config.trigger.triggerPrefix
            rebuildServices()
        }
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
        val savedConfig = config
        val prefix = activeCommandPrefix ?: savedConfig.trigger.triggerPrefix.also { activeCommandPrefix = it }
        // Keep listener parsing and command usage aligned with the host's registered paths.
        val serviceConfig = savedConfig.copy(trigger = savedConfig.trigger.copy(triggerPrefix = prefix))
        val workContext = context.copy(scope = workScope)
        // Presentation and memory updates can share the existing HTTP connection pool.
        if (!::dsClient.isInitialized || lastClientConfig != serviceConfig.api) {
            this.dsClient = DeepSeekClient(serviceConfig.api.apiKey, serviceConfig.api.apiUrl,
                requestTimeoutSeconds = serviceConfig.api.requestTimeoutSeconds.toLong(), maxRetries = serviceConfig.api.maxRetries)
            lastClientConfig = serviceConfig.api
        }
        // Keep active conversations while applying retention changes in place.
        if (!::sessionManager.isInitialized) {
            this.sessionManager = SessionManager(serviceConfig.chat)
        } else {
            sessionManager.updateConfig(serviceConfig.chat)
        }
        this.chatService = ChatService(serviceConfig, dsClient, sessionManager)

        this.messageListener = MessageListener(
            config = serviceConfig, chatService = chatService, sessionManager = sessionManager,
            dota2Service = dota2Service, dsClient = dsClient,
            imageConfig = serviceConfig.image, pluginContext = workContext,
            cacheUtils = cacheUtils, fontRegistry = fontRegistry,
        )

        this.commandHandlers = CommandHandlers(
            config = serviceConfig, chatService = chatService, sessionManager = sessionManager,
            dota2Service = dota2Service, dsClient = dsClient,
            imageConfig = serviceConfig.image, pluginContext = workContext, cacheUtils = cacheUtils,
            fontRegistry = fontRegistry,
        )
    }

    override fun currentConfig(): AgentPluginConfig = config

    override fun applyConfig(next: AgentPluginConfig): ConfigApplyResult {
        val normalized = normalizeAgentConfig(next)
        val prefixChanged = (activeCommandPrefix ?: config.trigger.triggerPrefix) != normalized.trigger.triggerPrefix
        context.configService.save(configId, normalized)
        this.config = normalized
        rebuildServices()
        return ConfigApplyResult(
            changed = true,
            restartRequired = prefixChanged,
            message = buildList {
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
