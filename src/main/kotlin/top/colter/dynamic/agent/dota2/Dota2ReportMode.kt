package top.colter.dynamic.agent.dota2

/** 每次请求独立选择模式，不修改共享客户端或全局配置。 */
enum class Dota2ReportMode(val command: String, val thinkingType: String) {
    NORMAL("战报", "disabled"),
    DEEP("深度战报", "enabled");

    fun progressText(matchId: Long): String = when (this) {
        NORMAL -> "正在生成比赛 #$matchId 的战报..."
        DEEP -> "正在生成深度战报 #$matchId，请稍候。"
    }

    companion object {
        fun fromCommand(command: String?): Dota2ReportMode? = entries.find { it.command == command }
    }
}

internal val dotaCommandHelp = """
    Dota2 指令（<必填>，[可选]，括号不用输入）：
    /dota 绑定 <玩家ID>
    /dota 历史 [玩家ID]：最近10场
    /dota 战报 [序号]
    /dota 战报 <玩家ID> [序号]：指定玩家
    /dota 战报 比赛 <比赛ID>：精确查询
    /dota 个人详情 [玩家ID]：最近10场分析
    /dota 分析 <比赛ID>：双阵营分析
    战报、个人详情加“深度”前缀可开启思考，耗时更长。
    查询前需先绑定；不填玩家用绑定账号，不填序号查最新。序号1～10；单参数>10按玩家ID。
""".trimIndent()
