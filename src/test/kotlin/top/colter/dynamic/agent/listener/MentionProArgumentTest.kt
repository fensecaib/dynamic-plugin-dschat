package top.colter.dynamic.agent.listener

import top.colter.dynamic.core.data.*
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import kotlin.test.*

class MentionProArgumentTest {
    private fun context(text: String, mentions: Set<String> = setOf("bot"), segments: List<IncomingMessageSegment> = emptyList()): IncomingMessageDispatchContext {
        val target = TargetAddress.of("qq", TargetKind.GROUP, "group")
        return IncomingMessageDispatchContext(
            message = IncomingMessage(PlatformId.of("qq"), target, "user", botAccountId = "bot", text = text, mentions = mentions, segments = segments),
            commandContext = CommandContext(target, "user", "bot", mentions), rawText = text,
        )
    }

    @Test fun `only exact slash pro with real mention selects pro`() {
        assertEquals("问题", mentionProArgument(context("  /PRO　问题")))
        assertEquals("问题", mentionProArgument(context("/pro\n问题")))
        assertEquals("", mentionProArgument(context("/pro")))
        for (text in listOf("pro 问题", "/profile 问题", "/pro问题", "请解释 /pro")) {
            assertNull(mentionProArgument(context(text)), text)
        }
        assertNull(mentionProArgument(context("/pro 问题", mentions = setOf("other-bot"))))
        assertNull(mentionProArgument(context("@bot /pro 问题", mentions = emptySet())))
    }

    @Test fun `structured mention removes only addressing prefix and preserves question mentions and links`() {
        val segments = listOf(
            IncomingMessageSegment.Reply("previous"),
            IncomingMessageSegment.Text(" "),
            IncomingMessageSegment.Mention("bot"),
            IncomingMessageSegment.Text(" /pro 比较"),
            IncomingMessageSegment.Mention("bot"),
            IncomingMessageSegment.Text("和"),
            IncomingMessageSegment.Mention("other"),
            IncomingMessageSegment.Link("https://example.com"),
        )
        val prompt = assertNotNull(mentionProArgument(context("@bot /pro 比较", segments = segments)))
        assertContains(prompt, "@bot")
        assertContains(prompt, "@other")
        assertContains(prompt, "https://example.com")
        assertFalse(prompt.contains("/pro"))
    }
}
