package top.colter.dynamic.agent.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.ANONYMOUS_PLAYER_NAME
import top.colter.dynamic.agent.dota2.Dota2MatchReport
import top.colter.dynamic.agent.dota2.Dota2PlayerCard
import top.colter.dynamic.agent.dota2.isAnonymousDota2PlayerName
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.layout.*

val C_BG    = Color.makeRGB( 15,  23,  42)
val C_ODD   = Color.makeRGB( 30,  41,  59)
val C_EVEN  = Color.makeRGB( 21,  30,  48)
val C_HDR   = Color.makeRGB( 30,  58,  95)
val C_TXT   = Color.makeRGB(226, 232, 240)
val C_TXT2  = Color.makeRGB(148, 163, 184)
// 次要信息仍需在深色背景上保持足够对比度，避免小字号发灰、发虚。
val C_DIM   = Color.makeRGB(125, 141, 163)
val C_GREEN = Color.makeRGB( 34, 197,  94)
val C_RED   = Color.makeRGB(239,  68,  68)
val C_GOLD  = Color.makeRGB(245, 158,  11)
val C_BLUE  = Color.makeRGB( 59, 130, 246)
val C_BORDER= Color.makeRGB( 51,  65,  85)
val C_NAME  = Color.makeRGB(232, 205, 126)
val C_ASSIST= Color.makeRGB( 96, 165, 250)
val C_AGHS  = Color.makeRGB(100, 180, 255).withAlpha(0.25f)
val C_SHARD = Color.makeRGB(180, 140, 240).withAlpha(0.25f)

fun ff(fr: FontRegistry = Fonts.default): String = fr.textTypeface?.familyName ?: ""

private fun fmtDur(s: Int) = "${s/60}分${s%60}秒"
private fun fmtTs(ts: Long): String {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    d.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    return d.format(java.util.Date(ts * 1000))
}
private fun fmtNumber(n: Int): String = java.lang.String.format(java.util.Locale.ROOT, "%,d", n)
private fun fmtMetric(n: Int): String = if (n >= 1000) {
    java.lang.String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0).replace(".0k", "k")
} else n.toString()
private fun fmtTeamNetWorth(n: Int): String = if (n >= 1000) {
    java.lang.String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0)
} else n.toString()
private fun sideLabel(label: String) = if (label == "RADIANT") "天辉" else "夜魇"
private fun resultLabel(won: Boolean) = if (won) "胜利" else "战败"
private fun stampLabel(tag: String) = when {
    "战犯" in tag -> "战犯"
    "SVP" in tag -> "SVP"
    "MVP" in tag -> "MVP"
    else -> tag
}

