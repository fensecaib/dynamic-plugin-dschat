package top.colter.dynamic.agent.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentConfigMigrationTest {
    @Test
    fun `legacy official Flash model aliases migrate without changing any other settings`() {
        for (alias in listOf("deepseek-v4-flash", "deepseek-v4-flash-vision-exp")) {
            val previous = AgentPluginConfig(
                api = ApiConfig(apiKey = "private-key", model = alias, requestTimeoutSeconds = 45, maxRetries = 0),
                chat = ChatConfig(enabled = false),
                trigger = TriggerConfig(enableAtTrigger = false),
                dota2 = Dota2Config(enableAnalysis = false),
                grok = GrokConfig(enabled = true, model = "vendor-model"),
                diagnostics = DiagnosticsConfig(enabled = false, slowStageWarningSeconds = 20),
            )
            val migrated = normalizeAgentConfig(previous)
            assertEquals(previous.copy(api = previous.api.copy(model = "deepseek-flash")), migrated)
            assertSame(migrated, normalizeAgentConfig(migrated))
        }
    }

    @Test
    fun `official v1 completion URL also migrates and hostname comparison ignores case`() {
        for (url in listOf(
            "https://api.deepseek.com/v1/chat/completions",
            "HTTPS://API.DEEPSEEK.COM/chat/completions",
            " https://api.deepseek.com/chat/completions ",
        )) {
            val config = AgentPluginConfig(api = ApiConfig(apiUrl = url, model = "deepseek-v4-flash"))
            assertEquals("deepseek-flash", normalizeAgentConfig(config).api.model, url)
            assertEquals(url, normalizeAgentConfig(config).api.apiUrl)
        }
    }

    @Test
    fun `third party and invalid API URLs retain their provider model names`() {
        for (url in listOf(
            "https://gateway.example/v1/chat/completions",
            "https://api.deepseek.com.gateway.example/chat/completions",
            "https://api.deepseek.com@gateway.example/chat/completions",
            "https://gateway.example/chat/completions?upstream=api.deepseek.com",
            "not a URL",
            "",
        )) {
            val config = AgentPluginConfig(api = ApiConfig(apiUrl = url, model = "deepseek-v4-flash"))
            assertSame(config, normalizeAgentConfig(config), url)
        }
    }

    @Test
    fun `official Pro and custom model names are never rewritten`() {
        for (model in listOf("deepseek-v4-pro", "deepseek-flash", "custom-model", "deepseek-chat", "")) {
            val config = AgentPluginConfig(api = ApiConfig(model = model))
            assertSame(config, normalizeAgentConfig(config), model)
        }
    }

    @Test
    fun `old stored config adds diagnostics and independent provider request controls`() {
        val old = Json.decodeFromString<AgentPluginConfig>(
            """{"api":{"apiKey":"existing-key","model":"deepseek-v4-flash"},"chat":{"enabled":false},"dota2":{"enableBinding":false}}""",
        )
        val migrated = normalizeAgentConfig(old)
        assertEquals("deepseek-flash", migrated.api.model)
        assertEquals("existing-key", migrated.api.apiKey)
        assertEquals(120, migrated.api.requestTimeoutSeconds)
        assertEquals(2, migrated.api.maxRetries)
        assertEquals(120, migrated.grok.requestTimeoutSeconds)
        assertEquals(2, migrated.grok.maxRetries)
        assertTrue(migrated.diagnostics.enabled)
        assertEquals(30, migrated.diagnostics.slowStageWarningSeconds)
        assertEquals(72, migrated.image.drawCacheRetentionHours)
        assertFalse(migrated.chat.enabled)
        assertFalse(migrated.dota2.enableBinding)
    }

    @Test
    fun `new request and diagnostic settings survive a config save and reload independently`() {
        val config = AgentPluginConfig(
            api = ApiConfig(model = "custom", requestTimeoutSeconds = 60, maxRetries = 0),
            grok = GrokConfig(requestTimeoutSeconds = 180, maxRetries = 1),
            diagnostics = DiagnosticsConfig(enabled = false, slowStageWarningSeconds = 15),
        )
        assertEquals(config, Json.decodeFromString<AgentPluginConfig>(Json.encodeToString(AgentPluginConfig.serializer(), config)))
    }

    @Test
    fun `model migration leaves trigger conflict resolution to the plugin`() {
        val config = AgentPluginConfig(
            api = ApiConfig(model = "deepseek-v4-flash"),
            grok = GrokConfig(enabled = true, useAsAtTrigger = true),
            trigger = TriggerConfig(enableAtTrigger = true),
        )
        val migrated = normalizeAgentConfig(config)
        assertTrue(migrated.trigger.enableAtTrigger)
        assertTrue(migrated.grok.useAsAtTrigger)
    }

    @Test
    fun `existing image config gains default retention without changing drawing settings`() {
        val old = Json.decodeFromString<AgentPluginConfig>(
            """{"image":{"defaultColor":"#123456;#abcdef","factor":2.0}}""",
        )
        assertEquals(72, ImageConfig().drawCacheRetentionHours)
        assertEquals(72, old.image.drawCacheRetentionHours)
        assertEquals("#123456;#abcdef", old.image.defaultColor)
        assertEquals(2f, old.image.factor)
    }

    @Test
    fun `custom hourly draw retention survives save reload and model migration`() {
        for (hours in listOf(1, 24, 72, 8760)) {
            val config = AgentPluginConfig(
                api = ApiConfig(model = "deepseek-v4-flash"),
                image = ImageConfig(defaultColor = "#123456;#abcdef", factor = 2f, drawCacheRetentionHours = hours),
            )
            val saved = Json.encodeToString(AgentPluginConfig.serializer(), config)
            val reloaded = Json.decodeFromString<AgentPluginConfig>(saved)
            assertEquals(config.image, reloaded.image)
            assertEquals(hours, normalizeAgentConfig(reloaded).image.drawCacheRetentionHours)
        }
    }
}
