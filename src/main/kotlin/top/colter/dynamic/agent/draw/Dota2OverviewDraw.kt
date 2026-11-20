package top.colter.dynamic.agent.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.Paragraph
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.*
import top.colter.skiko.FontRegistry
import top.colter.skiko.Fonts
import top.colter.skiko.withAlpha
import java.util.Locale
import kotlin.math.*

private fun percent(value: Double?) = value?.let { "%.1f%%".format(Locale.ROOT, it) } ?: "—"
private fun OverviewMatch.kda() = listOf("kills", "deaths", "assists").joinToString(" / ") { number(it) }
private const val tableRowHeight = 42f
private const val tableHeaderHeight = 41f

// AI 段落先按最终栏宽排版，实测高度同时决定底板、下一节位置和整张图大小。
private class OverviewLayout(private val fonts: FontRegistry) : AutoCloseable {
    private val paragraphs = mutableListOf<Paragraph>()
    private val rows = mutableListOf<Pair<Float, Dota2ReportCanvas.() -> Unit>>()
    private var height = 0f

    fun body(value: String, width: Float, size: Float = 17f, color: Int = C_TXT2) =
        reportParagraph(fonts, value, width, size, color, singleLine = false).also(paragraphs::add)

    fun row(h: Float, draw: Dota2ReportCanvas.() -> Unit) {
        rows += height to draw
        height += h
    }

    fun heading(title: String, note: String = "") = row(48f) { section(title, 0f, note) }

    fun render(config: ImageConfig): Image {
        val factor = config.factor.takeIf { it.isFinite() && it > 0 } ?: 1f
        val scale = min(1600f / pageWidth * factor.coerceAtMost(2f), sqrt(16_000_000f / (pageWidth * height)))
        return renderReportPage(height, scale, fonts) { rows.forEach { (y, draw) -> at(0f, y, draw) } }
    }

    override fun close() = paragraphs.forEach { it.close() }
}

