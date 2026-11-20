package top.colter.dynamic.agent.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.Paragraph
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.*
import top.colter.skiko.FontRegistry
import top.colter.skiko.Fonts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

// 中性黑灰作为底色，阵营色、经济色和数据色只标识相应内容。
val C_BG = hexColor("181818")
val C_ODD = hexColor("292929")
val C_HDR = hexColor("242424")
val C_BORDER = hexColor("444444")
val C_TXT = hexColor("F5F5F5")
val C_TXT2 = hexColor("D1D1D1")
val C_DIM = hexColor("A8A8A8")
val C_GREEN = hexColor("8ED7AE")
val C_RED = hexColor("FF9B88")
val C_GOLD = hexColor("F2CC80")
val C_NAME = C_TXT
val C_ASSIST = hexColor("A5CFF0")
private val ink = hexColor("161616")
private val slot = hexColor("202020")
private val goldInk = hexColor("392E1C")
internal const val pageWidth = 1184f
internal const val inset = 28f
internal const val pageInner = pageWidth - inset * 2
private const val cardWidth = 216f
private const val cardHeight = 394f
private const val gap = 12f
private const val headerHeight = 152f
private const val teamHeaderHeight = 60f
private const val reviewCardWidth = 330f
private const val reviewCardHeight = 84f
private const val reviewTextX = reviewCardWidth + 36f
internal val heroText = hexColor("FFF1CC")
private val idBadge = hexColor("2E4151")
private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd  HH:mm", Locale.ROOT)
    .withZone(ZoneId.of("Asia/Shanghai"))

fun ff(fr: FontRegistry = Fonts.default): String = fr.textTypeface?.familyName ?: ""

private fun number(value: Long) = String.format(Locale.ROOT, "%,d", value)
private fun metric(value: Int) = if (value < 1000) value.toString()
    else String.format(Locale.ROOT, "%.1fk", value / 1000.0).replace(".0k", "k")

suspend fun dota2MatchDraw(report: Dota2MatchReport, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): Image? =
    renderMatch(report, config, fontRegistry, full = false)

suspend fun dota2FullAnalyzeDraw(report: Dota2MatchReport, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): Image? =
    renderMatch(report, config, fontRegistry, full = true)

private data class Review(val tag: String, val name: String, val reason: String, val color: Int, val player: Dota2PlayerCard?)

private data class ReviewRow(val review: Review, val fallbackName: Paragraph?, val body: Paragraph) {
    val height = maxOf(reviewCardHeight, body.height, (fallbackName?.height ?: 0f) + 26f) + 24f
}

private fun reviews(r: Dota2MatchReport, full: Boolean): List<Review> {
    fun award(side: String, won: Boolean, name: String, reason: String): Review {
        val player = r.players.singleOrNull { it.isRadiant == (r.radiantWin == won) && (if (won) it.isMvp else it.isSvp) }
        return Review(side + if (won) "MVP" else "SVP", name, reason, if (won) C_GOLD else C_ASSIST, player)
    }
    fun criminal(side: String, radiant: Boolean?, name: String, reason: String) =
        Review(side + "战犯", name, reason, C_RED, r.players.singleOrNull { it.isCriminal && (radiant == null || it.isRadiant == radiant) })
    val entries = if (full) listOf(
        award("天辉 ", r.radiantWin, r.radiantMvp, r.radiantMvpReason),
        criminal("天辉 ", true, r.radiantCriminal, r.radiantCriminalReason),
        award("夜魇 ", !r.radiantWin, r.direMvp, r.direMvpReason),
        criminal("夜魇 ", false, r.direCriminal, r.direCriminalReason),
    ) else listOf(
        award("", true, r.mvpName, r.mvpReason), award("", false, r.svpName, r.svpReason),
        criminal("", null, r.criminalName, r.criminalReason),
    )
    return entries.filter { it.reason.isNotBlank() && it.reason != "N/A" && !(full && it.reason == "分析失败") }
}

