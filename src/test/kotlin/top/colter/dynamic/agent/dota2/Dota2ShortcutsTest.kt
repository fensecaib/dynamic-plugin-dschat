package top.colter.dynamic.agent.dota2

import kotlin.test.*

class Dota2ShortcutsTest {
    private val expected = listOf(
        "dota绑定" to "绑定",
        "dota历史" to "历史",
        "dota战报" to "战报",
        "dota深报" to "深度战报",
        "dota分析" to "分析",
        "dota详情" to "个人详情",
        "dota深详" to "深度个人详情",
        "dota帮助" to "帮助",
    )

    @Test fun `all eight shortcuts use unique whitespace free names in a stable order`() {
        assertEquals(expected, dotaShortcuts.map { it.token to it.command })
        assertEquals(dotaShortcuts.size, dotaShortcuts.map { it.token }.toSet().size)
        assertTrue(dotaShortcuts.all { shortcut ->
            shortcut.token.isNotBlank() && '/' !in shortcut.token &&
                shortcut.token.none { it.isWhitespace() } && shortcut.description.isNotBlank()
        })
    }

    @Test fun `all shortcuts translate to existing subcommands with their arguments`() {
        for ((token, command) in expected) {
            assertEquals("$command 9044880093", compactDotaCommand("/$token 9044880093"), token)
            assertEquals("$command 100000001 2", compactDotaCommand("/$token  100000001 2  "), token)
        }
    }

    @Test fun `empty parameters preserve the command including help`() {
        for ((token, command) in expected) {
            for (suffix in listOf("", " ", "\t\n", "\u3000\u3000")) {
                assertEquals(command, compactDotaCommand("/$token$suffix"), "$token with whitespace suffix")
            }
        }
        assertEquals("帮助", compactDotaCommand("/dota帮助"))
    }

    @Test fun `unicode full width whitespace and tabs delimit the command name`() {
        for ((token, command) in expected) {
            for (whitespace in listOf("\u3000", "\t", "\n", " \u3000\t")) {
                assertEquals("$command 123", compactDotaCommand("/$token${whitespace}123\u3000"), token)
            }
        }
    }

    @Test fun `similar names and suffixes cannot trigger a shorter shortcut`() {
        for ((token, _) in expected) {
            for (suffix in listOf("xxx", "记录", "123", "/")) {
                assertNull(compactDotaCommand("/$token$suffix"), token + suffix)
            }
        }
        assertNull(compactDotaCommand("/dota绑定xxx 123"))
        assertNull(compactDotaCommand("/dota历史记录"))
    }

    @Test fun `slash is required and original or generic commands are left to other handlers`() {
        for ((token, _) in expected) {
            assertNull(compactDotaCommand(token))
            assertNull(compactDotaCommand("$token 123"))
            assertNull(compactDotaCommand("//$token 123"))
            assertNull(compactDotaCommand(" /$token 123"))
        }
        for (message in listOf("", "/dota 历史", "/d2 战报", "/绑定 123", "/历史", "/战报", "/dota未知")) {
            assertNull(compactDotaCommand(message), message)
        }
    }
}
