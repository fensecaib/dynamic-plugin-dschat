package top.colter.dynamic.agent.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class SearchBackendClientTest {
    private fun result(text: String, error: Boolean = false): String = buildJsonObject {
        put("jsonrpc", "2.0")
        putJsonObject("result") {
            put("isError", error)
            putJsonArray("content") { addJsonObject { put("type", "text"); put("text", text) } }
        }
    }.toString()

    @Test
    fun acceptsJsonAndStopsBeforeAdditionalProviders() = runBlocking {
        val requests = mutableListOf<SearchBackendRequest>()
        var charges = 0
        val client = SearchBackendClient { request -> requests += request; WebHttpResponse(200, result("Exa evidence")) }
        assertEquals("Exa evidence", client.search("Tesla Newest Car") { charges++; true })
        assertEquals(1, charges)
        assertEquals(1, requests.size)
        assertEquals("https://mcp.exa.ai/mcp", requests.single().url)
        assertEquals(5L, requests.single().timeoutSec)
        val request = Json.parseToJsonElement(requests.single().body).jsonObject
        assertEquals("web_search_exa", request["params"]?.jsonObject?.get("name")?.jsonPrimitive?.content)
        assertEquals("Tesla Newest Car", request["params"]?.jsonObject?.get("arguments")?.jsonObject?.get("query")?.jsonPrimitive?.content)
    }

    @Test
    fun mcpErrorTextDoesNotStopFallbackAndParallelAcceptsSse() = runBlocking {
        val requests = mutableListOf<SearchBackendRequest>()
        val client = SearchBackendClient { request ->
            requests += request
            if (requests.size == 1) WebHttpResponse(200, result("Quota error must not be evidence", true))
            else WebHttpResponse(200, "event: message\r\ndata:${result("Parallel evidence")}\r\n\r\ndata: [DONE]\r\n\r\n")
        }
        assertEquals("Parallel evidence", client.search("query"))
        assertEquals(listOf("https://mcp.exa.ai/mcp", "https://search.parallel.ai/mcp"), requests.map { it.url })
        assertEquals("opencode", requests[1].headers["User-Agent"])
        assertTrue(requests.all { it.timeoutSec == 5L })
    }

    @Test
    fun jsonRpcErrorThenMalformedResponseReachesDdg() = runBlocking {
        val requests = mutableListOf<SearchBackendRequest>()
        val client = SearchBackendClient { request ->
            requests += request
            when (requests.size) {
                1 -> WebHttpResponse(200, """{"jsonrpc":"2.0","error":{"code":-1,"message":"error"}}""")
                2 -> WebHttpResponse(200, "invalid body")
                else -> WebHttpResponse(200, """{"Heading":"Tesla","AbstractText":"Fallback evidence","AbstractURL":"https://example.com/reference#summary"}""")
            }
        }
        assertEquals("Tesla\n来源: https://example.com/reference\n\nFallback evidence", client.search("Tesla Newest Car"))
        assertEquals("GET", requests.last().method)
        assertEquals("Tesla Newest Car", requests.last().params["q"])
        assertTrue(requests.all { !it.headers.containsKey("Authorization") })
    }

    @Test
    fun ddgWithoutHeadingStillIncludesCanonicalSourceAndPreservesEncodedComponents() = runBlocking {
        val client = SearchBackendClient {
            WebHttpResponse(200, """{"AbstractText":"Fallback evidence","AbstractURL":"HTTPS://EXAMPLE.COM:443/a%2Fb?q=a%2Bb&sig=x%2Fy#summary"}""")
        }

        assertEquals(
            "来源: https://example.com/a%2Fb?q=a%2Bb&sig=x%2Fy\n\nFallback evidence",
            client.searchDdgJson("query"),
        )
    }

    @Test
    fun ddgWithoutValidCitableSourceDoesNotBecomeSuccessfulFallbackEvidence() = runBlocking {
        for (url in listOf(null, "", "  ", "file:///tmp/article", "not-a-url", "https://user:password@example.com/article")) {
            var attempts = 0
            val client = SearchBackendClient {
                attempts++
                if (attempts < 3) WebHttpResponse(503, "unavailable")
                else WebHttpResponse(200, buildJsonObject {
                    put("Heading", "Uncitable result")
                    put("AbstractText", "This must not become evidence without a valid source")
                    url?.let { put("AbstractURL", it) }
                }.toString())
            }

            assertEquals("所有搜索后端均失败", client.search("query"), message = "source=$url")
            assertEquals(3, attempts)
        }
    }

    @Test
    fun budgetGateRunsBeforeEachHttpIncludingFailures() = runBlocking {
        var attempts = 0
        var charges = 0
        val client = SearchBackendClient { attempts++; WebHttpResponse(503, "private error") }
        assertEquals("", client.search("query") { charges++; charges <= 1 })
        assertEquals(1, attempts)
        assertEquals(2, charges)
        assertEquals("", client.search("query") { false })
        assertEquals(1, attempts)
    }

    @Test
    fun emptyDdgAndHttpFailuresDoNotBecomeEvidence() = runBlocking {
        var attempts = 0
        val client = SearchBackendClient {
            attempts++
            if (attempts < 3) WebHttpResponse(500, "fake evidence") else WebHttpResponse(200, """{"AbstractText":""}""")
        }
        assertEquals("所有搜索后端均失败", client.search("query"))
        assertEquals(3, attempts)
    }

    @Test
    fun externalCancellationNeverStartsTheNextBackend() = runBlocking {
        var attempts = 0
        val client = SearchBackendClient { attempts++; throw CancellationException("cancel") }
        assertFailsWith<CancellationException> { client.search("query") }
        assertEquals(1, attempts)
    }

    @Test
    fun sseWithMultilineDataRejectsAnyErrorEvent() = runBlocking {
        var attempts = 0
        val client = SearchBackendClient {
            attempts++
            when (attempts) {
                1 -> WebHttpResponse(200, "data: ${result("unreliable text")}\n\ndata: {\ndata: \"error\": {\"message\": \"no\"}\ndata: }\n\n")
                2 -> WebHttpResponse(200, "data: {\ndata: \"result\": {\"content\":[{\"type\":\"text\",\"text\":\"multiline evidence\"}]}\ndata: }\n\n")
                else -> error("must stop")
            }
        }
        assertEquals("multiline evidence", client.search("query"))
        assertEquals(2, attempts)
    }
}