private fun renderMatch(r: Dota2MatchReport, config: ImageConfig, fonts: FontRegistry, full: Boolean): Image {
    val (radiant, dire) = r.players.partition { it.isRadiant }
    val rows = listOf(radiant.chunked(5), dire.chunked(5))
    val reviewTop = headerHeight + 2 * teamHeaderHeight + rows.sumOf { it.size } * (cardHeight + gap)
    val paragraphs = mutableListOf<Paragraph>()
    fun measure(value: String, width: Float, bold: Boolean) =
        reportParagraph(fonts, value, width, 16f, if (bold) C_TXT else C_TXT2, bold, singleLine = false)
            .also { paragraphs.add(it) }
    try {
        val entries = reviews(r, full).map {
            val fallback = if (it.player == null) measure(it.name, reviewCardWidth - 28f, true) else null
            ReviewRow(it, fallback, measure(it.reason, pageInner - reviewTextX - 16f, false))
        }
        val footerTop = reviewTop + 56f + if (entries.isEmpty()) 52f else entries.sumOf { it.height.toDouble() }.toFloat()
        val scale = config.factor.takeIf { it.isFinite() && it > 0f } ?: 1f
        return renderReportPage(footerTop + 62f, scale, fonts) {
            header(r, full)
            var y = headerHeight
            rows.forEachIndexed { side, groups ->
                val isRadiant = side == 0
                val team = if (isRadiant) radiant else dire
                at(inset, y) { teamHeading(isRadiant, team.sumOf { it.netWorth.toLong() }) }
                y += teamHeaderHeight
                for (group in groups) {
                    group.forEachIndexed { i, player -> at(inset + i * (cardWidth + gap), y) { playerCard(player) } }
                    y += cardHeight + gap
                }
            }
            at(inset, reviewTop) { analysisHeading() }
            y = reviewTop + 56f
            if (entries.isEmpty()) text("暂无赛后分析", inset + 12f, y + 13f, pageInner, 16f, C_DIM)
            entries.forEach { row ->
                at(inset, y) { reviewLine(row) }
                y += row.height
            }
            at(inset, footerTop) { footer() }
        }
    } finally {
        paragraphs.forEach { it.close() }
    }
}

internal fun Dota2ReportCanvas.titleBar(title: String) {
    fade(0f, 0f, pageWidth, 96f, hexColor("353535"), ink)
    // 同向斜线把题签、比分和页脚的图形语言连起来。
    for (i in 0..3) line(594f + 20f * i, 94f, 658f + 20f * i, 0f, hexColor("303030"), 6f)
    at(inset, 24f) { battleMark(50f) }
    text("D O T A   2", 93f, 14f, 240f, 15f, C_GOLD, true)
    text(title, 91f, 34f, 510f, 38f, C_TXT, true)
}

private fun Dota2ReportCanvas.header(r: Dota2MatchReport, full: Boolean) {
    titleBar(if (full) "全场战报" else "比赛战报")
    text("天辉", 728f, 22f, 76f, 18f, C_GREEN, true, Alignment.RIGHT)
    text(if (r.radiantWin) "胜利" else "战败", 728f, 52f, 76f, 14f, C_TXT2, align = Alignment.RIGHT)
    text(r.radiantScore.toString(), 810f, 13f, 108f, 46f, C_TXT, true, Alignment.RIGHT)
    text(":", 923f, 18f, 34f, 37f, C_DIM, align = Alignment.CENTER)
    text(r.direScore.toString(), 964f, 13f, 108f, 46f, C_TXT, true)
    text("夜魇", 1083f, 22f, 72f, 18f, C_RED, true)
    text(if (!r.radiantWin) "胜利" else "战败", 1083f, 52f, 72f, 14f, C_TXT2)
    box(0f, 96f, pageWidth, 56f, C_HDR)
    line(inset, 96f, pageWidth - inset, 96f, C_BORDER)
    val clock = String.format(Locale.ROOT, "%02d:%02d", r.duration / 60, r.duration % 60)
    at(inset, 108f) { metadata("比赛编号", "#${r.matchId}", 238f) }
    at(308f, 108f) { metadata("比赛模式", r.gameMode.ifBlank { "—" }, 203f) }
    at(554f, 108f) { metadata("对局时长", clock, 155f) }
    at(752f, 108f) { metadata("开赛时间 · 北京时间", dateFormat.format(Instant.ofEpochSecond(r.startTime)), 392f) }
    for (x in listOf(285f, 531f, 729f)) line(x, 109f, x, 141f, C_BORDER)
}

