package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import top.colter.dynamic.agent.ds.ApiCallException
import top.colter.dynamic.agent.util.AgentDiagnostics

/** User-facing failure text is selected locally; never forward upstream bodies or exception causes. */
internal class DotaAnalysisUnavailableException(
    val category: String,
    val userMessage: String,
) : RuntimeException(userMessage)

internal fun analysisUnavailable(error: Throwable? = null, category: String = "analysis_format"): DotaAnalysisUnavailableException {
    if (error is CancellationException) throw error
    val requested = (error as? ApiCallException)?.category ?: category
    val detail = when (requested) {
        "unauthorized" -> "DeepSeek 认证失败，请联系管理员检查 API Key。"
        "payment_required" -> "DeepSeek 账户余额不足，请联系管理员处理。"
        "rate_limited" -> "DeepSeek 请求受到限流，请稍后重试。"
        "server_error" -> "DeepSeek 服务暂时异常，请稍后重试。"
        "network_timeout" -> "等待 DeepSeek 响应超时，请稍后重试。"
        "network_error" -> "暂时无法连接 DeepSeek，请稍后重试。"
        "analysis_timeout" -> "本次分析等待超时，已停止生成，请稍后重试。"
        "analysis_data" -> "比赛数据不足，无法完成本次分析。"
        "missing_api_key", "missing_api_url", "invalid_api_url", "invalid_request", "http_error" ->
            "DeepSeek 接口配置或请求异常，请联系管理员检查。"
        "analysis_format", "response_json", "empty_response", "empty_choices", "empty_content", "response_incomplete", "invalid_tool_calls", "api_error" ->
            "未获得完整有效的 DeepSeek 分析结果，请稍后重试。"
        else -> return DotaAnalysisUnavailableException("analysis_error", "当前 AI 分析暂不可用，请稍后重试。仍可使用 /dota历史 查询战绩。")
    }
    return DotaAnalysisUnavailableException(requested, "当前 AI 分析暂不可用：$detail 仍可使用 /dota历史 查询战绩。")
}

/** Leaves room for data queries and final delivery inside QQ's passive reply window. */
internal suspend fun <T> withDotaAnalysisBudget(timeoutMillis: Long = 180_000, block: suspend () -> T): T {
    require(timeoutMillis > 0)
    val completed = withTimeoutOrNull(timeoutMillis) { CompletedAnalysis(block()) }
    if (completed != null) return completed.value
    AgentDiagnostics.event("agent_analysis", "unavailable", mapOf("category" to "analysis_timeout", "budget_ms" to timeoutMillis), warning = true)
    throw analysisUnavailable(category = "analysis_timeout")
}

private class CompletedAnalysis<T>(val value: T)
