package top.colter.dynamic.agent.chat

import top.colter.dynamic.agent.agent.Agent
import top.colter.dynamic.agent.agent.AgentRouter
import top.colter.dynamic.agent.agent.FetchService
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.ds.*
import java.util.regex.Pattern

class ChatService(
    private val config: AgentPluginConfig,
    private val dsClient: DeepSeekClient,
    private val sessionManager: SessionManager,
) {
    private val urlPattern = Pattern.compile("""https?://[^\s\u4e00-\u9fff]+""")

    data class ChatResult(
        val content: String,
    )

    data class ChatOptions(
        val client: DeepSeekClient,
        val model: String,
        val systemPrompt: String,
        val enableWebSearch: Boolean,
    )

    suspend fun chat(
        prompt: String,
        sessionKey: SessionManager.SessionKey,
        options: ChatOptions? = null,
    ): ChatResult {
        val resolved = options ?: ChatOptions(
            client = dsClient,
            model = config.api.model,
            systemPrompt = config.chat.systemPrompt,
            enableWebSearch = config.webSearch.enabled,
        )
        val client = resolved.client
        val model = resolved.model
        val systemPrompt = resolved.systemPrompt
        // Capture this conversation before fetching URLs or waiting on the model.
        val turn = sessionManager.beginTurn(sessionKey, prompt, systemPrompt)

        val detectedUrls = urlPattern.matcher(prompt).let { matcher ->
            val urls = mutableListOf<String>()
            while (matcher.find()) urls.add(matcher.group())
            urls
        }

        val budget = SearchBudget(config.webSearch.totalTimeoutSeconds.coerceIn(10, 300) * 1000L)
        val fetchedContents = mutableListOf<String>()
        val failedUrls = mutableListOf<String>()
        if (resolved.enableWebSearch && detectedUrls.isNotEmpty()) {
            val results = budget.run {
                kotlinx.coroutines.withTimeoutOrNull(config.webSearch.fetchTimeout.coerceIn(1, 60) * 1000L) {
                    FetchService.fetchBatchViaTavily(detectedUrls, config.webSearch.tavilyApiKey)
                } ?: detectedUrls.map { FetchService.TavilyResult(it, false) }
            } ?: detectedUrls.map { FetchService.TavilyResult(it, false) }
            for (r in results) {
                if (r.success) fetchedContents.add("===页面内容: ${r.url}===\n${r.content}")
                else failedUrls.add(r.url)
            }
        }

        val augmentedPrompt = if (detectedUrls.isNotEmpty()) {
            val sb = StringBuilder(prompt)
            if (fetchedContents.isNotEmpty()) {
                sb.append("\n\n[系统已预先抓取以下网页的完整正文，请直接基于这些内容回复]\n")
                sb.append(fetchedContents.joinToString("\n\n---\n\n"))
            }
            if (failedUrls.isNotEmpty()) {
                val failedList = failedUrls.joinToString("、") { it }
                sb.append("\n\n[以下网页抓取失败: $failedList。")
                sb.append("请在回复的第一句说:\"进行联网搜索地址 $failedList 失败。\"")
                sb.append("然后使用web_search和web_fetch获取替代内容完成任务。]")
            }
            sb.toString()
        } else prompt

        val messages = turn.messages
        messages[messages.lastIndex] = ChatMessage("user", augmentedPrompt)

        val agent: Agent = if (resolved.enableWebSearch) {
            AgentRouter.create(config)
        } else {
            top.colter.dynamic.agent.agent.ChatAgent()
        }
        agent.prepareMessages(messages, augmentedPrompt, systemPrompt)

        val content = runChatSearch(
            messages, agent, model, config.chat.maxTokens, budget,
            config.webSearch.finalizeTimeoutSeconds.coerceIn(10, 180) * 1000L,
        ) { request -> client.chat(request) }

        if (config.chat.enableMemory) {
            sessionManager.saveTurn(sessionKey, turn, augmentedPrompt, content)
        }

        return ChatResult(content)
    }
}
