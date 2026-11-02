package top.colter.dynamic.agent.agent

import kotlinx.serialization.json.*
import top.colter.dynamic.agent.util.HttpUtils
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpTimeoutException
import java.time.Duration
import kotlin.coroutines.cancellation.CancellationException

internal data class WebSource(
    val title: String,
    val url: String,
    val date: String?,
    val summary: String,
    val content: String = "",
    val authority: Int? = null,
    val score: Double? = null
)

internal data class WebSearchResponse(val sources: List<WebSource>)
internal data class WebPageResponse(val title: String, val content: String)

/** Messages are intentionally fixed: upstream bodies and exception details can contain secrets. */
internal class WebRetrievalException(
    val code: String,
    val fallbackAllowed: Boolean,
    message: String
) : Exception(message)

internal data class WebHttpResponse(val status: Int, val body: String)

internal fun interface WebHttpTransport {
    suspend fun post(url: String, body: String, headers: Map<String, String>, timeoutSec: Long): WebHttpResponse
}

/** This client never follows redirects, so Authorization cannot escape the official host. */
private object OfficialWebTransport : WebHttpTransport {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    override suspend fun post(
        url: String, body: String, headers: Map<String, String>, timeoutSec: Long
    ): WebHttpResponse {
        val response = HttpUtils.httpPostAsync(url, body, headers, timeoutSec, client)
        return WebHttpResponse(response.statusCode(), response.body())
    }
}

internal class DeepSeekWebClient(
    apiKey: String,
    private val transport: WebHttpTransport = OfficialWebTransport
) {
    private val apiKey = apiKey.trim()

    suspend fun search(query: String, originalQuestion: String): WebSearchResponse {
        val normalizedQuery = query.trim().takeUnicode(128)
        if (normalizedQuery.isBlank()) fail("invalid_query", false, "搜索内容不能为空")
        val payload = buildJsonObject {
            put("query", normalizedQuery)
            put("count", 8)
            put("need_content", false)
            originalQuestion.trim().takeUnicode(128).takeIf(String::isNotBlank)?.let { put("ori_question", it) }
        }
        val data = request(SEARCH_ENDPOINT, payload, 10)
        val results = data["results"] as? JsonArray
            ?: fail("invalid_response", true, "搜索服务返回了无法识别的数据")
        val sources = results.mapNotNull { element ->
            val source = element as? JsonObject ?: return@mapNotNull null
            val url = source.string("url")?.takeIf(::validWebUrl) ?: return@mapNotNull null
            val summary = source.string("summary").orEmpty().ifBlank { source.string("snippet").orEmpty() }
            val content = source.string("content").orEmpty()
            if (summary.isBlank() && content.isBlank()) return@mapNotNull null
            WebSource(
                title = source.string("title").orEmpty(), url = url,
                date = source.string("date"), summary = summary, content = content,
                authority = (source["authority"] as? JsonPrimitive)?.intOrNull,
                score = (source["rerank_score"] as? JsonPrimitive)?.doubleOrNull?.takeIf(Double::isFinite)
            )
        }
        return WebSearchResponse(sources)
    }

    suspend fun fetch(url: String): WebPageResponse {
        val normalizedUrl = url.trim()
        if (!validWebUrl(normalizedUrl)) fail("invalid_url", false, "网页地址必须是无用户名和密码的 HTTP 或 HTTPS 链接")
        val data = request(FETCH_ENDPOINT, buildJsonObject { put("url", normalizedUrl) }, 15)
        val content = data.string("content")?.trim()
            ?: fail("invalid_response", true, "网页读取服务返回了无法识别的数据")
        if (content.isBlank()) fail("empty_content", true, "网页没有可读取的正文")
        val title = data.string("title").orEmpty()
        if (isProtectionPage(title, content)) fail("blocked_content", true, "网页返回了访问验证页面，未获得正文")
        return WebPageResponse(title, content)
    }

    private suspend fun request(endpoint: String, payload: JsonObject, timeoutSec: Long): JsonObject {
        if (apiKey.isBlank()) fail("missing_key", false, "请配置官方 DeepSeek API Key 后使用联网搜索")
        val response = try {
            transport.post(endpoint, payload.toString(), mapOf("Authorization" to "Bearer $apiKey"), timeoutSec)
        } catch (error: CancellationException) {
            throw error
        } catch (_: HttpTimeoutException) {
            fail("timeout", true, "联网服务响应超时")
        } catch (_: Exception) {
            fail("transport_error", true, "暂时无法连接联网服务")
        }
        when (response.status) {
            in 200..299 -> Unit
            401 -> fail("unauthorized", false, "DeepSeek 联网服务认证失败，请检查官方 API Key")
            402 -> fail("insufficient_balance", false, "DeepSeek 账户余额不足，请检查账户余额")
            403 -> fail("security_rejected", false, "该网页读取请求被安全策略拒绝，请更换来源，不要重试同一链接")
            404 -> fail("page_unavailable", false, "未获得该网页的可读正文，可搜索替代来源，不要重试同一链接")
            408 -> fail("timeout", true, "联网服务响应超时")
            429 -> fail("rate_limited", true, "联网服务请求过于频繁，请稍后再试")
            in 500..599 -> fail("upstream_unavailable", true, "联网服务暂时不可用")
            else -> fail("request_rejected", false, "联网服务未接受本次请求")
        }
        // A compromised proxy/transport must not echo the Bearer credential into model evidence.
        if (response.body.contains(apiKey)) fail("invalid_response", false, "联网服务返回了无法使用的数据")
        return try {
            HttpUtils.json.parseToJsonElement(response.body) as? JsonObject
                ?: fail("invalid_response", true, "联网服务返回了无法识别的数据")
        } catch (error: WebRetrievalException) {
            throw error
        } catch (_: Exception) {
            fail("invalid_response", true, "联网服务返回了无法识别的数据")
        }
    }

    private fun isProtectionPage(title: String, content: String): Boolean {
        val normalizedTitle = title.trim().lowercase()
        val opening = content.take(1500).lowercase()
        val clearMarkers = listOf("just a moment...", "verify you are human", "checking your browser", "akamai bot manager", "enable javascript and cookies to continue", "完成安全验证", "请完成验证后继续")
        val shortMarkers = listOf("access denied", "robot or human", "captcha", "访问被拒绝", "人机验证")
        return clearMarkers.any(normalizedTitle::contains) ||
            (content.length < 6000 && clearMarkers.any(opening::contains)) ||
            (content.length < 2500 && shortMarkers.any { normalizedTitle.contains(it) || opening.contains(it) })
    }

    companion object {
        const val SEARCH_ENDPOINT = "https://api.deepseek.com/v1/tools/search"
        const val FETCH_ENDPOINT = "https://api.deepseek.com/v1/tools/fetch"
    }
}

private fun JsonObject.string(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull || !it.isString }?.content

private fun String.takeUnicode(count: Int): String =
    if (codePointCount(0, length) <= count) this else substring(0, offsetByCodePoints(0, count))

private fun validWebUrl(value: String): Boolean {
    if (value.length > 8192) return false
    return try {
        val uri = URI(value)
        uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.rawUserInfo == null
    } catch (_: Exception) { false }
}

private fun fail(code: String, fallbackAllowed: Boolean, message: String): Nothing =
    throw WebRetrievalException(code, fallbackAllowed, message)
