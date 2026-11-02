package top.colter.dynamic.agent.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonIgnoreUnknownKeys
import top.colter.dynamic.core.config.ConfigMigration

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
data class AgentPluginConfig(
    val api: ApiConfig = ApiConfig(),
    val chat: ChatConfig = ChatConfig(),
    val trigger: TriggerConfig = TriggerConfig(),
    val image: ImageConfig = ImageConfig(),
    val webSearch: WebSearchConfig = WebSearchConfig(),
    val dota2: Dota2Config = Dota2Config(),
    val weibo: WeiboDrawConfig = WeiboDrawConfig(),
    val diagnostics: DiagnosticsConfig = DiagnosticsConfig(),
)

@Serializable
data class ApiConfig(
    val apiKey: String = "",
    val apiUrl: String = "https://api.deepseek.com/chat/completions",
    val model: String = "deepseek-flash",
    val requestTimeoutSeconds: Int = 120,
    val maxRetries: Int = 2,
)

@Serializable
data class ChatConfig(
    val enabled: Boolean = true,
    val systemPrompt: String = "你是一个有帮助的AI助手。",
    val maxTokens: Int = 4096,
    val textReplyThreshold: Int = 1200,
    val enableMemory: Boolean = true,
    val memoryRounds: Int = 10,
    val memoryTtlMinutes: Int = 30,
    val maxContextTokens: Int = 128000,
)

@Serializable
data class TriggerConfig(
    val triggerPrefix: String = "/ds",
    val enableAtTrigger: Boolean = true,
    val enableCommandTrigger: Boolean = true,
)

@Serializable
data class ImageConfig(
    val defaultColor: String = "#667eea;#764ba2",
    val factor: Float = 1f,
    val drawCacheRetentionHours: Int = 72,
)

@Serializable
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@JsonIgnoreUnknownKeys
data class WebSearchConfig(
    val enabled: Boolean = true,
    val officialApiKey: String = "",
    val fallbackEnabled: Boolean = false,
)

@Serializable
data class Dota2Config(
    val enableBinding: Boolean = true,
    val enabled: Boolean = true,
    val enableHistory: Boolean = true,
    val enableAnalysis: Boolean = true,
)

@Serializable
data class WeiboDrawConfig(
    val color: String = "#667eea;#764ba2",
    val factor: Float = 1f,
)

@Serializable
data class DiagnosticsConfig(
    val enabled: Boolean = true,
    val slowStageWarningSeconds: Int = 30,
)

/** 删除退役服务的凭据与设置，保留用户原先保存的 DeepSeek 触发选择。 */
internal val agentConfigMigrations = listOf(
    ConfigMigration(id = "remove-retired-grok-config", description = "移除退役对话服务的配置") {
        remove("grok")
    },
    ConfigMigration(id = "remove-retired-tavily-config", description = "移除退役网页预读取服务的配置") {
        remove("webSearch.tavilyApiKey")
    },
    ConfigMigration(id = "simplify-web-search-budget-config-v008", description = "移除由联网证据预算统一管理的旧调节项") {
        remove("webSearch.maxRounds")
        remove("webSearch.totalTimeoutSeconds")
        remove("webSearch.finalizeTimeoutSeconds")
        remove("webSearch.searchTimeout")
        remove("webSearch.fetchTimeout")
        remove("webSearch.fetchMaxChars")
    },
)

/** 仅迁移 DeepSeek 官方服务的旧 Flash 别名，保留第三方供应商自己的模型命名。 */
internal fun normalizeAgentConfig(config: AgentPluginConfig): AgentPluginConfig {
    val officialEndpoint = isOfficialDeepSeekEndpoint(config.api.apiUrl)
    val oldFlashAlias = config.api.model.trim() in setOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp")
    return if (officialEndpoint && oldFlashAlias) {
        config.copy(api = config.api.copy(model = "deepseek-flash"))
    } else config
}
