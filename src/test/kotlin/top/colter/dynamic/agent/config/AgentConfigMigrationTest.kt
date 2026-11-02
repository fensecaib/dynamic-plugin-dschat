package top.colter.dynamic.agent.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import top.colter.dynamic.core.config.MutableConfigDocument
import java.lang.reflect.Proxy
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
    fun `old stored config adds diagnostics and request controls`() {
        val old = Json.decodeFromString<AgentPluginConfig>(
            """{"api":{"apiKey":"existing-key","model":"deepseek-v4-flash"},"chat":{"enabled":false},"dota2":{"enableBinding":false}}""",
        )
        val migrated = normalizeAgentConfig(old)
        assertEquals("deepseek-flash", migrated.api.model)
        assertEquals("existing-key", migrated.api.apiKey)
        assertEquals(120, migrated.api.requestTimeoutSeconds)
        assertEquals(2, migrated.api.maxRetries)
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
            diagnostics = DiagnosticsConfig(enabled = false, slowStageWarningSeconds = 15),
        )
        assertEquals(config, Json.decodeFromString<AgentPluginConfig>(Json.encodeToString(AgentPluginConfig.serializer(), config)))
    }

    @Test
    fun `retired provider settings decode without changing saved choices and cannot be encoded again`() {
        val saveJson = Json { encodeDefaults = true }
        for (enableAtTrigger in listOf(false, true)) {
            val saved = """{
                "api":{"apiKey":"existing-key","model":"deepseek-v4-flash"},
                "trigger":{"enableAtTrigger":$enableAtTrigger,"enableCommandTrigger":false},
                "grok":{"enabled":true,"apiKey":"retired-grok-key","useAsAtTrigger":true,"triggerPrefix":"/grok"},
                "webSearch":{"enabled":false,"maxRounds":8,"totalTimeoutSeconds":120,"finalizeTimeoutSeconds":45,"searchTimeout":25,"fetchTimeout":20,"fetchMaxChars":12345,"tavilyApiKey":"retired-tavily-key"}
            }"""
            // Use strict default JSON so compatibility does not depend on host decoder options.
            val migrated = normalizeAgentConfig(Json.decodeFromString<AgentPluginConfig>(saved))
            assertEquals(enableAtTrigger, migrated.trigger.enableAtTrigger)
            assertFalse(migrated.trigger.enableCommandTrigger)
            assertFalse(migrated.webSearch.enabled)
            assertEquals("", migrated.webSearch.officialApiKey)
            assertFalse(migrated.webSearch.fallbackEnabled)
            assertEquals("existing-key", migrated.api.apiKey)
            assertEquals("deepseek-flash", migrated.api.model)
            val encoded = saveJson.encodeToString(AgentPluginConfig.serializer(), migrated)
            val encodedRoot = Json.parseToJsonElement(encoded).jsonObject
            assertFalse("grok" in encodedRoot)
            assertFalse("tavilyApiKey" in encodedRoot.getValue("webSearch").jsonObject)
            assertEquals(setOf("enabled", "officialApiKey", "fallbackEnabled"), encodedRoot.getValue("webSearch").jsonObject.keys)
            assertFalse(encoded.contains("retired-grok-key"))
            assertFalse(encoded.contains("retired-tavily-key"))
            assertEquals(migrated, Json.decodeFromString<AgentPluginConfig>(encoded))
        }
    }

    @Test
    fun `host migration removes only declared retired paths while preserving valid and unknown fields`() {
        for (enableAtTrigger in listOf(false, true)) {
            val fixture = MigrationDocumentFixture(
                """{
                    "api":{"apiKey":"existing-key","model":"deepseek-flash"},
                    "trigger":{"enableAtTrigger":$enableAtTrigger,"enableCommandTrigger":false},
                    "grok":{"apiKey":"retired-grok-key","useAsAtTrigger":true},
                    "webSearch":{"enabled":false,"officialApiKey":"official-search-key","fallbackEnabled":true,"maxRounds":2,"totalTimeoutSeconds":180,"finalizeTimeoutSeconds":60,"searchTimeout":30,"fetchTimeout":15,"fetchMaxChars":8000,"tavilyApiKey":"retired-tavily-key","customSetting":"keep-nested"},
                    "customRoot":{"grok":"keep-custom","tavilyApiKey":"keep-custom-key"},
                    "webSearch.tavilyApiKey":"keep-literal-dotted-key"
                }""",
            )
            val expected = Json.parseToJsonElement(
                """{
                    "api":{"apiKey":"existing-key","model":"deepseek-flash"},
                    "trigger":{"enableAtTrigger":$enableAtTrigger,"enableCommandTrigger":false},
                    "webSearch":{"enabled":false,"officialApiKey":"official-search-key","fallbackEnabled":true,"customSetting":"keep-nested"},
                    "customRoot":{"grok":"keep-custom","tavilyApiKey":"keep-custom-key"},
                    "webSearch.tavilyApiKey":"keep-literal-dotted-key"
                }""",
            )
            repeat(2) {
                agentConfigMigrations.forEach { it.apply(fixture.document) }
                assertEquals(expected, fixture.root)
            }
        }
    }

    @Test
    fun `search defaults use the official chain with backup opt in`() {
        val old = Json.decodeFromString<AgentPluginConfig>("""{"webSearch":{"enabled":true,"maxRounds":3}}""")
        assertEquals(WebSearchConfig(), old.webSearch)
        assertTrue(old.webSearch.enabled)
        assertFalse(old.webSearch.fallbackEnabled)
        assertEquals("", old.webSearch.officialApiKey)

        val configured = old.copy(webSearch = WebSearchConfig(enabled = false, officialApiKey = "official-key", fallbackEnabled = true))
        assertEquals(configured, Json.decodeFromString<AgentPluginConfig>(Json.encodeToString(AgentPluginConfig.serializer(), configured)))
    }

    @Test
    fun `nested retired field migration is a no-op when its parent is absent or not an object`() {
        for (saved in listOf("{}", """{"webSearch":null}""", """{"webSearch":"keep-invalid-value"}""")) {
            val fixture = MigrationDocumentFixture(saved)
            val original = fixture.root
            repeat(2) { agentConfigMigrations.forEach { it.apply(fixture.document) } }
            assertEquals(original, fixture.root)
        }
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

    /** Minimal host document fixture: dotted paths traverse objects and never match literal dotted keys. */
    private class MigrationDocumentFixture(saved: String) {
        var root: JsonObject = Json.parseToJsonElement(saved).jsonObject
            private set

        val document: MutableConfigDocument = Proxy.newProxyInstance(
            MutableConfigDocument::class.java.classLoader, arrayOf(MutableConfigDocument::class.java),
        ) { _, method, args ->
            check(method.name == "remove") { "Unexpected migration operation: ${method.name}" }
            val (updated, removed) = remove(root, (args.single() as String).split("."))
            root = updated
            removed
        } as MutableConfigDocument

        private fun remove(node: JsonObject, segments: List<String>): Pair<JsonObject, Boolean> {
            val key = segments.first()
            if (segments.size == 1) {
                if (key !in node) return node to false
                return JsonObject(node - key) to true
            }
            val child = node[key] as? JsonObject ?: return node to false
            val (updated, removed) = remove(child, segments.drop(1))
            if (!removed) return node to false
            return JsonObject(node + (key to updated)) to true
        }
    }
}
