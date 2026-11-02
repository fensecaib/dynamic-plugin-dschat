package top.colter.dynamic.agent.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import top.colter.dynamic.agent.config.WebSearchConfig
import top.colter.dynamic.agent.ds.ChatMessage
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.*

class SearchAgentBudgetTest {
    private class Backend : ResearchBackend {
        val searches = mutableListOf<Pair<String, String>>()
        val fetches = mutableListOf<String>()
        var searchAnswer: suspend (String) -> WebSearchResponse = { query ->
            WebSearchResponse(listOf(WebSource("官方资料 $query", "https://example.com/article", "2026-10-22", "摘要资料")))
        }
        var fetchAnswer: suspend (String) -> WebPageResponse = { WebPageResponse("原文标题", "可用于核实问题的正文") }
        override suspend fun search(query: String, originalQuestion: String): WebSearchResponse {
            searches.add(query to originalQuestion)
            return searchAnswer(query)
        }
        override suspend fun fetch(url: String): WebPageResponse {
            fetches.add(url)
            return fetchAnswer(url)
        }
    }

    private suspend fun prepare(agent: SearchAgent, prompt: String = "Tesla Newest Car"): MutableList<ChatMessage> {
        val messages = mutableListOf(ChatMessage("system", "保持原本的自然对话风格"), ChatMessage("user", prompt))
        agent.prepareMessages(messages, prompt, "保持原本的自然对话风格")
        return messages
    }

    private suspend fun SearchAgent.search(query: String, gap: String? = null): String =
        executeTool("web_search", arguments("query", query, gap), "search-$query").orEmpty()

    private suspend fun SearchAgent.fetch(url: String, gap: String? = null): String =
        executeTool("web_fetch", arguments("url", url, gap), "fetch-$url").orEmpty()

    private fun arguments(key: String, value: String, gap: String?) = buildJsonObject {
        put(key, value)
        gap?.let { put("gap", it) }
    }.toString()

    @Test fun `additional research requires a concrete evidence gap and preserves the original question`() = runBlocking {
        val backend = Backend()
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        val messages = prepare(agent)
        agent.search("Tesla newest car")
        val refusedSearch = agent.search("Tesla latest model")
        val refusedFetch = agent.fetch("https://example.com/article")
        assertEquals(1, backend.searches.size)
        assertTrue(refusedSearch.isNotBlank())
        assertTrue(refusedFetch.isNotBlank())

        agent.search("Tesla latest model", "还缺少发布日以及是否已交付的信息")
        agent.fetch("https://example.com/article", "核实新闻正文中的发布日期和量产计划")
        assertEquals(2, backend.searches.size)
        assertEquals(listOf("https://example.com/article"), backend.fetches)
        assertEquals(setOf("Tesla Newest Car"), backend.searches.map { it.second }.toSet())
        assertEquals("Tesla Newest Car", messages.single { it.role == "user" }.content)
        assertContains(messages.single { it.role == "system" }.content.orEmpty(), "保持原本的自然对话风格")
    }

    @Test fun `successful identical search and fragment variants of a page reuse evidence without another paid call`() = runBlocking {
        val backend = Backend()
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        val firstSearch = agent.search("Tesla Newest Car")
        val sameSearch = agent.search("  TESLA NEWEST CAR  ")
        assertContains(sameSearch, "已")
        assertFalse(sameSearch.contains("摘要资料"))
        assertEquals(1, backend.searches.size)
        assertContains(firstSearch, "官方资料")
        assertContains(firstSearch, "2026-10-22")
        assertContains(firstSearch, "https://example.com/article")

        val firstFetch = agent.fetch("https://example.com/a%2Fb?q=a%2Bb&sig=x%2Fy#details", "核实文章正文")
        val sameFetch = agent.fetch("https://example.com/a%2Fb?q=a%2Bb&sig=x%2Fy#release", "仍指向同一篇文章")
        assertContains(firstFetch, "可用于核实问题的正文")
        assertContains(sameFetch, "已")
        assertFalse(sameFetch.contains("可用于核实问题的正文"))
        assertEquals(1, backend.fetches.size)
        assertEquals("https://example.com/a%2Fb?q=a%2Bb&sig=x%2Fy", backend.fetches.single())
    }

    @Test fun `search evidence retains a useful body when the provider has no separate summary`() = runBlocking {
        val backend = Backend().apply {
            searchAnswer = { WebSearchResponse(listOf(WebSource("原文资料", "https://example.com/article", "2026-10-22", "", content = "正文包含独有的量产计划"))) }
        }
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        assertContains(agent.search("Tesla production plan"), "正文包含独有的量产计划")
        assertEquals(1, backend.searches.size)
    }

