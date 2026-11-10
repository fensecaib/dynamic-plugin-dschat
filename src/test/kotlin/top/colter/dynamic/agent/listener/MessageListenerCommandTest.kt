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
        assertEquals("", commandArgument("/dspro", "/dspro"))
        assertEquals("你好", commandArgument("/dspro　你好", "/dspro"))
        assertEquals("hello", commandArgument("!ask hello", "!ask"))
    }

    @Test
    fun `rejects partial and empty command prefixes`() {
        assertNull(commandArgument("/dsa 你好", "/ds"))
        assertNull(commandArgument("/dspro 你好", "/ds"))
        assertNull(commandArgument("/dsproextra 你好", "/dspro"))
        assertNull(commandArgument("/dota2", "/dota"))
        assertNull(commandArgument("anything", "  "))
    }
}
