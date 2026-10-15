package top.colter.dynamic.agent.config

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FeaturePolicyTest {
    private val ready = AgentPluginConfig(api = ApiConfig(apiKey = "test-key"))
    private val aiDotaCommands = listOf("战报", "深度战报", "分析", "个人详情", "深度个人详情")

    @Test
    fun `existing configuration keeps previously enabled features`() {
        val old = Json.decodeFromString<AgentPluginConfig>("""{"api":{"apiKey":"old-key"},"dota2":{"enableBinding":false}}""")
        assertTrue(old.chat.enabled)
        assertTrue(old.trigger.enableCommandTrigger)
        assertTrue(old.grok.enableCommandTrigger)
        assertFalse(old.grok.enabled)
        assertTrue(old.dota2.enabled)
        assertTrue(old.dota2.enableHistory)
        assertTrue(old.dota2.enableAnalysis)
        assertFalse(old.dota2.enableBinding)
        assertNull(old.chatCommandUnavailable("ds"))
    }

    @Test
    fun `all feature pauses survive a configuration round trip`() {
        val paused = ready.copy(
            chat = ready.chat.copy(enabled = false),
            trigger = ready.trigger.copy(enableCommandTrigger = false, enableAtTrigger = false),
            grok = ready.grok.copy(enableCommandTrigger = false),
            dota2 = Dota2Config(enabled = false, enableBinding = false, enableHistory = false, enableAnalysis = false),
        )
        assertEquals(paused, Json.decodeFromString<AgentPluginConfig>(Json.encodeToString(AgentPluginConfig.serializer(), paused)))
    }

    @Test
    fun `chat and dota can be enabled independently`() {
        val dotaOnly = ready.copy(chat = ready.chat.copy(enabled = false))
        assertTrue(dotaOnly.chatCommandUnavailable("ds")!!.contains("已暂停"))
        assertFalse(dotaOnly.chatAtAvailable("ds"))
        assertNull(dotaOnly.dotaCommandUnavailable("战报"))

        val chatOnly = ready.copy(dota2 = ready.dota2.copy(enabled = false))
        assertNull(chatOnly.chatCommandUnavailable("ds"))
        assertTrue(chatOnly.chatAtAvailable("ds"))
        for (command in listOf(null, "绑定", "历史") + aiDotaCommands) {
            assertTrue(chatOnly.dotaCommandUnavailable(command)!!.contains("Dota2 功能已暂停"))
        }
    }

    @Test
    fun `command and mention triggers can be paused independently`() {
        val mentionsOnly = ready.copy(trigger = ready.trigger.copy(enableCommandTrigger = false))
        assertTrue(mentionsOnly.chatCommandUnavailable("ds")!!.contains("命令对话已暂停"))
        assertTrue(mentionsOnly.chatAtAvailable("ds"))

        val commandsOnly = ready.copy(trigger = ready.trigger.copy(enableAtTrigger = false))
        assertNull(commandsOnly.chatCommandUnavailable("ds"))
        assertFalse(commandsOnly.chatAtAvailable("ds"))
    }

    @Test
    fun `grok requires its provider and own credentials and respects the global chat switch`() {
        val grok = AgentPluginConfig(grok = GrokConfig(enabled = true, apiKey = "grok-key", useAsAtTrigger = true))
        assertNull(grok.chatCommandUnavailable("grok"))
        assertTrue(grok.chatAtAvailable("grok"))
        assertFalse(grok.chatAtAvailable("ds"))
        assertTrue(grok.chatCommandUnavailable("ds")!!.contains("DeepSeek API Key"))
        assertFalse(grok.copy(grok = grok.grok.copy(enabled = false)).chatAtAvailable("grok"))

        val mentionsOnly = grok.copy(grok = grok.grok.copy(enableCommandTrigger = false))
        assertTrue(mentionsOnly.chatCommandUnavailable("grok")!!.contains("命令对话已暂停"))
        assertTrue(mentionsOnly.chatAtAvailable("grok"))
        val paused = grok.copy(chat = grok.chat.copy(enabled = false))
        assertTrue(paused.chatCommandUnavailable("grok")!!.contains("AI 对话功能已暂停"))
        assertFalse(paused.chatAtAvailable("grok"))
    }

    @Test
    fun `missing deepseek credentials block all ai features before a request but permit basic dota`() {
        val config = AgentPluginConfig(api = ApiConfig(apiKey = " \t\n"))
        assertNull(config.dotaCommandUnavailable("绑定"))
        assertNull(config.dotaCommandUnavailable("历史"))
        assertNull(config.dotaCommandUnavailable(null))
        assertTrue(config.chatCommandUnavailable("ds")!!.contains("DeepSeek API Key"))
        assertFalse(config.chatAtAvailable("ds"))
        for (command in aiDotaCommands) {
            assertTrue(config.dotaCommandUnavailable(command)!!.contains("DeepSeek API Key"), command)
        }
    }

    @Test
    fun `dota feature switches take precedence over missing credentials and do not disable other queries`() {
        val config = AgentPluginConfig(dota2 = Dota2Config(enableBinding = false, enableHistory = false, enableAnalysis = false))
        assertTrue(config.dotaCommandUnavailable("绑定")!!.contains("账号绑定已暂停"))
        assertTrue(config.dotaCommandUnavailable("历史")!!.contains("历史查询已暂停"))
        for (command in aiDotaCommands) assertTrue(config.dotaCommandUnavailable(command)!!.contains("AI 分析已暂停"), command)
        assertNull(ready.copy(dota2 = ready.dota2.copy(enableBinding = false)).dotaCommandUnavailable("历史"))
        assertNull(ready.copy(dota2 = ready.dota2.copy(enableHistory = false)).dotaCommandUnavailable("战报"))
        assertNull(ready.copy(dota2 = ready.dota2.copy(enableAnalysis = false)).dotaCommandUnavailable("历史"))
    }

    @Test
    fun `incomplete provider settings do not start chat or ai analysis`() {
        for (api in listOf(ready.api.copy(apiUrl = " "), ready.api.copy(model = " "))) {
            val config = ready.copy(api = api)
            assertTrue(config.chatCommandUnavailable("ds") != null)
            assertFalse(config.chatAtAvailable("ds"))
            assertTrue(config.dotaCommandUnavailable("战报") != null)
        }
        for (provider in listOf(GrokConfig(enabled = true, apiKey = " "), GrokConfig(enabled = true, apiKey = "key", apiUrl = " "), GrokConfig(enabled = true, apiKey = "key", model = " "))) {
            val config = ready.copy(grok = provider.copy(useAsAtTrigger = true))
            assertTrue(config.chatCommandUnavailable("grok") != null)
            assertFalse(config.chatAtAvailable("grok"))
        }
    }
}
