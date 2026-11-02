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

/** Monotonic budget shared by model calls and all search tools. */
internal class SearchBudget(private val timeoutMs: Long, private val reserveMs: Long = 0, private val nowNanos: () -> Long = System::nanoTime) {
    private val started = nowNanos()
    private fun remainingMs() = timeoutMs - (nowNanos() - started) / 1_000_000
    suspend fun <T : Any> run(block: suspend () -> T): T? {
        val remaining = remainingMs() - reserveMs
        return if (remaining <= 0) null else withTimeoutOrNull(remaining) { block() }
    }
    suspend fun <T : Any> runFinal(limitMs: Long, block: suspend () -> T): T? {
        val remaining = minOf(remainingMs(), limitMs)
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
            val output = budget.run { agent.executeTool(call.function.name, call.function.arguments, call.id) ?: "本次调用未返回资料。" }
                ?: "本次调用未完成，未获取资料。"
            messages.add(ChatMessage("tool", output, toolCallId = call.id, name = call.function.name))
        }
        round++
    }
    val limited = agent.tools.isNotEmpty()
    if (limited) messages.add(ChatMessage("system", "停止调用工具，使用已有资料回答。回复的语言、语气、篇幅和排版遵循原有系统提示。不要编造事实；仅对影响结论且无法确认的部分作简短说明。"))
    val final = budget.runFinal(finalizeTimeoutMs) { ask(request(false)) }
        ?: throw ApiCallException("chat_timeout", message = "回复生成超时，本次任务已结束，请缩小问题范围后重试。")
    val content = answer(final.replyOrThrow())
    return content
}