    @Test fun `distinct search sources preserve raw paths query boundaries ports schemes and language editions`() = runBlocking {
        val pairs = listOf(
            "https://example.com/a?b" to "https://example.com/ab",
            "https://example.com/a%2Fb" to "https://example.com/a/b",
            "https://example.com:8443/article" to "https://example.com:9443/article",
            "http://example.com/article" to "https://example.com/article",
            "https://example.com/en_us/learn/topic" to "https://example.com/learn/topic",
        )
        for ((firstUrl, secondUrl) in pairs) {
            val backend = Backend().apply {
                searchAnswer = { WebSearchResponse(listOf(
                    WebSource("第一来源", firstUrl, null, "第一来源独有资料"),
                    WebSource("第二来源", secondUrl, null, "第二来源独有资料"),
                )) }
            }
            val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
            prepare(agent)

            val output = agent.search("compare sources")

            assertContains(output, "第一来源独有资料", message = "$firstUrl vs $secondUrl")
            assertContains(output, "第二来源独有资料", message = "$firstUrl vs $secondUrl")
            assertEquals(1, backend.searches.size)
        }
    }

    @Test fun `equivalent search URLs deduplicate host case default ports and fragments without losing query distinctions`() = runBlocking {
        val backend = Backend().apply {
            searchAnswer = { WebSearchResponse(listOf(
                WebSource("初始来源", "HTTPS://EXAMPLE.COM:443/a%2Fb?q=a%2Bb&sig=x%2Fy#one", null, "保留的首条证据"),
                WebSource("重复来源", "https://example.com/a%2Fb?q=a%2Bb&sig=x%2Fy#two", null, "不应重复的证据"),
                WebSource("不同查询", "https://example.com/a%2Fb?q=a%2Bb&sig=x%2Fz", null, "不同查询的独有证据"),
            )) }
        }
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)

        val output = agent.search("compare source variants")

