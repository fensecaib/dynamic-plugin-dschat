package top.colter.dynamic.agent.agent

import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import top.colter.dynamic.agent.util.HttpUtils
import kotlin.coroutines.cancellation.CancellationException

object FetchService {
    private val client = HttpUtils.createClient(connectTimeoutSec = 10, requestTimeoutSec = 15)

    suspend fun fetch(url: String, maxChars: Int = 8000): String {
        return try {
            val resp = HttpUtils.httpGet(url,
                headers = mapOf("User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"),
                timeoutSec = 15, client = client)

            if (resp.statusCode() !in 200..299) {
                return "获取页面失败: HTTP ${resp.statusCode()}"
            }

            val ct = (resp.headers().firstValue("Content-Type").orElse(""))
            if (ct.isNotEmpty() && !ct.contains("text/html") && !ct.contains("text/plain")) {
                return "不支持的内容类型: $ct"
            }

            val text = extractText(resp.body())
            if (text.isBlank()) return "页面无有效文本内容"
            if (maxChars > 0 && text.length > maxChars) "${text.take(maxChars)}\n\n[...内容过长，已截断]"
            else text
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            "获取页面失败: ${e.message}"
        }
    }

    private fun extractText(html: String): String {
        return try {
            val doc = Jsoup.parse(html)
            doc.select("script, style, nav, footer, header, " +
                "iframe, noscript, .sidebar, .advertisement, " +
                "[role=navigation], [role=banner]").remove()

            doc.body().wholeText()
                .replace(Regex("\\n{3,}"), "\n\n")
                .replace(Regex("[\\t ]+"), " ")
                .trim()
        } catch (_: Exception) {
            html.replace(Regex("<[^>]*>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private val tavilyClient = HttpUtils.createClient(connectTimeoutSec = 15, requestTimeoutSec = 90)

    data class TavilyResult(
        val url: String,
        val success: Boolean,
        val content: String = ""
    )

    suspend fun fetchBatchViaTavily(urls: List<String>, apiKey: String): List<TavilyResult> {
        if (urls.isEmpty()) return emptyList()
        if (apiKey.isBlank()) {
            return urls.map { TavilyResult(it, false) }
        }
        return try {
            val body = HttpUtils.json.encodeToString(JsonObject.serializer(), buildJsonObject {
                putJsonArray("urls") { urls.forEach { add(it) } }
                put("extract_depth", "advanced")
                put("format", "text")
                put("chunks_per_source", 5)
                put("timeout", 60)
            })
            val resp = HttpUtils.httpPost(
                "https://api.tavily.com/extract", body,
                headers = mapOf("Authorization" to "Bearer $apiKey"),
                timeoutSec = 90, client = tavilyClient
            )
            if (resp.statusCode() !in 200..299) {
                return urls.map { TavilyResult(it, false) }
            }
            val data = HttpUtils.json.decodeFromString(JsonObject.serializer(), resp.body())
            val results = data["results"]?.jsonArray ?: return urls.map { TavilyResult(it, false) }
            urls.map { url ->
                val match = results.find {
                    it.jsonObject["url"]?.jsonPrimitive?.content == url
                }
                if (match != null) {
                    val content = match.jsonObject["raw_content"]?.jsonPrimitive?.content ?: ""
                    TavilyResult(url, content.isNotBlank(), content)
                } else {
                    TavilyResult(url, false)
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            urls.map { TavilyResult(it, false) }
        }
    }

    fun close() {}
}
