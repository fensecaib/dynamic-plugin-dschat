package top.colter.dynamic.agent.draw

import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.*
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.dota2.localizedDota2HeroName
import top.colter.skiko.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil

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
    val icons = if (service == null) previewIcons else withContext(Dispatchers.IO) {
        service.ensureConstants()
        matches.map { it.jsonObject["hero_id"]?.jsonPrimitive?.intOrNull ?: 0 }.distinct()
            .map { id -> async {
                id to try { service.loadHeroIcon(id) } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null // 单个资源失败保留头像槽位，不阻断整张历史图。
                }
            } }.awaitAll().toMap()
    }
    val results = matches.map { historyWon(it.jsonObject) }
    val wins = results.count { it == true }
    val losses = results.count { it == false }
    val unknown = results.count { it == null }
    val rate = if (wins + losses == 0) "—" else "${wins * 100 / (wins + losses)}%"
    val height = 180 + matches.size * 77 + 68
    val scale = config.factor.takeIf { it.isFinite() && it > 0 } ?: 1f
    return try { Surface.makeRasterN32Premul(ceil(1060 * scale).toInt(), ceil(height * scale).toInt()).use { surface ->
        val canvas = surface.canvas
        canvas.clear(C_BG)
        canvas.scale(scale, scale)
        fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
            Paint().use { it.color = color; canvas.drawRect(Rect.makeXYWH(x, y, w, h), it) }
        }
        // 与战报共用宿主 FontRegistry 和字体 fallback；段落限制一行，长名称省略而不挤占其他列。
        fun text(parts: List<Triple<String, Int, Float>>, x: Float, y: Float, w: Float, h: Float = 28f, bold: Boolean = false) {
            ParagraphStyle().use { ps ->
                ps.maxLinesCount = 1
                ps.ellipsis = "…"
                ParagraphBuilder(ps, fontRegistry.fonts).use { builder ->
                    parts.forEach { (value, color, size) ->
                        TextStyle().use { requested ->
                            requested.setColor(color).setFontSize(size).setFontFamily(ff(fontRegistry))
                                .setFontStyle(if (bold) FontStyle.BOLD else FontStyle.NORMAL)
                            fontRegistry.resolveTextStyle(requested).use { style -> builder.pushStyle(style).addText(value); builder.popStyle() }
                        }
                    }
                    builder.build().use { p -> p.layout(w); p.paint(canvas, x, y + (h - p.height) / 2f) }
                }
            }
        }
        fun label(value: String, x: Float, y: Float, w: Float, color: Int = C_TXT2, size: Float = 13f, bold: Boolean = false) =
            text(listOf(Triple(value, color, size)), x, y, w, bold=bold)
        fun metric(key: String, value: String, x: Float, y: Float, color: Int) =
            text(listOf(Triple("$key   ", C_TXT2, 12f), Triple(value, color, 16f)), x, y, 170f, bold=true)
        rect(0f, 0f, 1060f, 86f, C_HDR)
        label("最近 ${matches.size} 场战绩", 22f, 14f, 600f, C_ASSIST, 24f, true)
        label("DOTA 2 · ID $accountId", 22f, 46f, 600f)
        label("最新在前 · 北京时间", 830f, 29f, 210f)
        label("近 ${matches.size} 场胜负", 22f, 99f, 220f, size=12f)
        text(listOf(Triple("$wins 胜", C_GREEN, 23f), Triple("  /  ", C_TXT2, 23f), Triple("$losses 负", C_RED, 23f)), 22f, 130f, 230f, bold=true)
        label("胜率（已知结果）", 270f, 99f, 190f, size=12f)
        label(rate, 270f, 130f, 160f, C_TXT, 23f, true)
        label("近期走势 · 左侧最新" + if (unknown > 0) " · $unknown 场结果未知" else "", 475f, 99f, 560f, size=12f)
        results.forEachIndexed { i, win -> rect(475f + i * 30, 141f, 25f, 7f, when(win) { true -> C_GREEN; false -> C_RED; null -> C_DIM }) }
        matches.forEachIndexed { index, match ->
            val obj = match.jsonObject
            val y = 180f + index * 77
            val won = results[index]
            val accent = when(won) { true -> C_GREEN; false -> C_RED; null -> C_DIM }
            val hero = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
            val name = service?.heroName(hero) ?: localizedDota2HeroName(hero)
            fun number(key: String) = obj[key]?.jsonPrimitive?.intOrNull?.let { String.format(Locale.ROOT, "%,d", it) } ?: "—"
            val duration = obj["duration"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 0 }
                ?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) } ?: "—"
            val date = obj["start_time"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 }?.let {
                SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).apply { timeZone = TimeZone.getTimeZone("Asia/Shanghai") }.format(Date(it * 1000))
            } ?: "—"
            rect(0f, y, 1060f, 76f, accent.withAlpha(0.06f))
            rect(0f, y, 4f, 76f, accent)
            label("%02d".format(index + 1), 13f, y + 24f, 25f, C_DIM, 12f)
            val icon = icons[hero]
            if (icon != null) canvas.drawImageRect(icon, Rect.makeXYWH(44f, y + 16f, 76f, 43f))
            else { rect(44f, y + 16f, 76f, 43f, C_ODD); label("暂无头像", 50f, y + 24f, 66f, C_DIM, 11f) }
            label(name, 134f, y + 10f, 195f, C_NAME, 17f, true)
            label("#${obj["match_id"]?.jsonPrimitive?.contentOrNull ?: "—"}", 134f, y + 38f, 195f, size=12f)
            text(listOf(Triple(number("kills"), C_GREEN, 21f), Triple(" / ", C_TXT2, 21f), Triple(number("deaths"), C_RED, 21f), Triple(" / ", C_TXT2, 21f), Triple(number("assists"), C_ASSIST, 21f)), 346f, y + 10f, 180f, bold=true)
            metric("KDA", historyKda(obj)?.let { "%.1f".format(Locale.ROOT, it) } ?: "—", 346f, y + 38f, C_TXT)
            metric("GPM", number("gold_per_min"), 540f, y + 10f, C_GOLD)
            metric("XPM", number("xp_per_min"), 540f, y + 38f, C_ASSIST)
            metric("伤害", number("hero_damage"), 730f, y + 10f, hexColor("FB849D"))
            label(date, 730f, y + 38f, 190f)
            label(when(won) { true -> "胜利"; false -> "战败"; null -> "未知" }, 962f, y + 10f, 85f, accent, 18f, true)
            label(duration, 962f, y + 38f, 85f)
            rect(0f, y + 76f, 1060f, 1f, C_BORDER)
        }
        val footerY = 180f + matches.size * 77
        rect(0f, footerY, 1060f, 68f, C_ODD)
        label("查看最近第 N 场：/dota 战报 N（1～10） · 精确查询：/dota 战报 比赛ID", 22f, footerY + 6f, 1016f)
        label("普通战报关闭思考 · /dota 深度战报 [序号或比赛ID] 开启思考 · 新比赛使序号顺延 · — 暂无数据", 22f, footerY + 34f, 1016f, C_DIM, 12f)
        surface.makeImageSnapshot()
    } } finally {
        // 服务加载的图标是本次调用创建的 Skia 对象；预览传入的图标由调用者管理。
        if (service != null) icons.values.filterNotNull().forEach { it.close() }
    }
}
