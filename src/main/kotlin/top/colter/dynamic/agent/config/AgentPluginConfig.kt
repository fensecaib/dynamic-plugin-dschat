package top.colter.dynamic.agent.config

import kotlinx.serialization.Serializable

@Serializable
data class AgentPluginConfig(
    val api: ApiConfig = ApiConfig(),
    val chat: ChatConfig = ChatConfig(),
    val trigger: TriggerConfig = TriggerConfig(),
    val image: ImageConfig = ImageConfig(),
    val webSearch: WebSearchConfig = WebSearchConfig(),
    val dota2: Dota2Config = Dota2Config(),
    val weibo: WeiboDrawConfig = WeiboDrawConfig(),
)

@Serializable
data class ApiConfig(
    val apiKey: String = "",
    val apiUrl: String = "https://api.deepseek.com/chat/completions",
    val model: String = "deepseek-v4-flash",
)

@Serializable
data class ChatConfig(
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
)

@Serializable
data class ImageConfig(
    val defaultColor: String = "#667eea;#764ba2",
    val factor: Float = 1f,
)

@Serializable
data class WebSearchConfig(
    val enabled: Boolean = true,
    val maxRounds: Int = 15,
    val searchTimeout: Int = 30,
    val fetchMaxChars: Int = 8000,
    val fetchTimeout: Int = 15,
    val tavilyApiKey: String = "",
)

@Serializable
data class Dota2Config(
    val enableBinding: Boolean = true,
)

@Serializable
data class WeiboDrawConfig(
    val color: String = "#667eea;#764ba2",
    val factor: Float = 1f,
)
