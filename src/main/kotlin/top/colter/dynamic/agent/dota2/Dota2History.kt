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

internal const val dotaBindingRequired = "使用 Dota 功能前需先绑定账号：/dota 绑定 <玩家ID>（指定玩家查询也需先绑定）。"
internal fun dotaQueryRequiresBinding(command: String?) = command in setOf("历史", "战报", "深度战报", "个人详情", "深度个人详情", "分析")
internal fun hasDotaBinding(binding: Long?) = binding != null && binding in 1..4294967295L

internal fun dotaPlayerAccount(argument: String?, binding: Long?): Long {
    require(hasDotaBinding(binding)) { dotaBindingRequired }
    if(argument==null) return requireNotNull(binding)
    return requireNotNull(argument.takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()?.takeIf { it in 1..4294967295L }) { "请输入有效的玩家ID（1～4294967295）" }
}

internal suspend fun resolveDotaReportTarget(
    args: List<String>, binding: Long?, fetchRecent: suspend (Long) -> JsonArray?,
): Pair<Long,Long> {
    val account=dotaPlayerAccount(null,binding)
    val mode=requireNotNull(Dota2ReportMode.fromCommand(args.firstOrNull())) { "未知战报指令" }
    val usage="用法：/dota ${mode.command} [序号]；/dota ${mode.command} <玩家ID> [序号]；/dota ${mode.command} 比赛 <比赛ID>"
    require(args.size in 1..3) { usage }
    if(args.getOrNull(1)=="比赛") {
        require(args.size==3) { usage }
        val id=requireNotNull(args[2].takeIf { it.isNotEmpty() && it.all { c -> c in '0'..'9' } }?.toLongOrNull()?.takeIf { it>0 }) { "请输入有效比赛ID" }
        return account to id
    }
    if(args.size==1) return account to resolveDotaReportMatchId(null,mode) { fetchRecent(account) }
    val value=dotaPlayerAccount(args[1],binding)
    if(args.size==2 && value<=10) return account to resolveDotaReportMatchId(args[1],mode) { fetchRecent(account) }
    val index=args.getOrNull(2)
    if(index!=null) require(index.isNotEmpty() && index.all { it in '0'..'9' } && index.toIntOrNull() in 1..10) { "比赛序号必须为1～10" }
    return value to resolveDotaReportMatchId(index,mode) { fetchRecent(value) }
}

internal fun historyCommandHint(count: Int, accountId: Long? = null): String {
    val prefix=accountId?.let { "$it " }.orEmpty()
    return "查战报：/dota 战报 ${prefix}N（N=1～$count，1为最新，随新对局顺延）。开启思考用“深度战报”。"
}

internal fun historyTextFallback(matches: JsonArray): String = matches.mapIndexed { i, match ->
    "${i + 1}. ${match.jsonObject["match_id"]?.jsonPrimitive?.contentOrNull ?: "—"}"
}.joinToString("\n", prefix = "最近${matches.size}场（最新在前）：\n")

/** Normalize the response once so winner selection, AI input and cards use identical sides. */
internal data class DotaTargetMatch(val detail: JsonObject, val won: Boolean)

internal fun prepareDotaTargetMatch(detail: JsonObject, expectedMatchId: Long, accountId: Long): DotaTargetMatch {
    require((detail["match_id"] as? JsonPrimitive)?.longOrNull == expectedMatchId) { "返回的比赛ID与请求不一致，请重试" }
    val players=(detail["players"] as? JsonArray)?.map { it as? JsonObject ?: throw IllegalArgumentException("比赛玩家数据不完整") }
        ?: throw IllegalArgumentException("比赛缺少玩家数据")
    require(players.count { (it["account_id"] as? JsonPrimitive)?.longOrNull == accountId } == 1) { "该比赛中未找到唯一的玩家 #$accountId" }
    val radiantWin=requireNotNull((detail["radiant_win"] as? JsonPrimitive)?.booleanOrNull) { "比赛结果尚不可用，请稍后重试" }
    val normalized=players.map { player ->
        val explicit=(player["isRadiant"] as? JsonPrimitive)?.booleanOrNull
        val slot=(player["player_slot"] as? JsonPrimitive)?.intOrNull
        val fromSlot=when(slot) { in 0..4 -> true; in 128..132 -> false; else -> null }
        require(explicit==null || fromSlot==null || explicit==fromSlot) { "玩家阵营数据冲突，请稍后重试" }
        val side=requireNotNull(explicit ?: fromSlot) { "玩家阵营数据缺失，请稍后重试" }
        JsonObject(player+("isRadiant" to JsonPrimitive(side)))
    }
    val target=normalized.single { (it["account_id"] as? JsonPrimitive)?.longOrNull == accountId }
    return DotaTargetMatch(JsonObject(detail+("players" to JsonArray(normalized))),target["isRadiant"]!!.jsonPrimitive.boolean==radiantWin)
}