private fun Dota2ReportCanvas.metadata(label: String, value: String, width: Float) {
    box(0f, 4f, 3f, 8f, C_GOLD, 1f)
    text(label, 9f, 0f, width - 9f, 11f, C_DIM)
    text(value, 0f, 15f, width, 18f, C_TXT, true)
}

// 以交叉剑形作为战报题签；缩小后同样可用作页脚印记。
private fun Dota2ReportCanvas.battleMark(size: Float) {
    val save = canvas.save()
    try {
        canvas.scale(size / 50f, size / 50f)
        box(0f, 0f, 50f, 50f, goldInk, 9f)
        for (direction in listOf(1f, -1f)) {
            val transform = canvas.save()
            canvas.translate(25f, 25f)
            canvas.scale(direction, 1f)
            line(-12f, 13f, 12f, -13f, C_GOLD, 3.5f)
            line(-13f, 5f, -5f, 13f, C_GOLD, 2.5f)
            line(5f, -9f, 12f, -13f, C_GOLD, 2f)
            canvas.restoreToCount(transform)
        }
    } finally {
        canvas.restoreToCount(save)
    }
}

private fun Dota2ReportCanvas.teamHeading(radiant: Boolean, worth: Long) {
    val accent = if (radiant) C_GREEN else C_RED
    box(0f, 20f, 4f, 24f, accent, 2f)
    text(if (radiant) "天辉" else "夜魇", 15f, 13f, 68f, 24f, C_TXT, true)
    text(if (radiant) "R A D I A N T" else "D I R E", 84f, 25f, 120f, 12f, accent, true)
    line(210f, 33f, pageInner - 224f, 33f, C_BORDER)
    text("团队经济", pageInner - 213f, 22f, 77f, 15f, C_DIM, align = Alignment.RIGHT)
    text(number(worth), pageInner - 128f, 16f, 128f, 21f, C_GOLD, true, Alignment.RIGHT)
}

private fun Dota2ReportCanvas.playerCard(p: Dota2PlayerCard) {
    box(0f, 0f, cardWidth, cardHeight, C_BORDER, 9f)
    box(1f, 1f, cardWidth - 2, cardHeight - 2, C_ODD, 8f)
    val save = canvas.save()
    try {
        canvas.clipRRect(RRect.makeXYWH(1f, 1f, cardWidth - 2, cardHeight - 2, 8f), true)
        if (p.heroIcon != null) image(p.heroIcon, 1f, 1f, cardWidth - 2, 100f)
        else box(1f, 1f, cardWidth - 2, 100f, C_HDR)
        fade(1f, 32f, cardWidth - 2, 70f, Color.TRANSPARENT, hexColor("ED111111"), true)
    } finally {
        canvas.restoreToCount(save)
    }
    text(p.heroName, 10f, 69f, 194f, 22f, heroText, true)
    chip("Lv ${p.level}", 9f, 9f, 47f, hexColor("D9202020"), Color.WHITE)
    when {
        p.isMvp -> chip("MVP", 155f, 9f, 51f, goldInk, C_GOLD)
        p.isSvp -> chip("SVP", 155f, 9f, 51f, hexColor("233547"), C_ASSIST)
        p.isCriminal -> chip("战犯", 155f, 9f, 51f, hexColor("482D29"), C_RED)
    }
    val anonymous = isAnonymousDota2PlayerName(p.name)
    val rank = if (anonymous) "" else p.rankName
    val identityBaseline = 122f
    playerId(normalizeDota2PlayerName(p.name), 10f, identityBaseline, if (rank.isBlank()) 196f else 121f)
    if (rank.isNotBlank()) textOnBaseline(rank, 133f, identityBaseline, 72f, 12f, C_DIM, align = Alignment.RIGHT)
    line(10f, 133f, 206f, 133f, C_BORDER)
    text("K / D / A", 10f, 140f, 110f, 11f, C_DIM, true)
    text("经济", 135f, 139f, 70f, 12f, C_DIM, align = Alignment.RIGHT)
    text("${p.kills} / ${p.deaths} / ${p.assists}", 10f, 155f, 119f, 19f, C_TXT, true)
    text(number(p.netWorth.toLong()), 129f, 155f, 76f, 19f, C_GOLD, true, Alignment.RIGHT)

    stat("GPM", p.gpm.toString(), 10f, 188f)
    stat("XPM", p.xpm.toString(), 114f, 188f)
    stat("英雄伤害", metric(p.heroDamage), 10f, 209f)
    stat("建筑伤害", metric(p.towerDamage), 114f, 209f)
    stat("正补", p.lastHits.toString(), 10f, 230f)
    stat("反补", p.denies.toString(), 114f, 230f)
    at(0f, 256f) { inventory(p) }
}

