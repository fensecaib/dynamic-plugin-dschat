package top.colter.dynamic.agent.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import top.colter.dynamic.agent.util.HttpUtils

object SearchClient {
    private const val EXA_URL = "https://mcp.exa.ai/mcp"
    private const val PARALLEL_URL = "https://search.parallel.ai/mcp"
    private const val DDG_URL = "https://api.duckduckgo.com/"

    private val client = HttpUtils.createClient(connectTimeoutSec = 10, requestTimeoutSec = 30)

    @Serializable
    private data class McpRequest(
        val jsonrpc: String = "2.0",
        val id: Int = 1,
        val method: String,
        val params: McpParams
    )

    @Serializable
    private data class McpParams(
        val name: String,
        val arguments: JsonObject
    )

    suspend fun search(query: String): String {
        try {
            val result = searchExa(query)
            if (result.isNotBlank()) return result
        } catch (_: Exception) {}

        try {
            val result = searchParallel(query)
            if (result.isNotBlank()) return result
        } catch (_: Exception) {}

        try {
            val result = searchDdgJson(query)
            if (result.isNotBlank()) return result
        } catch (_: Exception) {}

        return "所有搜索后端均失败"
    }

    private suspend fun searchExa(query: String): String {
        val args = buildJsonObject {
            put("query", query)
            put("type", "auto")
            put("numResults", 5)
            put("livecrawl", "fallback")
            put("contextMaxCharacters", 10000)
        }
        val body = HttpUtils.json.encodeToString(McpRequest.serializer(),
            McpRequest(method = "tools/call", params = McpParams("web_search_exa", args)))

        val resp = HttpUtils.httpPost(EXA_URL, body,
            headers = mapOf("Accept" to "application/json, text/event-stream"),
            timeoutSec = 30, client = client)
        require(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}" }
        return parseSseText(resp.body())
    }

    private suspend fun searchParallel(query: String): String {
        val args = buildJsonObject {
            put("objective", query)
            putJsonArray("search_queries") { add(query) }
        }
        val body = HttpUtils.json.encodeToString(McpRequest.serializer(),
            McpRequest(method = "tools/call", params = McpParams("web_search", args)))

        val resp = HttpUtils.httpPost(PARALLEL_URL, body,
            headers = mapOf(
                "Accept" to "application/json, text/event-stream",
                "User-Agent" to "opencode"
            ),
            timeoutSec = 30, client = client)
        require(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}" }
        return extractTextContent(HttpUtils.json.decodeFromString(JsonObject.serializer(), resp.body()))
    }

    private suspend fun searchDdgJson(query: String): String {
        val resp = HttpUtils.httpGet(DDG_URL,
            params = mapOf(
                "q" to query, "format" to "json",
                "no_html" to "1", "skip_disambig" to "1"
            ),
            headers = mapOf("User-Agent" to "curl/8.0"),
            timeoutSec = 30, client = client)
        require(resp.statusCode() in 200..299) { "HTTP ${resp.statusCode()}" }

        val data = HttpUtils.json.decodeFromString(JsonObject.serializer(), resp.body())
        val abstract = data["AbstractText"]?.jsonPrimitive?.content?.trim() ?: ""
        val heading = data["Heading"]?.jsonPrimitive?.content?.trim() ?: ""
        if (abstract.isEmpty()) return ""

        return if (heading.isNotEmpty()) "$heading\n\n$abstract" else abstract
    }

    private fun parseSseText(raw: String): String {
        val sb = StringBuilder()
        for (line in raw.split("\n")) {
            if (!line.startsWith("data: ")) continue
            try {
                val data = HttpUtils.json.decodeFromString(JsonObject.serializer(), line.substring(6))
                sb.append(extractTextContent(data))
            } catch (_: Exception) {}
        }
        return sb.toString().trim()
    }

    private fun extractTextContent(data: JsonObject): String {
        val result = data["result"]?.jsonObject ?: return ""
        val content = result["content"]?.jsonArray ?: return ""
        val sb = StringBuilder()
        for (item in content) {
            val obj = item.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "text") {
                sb.append(obj["text"]?.jsonPrimitive?.content ?: "")
            }
        }
        return sb.toString()
    }

    fun close() {}
}
