package top.colter.dynamic.agent.draw

import kotlinx.coroutines.runBlocking
import top.colter.dynamic.agent.chat.*
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.data.MessageContent
import top.colter.skiko.Fonts
import java.nio.file.Files
import kotlin.test.*

class ChatPlainTextTest {
    @Test fun `short Markdown becomes readable text and restores only Markdown escapes`() {
        assertEquals("标题\n\n重点、斜体、value 和 `字面反引号`。", plainChatText("# 标题\n\n**重点**、*斜体*、`value` 和 \\`字面反引号\\`。"))
        assertEquals("a_b 与 *星号*", plainChatText("a\\_b 与 \\*星号\\*"))
        assertEquals("Tom & Jerry", plainChatText("Tom &amp; Jerry"))
        assertEquals("第一行\n第二行", plainChatText("第一行\n第二行"))
        assertEquals("旧值 → 新值", plainChatText("~~旧值~~ → **新值**"))
    }

    @Test fun `code preserves backslashes regex paths and literal formatting markers`() {
        val code = "val path = \"C:\\Users\\test\"\n    println(\"\\n **literal** \\d+\")"
        assertEquals(code, plainChatText("```kotlin\n$code\n```"))
        assertEquals("C:\\temp\\new", plainChatText("`C:\\temp\\new`"))
        assertEquals("a ` b", plainChatText("``a ` b``"))
        assertEquals("\\n", plainChatText("\\n"))
    }

    @Test fun `links lists quotes and tables retain useful structure`() {
        assertEquals("文档 (https://example.com/a_b?q=1&b=2)", plainChatText("[文档](https://example.com/a_b?q=1&b=2)"))
        assertEquals("• 第一项\n• 第二项\n  • 子项", plainChatText("- 第一项\n- 第二项\n  - 子项"))
        assertEquals("│ 引用\n│ 下一行", plainChatText("> 引用\n> 下一行"))
        assertEquals("名称：测试\n值：42", plainChatText("|名称|值|\n|---|---|\n|测试|42|"))
    }

    @Test fun `transport escaping is bounded and preserves Unicode and escape pairs`() {
        val source = "`literal` **stars** a_b \\path 🙂".repeat(500)
        val plainChunks = splitPlainChatText(source)
        assertEquals(source, plainChunks.joinToString(""))
        val discordChunks = splitPlainChatText(source, discord = true)
        assertTrue(discordChunks.all { it.length <= 1800 })
        for (chunk in discordChunks) {
            assertFalse(chunk.last().isHighSurrogate())
            val tailSlashes = chunk.reversed().takeWhile { it == '\\' }.length
            assertEquals(0, tailSlashes % 2)
        }
        assertEquals(source, discordChunks.joinToString("") { plainChatText(it) })
    }

    @Test fun `short and forced-text replies use the same conversion`() = runBlocking {
        val dir = Files.createTempDirectory("plain-chat-test-")
        try {
            val source = "**重点**：使用 `value`。"
            for (threshold in listOf(0, 1200)) {
                val result = prepareChatReply(source, threshold, ImageConfig(), Fonts.default, CacheUtils(dir)) { _, _, _, _ -> error("render unavailable") }
                assertEquals("重点：使用 value。", result.flatMap { it.content }.joinToString("") { (it as MessageContent.Text).fallbackText })
            }
            val discord = prepareChatReply("`a_b`", 1200, ImageConfig(), Fonts.default, CacheUtils(dir), platformId = "discord")
            assertEquals("a\\_b", (discord.single().content.single() as MessageContent.Text).fallbackText)
            val url = "https://example.com/a_b?q=(hello)&v=1"
            val links = prepareChatReply("[文档]($url)", 1200, ImageConfig(), Fonts.default, CacheUtils(dir), platformId = "discord")
            assertContains((links.single().content.single() as MessageContent.Text).fallbackText, "<$url>")
        } finally { Files.delete(dir) }
    }
}
