package top.colter.dynamic.agent.dota2

/** QQ official commands need one command name; arguments remain separate from that name. */
internal data class DotaShortcut(val token: String, val command: String, val description: String)

internal val dotaShortcuts = listOf(
    DotaShortcut("dota绑定", "绑定", "绑定 Dota2 账号"),
    DotaShortcut("dota历史", "历史", "查看最近比赛记录"),
    DotaShortcut("dota战报", "战报", "生成普通比赛战报"),
    DotaShortcut("dota深报", "深度战报", "生成深度比赛战报"),
    DotaShortcut("dota分析", "分析", "分析比赛双方表现"),
    DotaShortcut("dota详情", "个人详情", "查看玩家个人详情"),
    DotaShortcut("dota深详", "深度个人详情", "查看深度个人详情"),
    DotaShortcut("dota帮助", "帮助", "查看 Dota2 指令帮助"),
)

/** Return the existing Dota subcommand only after matching a complete shortcut token. */
internal fun compactDotaCommand(message: String): String? {
    for (shortcut in dotaShortcuts) {
        val prefix = "/${shortcut.token}"
        if (!message.startsWith(prefix)) continue
        val remainder = message.substring(prefix.length)
        if (remainder.isNotEmpty() && !remainder.first().isWhitespace()) continue
        val args = remainder.trim()
        return if (args.isEmpty()) shortcut.command else "${shortcut.command} $args"
    }
    return null
}
