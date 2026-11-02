package top.colter.dynamic.agent.agent

import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import top.colter.dynamic.agent.util.HttpUtils
import kotlin.coroutines.cancellation.CancellationException

/** Optional legacy fallback chain. Main retrieval is handled by DeepSeekWebClient. */
object SearchClient {
    private val delegate = SearchBackendClient()

    suspend fun search(query: String): String = search(query) { true }
    suspend fun search(query: String, beforeAttempt: () -> Boolean): String = delegate.search(query, beforeAttempt)

    fun close() {}
}

internal data class SearchBackendRequest(
    val method: String,
    val url: String,
    val body: String = "",
    val headers: Map<String, String> = emptyMap(),
    val params: Map<String, String> = emptyMap(),
    val timeoutSec: Long = 5
)

internal fun interface SearchBackendTransport {
    suspend fun execute(request: SearchBackendRequest): WebHttpResponse
}

private object DefaultSearchBackendTransport : SearchBackendTransport {
    private val client = HttpUtils.createClient(connectTimeoutSec = 5, requestTimeoutSec = 5)
    override suspend fun execute(request: SearchBackendRequest): WebHttpResponse {
        val response = if (request.method == "GET") {
            HttpUtils.httpGetAsync(request.url, request.headers, request.params, request.timeoutSec, client)
        } else {
            HttpUtils.httpPostAsync(request.url, request.body, request.headers, request.timeoutSec, client)
        }
        return WebHttpResponse(response.statusCode(), response.body())
    }
}

internal class SearchBackendClient(private val transport: SearchBackendTransport = DefaultSearchBackendTransport) {
    suspend fun search(query: String, beforeAttempt: () -> Boolean = { true }): String {
        if (query.isBlank()) return ""
        for (backend in listOf<suspend (String) -> String>(::searchExa, ::searchParallel, ::searchDdgJson)) {
            // Charge a shared budget before every HTTP attempt, including failed backends.
            if (!beforeAttempt()) return ""
            val result = try {
                withTimeoutOrNull(5_000) { backend(query) }.orEmpty()
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) { "" }
            if (result.isNotBlank()) return result
        }
        return "所有搜索后端均失败"
    }

    suspend fun searchExa(query: String): String {
        val args = buildJsonObject {
            put("query", query); put("type", "auto"); put("numResults", 5)
            put("livecrawl", "fallback"); put("contextMaxCharacters", 10000)
        }
        val response = transport.execute(SearchBackendRequest(
            method = "POST", url = "https://mcp.exa.ai/mcp", body = mcpRequest("web_search_exa", args),
            headers = mapOf("Accept" to "application/json, text/event-stream")
        ))
        return mcpText(response)
    }

    suspend fun searchParallel(query: String): String {
        val args = buildJsonObject {
            put("objective", query); putJsonArray("search_queries") { add(query) }
        }
        val response = transport.execute(SearchBackendRequest(
            method = "POST", url = "https://search.parallel.ai/mcp", body = mcpRequest("web_search", args),
            headers = mapOf("Accept" to "application/json, text/event-stream", "User-Agent" to "opencode")
        ))
        return mcpText(response)
    }

    suspend fun searchDdgJson(query: String): String {
        val response = transport.execute(SearchBackendRequest(
            method = "GET", url = "https://api.duckduckgo.com/",
            headers = mapOf("User-Agent" to "curl/8.0"),
            params = mapOf("q" to query, "format" to "json", "no_html" to "1", "skip_disambig" to "1")
        ))
        if (response.status !in 200..299) return ""
        val data = HttpUtils.json.parseToJsonElement(response.body) as? JsonObject ?: return ""
        val abstract = data.text("AbstractText").trim()
        if (abstract.isEmpty()) return ""
        val source = canonicalUrl(data.text("AbstractURL")) ?: return ""
        val heading = data.text("Heading").trim()
        return buildString {
            if (heading.isNotEmpty()) appendLine(heading)
            append("来源: $source\n\n$abstract")
        }
    }

    private fun mcpRequest(name: String, arguments: JsonObject): String = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
        putJsonObject("params") { put("name", name); put("arguments", arguments) }
    }.toString()

    private fun mcpText(response: WebHttpResponse): String {
        if (response.status !in 200..299) return ""
        val raw = response.body.trim()
        val frames = if (raw.startsWith("{")) {
            listOfNotNull(parseFrame(raw))
        } else {
            // SSE events can have several data lines; blank lines terminate each event.
            raw.replace("\r\n", "\n").split(Regex("\n[\\t ]*\n")).mapNotNull { event ->
                val payload = event.lines().filter { it.startsWith("data:") }
                    .joinToString("\n") { it.removePrefix("data:").removePrefix(" ") }
                parseFrame(payload)
            }
        }
        // Reject the complete response if an MCP error occurs, even alongside text content.
        if (frames.any { frame ->
                (frame["error"] != null && frame["error"] !is JsonNull) ||
                    ((frame["result"] as? JsonObject)?.get("isError") as? JsonPrimitive)?.booleanOrNull == true
            }) return ""
        return frames.flatMap { frame ->
            ((frame["result"] as? JsonObject)?.get("content") as? JsonArray).orEmpty().mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                obj.text("text").takeIf { obj.text("type") == "text" && it.isNotBlank() }
            }
        }.distinct().joinToString("\n").trim()
    }

    private fun parseFrame(raw: String): JsonObject? = try {
        HttpUtils.json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) { null }

    private fun JsonObject.text(name: String): String =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
}
