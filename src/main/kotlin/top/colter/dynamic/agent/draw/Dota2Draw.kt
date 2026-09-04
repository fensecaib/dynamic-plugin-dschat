package top.colter.dynamic.agent.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
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
val C_AGHS  = Color.makeRGB(100, 180, 255).withAlpha(0.25f)
val C_SHARD = Color.makeRGB(180, 140, 240).withAlpha(0.25f)

fun ff(fr: FontRegistry = Fonts.default): String = fr.textTypeface?.familyName ?: ""

private fun fmtDur(s: Int) = "${s/60}分${s%60}秒"
private fun fmtTs(ts: Long): String {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    d.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    return d.format(java.util.Date(ts * 1000))
}
private fun kdaC(k: Double) = when { k>=3.0->C_GREEN; k>=1.5->C_GOLD; else->C_RED }
private fun fmtK(n: Int): String = when { n>=100000->"${n/1000}k"; n>=10000-> java.lang.String.format("%.1f",n/1000.0)+"k"; else->n.toString() }
private fun fmtKda(k: Double): String = java.lang.String.format("%.1f", k)
private fun rankColor(tier: Int): Int = when(tier/10) {
    8 -> Color.makeRGB(230, 180, 80); 7 -> Color.makeRGB(200, 120, 60)
    6 -> Color.makeRGB(180, 120, 210); else -> C_TXT2
}
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
        // 先形成一行共享高度的元信息，再将整行放到顶栏左侧的垂直中心。
        Box(Modifier().width(720.dp).height(44.dp).padding(top=8.dp), alignment=LayoutAlignment.LEFT) {
            Row(Modifier().width(720.dp), alignment=LayoutAlignment.LEFT) {
                Text(text="比赛编号#${r.matchId}", color=C_BLUE, fontSize=20.dp, fontFamily=ff, modifier=Modifier().margin(left=12.dp))
                Text(text=r.gameMode, color=C_TXT2, fontSize=18.dp, fontFamily=ff, modifier=Modifier().margin(left=14.dp))
                Text(text=fmtDur(r.duration), color=C_TXT2, fontSize=18.dp, fontFamily=ff, modifier=Modifier().margin(left=14.dp))
            }
        }
        Box(Modifier().width(340.dp).height(44.dp), alignment=LayoutAlignment.RIGHT) {
            Text(text="${fmtTs(r.startTime)}  ", color=C_DIM, fontSize=16.dp, fontFamily=ff, alignment=LayoutAlignment.RIGHT)
        }
    }
}