        assertContains(output, "保留的首条证据")
        assertFalse(output.contains("不应重复的证据"))
        assertContains(output, "不同查询的独有证据")
        assertEquals(1, backend.searches.size)
    }

    @Test fun `a user supplied URL can be read directly without a speculative search or evidence gap`() = runBlocking {
        val backend = Backend()
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent, "阅读 [官方页面](https://example.com/article) 并简要解释。")
        val output = agent.fetch("https://example.com/article")
        assertEquals(emptyList(), backend.searches)
        assertEquals(listOf("https://example.com/article"), backend.fetches)
        assertContains(output, "可用于核实问题的正文")
    }

    @Test fun `long pages and the total evidence cap are marked as partial instead of implying a complete read`() = runBlocking {
        val tail = "页面末尾尚未读取的独有标记"
        val backend = Backend().apply {
            fetchAnswer = { WebPageResponse("长页面", "页面开头的有效证据" + "较长的正文资料".repeat(2000) + tail) }
        }
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        val outputs = (0..2).map { agent.fetch("https://example.com/long-$it", "核实该来源原文") }
        assertEquals(3, backend.fetches.size)
        assertTrue(outputs.sumOf { it.length } <= 18000)
        outputs.forEach { output ->
            assertContains(output, "已截断")
            assertContains(output, "仅包含部分内容")
            assertFalse(output.contains(tail))
        }
        assertContains(outputs.first(), "页面开头的有效证据")
        assertFalse(agent.shouldContinue(0))

        val receipt = agent.fetch("https://example.com/long-0", "不应为了同一篇正文再支付一次")
        assertContains(receipt, "已")
        assertFalse(receipt.contains("页面开头的有效证据"))
        assertEquals(3, backend.fetches.size)
    }

    @Test fun `a future source date is labelled so it cannot establish an already happened event`() = runBlocking {
        val backend = Backend().apply {
            searchAnswer = { WebSearchResponse(listOf(WebSource("未来来源", "https://example.com/future", "2099-01-01", "计划发布消息"))) }
        }
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        val output = agent.search("Tesla future release")
        assertContains(output, "2099-01-01")
        assertContains(output, "晚于当前日期")
    }

    @Test fun `search and fetch have separate three call caps even in a long tool batch`() = runBlocking {
        val backend = Backend()
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        repeat(7) { agent.search("different query $it", "还缺少第 $it 个问题的资料") }
        repeat(7) { agent.fetch("https://example.com/article-$it", "核实第 $it 个来源") }
        assertEquals(3, backend.searches.size)
        assertEquals(3, backend.fetches.size)
        assertFalse(agent.shouldContinue(0))
    }

    @Test fun `failed requests also consume the paid attempt caps`() = runBlocking {
        val backend = Backend().apply {
            searchAnswer = { throw WebRetrievalException("unavailable", true, "暂时不可用") }
            fetchAnswer = { throw WebRetrievalException("unavailable", true, "暂时不可用") }
        }
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        repeat(4) { agent.search("failure $it", "重试不同来源以补齐当前缺口") }
        repeat(4) { agent.fetch("https://example.com/failure-$it", "核实来源正文") }
        assertEquals(3, backend.searches.size)
        assertEquals(3, backend.fetches.size)
    }

    @Test fun `cancelled retrieval remains cancelled and cannot restore a consumed paid attempt`() = runBlocking {
        val backend = Backend().apply { searchAnswer = { throw CancellationException("cancelled") } }
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        repeat(3) { index ->
            assertFailsWith<CancellationException> { agent.search("cancelled $index", "核实另一项缺失证据") }
        }
        agent.search("one too many", "额度已耗尽")
        assertEquals(3, backend.searches.size)
    }

    @Test fun `legacy fallback is disabled by default and must not run after primary failure`() = runBlocking {
        val backend = Backend().apply { searchAnswer = { throw WebRetrievalException("unavailable", true, "暂时不可用") } }
        var fallbackCalls = 0
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend) { _, _ -> fallbackCalls++; "备用资料" }
        prepare(agent)
        agent.search("Tesla newest car")
        assertEquals(1, backend.searches.size)
        assertEquals(0, fallbackCalls)
    }

    @Test fun `explicit fallback allows only one bounded chain per conversation turn`() = runBlocking {
        val backend = Backend().apply { searchAnswer = { throw WebRetrievalException("unavailable", true, "暂时不可用") } }
        var fallbackChains = 0
        var legacyHttpCalls = 0
        val agent = SearchAgent(WebSearchConfig(fallbackEnabled = true), "test-key", backend) { _, reserve ->
            fallbackChains++
            repeat(7) { if (reserve()) legacyHttpCalls++ }
            "备用来源资料"
        }
        prepare(agent)
        val first = agent.search("Tesla newest car")
        agent.search("Tesla recent release", "备用资料仍没有交付日期，需要换关键词核实")
        assertContains(first, "备用来源资料")
        assertEquals(2, backend.searches.size)
        assertEquals(1, fallbackChains)
        assertEquals(3, legacyHttpCalls)
    }

    @Test fun `an unresolved evidence gap after three successful searches uses the optional fallback only once`() = runBlocking {
        for (enabled in listOf(false, true)) {
            val backend = Backend()
            var fallbackChains = 0
            var legacyHttpCalls = 0
            val agent = SearchAgent(WebSearchConfig(fallbackEnabled = enabled), "test-key", backend) { _, reserve ->
                fallbackChains++
                repeat(5) { if (reserve()) legacyHttpCalls++ }
                "独立备用来源补齐的交付时间"
            }
            prepare(agent)
            repeat(3) { agent.search("successful primary query $it", "仍需核实市场和交付状态") }
            assertEquals(3, backend.searches.size)
            assertEquals(0, fallbackChains)

            val fourth = agent.search("fourth query for independent confirmation", "三次主链资料仍没有独立来源确认交付时间")
            agent.search("fifth query must not start another fallback", "仍有待确认的问题")
            assertEquals(3, backend.searches.size)
            assertEquals(if (enabled) 1 else 0, fallbackChains)
            assertEquals(if (enabled) 3 else 0, legacyHttpCalls)
            assertEquals(enabled, fourth.contains("独立备用来源补齐的交付时间"))
        }
    }

    @Test fun `authentication and balance errors do not trigger legacy fallback`() = runBlocking {
        for (code in listOf("unauthorized", "insufficient_balance")) {
            val backend = Backend().apply { searchAnswer = { throw WebRetrievalException(code, false, "检查账户配置") } }
            var fallbackCalls = 0
            val agent = SearchAgent(WebSearchConfig(fallbackEnabled = true), "test-key", backend) { _, _ -> fallbackCalls++; "备用资料" }
            prepare(agent)
            agent.search("Tesla newest car")
            assertEquals(1, backend.searches.size)
            assertEquals(0, fallbackCalls)
        }
    }

    @Test fun `missing official key does not call either primary or legacy services`() = runBlocking {
        val backend = Backend()
        var fallbackCalls = 0
        val agent = SearchAgent(WebSearchConfig(fallbackEnabled = true), "", backend) { _, _ -> fallbackCalls++; "备用资料" }
        prepare(agent)
        val output = agent.search("Tesla newest car")
        assertEquals(0, backend.searches.size)
        assertEquals(0, fallbackCalls)
        assertContains(output, "Key")
    }

    @Test fun `time lookups use no paid calls but pathological batches have a tool call limit`() = runBlocking {
        val backend = Backend()
        val agent = SearchAgent(WebSearchConfig(), "test-key", backend)
        prepare(agent)
        repeat(20) { agent.executeTool("current_time", "{}", "time-$it") }
        agent.search("Tesla newest car")
        assertEquals(0, backend.searches.size)
        assertEquals(0, backend.fetches.size)
        assertFalse(agent.shouldContinue(0))
    }
}
