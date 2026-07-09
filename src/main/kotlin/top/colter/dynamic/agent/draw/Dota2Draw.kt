package top.colter.dynamic.agent.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Font
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.Dota2MatchReport
import top.colter.dynamic.agent.dota2.Dota2PlayerCard
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
val C_DIM   = Color.makeRGB(100, 116, 139)
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
    val text = "[$label]"
    val width = 74
    val height = 34
    val surface = Surface.makeRasterN32Premul(width, height)
    val canvas = surface.canvas
    canvas.clear(Color.TRANSPARENT)

    val borderPaint = Paint().apply {
        color = accent
        mode = PaintMode.STROKE
        strokeWidth = 2.2f
    }
    val bgPaint = Paint().apply {
        color = accent.withAlpha(0.10f)
        mode = PaintMode.FILL
    }
    val textPaint = Paint().apply {
        color = accent
    }
    val stampFont = fr.textTypeface?.let { Font(it, 12f) } ?: Font().apply { size = 12f }

    canvas.save()
    canvas.rotate(-10f, width / 2f, height / 2f)
    val r = RRect.makeLTRB(7f, 8f, width - 7f, height - 8f, 4f)
    canvas.drawRRect(r, bgPaint)
    canvas.drawRRect(r, borderPaint)
    val textWidth = stampFont.measureTextWidth(text, textPaint)
    canvas.drawString(text, (width - textWidth) / 2f, 22f, stampFont, textPaint)
    canvas.restore()

    return surface.makeImageSnapshot()
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
        Row(Modifier().width(720.dp).height(44.dp), alignment=LayoutAlignment.LEFT) {
            Text(text="  比赛编号#${r.matchId}", color=C_BLUE, fontSize=20.dp, fontFamily=ff)
            Text(text="  ${r.gameMode}", color=C_TXT2, fontSize=18.dp, fontFamily=ff)
            Text(text="  ${fmtDur(r.duration)}", color=C_TXT2, fontSize=18.dp, fontFamily=ff)
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
    val displayName = when { p.name.isEmpty() || p.name == "?" -> p.heroName; else -> p.name }

    Row(Modifier().fillMaxWidth().height(rowH).background(bg), alignment=LayoutAlignment.LEFT) {
        Box(Modifier().width(22.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if(tag!=null) {
                Box(Modifier().width(20.dp).height(14.dp).background(tb).border(0.dp,3.dp), alignment=LayoutAlignment.CENTER) {
                    Text(text=tag, color=Color.WHITE, fontSize=8.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                }
            } else Text(text="$idx", color=C_DIM, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
        }
        Box(Modifier().width(68.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if (p.heroIcon != null) {
                Image(image=p.heroIcon, modifier=Modifier().width(64.dp).height(36.dp))
            } else {
                Box(Modifier().width(64.dp).height(36.dp).background(C_BORDER.withAlpha(0.2f)).border(1.dp,3.dp,C_DIM), alignment=LayoutAlignment.CENTER) {
                    Text(text=p.heroName.take(3), color=C_DIM.withAlpha(0.35f), fontSize=9.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                }
            }
        }
        Box(Modifier().width(120.dp).height(rowH).margin(0.dp, 0.dp, 0.dp, 2.dp), alignment=LayoutAlignment.LEFT) {
            Column(Modifier().width(118.dp).height(34.dp), alignment=LayoutAlignment.LEFT) {
                Text(text=displayName, color=C_TXT, fontSize=14.dp, fontFamily=ff)
                if (p.rankName.isNotEmpty()) {
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
        Row(modifier = Modifier().width(50.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if (p.aghsScepterIcon != null) {
                Image(image=p.aghsScepterIcon, modifier=Modifier().width(22.dp).height(22.dp))
            } else {
                aghsBox("A", p.hasAghsScepter, C_BLUE, C_AGHS, fr)
            }
            Box(Modifier().width(4.dp).height(1.dp))
            if (p.aghsShardIcon != null) {
                Image(image=p.aghsShardIcon, modifier=Modifier().width(22.dp).height(22.dp))
            } else {
                aghsBox("S", p.hasAghsShard, Color.makeRGB(160,120,230), C_SHARD, fr)
            }
        }
    }
}

fun Layout.aghsBox(label: String, has: Boolean, onColor: Int, bgColor: Int, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(20.dp).height(20.dp).background(if(has) bgColor else C_DIM.withAlpha(0.08f))
        .border(1.dp, 2.dp, if(has) onColor.withAlpha(0.4f) else C_DIM.withAlpha(0.15f)),
        alignment=LayoutAlignment.CENTER) {
        Text(text=label, color=if(has) onColor else C_DIM.withAlpha(0.2f), fontSize=10.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
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

fun Layout.itemSlot(icon: Image? = null, s: Dp = 28.dp, neutral: Boolean = false, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val bc = when { neutral -> C_GOLD.withAlpha(0.3f); s < 28.dp -> C_DIM.withAlpha(0.1f); else -> C_BORDER }
    val pad = (50.dp - s) / 2f
    Box(Modifier().width(s).height(s).background(C_ODD.withAlpha(0.3f))
        .border(1.dp, 4.dp, bc).margin(left=2.dp,right=2.dp,top=pad,bottom=pad),
        alignment=LayoutAlignment.CENTER) {
        if (icon != null) Image(image=icon, modifier=Modifier().width(s-1.dp).height(s-1.dp))
        else Text(text="·", color=C_DIM.withAlpha(0.15f), fontSize=8.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
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
    val tagWidth = when {
        tag.length >= 5 -> 96.dp
        tag.length >= 3 -> 64.dp
        else -> 50.dp
    }
    Column(Modifier().fillMaxWidth().padding(4.dp, 12.dp, 4.dp, 12.dp)) {
        Row(Modifier().fillMaxWidth().margin(0.dp, 0.dp, 3.dp, 0.dp), alignment=LayoutAlignment.LEFT) {
            if (heroIcon != null) {
                Image(image=heroIcon, modifier=Modifier().width(42.dp).height(24.dp).margin(0.dp, 5.dp, 0.dp, 0.dp))
            }
            Box(Modifier().width(tagWidth).height(22.dp).background(accent).border(0.dp,3.dp), alignment=LayoutAlignment.CENTER) {
                Text(text=tag, color=Color.WHITE, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
            }
            Text(text="  $name", color=C_TXT, fontSize=15.dp, fontFamily=ff, modifier=Modifier().maxWidth(360.dp))
            Image(image=stamp, modifier=Modifier().width(74.dp).height(34.dp).margin(0.dp, 0.dp, 0.dp, 6.dp))
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