internal fun dota2OverviewDraw(report: GeneratedOverview, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): Image {
    val s = report.snapshot
    val a = report.analysis
    val assets = report.assets
    val matches = s.matches
    val groups = matches.groupBy { it.heroId }.values.sortedWith(
        compareByDescending<List<OverviewMatch>> { it.size }.thenBy { it.first().heroId }
    )
    return OverviewLayout(fontRegistry).use { layout -> with(layout) {
        row(177f) {
            titleBar(report.mode.command)
            val known = s.wins + s.losses
            val rate = if (known > 0) "${s.wins * 100 / known}%" else "—"
            text("最近 ${matches.size} 场   /   胜率 $rate", 720f, 17f, 436f, 15f, C_DIM, align = Alignment.RIGHT)
            text("${s.wins} 胜   /   ${s.losses} 负", 720f, 42f, 436f, 27f, C_TXT, true, Alignment.RIGHT)
            box(0f, 96f, pageWidth, 64f, C_HDR)
            var x = inset
            assets.avatar?.let { image(it, x, 104f, 48f, 48f, 5f); x += 60f }
            playerId(s.player, x, 124f, 500f)
            text(s.accountId.toString(), x + 27, 132f, 460f, 13f, C_DIM)
            text("统计区间 · 北京时间", 665f, 101f, 491f, 13f, C_DIM, align = Alignment.RIGHT)
            text("${matchDate(matches.firstOrNull()?.start)}  —  ${matchDate(matches.lastOrNull()?.start)}",
                630f, 123f, 526f, 19f, C_TXT, true, Alignment.RIGHT)
        }
        val stats = listOf(
            "场均 K / D / A" to listOf("kills", "deaths", "assists").joinToString(" / ") { s.average(it).display() },
            "场均 GPM" to s.average("gold_per_min").display(0), "场均 XPM" to s.average("xp_per_min").display(0),
            "场均击杀参与率" to percent(s.average("kill_participation").value)
        )
        row(94f) {
            stats.forEachIndexed { i, (label, value) ->
                val x = inset + i * 285
                panel(x, 0f, 273f, 74f)
                text(label, x + 15, 8f, 244f, 15f, C_DIM)
                text(value, x + 14, 30f, 245f, 25f, if (i == 1) C_GOLD else C_TXT, true)
            }
        }
        a.notice?.let {
            val message = body(it, pageInner - 34, color = C_GOLD)
            row(message.height + 38) { panel(inset, 0f, pageInner, message.height + 22); message.paint(canvas, inset + 17, 11f) }
        }
        heading("最近 ${matches.size} 场明细", "与趋势图一致   /   从旧到新")
        val tableHeight = tableHeaderHeight + tableRowHeight * matches.size + 4
        row(tableHeight + 17) { overviewTable(matches, assets, tableHeight) }
        heading("近期表现")
        val chartHeight = max(277f, 84f + groups.size * 40)
        row(chartHeight + 12) {
            panel(inset, 0f, 548f, chartHeight)
            text("英雄分布", 44f, 12f, 250f, 21f, C_TXT, true)
            for ((x, label) in listOf(312f to "场次", 394f to "胜 / 负", 484f to "GPM")) {
                text(label, x, 18f, 71f, 14f, C_DIM, align = Alignment.RIGHT)
            }
            groups.forEachIndexed { i, group ->
                val m = group.first()
                val y = 54f + i * 40
                assets.heroes[m.heroId]?.let { image(it, 44f, y, 51f, 30f, 3f) }
                text(m.hero, 106f, y + 1, 125f, 17f, heroText, true)
                box(235f, y + 13, 65f, 4f, C_BORDER, 2f)
                box(235f, y + 13, 65f * group.size / groups.first().size, 4f, C_GOLD, 2f)
                text(group.size.toString(), 312f, y + 2, 71f, 17f, C_TXT, align = Alignment.RIGHT)
                text("${group.count { it.won == true }} / ${group.count { it.won == false }}", 394f, y + 2, 71f, 16f, C_TXT, align = Alignment.RIGHT)
                val gpms = group.mapNotNull { it.values["gold_per_min"] }
                val gpm = gpms.takeIf { it.isNotEmpty() }?.average()?.roundToInt()?.toString() ?: "—"
                text(gpm, 484f, y + 2, 71f, 17f, C_GOLD, true, Alignment.RIGHT)
            }
            text("样本场次仅表示近期使用频率", 44f, chartHeight - 28, 490f, 13f, C_DIM)
            panel(590f, 0f, 566f, chartHeight)
            radar(s, chartHeight)
        }
        val overview = body(a.body("overview"), 933f)
        val overviewHeight = max(76f, overview.height + 32)
        row(overviewHeight + 12) {
            panel(inset, 0f, pageInner, overviewHeight)
            text("表现总览", 44f, 15f, 142f, 20f, C_TXT, true)
            overview.paint(canvas, 200f, 16f)
        }
        row(231f) { trend(matches, assets) }
        heading("多维分析")
        val metrics = mapOf(
            "economy" to "GPM  ${s.average("gold_per_min").display(0)}   /   XPM  ${s.average("xp_per_min").display(0)}",
            "output" to "英雄伤害  ${s.average("hero_damage").display(0)}   /   塔伤  ${s.average("tower_damage").display(0)}",
            "survival" to "死亡  ${s.average("deaths").display()}   /   参战率  ${percent(s.average("kill_participation").value)}",
            "heroes" to "${groups.size} 个英雄   /   ${matches.size} 场"
        )
        overviewDimensions.entries.chunked(2).forEach { pair ->
            val bodies = pair.map { body(a.body(it.key), 524f) }
            val h = 64 + bodies.maxOf { it.height }
            row(h + 14) {
                pair.forEachIndexed { i, (key, title) ->
                    val x = inset + i * 571
                    panel(x, 0f, 557f, h)
                    text(title, x + 16, 10f, 205f, 20f, C_TXT, true)
                    text(metrics.getValue(key), x + 220, 16f, 320f, 14f, C_GOLD, align = Alignment.RIGHT)
                    bodies[i].paint(canvas, x + 16, 47f)
                }
            }
        }
        heading("代表对局", "按经济、输出、推进三项同英雄百分位均值选取")
        val representatives = s.representatives
        if (representatives.isEmpty()) row(50f) { text("同英雄基准不足，暂无代表对局。", 44f, 4f, pageInner, 17f, C_DIM) }
        for (rep in representatives) {
            val review = body(a.body("match_${rep.match.id}"), 732f)
            val h = max(229f, 62f + review.height)
            row(h + 14) {
                panel(inset, 0f, pageInner, h)
                at(39f, (h - 207f) / 2) { representativeCard(rep, s.player, assets) }
                line(390f, 14f, 390f, h - 14, C_BORDER)
                val y = (h - review.height - 36) / 2
                text("对局复盘", 407f, y, 310f, 20f, C_TXT, true)
                text("#${rep.match.id}    /    ${matchDate(rep.match.start)}", 719f, y + 5, 420f, 14f, C_DIM, align = Alignment.RIGHT)
                review.paint(canvas, 407f, y + 36)
            }
        }
        heading("复盘建议")
        val conclusion = body(a.body("conclusion"), pageInner - 38)
        row(conclusion.height + 46) {
            panel(inset, 0f, pageInner, conclusion.height + 30)
            conclusion.paint(canvas, inset + 19, 15f)
        }
        val note = body("详情覆盖 ${s.details} / ${matches.size} 场，未知胜负 ${matches.size - s.wins - s.losses} 场。均值只计有效数据，缺失显示 —。百分位以相同英雄为基准，三项均值用于选取样本。分析基于赛后记录，未使用录像、分路、购买记录或经济时间线。", pageInner, 14f, C_DIM)
        row(note.height + 6) { note.paint(canvas, inset, 0f) }
        row(60f) { at(inset, 0f) { footer() } }
        render(config)
    } }
}

