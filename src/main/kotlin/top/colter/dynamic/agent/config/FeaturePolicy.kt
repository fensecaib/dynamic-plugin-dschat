package top.colter.dynamic.agent.config

import top.colter.dynamic.agent.dota2.Dota2ReportMode
import top.colter.dynamic.agent.dota2.OverviewMode

/** 在接收消息和宿主命令入口复用，先判断开关，再判断所需的 API 配置。 */
internal fun AgentPluginConfig.chatCommandUnavailable(): String? {
    if (!chat.enabled) return "AI 对话功能已暂停，请在插件管理的功能开关中启用"
    if (!trigger.enableCommandTrigger) return "DeepSeek 命令对话已暂停，请在插件管理中启用命令触发"
    return deepSeekUnavailable()
}

/** @Bot 触发只在开关及配置全部就绪时响应，未配置时保持安静。 */
internal fun AgentPluginConfig.chatAtAvailable(): Boolean =
    chat.enabled && trigger.enableAtTrigger && deepSeekUnavailable() == null

/** Dota 绑定和历史不依赖 AI；战报、双阵营分析及个人详情依赖 DeepSeek。 */
internal fun AgentPluginConfig.dotaCommandUnavailable(command: String?): String? {
    if (!dota2.enabled) return "Dota2 功能已暂停，请在插件管理的功能开关中启用"
    return when {
        command == "绑定" && !dota2.enableBinding -> "Dota2 账号绑定已暂停，请在插件管理中启用"
        command == "历史" && !dota2.enableHistory -> "Dota2 历史查询已暂停，请在插件管理中启用"
        command == "分析" || Dota2ReportMode.fromCommand(command) != null || OverviewMode.fromCommand(command) != null -> {
            if (!dota2.enableAnalysis) "Dota2 AI 分析已暂停，请在插件管理中启用"
            else deepSeekUnavailable()?.let { "Dota2 AI 分析不可用：$it" }
        }
        else -> null
    }
}

internal fun AgentPluginConfig.deepSeekUnavailable(): String? = when {
    api.apiKey.isBlank() -> "请先在插件配置中填写 DeepSeek API Key"
    api.apiUrl.isBlank() -> "请先在插件配置中填写 DeepSeek API URL"
    api.model.isBlank() -> "请先在插件配置中选择 DeepSeek 模型"
    else -> null
}
