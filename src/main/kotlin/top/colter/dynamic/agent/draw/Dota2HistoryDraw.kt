package top.colter.dynamic.agent.draw

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.dota2.localizedDota2HeroName
import top.colter.skiko.*
import java.util.*
import java.util.concurrent.ConcurrentHashMap

internal fun historyWon(obj: JsonObject): Boolean? {
    val slot = obj["player_slot"]?.jsonPrimitive?.intOrNull ?: return null
    val win = obj["radiant_win"]?.jsonPrimitive?.booleanOrNull ?: return null
    if (slot !in 0..4 && slot !in 128..132) return null
    return (slot < 128) == win
}

internal fun historyKda(obj: JsonObject): Double? {
    val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: return null
    val d = obj["deaths"]?.jsonPrimitive?.intOrNull ?: return null
    val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: return null
    return (k.toDouble() + a) / d.coerceAtLeast(1)
}

/** 输入为服务层已排序的列表。固定逻辑坐标绘制并整体缩放，避免全局 Dp 状态和多字号基线漂移。 */
suspend fun dota2HistoryDraw(
    accountId: Long, matches: JsonArray, config: ImageConfig,
    fontRegistry: FontRegistry = Fonts.default,
    service: Dota2Service? = null,
    previewIcons: Map<Int, Image?> = emptyMap(),
): Image? {
    val loadedIcons = ConcurrentHashMap<Int, Image>()
    try {
        if (service != null) withContext(Dispatchers.IO) {
            service.ensureConstants()
            val heroes = matches.mapNotNull { it.jsonObject["hero_id"]?.jsonPrimitive?.intOrNull }.distinct()
            for (id in heroes) launch {
                val icon = try { service.loadHeroIcon(id) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                if (icon != null) loadedIcons[id] = icon
            }
        }
        val icons = if (service == null) previewIcons else loadedIcons
        val results = matches.map { historyWon(it.jsonObject) }
        val wins = results.count { it == true }
        val losses = results.count { it == false }
        val unknown = results.size - wins - losses
        val footerY = 276f + matches.size * 74f
        val scale = config.factor.takeIf { it.isFinite() && it > 0 } ?: 1f
        return renderReportPage(footerY + 140, scale, fontRegistry) {
            titleBar("近期战绩")
            text("玩家 ID", 810f, 16f, 346f, 14f, C_DIM, align = Alignment.RIGHT)
            text(accountId.toString(), 810f, 37f, 346f, 28f, C_ASSIST, true, Alignment.RIGHT)
            panel(inset, 110f, pageInner, 80f)
            text("最近 ${matches.size} 场", 47f, 120f, 156f, 14f, C_DIM)
            text("$wins 胜", 46f, 141f, 80f, 24f, C_GREEN, true)
            text("$losses 负", 129f, 141f, 80f, 24f, C_RED, true)
            line(233f, 123f, 233f, 177f, C_BORDER)
            text("已知结果胜率", 256f, 120f, 162f, 14f, C_DIM)
            val rate = if (wins + losses == 0) "—" else "${wins * 100 / (wins + losses)}%"
            text(rate, 255f, 141f, 150f, 24f, C_GOLD, true)
            line(415f, 123f, 415f, 177f, C_BORDER)
            val unknownNote = if (unknown > 0) "   ·   $unknown 场未知" else ""
            text("胜负走势 · 最新在左$unknownNote", 442f, 120f, 665f, 14f, C_DIM)
            results.forEachIndexed { i, won ->
                val color = resultColor(won)
                chip(resultLabel(won), 442f + 69f * i, 147f, 59f, color.withAlpha(.13f), color)
            }
            section("比赛记录", 207f, "按序号查询   /   01 为最新一场")
            listOf(42f to "序号", 116f to "英雄 / 比赛编号", 384f to "K / D / A", 579f to "GPM / XPM",
                731f to "英雄伤害", 863f to "开赛时间 · 北京时间", 1063f to "结果").forEach { (x, label) ->
                text(label, x, 249f, 170f, 14f, C_DIM)
            }
            matches.forEachIndexed { index, match ->
                val obj = match.jsonObject
                val hero = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
                at(inset, 276f + index * 74f) {
                    historyRow(index + 1, obj, results[index], service?.heroName(hero) ?: localizedDota2HeroName(hero), icons[hero])
                }
            }
            text("查看第 N 场", inset, footerY + 12, 125f, 17f, C_TXT, true)
            text("/dota战报 $accountId N", 161f, footerY + 11, 430f, 18f, C_ASSIST, true)
            text("不填 N 查最新一场，新对局会使序号顺延。", 590f, footerY + 15, 566f, 15f, C_TXT2, align = Alignment.RIGHT)
            text("按编号查询  /dota战报 比赛 <比赛ID>（使用绑定账号）    ·    深度战报开启思考", inset, footerY + 45, pageInner, 14f, C_DIM)
            at(inset, footerY + 78) { footer() }
        }
    } finally {
        loadedIcons.values.forEach { it.close() }
    }
}

private fun Dota2ReportCanvas.historyRow(index: Int, obj: JsonObject, won: Boolean?, name: String, icon: Image?) {
    fun number(key: String) = obj[key]?.jsonPrimitive?.intOrNull?.let { String.format(Locale.ROOT, "%,d", it) } ?: "—"
    panel(0f, 0f, pageInner, 68f)
    val color = resultColor(won)
    box(1f, 9f, 3f, 50f, color, 1.5f)
    text("%02d".format(Locale.ROOT, index), 10f, 16f, 54f, 25f, if (index == 1) C_GOLD else C_TXT, true, Alignment.CENTER)
    box(80f, 8f, 90f, 52f, C_ODD, 4f)
    if (icon != null) image(icon, 80f, 8f, 90f, 52f, 4f)
    else text("暂无头像", 80f, 25f, 90f, 12f, C_DIM, align = Alignment.CENTER)
    text(name, 182f, 8f, 161f, 20f, heroText, true)
    text("#${obj["match_id"]?.jsonPrimitive?.contentOrNull ?: "—"}", 182f, 38f, 161f, 14f, C_DIM)
    // 对齐各个数字的基线，同时保留击杀、死亡和助攻的颜色。
    val stats = listOf(number("kills") to C_GREEN, number("deaths") to C_RED, number("assists") to C_ASSIST)
    stats.forEachIndexed { i, (value, tint) ->
        textOnBaseline(value, 356f + i * 48, 32f, 39f, 22f, tint, true)
        if (i < 2) textOnBaseline("/", 392f + i * 48, 32f, 12f, 17f, C_DIM)
    }
    val kda = historyKda(obj)?.let { "%.1f".format(Locale.ROOT, it) } ?: "—"
    text("KDA  $kda", 356f, 39f, 155f, 14f, C_DIM)
    text(number("gold_per_min"), 551f, 8f, 116f, 21f, C_GOLD, true)
    text(number("xp_per_min"), 551f, 38f, 116f, 16f, C_TXT2)
    text(number("hero_damage"), 703f, 18f, 123f, 20f, C_TXT, true)
    val start = obj["start_time"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }
    val duration = obj["duration"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
    text(matchDate(start), 835f, 10f, 172f, 17f, C_TXT)
    text("时长  ${matchDuration(duration)}", 835f, 38f, 172f, 14f, C_DIM)
    chip(resultLabel(won), 1035f, 23f, 67f, color.withAlpha(.13f), color)
}