private fun makeStampImage(label: String, accent: Int, fr: FontRegistry = Fonts.default): Image {
    val width = 86
    val height = 36
    return Surface.makeRasterN32Premul(width, height).use { surface ->
        Paint().use { borderPaint ->
            borderPaint.color = accent
            borderPaint.mode = PaintMode.STROKE
            borderPaint.strokeWidth = 2f
            borderPaint.isAntiAlias = true

            Paint().use { bgPaint ->
                bgPaint.color = accent.withAlpha(0.08f)
                bgPaint.mode = PaintMode.FILL
                bgPaint.isAntiAlias = true

                // 印章文字也走布局库使用的 Paragraph/FontCollection，确保自定义字体、中文和
                // fallback 字体都能生效。Canvas.drawString 只绑定单个 Typeface，在宿主字体
                // 注册表下会出现边框存在、文字却没有 glyph 的情况。
                val family = fr.textTypeface?.familyName ?: ""
                TextStyle().use { requestedStyle ->
                    requestedStyle.setColor(accent).setFontSize(14f).setFontFamily(family)
                    fr.resolveTextStyle(requestedStyle).use { stampStyle ->
                        ParagraphStyle().use { paragraphStyle ->
                            paragraphStyle.alignment = Alignment.CENTER
                            paragraphStyle.maxLinesCount = 1
                            ParagraphBuilder(paragraphStyle, fr.fonts).use { builder ->
                                builder.pushStyle(stampStyle).addText(label).build().use { paragraph ->
                                    paragraph.layout(width - 16f)
                                    val canvas = surface.canvas
                                    canvas.clear(Color.TRANSPARENT)
                                    canvas.save()
                                    try {
                                        canvas.rotate(-8f, width / 2f, height / 2f)
                                        // 为旋转后的四角预留安全区，避免印章边框和文字被图片边界裁掉。
                                        val r = RRect.makeLTRB(6f, 7f, width - 6f, height - 7f, 4f)
                                        canvas.drawRRect(r, bgPaint)
                                        canvas.drawRRect(r, borderPaint)
                                        paragraph.paint(canvas, 8f, ((height - paragraph.height) / 2f).coerceAtLeast(0f))
                                    } finally {
                                        canvas.restore()
                                    }
                                    surface.makeImageSnapshot()
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

suspend fun dota2MatchDraw(report: Dota2MatchReport, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): Image? {
    Dp.factor = config.factor
    return View(Modifier().width(1060.dp).background(C_BG), fontRegistry = fontRegistry) {
        Column(Modifier().fillMaxWidth()) {
            topBar(report, fontRegistry); sep()
            teamTable("RADIANT", report.radiantScore, report.radiantWin, report.players.filter{it.isRadiant}, fontRegistry); sep()
            teamTable("DIRE", report.direScore, !report.radiantWin, report.players.filter{!it.isRadiant}, fontRegistry); sep()
            analysisBlock(report, fontRegistry); sep()
            foot(report, fontRegistry)
        }
    }
}

fun Layout.sep() = Box(Modifier().fillMaxWidth().height(1.dp).background(C_BORDER))

fun Layout.topBar(r: Dota2MatchReport, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Row(Modifier().fillMaxWidth().height(44.dp).background(C_HDR), alignment=LayoutAlignment.LEFT) {
        topBarText("比赛编号#${r.matchId}", C_BLUE, 292.dp, 20.dp, true, 12.dp, fr)
        topBarText(r.gameMode, C_TXT2, 230.dp, 17.dp, false, 14.dp, fr)
        topBarText(fmtDur(r.duration), C_TXT2, 198.dp, 17.dp, false, 14.dp, fr)
        Box(Modifier().width(340.dp).height(44.dp), alignment=LayoutAlignment.RIGHT) {
            Text(text="${fmtTs(r.startTime)}  ", color=C_DIM, fontSize=16.dp, fontFamily=ff, alignment=LayoutAlignment.RIGHT)
        }
    }
}

private fun Layout.topBarText(
    text: String,
    color: Int,
    width: Dp,
    size: Dp,
    bold: Boolean,
    left: Dp,
    fr: FontRegistry,
) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(width).height(44.dp), alignment=LayoutAlignment.LEFT) {
        Text(
            text=text,
            color=color,
            fontSize=size,
            fontFamily=ff,
            fontStyle=if (bold) FontStyle.BOLD else FontStyle.NORMAL,
            // Skia 段落的字体上沿留白偏小，向下做视觉补偿后才与右侧时间同轴。
            modifier=Modifier().margin(left=left).offset(y=7.dp)
        )
    }
}

fun Layout.teamTable(label: String, score: Int, won: Boolean, players: List<Dota2PlayerCard>, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val sideAccent = if (label == "RADIANT") C_GREEN else C_RED
    val totalNetWorth = players.fold(0) { total, player -> total + player.netWorth }
    Row(Modifier().fillMaxWidth().height(44.dp).background(sideAccent.withAlpha(0.09f)), alignment=LayoutAlignment.LEFT) {
        Box(Modifier().width(250.dp).height(44.dp), alignment=LayoutAlignment.LEFT) {
            Text(
                text="${sideLabel(label)}  ${resultLabel(won)}",
                color=sideAccent,
                fontSize=20.dp,
                fontFamily=ff,
                fontStyle=FontStyle.BOLD,
                modifier=Modifier().margin(left=14.dp).offset(y=5.dp)
            )
        }
        teamSummary(Dota2UiIcons.kills, "击杀", score.toString(), C_RED, 150.dp, fr)
        teamSummary(Dota2UiIcons.economy, "团队经济", fmtTeamNetWorth(totalNetWorth), C_GOLD, 210.dp, fr)
    }
    players.forEachIndexed{i,p->
        val b = when{ p.isMvp-> C_GREEN.withAlpha(0.07f); p.isSvp-> C_GOLD.withAlpha(0.07f)
            p.isCriminal->C_RED.withAlpha(0.07f); i%2==0->C_EVEN; else->C_BG }
        playerRow(i+1,p,b,fr)
        sep()
    }
}

fun Layout.playerRow(idx: Int, p: Dota2PlayerCard, bg: Int, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val tag = when{ p.isMvp->"MVP"; p.isSvp->"SVP"; p.isCriminal->"CW"; else->null }
    val tb  = when{ p.isMvp->C_GREEN; p.isSvp->C_GOLD; p.isCriminal->C_RED; else->C_BG }
    val sideAccent = if (p.isRadiant) C_GREEN else C_RED
    val rowH = 72.dp
    val anonymous = isAnonymousDota2PlayerName(p.name)
    val primaryLabel = if (anonymous) p.heroName else p.name
    val identityMeta = buildList {
        add(if (anonymous) ANONYMOUS_PLAYER_NAME else p.heroName)
        add("Lv${p.level}")
        if (!anonymous && p.rankName.isNotBlank()) add(p.rankName)
    }.joinToString(" · ")

    Row(Modifier().fillMaxWidth().height(rowH).background(bg), alignment=LayoutAlignment.LEFT) {
        Box(Modifier().width(4.dp).height(rowH).background(sideAccent.withAlpha(0.75f)))
        Box(Modifier().width(28.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if(tag!=null) {
                statusBadge(tag, tb, 24.dp, 18.dp, 8.dp, fr)
            } else Text(text="$idx", color=C_DIM, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
        }
        Box(Modifier().width(82.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if (p.heroIcon != null) {
                Image(image=p.heroIcon, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(76.dp))
            } else {
                Box(Modifier().width(76.dp).height(43.dp).background(C_BORDER.withAlpha(0.2f)).border(1.dp,5.dp,C_DIM), alignment=LayoutAlignment.CENTER) {
                    Text(text=p.heroName.take(3), color=C_DIM.withAlpha(0.35f), fontSize=9.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                }
            }
        }
        Box(Modifier().width(210.dp).height(rowH), alignment=LayoutAlignment.LEFT) {
            // 头像槽位和身份信息之间保留独立内边距，不改变后续数据列的起始坐标。
            Column(Modifier().width(194.dp).height(58.dp).margin(left=10.dp), alignment=LayoutAlignment.LEFT) {
                Text(text=primaryLabel, color=C_NAME, fontSize=15.dp, fontFamily=ff, fontStyle=FontStyle.BOLD, modifier=Modifier().maxWidth(194.dp))
                Text(text=identityMeta, color=C_DIM, fontSize=10.dp, fontFamily=ff, modifier=Modifier().maxWidth(194.dp))
                kdaLine(p.kills, p.deaths, p.assists, fr)
            }
        }
        matchStatPair("GPM", p.gpm.toString(), Dota2UiIcons.economy, fmtNumber(p.netWorth), C_TXT, C_NAME, fr)
        matchStatPair("XPM", p.xpm.toString(), Dota2UiIcons.damage, fmtMetric(p.heroDamage), C_TXT, C_TXT, fr)
        matchStatPair("补刀", "${p.lastHits}/${p.denies}", Dota2UiIcons.tower, fmtMetric(p.towerDamage), C_TXT, if (p.towerDamage > 0) C_TXT else C_DIM, fr)
        Column(modifier = Modifier().width(50.dp).height(52.dp), alignment=LayoutAlignment.CENTER) {
            aghsIcon(p.aghsScepterIcon, "A", p.hasAghsScepter, 22.dp, C_BLUE, C_AGHS, fr)
            Box(Modifier().width(1.dp).height(2.dp), alignment=LayoutAlignment.CENTER)
            aghsIcon(p.aghsShardIcon, "S", p.hasAghsShard, 18.dp, Color.makeRGB(160,120,230), C_SHARD, fr)
        }
        mainItemGrid(p.items.take(6), fr)
        backpackColumn(p.backpackItems, fr)
        neutralItem(p.items.getOrNull(6), fr)
    }
}

fun Layout.teamSummary(icon: Image?, label: String, value: String, valueColor: Int, width: Dp, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Row(Modifier().width(width).height(44.dp), alignment=LayoutAlignment.LEFT) {
        metricIcon(icon, 26.dp, 16.dp)
        Box(Modifier().width(if (label.length > 2) 66.dp else 38.dp).height(44.dp), alignment=LayoutAlignment.LEFT) {
            Text(text=label, color=C_TXT2, fontSize=12.dp, fontFamily=ff, modifier=Modifier().offset(y=5.dp))
        }
        Box(Modifier().fillWidth().height(44.dp), alignment=LayoutAlignment.LEFT) {
            Text(text=value, color=valueColor, fontSize=19.dp, fontFamily=ff, fontStyle=FontStyle.BOLD, modifier=Modifier().offset(y=5.dp))
        }
    }
}

private fun Layout.kdaLine(kills: Int, deaths: Int, assists: Int, fr: FontRegistry) {
    val ff = fr.textTypeface?.familyName ?: ""
    fun style(color: Int) = TextStyle()
        .setColor(color)
        .setFontSize(19.px)
        .setFontFamily(ff)
        .setFontStyle(FontStyle.BOLD)
    val killStyle = style(C_GREEN)
    val deathStyle = style(C_RED)
    val assistStyle = style(C_ASSIST)
    val slashStyle = style(C_TXT2)
    val paragraph = RichParagraphBuilder(killStyle).apply {
        addText(kills.toString(), killStyle)
        addText("  /  ", slashStyle)
        addText(deaths.toString(), deathStyle)
        addText("  /  ", slashStyle)
        addText(assists.toString(), assistStyle)
    }
    Box(Modifier().width(194.dp).height(23.dp), alignment=LayoutAlignment.LEFT) {
        RichText(paragraph=paragraph.build(), modifier=Modifier().width(194.dp))
    }
}

fun Layout.matchStatPair(
    topLabel: String,
    topValue: String,
    bottomIcon: Image?,
    bottomValue: String,
    topColor: Int,
    bottomColor: Int,
    fr: FontRegistry = Fonts.default,
) {
    val ff = fr.textTypeface?.familyName ?: ""
    Column(Modifier().width(140.dp).height(54.dp), alignment=LayoutAlignment.CENTER) {
        // 标签和数值位于同一个 Paragraph，共享文字基线，避免不同字号分别居中造成上下漂移。
        Box(Modifier().width(132.dp).height(27.dp), alignment=LayoutAlignment.LEFT) {
            metricTopLine(topLabel, topValue, topColor, fr)
        }
        Row(Modifier().width(132.dp).height(27.dp), alignment=LayoutAlignment.LEFT) {
            metricIcon(bottomIcon, 28.dp, 16.dp)
            Box(Modifier().width(104.dp).height(27.dp), alignment=LayoutAlignment.LEFT) {
                Text(text=bottomValue, color=bottomColor, fontSize=15.dp, fontFamily=ff, fontStyle=FontStyle.BOLD)
            }
        }
    }
}

private fun Layout.metricTopLine(label: String, value: String, valueColor: Int, fr: FontRegistry) {
    val ff = fr.textTypeface?.familyName ?: ""
    val labelStyle = TextStyle()
        .setColor(C_TXT2)
        .setFontSize(11.px)
        .setFontFamily(ff)
        .setFontStyle(FontStyle.BOLD)
    val valueStyle = TextStyle()
        .setColor(valueColor)
        .setFontSize(15.px)
        .setFontFamily(ff)
        .setFontStyle(FontStyle.BOLD)
    val paragraph = RichParagraphBuilder(labelStyle).apply {
        addText(label, labelStyle)
        addText("   $value", valueStyle)
    }
    RichText(paragraph=paragraph.build(), modifier=Modifier().width(132.dp))
}

private fun Layout.metricIcon(icon: Image?, slotWidth: Dp, iconSize: Dp) {
    Box(Modifier().width(slotWidth).height(25.dp), alignment=LayoutAlignment.CENTER) {
        if (icon != null) {
            Image(image=icon, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(iconSize).height(iconSize))
        }
    }
}

fun Layout.mainItemGrid(items: List<Image?>, fr: FontRegistry = Fonts.default) {
    Column(Modifier().width(132.dp).height(56.dp), alignment=LayoutAlignment.CENTER) {
        Row(Modifier().width(126.dp).height(27.dp), alignment=LayoutAlignment.LEFT) {
            repeat(3) { compactItemSlot(items.getOrNull(it), 40.dp, 25.dp, fr) }
        }
        Row(Modifier().width(126.dp).height(27.dp), alignment=LayoutAlignment.LEFT) {
            repeat(3) { compactItemSlot(items.getOrNull(it + 3), 40.dp, 25.dp, fr) }
        }
    }
}

fun Layout.backpackColumn(items: List<Image?>, fr: FontRegistry = Fonts.default) {
    Column(Modifier().width(42.dp).height(58.dp), alignment=LayoutAlignment.CENTER) {
        repeat(3) { compactItemSlot(items.getOrNull(it), 30.dp, 17.dp, fr, subdued=true) }
    }
}

fun Layout.neutralItem(icon: Image?, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(54.dp).height(72.dp), alignment=LayoutAlignment.CENTER) {
        if (icon != null) {
            // 中立物品素材通常是横向矩形，先取中心正方形再裁成圆形，避免矩形图标压在圆框中。
            Canvas(Modifier().width(40.dp).height(40.dp), alignment=LayoutAlignment.CENTER) { bounds ->
                val skiaCanvas = this
                val outerRadius = minOf(bounds.width, bounds.height) / 2f
                val innerRadius = outerRadius - 2f
                val cx = bounds.left + bounds.width / 2f
                val cy = bounds.top + bounds.height / 2f
                val srcSize = minOf(icon.width, icon.height).toFloat()
                val src = Rect.makeLTRB(
                    (icon.width - srcSize) / 2f,
                    (icon.height - srcSize) / 2f,
                    (icon.width + srcSize) / 2f,
                    (icon.height + srcSize) / 2f,
                )
                val dst = Rect.makeLTRB(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

                Paint().use { paint ->
                    paint.isAntiAlias = true
                    paint.color = C_ODD
                    skiaCanvas.drawCircle(cx, cy, outerRadius, paint)

                    val saveCount = skiaCanvas.save()
                    skiaCanvas.clipRRect(RRect.makeLTRB(dst.left, dst.top, dst.right, dst.bottom, innerRadius), true)
                    paint.color = Color.WHITE
                    skiaCanvas.drawImageRect(icon, src, dst, paint, true)
                    skiaCanvas.restoreToCount(saveCount)

                    paint.mode = PaintMode.STROKE
                    paint.strokeWidth = 1.5f
                    paint.color = C_GOLD.withAlpha(0.55f)
                    skiaCanvas.drawCircle(cx, cy, outerRadius - 1f, paint)
                }
            }
        } else {
            Box(Modifier().width(40.dp).height(40.dp).background(C_ODD.withAlpha(0.45f))
                .border(1.dp, 20.dp, C_GOLD.withAlpha(0.35f)), alignment=LayoutAlignment.CENTER) {
                Text(text="·", color=C_DIM.withAlpha(0.35f), fontSize=10.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
            }
        }
    }
}

fun Layout.compactItemSlot(
    icon: Image?,
    width: Dp,
    height: Dp,
    fr: FontRegistry = Fonts.default,
    subdued: Boolean = false,
) {
    val ff = fr.textTypeface?.familyName ?: ""
    val border = if (subdued) C_DIM.withAlpha(0.16f) else C_BORDER
    Box(Modifier().width(width).height(height).margin(left=1.dp, right=1.dp)
        .background(C_ODD.withAlpha(if (subdued) 0.22f else 0.45f))
        .border(1.dp, 4.dp, border), alignment=LayoutAlignment.CENTER) {
        if (icon != null) {
            Image(image=icon, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(width - 3.dp))
        } else {
            Text(text="·", color=C_DIM.withAlpha(0.22f), fontSize=8.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
        }
    }
}

fun Layout.aghsIcon(icon: Image?, label: String, has: Boolean, size: Dp, onColor: Int, bgColor: Int, fr: FontRegistry = Fonts.default) {
    if (icon != null) {
        Image(image=icon, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(size))
    } else {
        aghsBox(label, has, size, onColor, bgColor, fr)
    }
}

// A杖/魔晶文字方块兜底。尺寸与真实图标一致，缺图时也不改变列内占位。
fun Layout.aghsBox(label: String, has: Boolean, size: Dp, onColor: Int, bgColor: Int, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(size).height(size).background(if(has) bgColor else C_DIM.withAlpha(0.10f))
        .border(1.dp, 3.dp, if(has) onColor.withAlpha(0.55f) else C_DIM.withAlpha(0.28f)),
        alignment=LayoutAlignment.CENTER) {
        Text(text=label, color=if(has) onColor else C_DIM.withAlpha(0.55f), fontSize=9.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
    }
}

fun Layout.hdr(txt: String, w: Dp, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(w).height(24.dp), alignment=LayoutAlignment.CENTER) {
        Text(text=txt, color=C_DIM, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
    }
}

// 横向物品图保持原始宽高比；外层仍严格占用 s+4dp，避免挤压后续列。
fun Layout.itemSlot(icon: Image? = null, s: Dp = 28.dp, neutral: Boolean = false, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val bc = when { neutral -> C_GOLD.withAlpha(0.3f); s < 28.dp -> C_DIM.withAlpha(0.1f); else -> C_BORDER }
    val slotH = if (s < 28.dp) 18.dp else 21.dp
    Box(Modifier().width(s).height(50.dp).margin(left=2.dp,right=2.dp), alignment=LayoutAlignment.CENTER) {
        Box(Modifier().width(s).height(slotH).background(C_ODD.withAlpha(0.3f))
            .border(1.dp, 4.dp, bc), alignment=LayoutAlignment.CENTER) {
            if (icon != null) {
                // 只限定宽度，由 Image 按素材原始比例计算高度，避免装备图被裁成方形。
                Image(image=icon, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(s-1.dp))
            } else {
                Text(text="·", color=C_DIM.withAlpha(0.24f), fontSize=8.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
            }
        }
    }
}

fun Layout.statusBadge(label: String, accent: Int, width: Dp, height: Dp, fontSize: Dp, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(
        Modifier().width(width).height(height)
            .background(accent.withAlpha(0.16f))
            .border(1.dp, 5.dp, accent.withAlpha(0.78f)),
        alignment=LayoutAlignment.CENTER
    ) {
        Text(text=label, color=Color.WHITE, fontSize=fontSize, fontFamily=ff, alignment=LayoutAlignment.CENTER)
    }
}

fun Layout.analysisBlock(r: Dota2MatchReport, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Row(Modifier().fillMaxWidth().height(28.dp).background(C_HDR), alignment=LayoutAlignment.LEFT) {
        Text(text="  分析详情", color=C_BLUE, fontSize=16.dp, fontFamily=ff)
    }
    if (r.mvpReason != "N/A") {
        val icon = r.players.find { it.isMvp }?.heroIcon
        aLine(C_GREEN, "MVP", r.mvpName, r.mvpReason, icon, fr)
    }
    if (r.svpReason != "N/A") {
        val icon = r.players.find { it.isSvp }?.heroIcon
        aLine(C_GOLD, "SVP", r.svpName, r.svpReason, icon, fr)
    }
    val criminalIcon = r.players.find { it.isCriminal }?.heroIcon
    aLine(C_RED, "战犯", r.criminalName, r.criminalReason, criminalIcon, fr)
}

fun Layout.aLine(accent: Int, tag: String, name: String, reason: String, heroIcon: Image? = null, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val stamp = makeStampImage(stampLabel(tag), accent, fr)
    Column(Modifier().fillMaxWidth().padding(4.dp, 12.dp, 4.dp, 12.dp)) {
        Row(Modifier().fillMaxWidth().height(36.dp).margin(bottom=3.dp), alignment=LayoutAlignment.LEFT) {
            // 固定每一段的占位。不同长度的标签和名称不再推动右侧印章，且所有元素
            // 都由 36dp 高的 Box 沿同一条水平中心线摆放。
            Box(Modifier().width(47.dp).height(36.dp), alignment=LayoutAlignment.CENTER) {
                if (heroIcon != null) {
                    Image(image=heroIcon, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(42.dp))
                } else {
                    Box(Modifier().width(42.dp).height(24.dp).background(C_BORDER.withAlpha(0.22f))
                        .border(1.dp, 3.dp, C_DIM.withAlpha(0.28f)), alignment=LayoutAlignment.CENTER)
                }
            }
            Box(Modifier().width(96.dp).height(36.dp), alignment=LayoutAlignment.CENTER) {
                statusBadge(tag, accent, 88.dp, 24.dp, 12.dp, fr)
            }
            Box(Modifier().width(360.dp).height(36.dp), alignment=LayoutAlignment.LEFT) {
                Text(
                    text=name,
                    color=C_TXT,
                    fontSize=15.dp,
                    fontFamily=ff,
                    alignment=LayoutAlignment.LEFT,
                    modifier=Modifier().margin(left=8.dp).maxWidth(344.dp)
                )
            }
            Box(Modifier().width(92.dp).height(36.dp), alignment=LayoutAlignment.CENTER) {
                Image(image=stamp, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(86.dp).height(36.dp))
            }
        }
        val style = TextStyle().setColor(C_TXT2).setFontSize(13.px).setFontFamily(ff)
        val paragraph = RichParagraphBuilder(style)
        paragraph.addText(reason)
        RichText(paragraph=paragraph.build(), modifier=Modifier().fillMaxWidth())
    }
}

fun Layout.foot(r: Dota2MatchReport, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Row(Modifier().fillMaxWidth().height(26.dp).background(C_ODD), alignment=LayoutAlignment.CENTER) {
        Text(text="OpenDota · ${fmtTs(r.startTime)} · #${r.matchId} · 仅供参考", color=C_DIM, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
    }
}

suspend fun dota2FullAnalyzeDraw(report: Dota2MatchReport, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): Image? {
    Dp.factor = config.factor
    return View(Modifier().width(1060.dp).background(C_BG), fontRegistry = fontRegistry) {
        Column(Modifier().fillMaxWidth()) {
            topBar(report, fontRegistry); sep()
            teamTable("RADIANT", report.radiantScore, report.radiantWin, report.players.filter{it.isRadiant}, fontRegistry); sep()
            teamTable("DIRE", report.direScore, !report.radiantWin, report.players.filter{!it.isRadiant}, fontRegistry); sep()
            fullAnalysisBlock(report, fontRegistry); sep()
            foot(report, fontRegistry)
        }
    }
}

fun Layout.fullAnalysisBlock(r: Dota2MatchReport, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Row(Modifier().fillMaxWidth().height(28.dp).background(C_HDR), alignment=LayoutAlignment.LEFT) {
        Text(text="  分析详情", color=C_BLUE, fontSize=16.dp, fontFamily=ff)
    }
    if (r.radiantMvpReason.isNotBlank() && r.radiantMvpReason != "N/A" && r.radiantMvpReason != "分析失败") {
        val icon = r.players.find { it.isRadiant && it.heroName in r.radiantMvp }?.heroIcon
        val tag = if (r.radiantWin) "天辉MVP" else "天辉SVP"
        aLine(if (r.radiantWin) C_GREEN else C_GOLD, tag, r.radiantMvp, r.radiantMvpReason, icon, fr)
    }
    if (r.radiantCriminalReason.isNotBlank() && r.radiantCriminalReason != "分析失败") {
        val icon = r.players.find { it.isRadiant && it.heroName in r.radiantCriminal }?.heroIcon
        aLine(C_RED, "天辉战犯", r.radiantCriminal, r.radiantCriminalReason, icon, fr)
    }
    if (r.direMvpReason.isNotBlank() && r.direMvpReason != "N/A" && r.direMvpReason != "分析失败") {
        val icon = r.players.find { !it.isRadiant && it.heroName in r.direMvp }?.heroIcon
        val tag = if (r.radiantWin) "夜魇SVP" else "夜魇MVP"
        aLine(if (r.radiantWin) C_GOLD else C_GREEN, tag, r.direMvp, r.direMvpReason, icon, fr)
    }
    if (r.direCriminalReason.isNotBlank() && r.direCriminalReason != "分析失败") {
        val icon = r.players.find { !it.isRadiant && it.heroName in r.direCriminal }?.heroIcon
        aLine(C_RED, "夜魇战犯", r.direCriminal, r.direCriminalReason, icon, fr)
    }
}
