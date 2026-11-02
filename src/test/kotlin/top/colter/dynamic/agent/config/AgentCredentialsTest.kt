package top.colter.dynamic.agent.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentCredentialsTest {
    @Test
    fun `official secure chat endpoints can share a trimmed key`() {
        for (url in listOf(
            "https://api.deepseek.com/chat/completions",
            "https://api.deepseek.com/v1/chat/completions",
            " HTTPS://API.DEEPSEEK.COM:443/chat/completions ",
        )) {
            assertTrue(isOfficialDeepSeekEndpoint(url), url)
            assertEquals("chat-key", resolveSearchApiKey(AgentPluginConfig(api = ApiConfig(apiKey = " chat-key ", apiUrl = url))))
        }
    }

    @Test
    fun `third party or unsafe endpoints never donate their chat key`() {
        for (url in listOf(
            "https://gateway.example/v1/chat/completions",
            "https://api.deepseek.com.gateway.example/chat/completions",
            "https://api.deepseek.com@gateway.example/chat/completions",
            "https://gateway.example/chat/completions?upstream=api.deepseek.com",
            "https://gateway.example/#https://api.deepseek.com",
            "https://api.deepseek.com:8443/chat/completions",
            "https://other-key@api.deepseek.com/chat/completions",
            "http://api.deepseek.com/chat/completions",
            "//api.deepseek.com/chat/completions",
            "not a URL",
            "",
        )) {
            assertFalse(isOfficialDeepSeekEndpoint(url), url)
            val config = AgentPluginConfig(api = ApiConfig(apiKey = "third-party-key", apiUrl = url))
            assertEquals("", resolveSearchApiKey(config), url)
        }
    }

    @Test
    fun `explicit official search credentials take priority for all model providers`() {
        for (url in listOf("https://api.deepseek.com/chat/completions", "https://gateway.example/chat/completions", "")) {
            val config = AgentPluginConfig(
                api = ApiConfig(apiKey = "chat-key", apiUrl = url),
                webSearch = WebSearchConfig(officialApiKey = " official-key "),
            )
            assertEquals("official-key", resolveSearchApiKey(config), url)
        }
    }

    @Test
    fun `whitespace official key falls back only for official chat`() {
        val config = AgentPluginConfig(api = ApiConfig(apiKey = " chat-key "), webSearch = WebSearchConfig(officialApiKey = " \t\n"))
        assertEquals("chat-key", resolveSearchApiKey(config))
        assertEquals("", resolveSearchApiKey(config.copy(api = config.api.copy(apiUrl = "https://gateway.example/chat/completions"))))
        assertEquals("", resolveSearchApiKey(config.copy(api = config.api.copy(apiKey = " "))))
    }
}
