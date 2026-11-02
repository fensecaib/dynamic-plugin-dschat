package top.colter.dynamic.agent.agent

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.*
import java.net.http.HttpTimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class DeepSeekWebClientTest {
    private val key = "only-in-memory-test-credential"

    @Test
    fun searchSerializesFixedEndpointAndUnicodeLimitsAndParsesRankedEvidence() = runBlocking {
        var requests = 0
        val transport = WebHttpTransport { url, body, headers, timeout ->
            requests++
            assertEquals(DeepSeekWebClient.SEARCH_ENDPOINT, url)
            assertEquals(10L, timeout)
            assertEquals(mapOf("Authorization" to "Bearer $key"), headers)
            val payload = Json.parseToJsonElement(body).jsonObject
            val query = payload.getValue("query").jsonPrimitive.content
            val original = payload.getValue("ori_question").jsonPrimitive.content
            assertEquals(128, query.codePointCount(0, query.length))
            assertEquals(128, original.codePointCount(0, original.length))
            assertFalse(query.last().isHighSurrogate())
            assertEquals(8, payload.getValue("count").jsonPrimitive.int)
            assertFalse(payload.getValue("need_content").jsonPrimitive.boolean)
            assertFalse(body.contains(key))
            WebHttpResponse(200, """{"results":[
              {"title":"Model announcement","url":"https://example.com/news","date":"2026-10-22T00:00:00Z","summary":"Official announcement","snippet":"short","content":"","authority":4,"rerank_score":2.8},
              {"title":"Snippet only","url":"https://example.org/article","date":null,"snippet":"Short evidence"},
              {"title":"Untrusted URL","url":"https://user:pass@example.com/","summary":"Do not expose credentials"}
            ]}""")
        }
        val result = DeepSeekWebClient(key, transport).search("😀".repeat(150), "界😀".repeat(100))
        assertEquals(1, requests)
        assertEquals(2, result.sources.size)
        assertEquals("Official announcement", result.sources[0].summary)
        assertEquals(4, result.sources[0].authority)
        assertEquals(2.8, result.sources[0].score)
        assertNull(result.sources[1].date)
        assertEquals("Short evidence", result.sources[1].summary)
    }

    @Test
    fun emptyOriginalQuestionIsOmittedAndNoResultsIsValid() = runBlocking {
        val result = DeepSeekWebClient(key, WebHttpTransport { _, body, _, _ ->
            assertFalse(Json.parseToJsonElement(body).jsonObject.containsKey("ori_question"))
            WebHttpResponse(200, """{"results":[]}""")
        }).search(" query ", "  ")
        assertTrue(result.sources.isEmpty())
    }

    @Test
    fun fetchSendsOnlyTargetInBodyAndParsesMarkdown() = runBlocking {
        val result = DeepSeekWebClient(key, WebHttpTransport { endpoint, body, headers, timeout ->
            assertEquals(DeepSeekWebClient.FETCH_ENDPOINT, endpoint)
            assertEquals(15L, timeout)
            assertEquals("https://example.org/article?q=hello", Json.parseToJsonElement(body).jsonObject["url"]?.jsonPrimitive?.content)
            assertEquals("Bearer $key", headers["Authorization"])
            WebHttpResponse(200, """{"title":"Article","content":"# Headline\n\nActual page evidence"}""")
        }).fetch(" https://example.org/article?q=hello ")
        assertEquals(WebPageResponse("Article", "# Headline\n\nActual page evidence"), result)
    }

    @Test
    fun invalidInputsNeverMakeHttpRequests() = runBlocking {
        val client = DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ -> error("must not call") })
        for (url in listOf("", "file:///tmp/page", "https://user:password@example.com/a", "https:///no-host", "https://example.com/" + "a".repeat(8192))) {
            val error = assertFailsWith<WebRetrievalException> { client.fetch(url) }
            assertEquals("invalid_url", error.code)
            assertFalse(error.fallbackAllowed)
            assertFalse(error.message.orEmpty().contains("password"))
        }
        assertEquals("invalid_query", assertFailsWith<WebRetrievalException> { client.search(" \n", "question") }.code)
        val noKey = DeepSeekWebClient("", WebHttpTransport { _, _, _, _ -> error("must not call") })
        assertEquals("missing_key", assertFailsWith<WebRetrievalException> { noKey.search("query", "question") }.code)
    }

    @Test
    fun httpFailuresHaveSafeMessagesAndCorrectFallbackPolicy() = runBlocking {
        for ((status, fallback, code) in listOf(
            Triple(401, false, "unauthorized"), Triple(402, false, "insufficient_balance"),
            Triple(403, false, "security_rejected"), Triple(404, false, "page_unavailable"), Triple(408, true, "timeout"),
            Triple(429, true, "rate_limited"), Triple(500, true, "upstream_unavailable"),
            Triple(302, false, "request_rejected"), Triple(400, false, "request_rejected")
        )) {
            val error = assertFailsWith<WebRetrievalException> {
                DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ -> WebHttpResponse(status, "upstream body $key password=hidden") }).search("query", "question")
            }
            assertEquals(code, error.code)
            assertEquals(fallback, error.fallbackAllowed)
            assertFalse(error.message.orEmpty().contains(key))
            assertFalse(error.message.orEmpty().contains("hidden"))
            assertNull(error.cause)
        }
    }

    @Test
    fun badJsonEchoedCredentialAndProtectedPagesDoNotBecomeEvidence() = runBlocking {
        for (body in listOf("not-json", "[]", "{}", """{"results":"invalid"}""", """{"results":[],"echo":"$key"}""")) {
            assertEquals("invalid_response", assertFailsWith<WebRetrievalException> {
                DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ -> WebHttpResponse(200, body) }).search("query", "question")
            }.code)
        }
        for ((body, code) in listOf(
            """{"title":"Article","content":"  "}""" to "empty_content",
            """{"title":"Just a moment...","content":"Enable JavaScript and cookies to continue"}""" to "blocked_content",
            """{"title":"Access Denied","content":"You don't have permission to access this server."}""" to "blocked_content"
        )) {
            assertEquals(code, assertFailsWith<WebRetrievalException> {
                DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ -> WebHttpResponse(200, body) }).fetch("https://example.com/")
            }.code)
        }
    }

    @Test
    fun transportFailureDetailsAreDiscardedAndTimeoutIsDistinguished() = runBlocking {
        val failure = assertFailsWith<WebRetrievalException> {
            DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ -> throw IllegalStateException("Bearer $key") }).search("q", "q")
        }
        assertEquals("transport_error", failure.code)
        assertFalse(failure.message.orEmpty().contains(key))
        assertTrue(failure.fallbackAllowed)
        val timeout = assertFailsWith<WebRetrievalException> {
            DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ -> throw HttpTimeoutException("private upstream details") }).fetch("https://example.com/")
        }
        assertEquals("timeout", timeout.code)
        assertTrue(timeout.fallbackAllowed)
    }

    @Test
    fun cancellationPropagatesAndStopsTheRequest() = runBlocking {
        var entered = false
        var finalized = false
        val client = DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ ->
            entered = true
            try { awaitCancellation() } finally { finalized = true }
        })
        val job = launch { client.search("query", "question") }
        yield()
        assertTrue(entered)
        job.cancel()
        job.join()
        assertTrue(finalized)
        assertTrue(job.isCancelled)
        assertFailsWith<CancellationException> {
            DeepSeekWebClient(key, WebHttpTransport { _, _, _, _ -> throw CancellationException("cancel") }).fetch("https://example.com/")
        }
    }
}
