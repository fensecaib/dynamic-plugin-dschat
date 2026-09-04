package top.colter.dynamic.agent

import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.command.CommandHandlers
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.listener.MessageListener
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.config.ConfigApplyResult
import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
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

class DynamicAgentPlugin : Plugin, IncomingMessageConsumerPlugin, CommandContributor,
    ConfigurablePlugin<AgentPluginConfig> {

    private lateinit var context: PluginContext
    private lateinit var dataDir: Path
    private lateinit var config: AgentPluginConfig
    private lateinit var dsClient: DeepSeekClient
    private var grokClient: DeepSeekClient? = null
    private lateinit var sessionManager: SessionManager
    private lateinit var chatService: ChatService
    private lateinit var dota2Service: Dota2Service
    private lateinit var cacheUtils: CacheUtils
    private lateinit var messageListener: MessageListener
    private lateinit var commandHandlers: CommandHandlers
    private lateinit var fontRegistry: FontRegistry

    override val configId: String = "dynamic-agent"
    override val configName: String = "\u591a\u529f\u80fdAgent\u63d2\u4ef6"
    override val configDescription: String = "DeepSeek AI\u5bf9\u8bdd / Grok AI\u5bf9\u8bdd / Dota2\u6218\u62a5 / \u8054\u7f51\u641c\u7d22"
    override val configClass = AgentPluginConfig::class

    override val configFormSpec = ConfigFormSpec(
        title = "Agent\u63d2\u4ef6\u914d\u7f6e",
        fields = listOf(
            ConfigFieldSpec("api.apiKey", "API Key", ConfigFieldType.SECRET, section = "DeepSeek API", required = true),
            ConfigFieldSpec("api.apiUrl", "API URL", ConfigFieldType.TEXT, section = "DeepSeek API", description = "\u9ed8\u8ba4 https://api.deepseek.com/chat/completions"),
            ConfigFieldSpec("api.model", "\u6a21\u578b", ConfigFieldType.SELECT, section = "DeepSeek API", options = listOf(
                top.colter.dynamic.core.config.ConfigFieldOption("deepseek-v4-flash", "V4 Flash (\u5feb\u901f)"),
                top.colter.dynamic.core.config.ConfigFieldOption("deepseek-v4-pro", "V4 Pro (\u7cbe\u786e)")
            )),
            ConfigFieldSpec("chat.systemPrompt", "\u7cfb\u7edf\u63d0\u793a\u8bcd", ConfigFieldType.TEXTAREA, section = "\u5bf9\u8bdd"),
            ConfigFieldSpec("chat.maxTokens", "\u6700\u5927Token", ConfigFieldType.NUMBER, section = "\u5bf9\u8bdd", numberKind = top.colter.dynamic.core.config.ConfigNumberKind.INTEGER, min = 256, max = 16384),
            ConfigFieldSpec("chat.textReplyThreshold", "\u56fe\u7247\u8f6c\u6362\u9608\u503c", ConfigFieldType.NUMBER, section = "\u5bf9\u8bdd", description = "\u8d85\u8fc7\u6b64\u5b57\u7b26\u6570\u81ea\u52a8\u6e32\u67d3\u4e3a\u56fe\u7247\uff0c0=\u59cb\u7ec8\u6587\u672c", numberKind = top.colter.dynamic.core.config.ConfigNumberKind.INTEGER, min = 0, max = 50000),
            ConfigFieldSpec("chat.enableMemory", "\u542f\u7528\u591a\u8f6e\u8bb0\u5fc6", ConfigFieldType.BOOLEAN, section = "\u5bf9\u8bdd"),
            ConfigFieldSpec("chat.memoryRounds", "\u8bb0\u5fc6\u8f6e\u6570", ConfigFieldType.NUMBER, section = "\u5bf9\u8bdd", numberKind = top.colter.dynamic.core.config.ConfigNumberKind.INTEGER, min = 1, max = 50),
            ConfigFieldSpec("chat.memoryTtlMinutes", "\u4f1a\u8bdd\u8d85\u65f6(\u5206\u949f)", ConfigFieldType.NUMBER, section = "\u5bf9\u8bdd", numberKind = top.colter.dynamic.core.config.ConfigNumberKind.INTEGER, min = 1, max = 1440),
            ConfigFieldSpec("chat.maxContextTokens", "\u4e0a\u4e0b\u6587Token\u9650\u5236", ConfigFieldType.NUMBER, section = "\u5bf9\u8bdd", description = "\u8d85\u8fc7\u540e\u81ea\u52a8\u8e22\u51fa\u65e9\u671f\u8f6e\u6b21", numberKind = top.colter.dynamic.core.config.ConfigNumberKind.INTEGER, min = 1024, max = 256000),
            ConfigFieldSpec("trigger.triggerPrefix", "DeepSeek \u89e6\u53d1\u524d\u7f00", ConfigFieldType.TEXT, section = "\u89e6\u53d1", description = "\u9ed8\u8ba4 /ds"),
            ConfigFieldSpec("trigger.enableAtTrigger", "DeepSeek @Bot \u89e6\u53d1", ConfigFieldType.BOOLEAN, section = "\u89e6\u53d1", description = "\u4e0e Grok @Bot \u89e6\u53d1\u4e92\u65a5"),
            ConfigFieldSpec("grok.enabled", "\u542f\u7528 Grok", ConfigFieldType.BOOLEAN, section = "Grok API"),
            ConfigFieldSpec("grok.apiKey", "Grok API Key", ConfigFieldType.SECRET, section = "Grok API"),
            ConfigFieldSpec("grok.apiUrl", "Grok API URL", ConfigFieldType.TEXT, section = "Grok API", description = "OpenAI \u517c\u5bb9\u5730\u5740\uff0c\u9700\u5305\u542b /chat/completions"),
            ConfigFieldSpec("grok.model", "Grok \u6a21\u578b", ConfigFieldType.TEXT, section = "Grok API", description = "\u4f8b\u5982 grok-4.5"),
            ConfigFieldSpec("grok.triggerPrefix", "Grok \u89e6\u53d1\u524d\u7f00", ConfigFieldType.TEXT, section = "Grok API", description = "\u9ed8\u8ba4 /grok"),
            ConfigFieldSpec("grok.useAsAtTrigger", "Grok \u4f5c\u4e3a @Bot \u89e6\u53d1", ConfigFieldType.BOOLEAN, section = "Grok API", description = "\u5f00\u542f\u540e @Bot \u8d70 Grok\uff0c\u4e14\u4e0e DeepSeek @Bot \u4e92\u65a5"),
            ConfigFieldSpec("grok.systemPrompt", "Grok \u7cfb\u7edf\u63d0\u793a\u8bcd", ConfigFieldType.TEXTAREA, section = "Grok API"),
            ConfigFieldSpec("webSearch.enabled", "\u542f\u7528\u8054\u7f51\u641c\u7d22", ConfigFieldType.BOOLEAN, section = "\u641c\u7d22"),
            ConfigFieldSpec("webSearch.maxRounds", "\u641c\u7d22\u6700\u5927\u8f6e\u6570", ConfigFieldType.NUMBER, section = "\u641c\u7d22", numberKind = top.colter.dynamic.core.config.ConfigNumberKind.INTEGER, min = 1, max = 50, restartRequired = true),
            ConfigFieldSpec("webSearch.tavilyApiKey", "Tavily API Key", ConfigFieldType.SECRET, section = "\u641c\u7d22", description = "\u7528\u4e8eURL\u9884\u6293\u53d6(\u53ef\u9009)"),
            ConfigFieldSpec("image.defaultColor", "\u56fe\u7247\u4e3b\u9898\u8272", ConfigFieldType.TEXT, section = "\u56fe\u7247", description = "\u5206\u53f7\u5206\u9694\uff0c\u5982 #667eea;#764ba2"),
            ConfigFieldSpec("image.factor", "\u56fe\u7247\u500d\u7387", ConfigFieldType.NUMBER, section = "\u56fe\u7247", numberKind = top.colter.dynamic.core.config.ConfigNumberKind.DECIMAL, min = 1, max = 4),
        )
    )

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        this.dataDir = context.dataStore.dataDir
        this.cacheUtils = CacheUtils(dataDir)
        this.fontRegistry = Fonts.default

        loadFonts()

        this.config = context.configService.loadOrCreate(configId, AgentPluginConfig::class, defaultProvider = { AgentPluginConfig() })

        this.dota2Service = Dota2Service(dataDir.toFile(), cacheUtils)
        this.dota2Service.init()

        rebuildServices()
    }

    override suspend fun onStart() {
        dota2Service.init()
    }

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
        this.dsClient = DeepSeekClient(config.api.apiKey, config.api.apiUrl)
        this.grokClient = if (config.grok.enabled && config.grok.apiKey.isNotBlank()) {
            DeepSeekClient(config.grok.apiKey, config.grok.apiUrl)
        } else null
        this.sessionManager = SessionManager(config.chat)
        this.chatService = ChatService(config, dsClient, sessionManager)

        this.messageListener = MessageListener(
            config = config, chatService = chatService, sessionManager = sessionManager,
            dota2Service = dota2Service, dsClient = dsClient, grokClient = grokClient,
            imageConfig = config.image, pluginContext = context, dataDir = dataDir,
            cacheUtils = cacheUtils, fontRegistry = fontRegistry,
        )

        this.commandHandlers = CommandHandlers(
            config = config, chatService = chatService, sessionManager = sessionManager,
            dota2Service = dota2Service, dsClient = dsClient, grokClient = grokClient,
            imageConfig = config.image, pluginContext = context, cacheUtils = cacheUtils,
            fontRegistry = fontRegistry,
        )
    }

    override fun currentConfig(): AgentPluginConfig = config

    override fun applyConfig(next: AgentPluginConfig): ConfigApplyResult {
        val normalized = if (next.grok.useAsAtTrigger && next.trigger.enableAtTrigger) {
            next.copy(trigger = next.trigger.copy(enableAtTrigger = false))
        } else next
        this.config = normalized
        context.configService.save(configId, normalized)
        rebuildServices()
        return ConfigApplyResult(
            changed = true,
            message = if (next.grok.useAsAtTrigger && next.trigger.enableAtTrigger) {
                "Grok @Bot \u89e6\u53d1\u5df2\u5f00\u542f\uff0c\u5df2\u81ea\u52a8\u5173\u95ed DeepSeek @Bot \u89e6\u53d1"
            } else ""
        )
    }

    override fun commandHandlers(): Collection<CommandHandler> = commandHandlers.all()

    override suspend fun onIncomingMessage(context: IncomingMessageDispatchContext) {
        messageListener.handle(context)
    }
}