private fun Dota2ReportCanvas.inventory(p: Dota2PlayerCard) {
    line(10f, 0f, 206f, 0f, C_BORDER)
    text("主装备", 10f, 4f, 140f, 12f, C_TXT2)
    text("背包", 171f, 4f, 35f, 12f, C_TXT2, align = Alignment.CENTER)
    repeat(6) { i ->
        item(p.items.getOrNull(i), p.itemIds.getOrNull(i), 10f + (i % 3) * 53f, 23f + (i / 3) * 37f, 50f, 35f)
    }
    repeat(3) { i ->
        item(p.backpackItems.getOrNull(i), p.backpackIds.getOrNull(i), 173f, 23f + i * 25f, 31f, 22f)
    }
    line(10f, 103f, 206f, 103f, C_BORDER)
    text("中立", 10f, 112f, 32f, 12f, C_TXT2)
    item(p.items.getOrNull(6), p.itemIds.getOrNull(6), 41f, 107f, 25f, 25f, round = true)
    upgrade("神杖", p.hasAghsScepter, p.aghsScepterIcon, 87f, C_ASSIST)
    upgrade("魔晶", p.hasAghsShard, p.aghsShardIcon, 152f, hexColor("D4B8EF"))
}

internal fun Dota2ReportCanvas.playerId(name: String, x: Float, baseline: Float, width: Float) {
    textOnBaseline("ID", x, baseline, 22f, 11f, C_ASSIST, true, Alignment.CENTER, idBadge)
    textOnBaseline(name, x + 27f, baseline, width - 27f, 15f, C_ASSIST)
}

internal fun Dota2ReportCanvas.chip(label: String, x: Float, y: Float, w: Float, fill: Int, color: Int) {
    box(x, y, w, 21f, fill, 4f)
    text(label, x, y + 2f, w, 12f, color, true, Alignment.CENTER)
}

private fun Dota2ReportCanvas.stat(label: String, value: String, x: Float, y: Float) {
    text(label, x, y + 2f, 54f, 12f, C_DIM)
    text(value, x + 45f, y, 47f, 14f, C_TXT, true, Alignment.RIGHT)
}

internal fun Dota2ReportCanvas.item(icon: Image?, id: Int?, x: Float, y: Float, w: Float, h: Float, round: Boolean = false) {
    val radius = if (round) w / 2 else 3f
    box(x, y, w, h, if (round) hexColor("84704B") else C_BORDER, radius)
    box(x + 1, y + 1, w - 2, h - 2, slot, radius)
    if (icon != null) image(icon, x + 1, y + 1, w - 2, h - 2, radius)
    else if (id != null && id > 0) {
        val size = (w * 1.4f / (id.toString().length + 1)).coerceAtMost(12f)
        text("#$id", x, y + (h - size * 1.4f) / 2, w, size, C_GOLD, align = Alignment.CENTER)
    }
}