private fun Dota2ReportCanvas.radar(snapshot: OverviewSnapshot, height: Float) {
    text("同英雄基准雷达", 607f, 12f, 370f, 21f, C_TXT, true)
    text("平均百分位", 980f, 19f, 157f, 14f, C_DIM, align = Alignment.RIGHT)
    val means = overviewMetrics.keys.map { snapshot.average(it, true) }
    val center = Point(872f, height / 2 + 6)
    fun point(i: Int, radius: Float): Point {
        val angle = -PI / 2 + i * 2 * PI / means.size
        return Point(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
    }
    val radius = 69f
    for (i in means.indices) {
        val next = (i + 1) % means.size
        for (fraction in listOf(.25f, .5f, .75f, 1f)) {
            val a = point(i, radius * fraction)
            val b = point(next, radius * fraction)
            line(a.x, a.y, b.x, b.y, C_BORDER)
        }
        val axis = point(i, radius)
        line(center.x, center.y, axis.x, axis.y, C_BORDER)
    }
    val vertices = means.mapIndexed { i, mean -> mean.value?.let { point(i, radius * it.toFloat() / 100) } }
    if (vertices.all { it != null }) {
        PathBuilder().use { path ->
            vertices.filterNotNull().forEachIndexed { i, p -> if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
            path.closePath()
            path.detach().use { shape -> Paint().use { it.color = C_GOLD.withAlpha(.18f); canvas.drawPath(shape, it) } }
        }
    }
    vertices.forEachIndexed { i, p ->
        if (p != null) {
            vertices[(i + 1) % vertices.size]?.let { line(p.x, p.y, it.x, it.y, C_GOLD, 2f) }
            box(p.x - 3, p.y - 3, 6f, 6f, C_GOLD, 3f)
        }
        val label = point(i, radius + 30)
        text("${overviewMetrics.values.elementAt(i)} ${means[i].display(0)}", label.x - 55, label.y - 10, 110f, 15f, C_TXT2, true, Alignment.CENTER)
    }
    val counts = means.map { it.count }
    val range = if (counts.min() == counts.max()) "${counts.min()}" else "${counts.min()}–${counts.max()}"
    text("有效样本  $range / ${snapshot.matches.size} 场", 607f, height - 28, 531f, 13f, C_DIM)
}

private fun Dota2ReportCanvas.trend(matches: List<OverviewMatch>, assets: Dota2ReportAssets) {
    panel(inset, 0f, pageInner, 211f)
    text("逐场趋势", 44f, 12f, 230f, 21f, C_TXT, true)
    text("经济、输出、推进的同英雄百分位均值    ·    左旧右新", 435f, 18f, 703f, 14f, C_DIM, align = Alignment.RIGHT)
    for (tick in listOf(0, 50, 100)) {
        val y = 143f - tick * .83f
        line(95f, y, 1110f, y, C_BORDER)
        text(tick.toString(), 44f, y - 10, 39f, 12f, C_DIM, align = Alignment.RIGHT)
    }
    var previous: Point? = null
    matches.forEachIndexed { i, match ->
        val x = if (matches.size == 1) 602f else 111f + i * 980f / (matches.size - 1)
        val p = match.selectionScore?.let { Point(x, 143f - it.toFloat() * .83f) }
        if (p != null) {
            previous?.let { line(it.x, it.y, p.x, p.y, C_GOLD, 2f) }
            box(p.x - 4, p.y - 4, 8f, 8f, resultColor(match.won), 4f)
        }
        previous = p
        assets.heroes[match.heroId]?.let { image(it, x - 18, 154f, 36f, 21f, 3f) }
        text((i + 1).toString(), x - 20, 181f, 40f, 13f, resultColor(match.won), align = Alignment.CENTER)
    }
}

private fun Dota2ReportCanvas.representativeCard(rep: OverviewRepresentative, player: String, assets: Dota2ReportAssets) {
    val m = rep.match
    box(0f, 0f, 339f, 207f, C_ODD, 6f)
    box(7f, 7f, 124f, 77f, C_HDR, 4f)
    assets.heroes[m.heroId]?.let { image(it, 7f, 7f, 124f, 77f, 4f) }
    fade(7f, 47f, 124f, 37f, Color.TRANSPARENT, hexColor("EE141414"), true)
    text(m.hero, 12f, 57f, 113f, 18f, heroText, true)
    text(rep.label, 142f, 8f, 122f, 17f, C_GOLD, true)
    val tint = resultColor(m.won)
    chip(resultLabel(m.won), 274f, 9f, 55f, tint.withAlpha(.15f), tint)
    playerId(player, 142f, 54f, 187f)
    text("时长  ${matchDuration(m.duration)}", 142f, 64f, 190f, 13f, C_DIM)
    line(10f, 91f, 329f, 91f, C_BORDER)
    text("K / D / A", 10f, 96f, 140f, 12f, C_DIM)
    text(m.kda(), 10f, 111f, 140f, 19f, C_TXT, true)
    text("GPM / XPM", 155f, 96f, 117f, 12f, C_DIM)
    text("${m.number("gold_per_min")} / ${m.number("xp_per_min")}", 155f, 114f, 117f, 15f, C_GOLD, true)
    text("参战率", 269f, 96f, 60f, 12f, C_DIM, align = Alignment.RIGHT)
    text(percent(m.values["kill_participation"]), 260f, 114f, 69f, 15f, C_TXT, true, Alignment.RIGHT)
    line(10f, 139f, 329f, 139f, C_BORDER)
    text("终局装备", 10f, 144f, 100f, 13f, C_TXT2)
    text("三项均值  ${m.selectionScore?.let { "%.1f".format(Locale.ROOT, it) } ?: "—"} / 100",
        128f, 145f, 200f, 12f, C_DIM, align = Alignment.RIGHT)
    repeat(6) { i ->
        val id = m.items.getOrNull(i)
        item(id?.let { assets.items[it] }, id, 10f + i * 54, 165f, 49f, 33f)
    }
}

private fun Dota2ReportCanvas.overviewTable(matches: List<OverviewMatch>, assets: Dota2ReportAssets, height: Float) {
    panel(inset, 0f, pageInner, height)
    val headers = listOf(44f to "序号 / 英雄", 299f to "开赛时间", 489f to "结果 / 时长",
        667f to "K / D / A", 842f to "GPM / XPM", 1040f to "参战率")
    headers.forEach { (x, title) -> text(title, x, 11f, 178f, 14f, C_DIM) }
    matches.forEachIndexed { i, m ->
        val y = tableHeaderHeight + tableRowHeight * i
        if (i % 2 == 0) box(37f, y, 1110f, 39f, C_ODD, 4f)
        text("%02d".format(Locale.ROOT, i + 1), 44f, y + 8, 37f, 14f, C_DIM)
        assets.heroes[m.heroId]?.let { image(it, 82f, y + 4, 54f, 31f, 3f) }
        text(m.hero, 146f, y + 6, 142f, 17f, heroText, true)
        text(matchDate(m.start), 299f, y + 8, 177f, 15f, C_TXT2)
        text("${resultLabel(m.won)}   ${matchDuration(m.duration)}", 489f, y + 8, 163f, 15f, resultColor(m.won))
        text(m.kda(), 667f, y + 6, 160f, 17f, C_TXT, true)
        text("${m.number("gold_per_min")} / ${m.number("xp_per_min")}", 842f, y + 8, 177f, 15f, C_TXT2)
        text(percent(m.values["kill_participation"]), 1040f, y + 8, 99f, 15f, C_TXT2)
    }
}
