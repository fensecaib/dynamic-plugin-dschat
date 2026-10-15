package top.colter.dynamic.agent.chat

import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.agent.agent.Agent
import top.colter.dynamic.agent.ds.*
import kotlin.coroutines.cancellation.CancellationException

/** A transport failure is not an assistant answer and must never enter conversation memory. */
private fun Result<ChatResponse>.replyOrThrow(): ChatResponse = getOrElse { failure ->
    throw when (failure) {
        is CancellationException -> failure
        is ApiCallException -> failure
        else -> ApiCallException("chat_request_failed", message = "AI 请求失败，请稍后重试。")
    }
}

/** Monotonic budget shared by URL prefetch, model calls and all search tools. */
internal class SearchBudget(private val timeoutMs: Long) {
    private val started = System.nanoTime()
    private fun remainingMs() = timeoutMs - (System.nanoTime() - started) / 1_000_000
    suspend fun <T : Any> run(block: suspend () -> T): T? {
        val remaining = remainingMs()
        return if (remaining <= 0) null else withTimeoutOrNull(remaining) { block() }
    }
}

internal suspend fun runChatSearch(
    messages: MutableList<ChatMessage>, agent: Agent, model: String, maxTokens: Int,
    budget: SearchBudget, finalizeTimeoutMs: Long,
    ask: suspend (ChatRequest) -> Result<ChatResponse>,
): String {
    fun answer(response: ChatResponse): String {
        val choice = response.choices.firstOrNull()
        return when (choice?.finishReason) {
            "length" -> (choice.message?.content ?: "") + "\n\n（回复达到长度上限）"
            "content_filter" -> "内容已被安全过滤，请尝试换一种表达方式。"
            else -> choice?.message?.content?.takeIf { it.isNotBlank() } ?: "未能生成有效回复，请稍后重试。"
        }
    }
    fun request(tools: Boolean) = ChatRequest(model = model, messages = messages.toList(), maxTokens = maxTokens,
        tools = agent.tools.takeIf { tools && it.isNotEmpty() }, toolChoice = if (tools && agent.tools.isNotEmpty()) "auto" else null)
    var round = 0
    while (agent.shouldContinue(round)) {
        val result = budget.run { ask(request(true)) } ?: break
        val response = result.replyOrThrow()
        val reply = response.choices.firstOrNull()?.message
        val calls = reply?.toolCalls.orEmpty()
        if (calls.isEmpty()) return answer(response)
        messages.add(checkNotNull(reply))
        // Always complete every tool_call_id, even if the budget expires mid-batch.
        for (call in calls) {
            val output = budget.run { agent.executeTool(call.function.name, call.function.arguments, call.id) ?: "工具返回空结果" }
                ?: "搜索时间预算已用尽，此工具未完成。请使用已有资料并说明信息限制。"
            messages.add(ChatMessage("tool", output, toolCallId = call.id, name = call.function.name))
        }
        round++
    }
    val limited = agent.tools.isNotEmpty()
    if (limited) messages.add(ChatMessage("system", "搜索已达到轮数或时间预算。停止调用工具，立即根据已获取的资料回答。不要编造未查证信息；资料不足时明确说明。"))
    val final = withTimeoutOrNull(finalizeTimeoutMs) { ask(request(false)) }
        ?: throw ApiCallException("chat_timeout", message = "回复生成超时，本次任务已结束，请缩小问题范围后重试。")
    val content = answer(final.replyOrThrow())
    return if (limited) "（联网搜索已达到轮数或时间上限，以下根据已获取资料整理。）\n\n$content" else content
}
