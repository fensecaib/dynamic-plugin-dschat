package top.colter.dynamic.agent.dota2

/** 每次请求独立选择模式，不修改共享客户端或全局配置。 */
enum class Dota2ReportMode(val command: String, val thinkingType: String) {
    NORMAL("战报", "disabled"),
    DEEP("深度战报", "enabled");

    fun progressText(matchId: Long): String = when (this) {
        NORMAL -> "正在生成比赛 #$matchId 的战报..."
        DEEP -> "正在生成比赛 #$matchId 的深度战报，已开启思考，预计约需 1～3 分钟，实际耗时可能波动。"
    }

    companion object {
        fun fromCommand(command: String?): Dota2ReportMode? = entries.find { it.command == command }
    }
}

internal val dotaCommandHelp = """
    Dota2 指令：
    /dota 绑定 <9位ID>：绑定 Dota2 账号
    /dota 历史：查看最近 10 场战绩，最新在前
    /dota 战报 [序号或比赛ID]：普通战报，关闭思考，出报更快
    /dota 深度战报 [序号或比赛ID]：开启思考，预计约需 1～3 分钟，耗时可能波动
    两种战报均支持：不填查最新一场；1～10 查实时最近第 N 场；大于 10 的正整数按比赛 ID 查询。
    无需先查历史；有新比赛时序号会顺延，精确查询请使用比赛 ID。
    普通战报与深度战报共用一个任务名额，忙碌时请等待完成后重发，不排队。
    /dota 分析 <比赛ID>：双阵营完整分析
    /dota 个人详情 [ID]：玩家数据与 AI 诊断，不填使用绑定账号
""".trimIndent()
