package top.colter.dynamic.agent.chat

import top.colter.dynamic.agent.agent.AgentRouter
import top.colter.dynamic.agent.agent.Agent
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.ds.*

class ChatService(
    private val config: AgentPluginConfig,
    private val dsClient: DeepSeekClient,
    private val sessionManager: SessionManager,
    private val agentFactory: (AgentPluginConfig) -> Agent = AgentRouter::create,
) {
    data class ChatResult(
        val content: String,
    )

    suspend fun chat(
        prompt: String,
        sessionKey: SessionManager.SessionKey,
    ): ChatResult {
        val model = config.api.model
        val systemPrompt = config.chat.systemPrompt
        // Capture this conversation before waiting on the model or running its tools.
        val turn = sessionManager.beginTurn(sessionKey, prompt, systemPrompt)
        val budget = if (config.webSearch.enabled) SearchBudget(90000, reserveMs = 30000)
            else SearchBudget(config.api.requestTimeoutSeconds.coerceIn(10, 300) * 1000L)
        val messages = turn.messages
        val agent = agentFactory(config)
        agent.prepareMessages(messages, prompt, systemPrompt)

        val content = runChatSearch(
            messages, agent, model, config.chat.maxTokens, budget,
            if (config.webSearch.enabled) 30000L else config.api.requestTimeoutSeconds.coerceIn(10, 300) * 1000L,
        ) { request -> dsClient.chat(request) }

        // The manager applies the current memory policy, including changes during this request.
        sessionManager.saveTurn(sessionKey, turn, prompt, content)

        return ChatResult(content)
    }
}
