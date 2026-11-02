package top.colter.dynamic.agent.ds

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import top.colter.dynamic.agent.util.AgentDiagnostics
import top.colter.dynamic.agent.util.HttpUtils
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class DeepSeekClient(
    private val apiKey: String,
    private val apiUrl: String,
    requestTimeoutSeconds: Long = 120,
    maxRetries: Int = 2,
) {
    private val requestTimeoutSeconds = requestTimeoutSeconds.coerceAtLeast(1)
    private val defaultRetries = maxRetries.coerceAtLeast(0)
    // Keep the credential and conversation body on the explicitly configured endpoint.
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(minOf(30, this.requestTimeoutSeconds)))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    suspend fun chat(request: ChatRequest, retries: Int = defaultRetries): Result<ChatResponse> {
        currentCoroutineContext().ensureActive()
        val totalAttempts = retries.coerceIn(0, Int.MAX_VALUE - 1) + 1
        val fields = mapOf(
            "model" to safeModelLogLabel(request.model, apiKey),
            "thinking" to when (request.thinking?.type) {
                null -> "unspecified"
                "enabled" -> "enabled"
                "disabled" -> "disabled"
                else -> "other"
            },
            "total_attempts" to totalAttempts,
            "timeout_seconds" to requestTimeoutSeconds,
        )
        suspend fun configurationFailure(category: String, message: String): Result<ChatResponse> {
            AgentDiagnostics.event("agent_ai", "failed", fields + ("category" to category), warning = true)
            return Result.failure(ApiCallException(category, message = message))
        }
        if (apiKey.isBlank()) {
            return configurationFailure("missing_api_key", "未配置 API Key，请先在宿主插件管理中配置对应的 API。")
        }
        if (apiUrl.isBlank()) {
            return configurationFailure("missing_api_url", "未配置 API URL，请先在宿主插件管理中配置对应的 API。")
        }
        val validUrl = runCatching { URI.create(apiUrl) }.getOrNull()?.let {
            it.scheme in setOf("http", "https") && !it.host.isNullOrBlank() && it.userInfo == null
        } == true
        if (!validUrl) {
            return configurationFailure("invalid_api_url", "API URL 格式无效，请检查插件中的接口地址。")
        }
        val requestBody = try {
            HttpUtils.json.encodeToString(ChatRequest.serializer(), request)
        } catch (_: Exception) {
            return configurationFailure("invalid_request", "无法构建 AI 请求，请检查模型及请求配置。")
        }

        var lastFailure: ApiCallException? = null
        for (attempt in 1..totalAttempts) {
            currentCoroutineContext().ensureActive()
            val attemptFields = fields + ("attempt" to attempt)
            if (attempt > 1) {
                val delayMs = (attempt - 1) * 2_000L
                AgentDiagnostics.event("agent_ai", "retry", attemptFields + mapOf(
                    "category" to lastFailure?.category,
                    "http_status" to lastFailure?.statusCode,
                    "retry_delay_ms" to delayMs,
                ), warning = true)
                try {
                    delay(delayMs)
                } catch (e: CancellationException) {
                    AgentDiagnostics.event("agent_ai", "cancelled", attemptFields + ("phase" to "retry_wait"))
                    throw e
                }
            }
            val started = System.nanoTime()
            fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
            AgentDiagnostics.event("agent_ai", "start", attemptFields + ("elapsed_ms" to 0))
            val response = try {
                // Bound the whole response, including providers that send headers or blank
                // keep-alive bytes early and then stall before completing their JSON body.
                withTimeoutOrNull(requestTimeoutSeconds.seconds) {
                    runInterruptible(Dispatchers.IO) {
                        HttpUtils.httpPost(
                            url = apiUrl,
                            body = requestBody,
                            headers = mapOf("Authorization" to "Bearer $apiKey"),
                            timeoutSec = requestTimeoutSeconds,
                            client = client,
                        )
                    }
                } ?: throw HttpTimeoutException("AI request deadline exceeded")
            } catch (e: CancellationException) {
                AgentDiagnostics.event("agent_ai", "cancelled", attemptFields + ("elapsed_ms" to elapsedMs()))
                throw e
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                val failure = when (e) {
                    is HttpTimeoutException, is java.net.SocketTimeoutException -> ApiCallException(
                        "network_timeout", message = "AI 请求超时，请稍后重试或调整请求超时设置。",
                    )
                    is IllegalArgumentException -> ApiCallException(
                        "invalid_request", message = "AI 请求配置无效，请检查接口地址及 API Key 格式。",
                    )
                    else -> ApiCallException(
                        "network_error", message = "无法连接 AI 服务，请检查网络和接口地址。",
                    )
                }
                AgentDiagnostics.event("agent_ai", "failed", attemptFields + mapOf(
                    "category" to failure.category,
                    "elapsed_ms" to elapsedMs(),
                ), warning = true)
                if (failure.category == "invalid_request") return Result.failure(failure)
                lastFailure = failure
                continue
            }
            val status = response.statusCode()
            if (status !in 200..299) {
                val failure = httpFailure(status)
                AgentDiagnostics.event("agent_ai", "failed", attemptFields + mapOf(
                    "category" to failure.category,
                    "http_status" to status,
                    "elapsed_ms" to elapsedMs(),
                ), warning = true)
                if (status !in 500..599) return Result.failure(failure)
                lastFailure = failure
                continue
            }

            // Repeating a valid HTTP request cannot safely repair an incomplete answer.
            val decoded = decodeResponse(response.body(), status)
            val failure = decoded.result.exceptionOrNull() as? ApiCallException
            if (failure != null) {
                AgentDiagnostics.event("agent_ai", "failed", attemptFields + decoded.details + mapOf(
                    "category" to failure.category,
                    "http_status" to status,
                    "elapsed_ms" to elapsedMs(),
                ), warning = true)
                return Result.failure(failure)
            }
            val chatResponse = decoded.result.getOrThrow()
            AgentDiagnostics.event("agent_ai", "success", attemptFields + decoded.details + mapOf(
                "http_status" to status,
                "elapsed_ms" to elapsedMs(),
            ))
            return Result.success(chatResponse)
        }
        return Result.failure(lastFailure ?: ApiCallException("network_error", message = "AI 请求失败，请稍后重试。"))
    }

    fun close() {
        // The JDK 17 HttpClient has no close operation.
    }
}

