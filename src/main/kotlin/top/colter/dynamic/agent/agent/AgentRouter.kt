package top.colter.dynamic.agent.agent

import top.colter.dynamic.agent.config.AgentPluginConfig

object AgentRouter {
    fun create(config: AgentPluginConfig): Agent {
        return if (config.webSearch.enabled) SearchAgent(config.webSearch) else ChatAgent()
    }
}