fun Layout.teamTable(label: String, score: Int, won: Boolean, players: List<Dota2PlayerCard>, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val accent = if(won) C_GREEN else C_RED
    Row(Modifier().fillMaxWidth().height(34.dp).background(accent.withAlpha(0.10f)), alignment=LayoutAlignment.LEFT) {
        Text(text="  ${sideLabel(label)}  击杀数$score  ${resultLabel(won)}", color=accent, fontSize=22.dp, fontFamily=ff)
    }
    Row(Modifier().fillMaxWidth().height(24.dp).background(C_ODD), alignment=LayoutAlignment.LEFT) {
        hdr("",22.dp,fr); hdr("英雄",68.dp,fr); hdr("玩家",120.dp,fr); hdr("Lv",26.dp,fr); hdr("K",26.dp,fr); hdr("D",26.dp,fr); hdr("A",26.dp,fr)
        hdr("KDA",42.dp,fr); hdr("补刀",60.dp,fr); hdr("财产",50.dp,fr); hdr("GPM",38.dp,fr); hdr("XPM",38.dp,fr)
        hdr("伤害",50.dp,fr); hdr("治疗",34.dp,fr); itemHdr(320.dp,fr); hdr("A杖", 50.dp,fr)
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
    val rowH = 50.dp
    val anonymous = isAnonymousDota2PlayerName(p.name)
    val primaryLabel = if (anonymous) p.heroName else p.name

    Row(Modifier().fillMaxWidth().height(rowH).background(bg), alignment=LayoutAlignment.LEFT) {
        Box(Modifier().width(22.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if(tag!=null) {
                statusBadge(tag, tb, 20.dp, 16.dp, 8.dp, fr)
            } else Text(text="$idx", color=C_DIM, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
        }
        Box(Modifier().width(68.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if (p.heroIcon != null) {
                Image(image=p.heroIcon, alignment=LayoutAlignment.CENTER, modifier=Modifier().width(64.dp))
            } else {
                Box(Modifier().width(64.dp).height(36.dp).background(C_BORDER.withAlpha(0.2f)).border(1.dp,3.dp,C_DIM), alignment=LayoutAlignment.CENTER) {
                    Text(text=p.heroName.take(3), color=C_DIM.withAlpha(0.35f), fontSize=9.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                }
            }
        }
        Box(Modifier().width(120.dp).height(rowH).margin(0.dp, 0.dp, 0.dp, 2.dp), alignment=LayoutAlignment.LEFT) {
            Column(Modifier().width(118.dp).height(34.dp), alignment=LayoutAlignment.LEFT) {
                Text(text=primaryLabel, color=C_TXT, fontSize=14.dp, fontFamily=ff)
                if (anonymous) {
                    Text(text=ANONYMOUS_PLAYER_NAME, color=C_DIM, fontSize=11.dp, fontFamily=ff)
                } else if (p.rankName.isNotEmpty()) {
                    Text(text=p.rankName, color=rankColor(p.rankTier), fontSize=11.dp, fontFamily=ff)
                } else {
                    Text(text=p.heroName, color=C_DIM, fontSize=11.dp, fontFamily=ff)
                }
            }
        }
        cel(p.level.toString(),    26.dp, C_TXT, fr)
        cel(p.kills.toString(),    26.dp, C_GREEN, fr)
        cel(p.deaths.toString(),   26.dp, C_RED, fr)
        cel(p.assists.toString(),  26.dp, C_TXT, fr)
        cel(fmtKda(p.kda),         42.dp, kdaC(p.kda), fr)
        Box(Modifier().width(60.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            Text(text="${p.lastHits}/${p.denies}", color=C_TXT, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
        }
        cel(fmtK(p.netWorth),      50.dp, C_TXT2, fr)
        cel(p.gpm.toString(),      38.dp, C_GOLD, fr)
        cel(p.xpm.toString(),      38.dp, C_TXT2, fr)
        cel(fmtK(p.heroDamage),    50.dp, C_TXT, fr)
        cel(if(p.heroHealing>0) fmtK(p.heroHealing) else "-", 34.dp, if(p.heroHealing>0) C_GREEN else C_DIM, fr)
        Row(modifier = Modifier().width(320.dp).height(rowH), alignment=LayoutAlignment.LEFT) {
            Box(Modifier().margin(left=1.dp).width(1.dp).height(1.dp))
            p.items.take(6).forEach { img -> itemSlot(img, 28.dp, fr=fr) }
            repeat(6 - p.items.take(6).size) { itemSlot(null, 28.dp, fr=fr) }
            itemSlot(p.items.getOrNull(6), 28.dp, neutral=true, fr=fr)
            Box(Modifier().margin(left=3.dp, right=3.dp).width(1.dp).height(20.dp).background(C_DIM.withAlpha(0.2f)))
            p.backpackItems.take(3).forEach { img -> itemSlot(img, 24.dp, fr=fr) }
            repeat(3 - p.backpackItems.take(3).size) { itemSlot(null, 24.dp, fr=fr) }
        }
        // 神杖/魔晶共享原来的 50dp 列宽，改为上下堆叠；魔晶略小并与神杖同轴居中。
        Column(modifier = Modifier().width(50.dp).height(42.dp), alignment=LayoutAlignment.CENTER) {
            aghsIcon(p.aghsScepterIcon, "A", p.hasAghsScepter, 22.dp, C_BLUE, C_AGHS, fr)
            Box(Modifier().width(1.dp).height(2.dp), alignment=LayoutAlignment.CENTER)
            aghsIcon(p.aghsShardIcon, "S", p.hasAghsShard, 18.dp, Color.makeRGB(160,120,230), C_SHARD, fr)
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

fun Layout.cel(txt: String, w: Dp, c: Int, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(w).height(50.dp), alignment=LayoutAlignment.CENTER) {
        Text(text=txt, color=c, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
    }
}

fun Layout.hdr(txt: String, w: Dp, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(w).height(24.dp), alignment=LayoutAlignment.CENTER) {
        Text(text=txt, color=C_DIM, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
    }
}

fun Layout.itemHdr(w: Dp, fr: FontRegistry = Fonts.default) {
    Row(Modifier().width(w).height(24.dp), alignment=LayoutAlignment.LEFT) {
        hdr("物品", 192.dp, fr)
        hdr("中立", 34.dp, fr)
        Box(Modifier().width(8.dp).height(24.dp))
        hdr("背包", 86.dp, fr)
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
