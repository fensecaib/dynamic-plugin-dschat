package top.colter.dynamic.agent.agent

import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.resolveSearchApiKey

object AgentRouter {
    fun create(config: AgentPluginConfig): Agent {
        return if (config.webSearch.enabled) SearchAgent(config.webSearch, resolveSearchApiKey(config)) else ChatAgent()
    }
}
