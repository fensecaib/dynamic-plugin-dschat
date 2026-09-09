package top.colter.dynamic.agent.dota2

import kotlinx.serialization.json.*

/** 历史图和实时序号查询使用同一顺序；同时间比赛按 ID 降序，缺失时间排最后。 */
internal fun orderedRecentMatches(matches: JsonArray, limit: Int = 10): JsonArray = JsonArray(
    matches.filterIsInstance<JsonObject>().sortedWith(
        compareByDescending<JsonObject> { it["start_time"]?.jsonPrimitive?.longOrNull ?: Long.MIN_VALUE }
            .thenByDescending { it["match_id"]?.jsonPrimitive?.longOrNull ?: Long.MIN_VALUE }
    ).take(limit.coerceAtLeast(0))
)

/** 1..10 是实时最近第 N 场；更大的正整数保留为比赛 ID，不依赖查询历史的会话状态。 */
internal suspend fun resolveDotaReportMatchId(
    argument: String?,
    mode: Dota2ReportMode = Dota2ReportMode.NORMAL,
    fetchRecent: suspend () -> JsonArray?,
): Long {
    val value = if (argument == null) 1L else {
        require(argument.isNotEmpty() && argument.all { it in '0'..'9' }) {
            "参数无效：请使用 /dota ${mode.command} [1～10 或比赛ID]（不填为最新一场）"
        }
        argument.toLongOrNull() ?: throw IllegalArgumentException("比赛ID超出有效范围")
    }
    require(value > 0) { "序号为1～10，比赛ID必须为正整数" }
    if (value > 10) return value
    val matches = fetchRecent() ?: throw IllegalArgumentException("获取最近战绩失败，请稍后重试")
    val ordered = orderedRecentMatches(matches)
    require(ordered.isNotEmpty()) { "未找到最近对局" }
    require(value <= ordered.size) { "当前仅有${ordered.size}场最近战绩，无法查询第${value}场" }
    return ordered[value.toInt() - 1].jsonObject["match_id"]?.jsonPrimitive?.longOrNull
        ?.takeIf { it > 0 } ?: throw IllegalArgumentException("该场缺少有效比赛ID，请使用完整比赛ID查询")
}

internal fun historyCommandHint(count: Int): String =
    "查看最近第N场：/dota 战报 N（N为1～$count）。\n序号按实时最新战绩查询；精确查询：/dota 战报 比赛ID\n普通战报关闭思考；需要开启思考时使用 /dota 深度战报 [序号或比赛ID]，预计约需1～3分钟。"

internal fun historyTextFallback(matches: JsonArray): String = matches.mapIndexed { i, match ->
    "${i + 1}. ${match.jsonObject["match_id"]?.jsonPrimitive?.contentOrNull ?: "—"}"
}.joinToString("\n", prefix = "最近${matches.size}场（最新在前）：\n")