/** Only fixed categories and text escape this boundary; upstream bodies and causes may contain secrets. */
private fun httpFailure(status: Int): ApiCallException = when (status) {
    in 300..399 -> ApiCallException("redirect_rejected", status, "AI 接口返回重定向（HTTP $status），请填写最终的直接请求地址。")
    401 -> ApiCallException("unauthorized", status, "AI 服务认证失败（HTTP 401），请检查 API Key。")
    402 -> ApiCallException("payment_required", status, "AI 服务余额不足（HTTP 402），请检查账户余额。")
    429 -> ApiCallException("rate_limited", status, "AI 服务请求过于频繁（HTTP 429），请稍后重试。")
    in 500..599 -> ApiCallException("server_error", status, "AI 服务暂时异常（HTTP $status），请稍后重试。")
    else -> ApiCallException("http_error", status, "AI 服务拒绝请求（HTTP $status），请检查接口及模型配置。")
}

private data class ResponseDecode(val result: Result<ChatResponse>, val details: Map<String, Any?>)

private fun decodeResponse(body: String, status: Int): ResponseDecode {
    var details = emptyMap<String, Any?>()
    fun fail(category: String, message: String) = ResponseDecode(
        Result.failure(ApiCallException(category, status, message)), details,
    )
    if (body.isBlank()) return fail("empty_response", "AI 服务返回空响应，未获得完整结果。")
    val payload = try {
        HttpUtils.json.parseToJsonElement(body) as? JsonObject
            ?: return fail("response_json", "AI 服务返回了无效响应格式，未获得完整结果。")
    } catch (_: Exception) {
        return fail("response_json", "AI 服务返回了无效 JSON，未获得完整结果。")
    }
    if (payload["error"] != null && payload["error"] != JsonNull) {
        return fail("api_error", "AI 服务返回错误信息，未获得完整结果，请查看服务账户及配置。")
    }
    val response = try {
        HttpUtils.json.decodeFromJsonElement(ChatResponse.serializer(), payload)
    } catch (_: Exception) {
        return fail("response_json", "AI 服务返回了不兼容的响应结构，未获得完整结果。")
    }
    if (response.choices.isEmpty()) return fail("empty_choices", "AI 服务未返回任何回答，未获得完整结果。")
    val choice = response.choices.first()
    details = mapOf(
        "finish_reason" to safeFinishReason(choice.finishReason),
        "prompt_tokens" to response.usage?.promptTokens,
        "completion_tokens" to response.usage?.completionTokens,
        "total_tokens" to response.usage?.totalTokens,
    )
    val toolCalls = choice.message?.toolCalls.orEmpty()
    if (choice.finishReason !in setOf("", "stop", "tool_calls")) {
        val message = if (choice.finishReason == "length") "AI 回复达到长度上限，结果不完整，请提高输出上限或缩小请求范围。"
        else "AI 回复未正常完成，未获得完整结果，请调整请求后重试。"
        return fail("response_incomplete", message)
    }
    if (toolCalls.isNotEmpty()) {
        if (toolCalls.map { it.id }.distinct().size != toolCalls.size || toolCalls.any {
                it.id.isBlank() || it.type != "function" || it.function.name.isBlank() ||
                    runCatching { HttpUtils.json.parseToJsonElement(it.function.arguments) }.getOrNull() !is JsonObject
            }) {
            return fail("invalid_tool_calls", "AI 服务返回了不完整的工具调用，无法继续执行。")
        }
    } else if (choice.finishReason == "tool_calls") {
        return fail("invalid_tool_calls", "AI 服务未返回工具调用内容，无法继续执行。")
    } else if (choice.message?.content.isNullOrBlank()) {
        return fail("empty_content", "AI 服务未返回最终回答，未获得完整结果。")
    }
    return ResponseDecode(Result.success(response), details)
}

internal fun safeModelLogLabel(model: String, apiKey: String): String = when {
    apiKey.isNotBlank() && model.contains(apiKey) -> "redacted"
    model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,95}")) -> model
    else -> "custom"
}

private fun safeFinishReason(reason: String): String = when (reason) {
    "" -> "unspecified"
    "stop", "tool_calls", "length", "content_filter", "insufficient_system_resource" -> reason
    else -> "other"
}
