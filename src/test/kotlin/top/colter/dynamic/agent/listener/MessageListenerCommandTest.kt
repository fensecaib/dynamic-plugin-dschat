package top.colter.dynamic.agent.listener

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MessageListenerCommandTest {
    @Test
    fun `accepts exact command prefix and whitespace separated argument`() {
        assertEquals("", commandArgument("/ds", "/ds"))
        assertEquals("你好", commandArgument("/ds  你好", "/ds"))
        assertEquals("你好", commandArgument("/ds　你好", "/ds"))
        assertEquals("hello", commandArgument("!grok hello", "!grok"))
    }

    @Test
    fun `rejects partial and empty command prefixes`() {
        assertNull(commandArgument("/dsa 你好", "/ds"))
        assertNull(commandArgument("/dota2", "/dota"))
        assertNull(commandArgument("anything", "  "))
    }
}
