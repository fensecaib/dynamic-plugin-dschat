package top.colter.dynamic.agent.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
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
        val isLong: Boolean,
    )

    suspend fun chat(
        prompt: String,
        sessionKey: SessionManager.SessionKey,
    ): ChatResult {
        val systemPrompt = config.chat.systemPrompt

        val detectedUrls = urlPattern.matcher(prompt).let { matcher ->
            val urls = mutableListOf<String>()
            while (matcher.find()) urls.add(matcher.group())
            urls
        }

        val fetchedContents = mutableListOf<String>()
        val failedUrls = mutableListOf<String>()
        if (detectedUrls.isNotEmpty()) {
            val results = FetchService.fetchBatchViaTavily(detectedUrls, config.webSearch.tavilyApiKey)
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

        val messages = sessionManager.buildMessages(sessionKey, augmentedPrompt, systemPrompt)

        val agent: Agent = AgentRouter.create(config)
        agent.prepareMessages(messages, augmentedPrompt, systemPrompt)

        var round = 0
        var finalContent: String? = null

        if (!agent.shouldContinue(0)) {
            val request = ChatRequest(
                model = config.api.model,
                messages = messages.toList(),
                maxTokens = config.chat.maxTokens,
            )
            val result = dsClient.chat(request)
            finalContent = result.fold(
                onSuccess = { it.choices.firstOrNull()?.message?.content ?: "(空回复)" },
                onFailure = { "请求失败: ${it.message ?: "未知错误"}" }
            )
        }

        while (agent.shouldContinue(round)) {
            val request = ChatRequest(
                model = config.api.model,
                messages = messages.toList(),
                maxTokens = config.chat.maxTokens,
                tools = agent.tools.takeIf { it.isNotEmpty() },
                toolChoice = if (agent.tools.isNotEmpty()) "auto" else null,
            )

            val result = dsClient.chat(request)

            result.fold(
                onSuccess = { response ->
                    val choice = response.choices.firstOrNull()
                    val replyMsg = choice?.message

                    if (replyMsg?.toolCalls?.isNotEmpty() == true) {
                        messages.add(replyMsg)

                        for (call in replyMsg.toolCalls) {
                            val toolResult = agent.executeTool(
                                call.function.name,
                                call.function.arguments,
                                call.id
                            ) ?: "工具执行返回空结果"

                            messages.add(ChatMessage(
                                role = "tool",
                                content = toolResult,
                                toolCallId = call.id,
                                name = call.function.name,
                            ))
                        }
                        round++
                        return@fold
                    }

                    finalContent = when (choice?.finishReason) {
                        "stop" -> replyMsg?.content ?: "(空回复)"
                        "length" -> (replyMsg?.content ?: "") + "\n\n---\n(回复过长，已被截断)"
                        "content_filter" -> "内容已被安全过滤，请尝试换一种表达方式。"
                        "insufficient_system_resource" -> "服务繁忙，请稍后重试。"
                        else -> replyMsg?.content ?: "未知错误，请稍后重试。"
                    }
                },
                onFailure = { e ->
                    finalContent = "请求失败: ${e.message ?: "未知错误"}"
                }
            )

            if (finalContent != null) break
        }

        val content = finalContent ?: "(搜索轮次超限，请简化问题重试)"

        if (config.chat.enableMemory) {
            sessionManager.saveToSession(sessionKey, augmentedPrompt, content)
        }

        val isLong = config.chat.textReplyThreshold > 0 && content.length > config.chat.textReplyThreshold
        return ChatResult(content, isLong)
    }
}