private fun Dota2ReportCanvas.upgrade(label: String, owned: Boolean, icon: Image?, x: Float, accent: Int) {
    val color = if (owned) accent else C_DIM
    if (icon != null) {
        val size = 19f
        val scale = size / maxOf(icon.width, icon.height)
        val w = icon.width * scale
        val h = icon.height * scale
        image(icon, x + (size - w) / 2, 120f - h / 2, w, h)
    } else {
        box(x + 4f, 116f, 9f, 9f, color, 5f)
        if (!owned) box(x + 5.5f, 117.5f, 6f, 6f, C_ODD, 3f)
    }
    text(label, x + 22f, 112f, 34f, 12f, color)
}

private fun Dota2ReportCanvas.analysisHeading() {
    box(0f, 18f, 4f, 23f, C_GOLD, 2f)
    text("赛后分析", 15f, 9f, 136f, 25f, C_TXT, true)
    line(148f, 30f, pageInner, 30f, C_BORDER)
}

private fun Dota2ReportCanvas.reviewLine(row: ReviewRow) {
    val h = row.height - 6f
    box(0f, 0f, pageInner, h, C_HDR, 7f)
    val review = row.review
    val player = review.player
    val fallback = row.fallbackName
    if (player != null) {
        at(9f, (h - reviewCardHeight) / 2) { reviewPlayerCard(review, player) }
    } else if (fallback != null) {
        val y = (h - fallback.height - 22f) / 2
        text(review.tag, 18f, y, reviewCardWidth - 28f, 14f, review.color, true)
        fallback.paint(canvas, 18f, y + 22f)
    }
    line(reviewTextX - 14f, 14f, reviewTextX - 14f, h - 14f, C_BORDER)
    row.body.paint(canvas, reviewTextX, (h - row.body.height) / 2)
}

private fun Dota2ReportCanvas.reviewPlayerCard(review: Review, p: Dota2PlayerCard) {
    box(0f, 0f, reviewCardWidth, reviewCardHeight, C_ODD, 6f)
    box(6f, 6f, 96f, 72f, slot, 4f)
    p.heroIcon?.let { image(it, 6f, 6f, 96f, 72f, 4f) }
    box(6f, 55f, 96f, 23f, ink, 3f)
    text(review.tag, 6f, 57f, 96f, 14f, review.color, true, Alignment.CENTER)
    text(p.heroName, 114f, 5f, 206f, 20f, heroText, true)
    playerId(normalizeDota2PlayerName(p.name), 114f, 47f, 206f)
    textOnBaseline("K/D/A", 114f, 72f, 36f, 11f, C_DIM)
    textOnBaseline("${p.kills}/${p.deaths}/${p.assists}", 153f, 72f, 84f, 16f, C_TXT, true)
    textOnBaseline("经济", 237f, 72f, 26f, 11f, C_DIM)
    textOnBaseline(number(p.netWorth.toLong()), 264f, 72f, 57f, 14f, C_GOLD, true, Alignment.RIGHT)
}

internal fun Dota2ReportCanvas.footer() {
    line(0f, 13f, pageInner, 13f, C_BORDER)
    line(0f, 13f, 46f, 13f, C_GOLD, 2f)
    at(0f, 24f) { battleMark(25f) }
    text("DOTA 2", 36f, 28f, 92f, 14f, C_TXT, true)
    text("数据来源  /  OpenDota", 134f, 28f, 380f, 14f, C_DIM)
    text("来源dynamic-bot与dynamic-plugin-dschat", pageInner - 480f, 28f, 480f, 14f, C_DIM, align = Alignment.RIGHT)
}
