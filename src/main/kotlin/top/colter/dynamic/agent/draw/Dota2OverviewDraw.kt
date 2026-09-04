package top.colter.dynamic.agent.draw

import kotlinx.serialization.json.*
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.dota2.PlayerOverview
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.layout.*

typealias WorstDetail = Pair<JsonObject, JsonObject>

suspend fun dota2OverviewDraw(
    accountId: Long,
    dota2Service: Dota2Service,
    analysisText: String? = null,
    worstDetails: List<WorstDetail> = emptyList(),
    config: ImageConfig,
    fontRegistry: FontRegistry = Fonts.default
): Image? {
    Dp.factor = config.factor
    val ov = dota2Service.getPlayerOverview(accountId) ?: return null
    dota2Service.ensureConstants()
    val heroIcons = mutableMapOf<Int, Image?>()
    val itemIcons = mutableMapOf<Int, Image?>()
    ov.recentMatches.forEach { m ->
        val hid = m.jsonObject["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
        if (hid !in heroIcons) heroIcons[hid] = dota2Service.loadHeroIcon(hid)
    }
    worstDetails.forEach { (sm, dt) ->
        val players = dt["players"]?.jsonArray ?: return@forEach
        val me = players.find {
            it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == accountId
        }?.jsonObject ?: return@forEach
        for (i in 0..5) {
            val iid = me["item_$i"]?.jsonPrimitive?.intOrNull ?: 0
            if (iid > 0 && iid !in itemIcons) itemIcons[iid] = dota2Service.loadItemIcon(iid)
        }
        val nid = me["item_neutral"]?.jsonPrimitive?.intOrNull ?: 0
        if (nid > 0 && nid !in itemIcons) itemIcons[nid] = dota2Service.loadItemIcon(nid)
        for (i in 0..2) {
            val bid = me["backpack_$i"]?.jsonPrimitive?.intOrNull ?: 0
            if (bid > 0 && bid !in itemIcons) itemIcons[bid] = dota2Service.loadItemIcon(bid)
        }
    }

    return View(Modifier().width(1060.dp).background(C_BG), fontRegistry = fontRegistry) {
        Column(Modifier().fillMaxWidth()) {
            overviewMatchTable(ov, heroIcons, dota2Service, fontRegistry)
            if (analysisText != null) {
                sep()
                analysisSection(analysisText, worstDetails, heroIcons, itemIcons, accountId, dota2Service, fontRegistry)
            }
            sep()
            overviewFoot(fontRegistry)
        }
    }
}

fun Layout.overviewMatchTable(ov: PlayerOverview, icons: Map<Int, Image?>, ds: Dota2Service, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Row(Modifier().fillMaxWidth().height(28.dp).background(C_HDR), alignment = LayoutAlignment.LEFT) {
        Text(text = "  ${ov.playerName}  胜 ${ov.totalWins} / 负 ${ov.totalLosses}  胜率 ${fmtOvrPct(ov.winRate)}  近${ov.recentMatches.size}场 ${ov.recentWins}胜${ov.recentLosses}负", color = C_BLUE, fontSize = 14.dp, fontFamily = ff)
    }
    Row(Modifier().fillMaxWidth().height(24.dp).background(C_ODD), alignment = LayoutAlignment.LEFT) {
        hdr("", 22.dp, fr); hdr("英雄", 76.dp, fr); hdr("结果", 42.dp, fr)
        hdr("K/D/A", 68.dp, fr); hdr("KDA", 42.dp, fr)
        hdr("GPM", 40.dp, fr); hdr("XPM", 40.dp, fr); hdr("伤害", 58.dp, fr)
        hdr("时长", 52.dp, fr); hdr("日期", 62.dp, fr); hdr("分路", 40.dp, fr); hdr("段位", 72.dp, fr)
        hdr("比赛ID", 86.dp, fr)
    }
    ov.recentMatches.forEachIndexed { i, m ->
        ovrMatchRow(i + 1, m.jsonObject, icons, ds, fr)
        sep()
    }
}

fun Layout.ovrMatchRow(idx: Int, obj: JsonObject, icons: Map<Int, Image?>, ds: Dota2Service, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val slot = obj["player_slot"]?.jsonPrimitive?.intOrNull ?: 0
    val rw = obj["radiant_win"]?.jsonPrimitive?.boolean ?: false
    val won = (slot < 128) == rw
    val bg = if (won) C_GREEN.withAlpha(0.06f) else C_RED.withAlpha(0.06f)
    val hid = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
    val hn = ds.heroName(hid)
    val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0
    val d = kotlin.math.max(1, obj["deaths"]?.jsonPrimitive?.intOrNull ?: 1)
    val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0
    val kd = (k + a).toDouble() / d

    Row(Modifier().fillMaxWidth().height(34.dp).background(bg), alignment = LayoutAlignment.LEFT) {
        Box(Modifier().width(22.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = "$idx", color = C_DIM, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(38.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            val icon = icons[hid]
            if (icon != null) Image(image = icon, modifier = Modifier().width(32.dp).height(20.dp))
            else Box(Modifier().width(32.dp).height(20.dp).background(C_ODD).border(1.dp, 3.dp, C_BORDER), alignment = LayoutAlignment.CENTER) {
                Text(text = hn.take(2), color = C_DIM, fontSize = 10.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
            }
        }
        Box(Modifier().width(38.dp).height(34.dp), alignment = LayoutAlignment.LEFT) {
            Text(text = hn.take(5), color = C_TXT2, fontSize = 12.dp, fontFamily = ff)
        }
        Box(Modifier().width(42.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = if (won) "胜" else "败", color = if (won) C_GREEN else C_RED, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(68.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = "$k/$d/$a", color = C_TXT, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(42.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = fmtOvrKda(kd), color = kdaC(kd), fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(40.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = obj["gold_per_min"]?.jsonPrimitive?.intOrNull?.toString() ?: "0", color = C_GOLD, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(40.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = obj["xp_per_min"]?.jsonPrimitive?.intOrNull?.toString() ?: "0", color = C_TXT2, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(58.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = fmtK(obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0), color = C_TXT, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(52.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = ovrDur(obj["duration"]?.jsonPrimitive?.intOrNull ?: 0), color = C_TXT2, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(62.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = ovrTs(obj["start_time"]?.jsonPrimitive?.longOrNull ?: 0).take(11), color = C_DIM, fontSize = 11.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        val ln = obj["lane_role"]?.jsonPrimitive?.intOrNull ?: -1
        Box(Modifier().width(40.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = when (ln) { 1 -> "优势"; 2 -> "中路"; 3 -> "劣势"; else -> "-" }, color = C_BLUE, fontSize = 11.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(72.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = ds.rankName(obj["average_rank"]?.jsonPrimitive?.intOrNull ?: 0), color = C_GOLD, fontSize = 11.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
        Box(Modifier().width(86.dp).height(34.dp), alignment = LayoutAlignment.CENTER) {
            Text(text = obj["match_id"]?.jsonPrimitive?.content ?: "?", color = C_DIM, fontSize = 11.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        }
    }
}

fun Layout.heroMatchCard(obj: JsonObject, icons: Map<Int, Image?>, itemIcons: Map<Int, Image?>, detail: JsonObject, accountId: Long, ds: Dota2Service, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val hid = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
    val hn = ds.heroName(hid)
    val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0
    val d = kotlin.math.max(1, obj["deaths"]?.jsonPrimitive?.intOrNull ?: 1)
    val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0
    val gpm = obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0
    val xpm = obj["xp_per_min"]?.jsonPrimitive?.intOrNull ?: 0
    val dmg = obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0
    val lh = obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0
    val s = obj["player_slot"]?.jsonPrimitive?.intOrNull ?: 0
    val rw = obj["radiant_win"]?.jsonPrimitive?.boolean ?: false
    val won = (s < 128) == rw
    val cb = Color.makeRGB(15, 23, 42)
    val cbo = Color.makeRGB(255, 255, 255).withAlpha(0.08f)

    val players = detail["players"]?.jsonArray ?: JsonArray(emptyList())
    val me = players.find { it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == accountId }?.jsonObject
    val mainItems = (0..5).map { me?.get("item_$it")?.jsonPrimitive?.intOrNull ?: 0 }
    val neutralId = me?.get("item_neutral")?.jsonPrimitive?.intOrNull ?: 0

    Column(Modifier().width(255.dp).height(240.dp).background(cb).border(1.dp, 16.dp, cbo)) {
        Row(Modifier().width(223.dp).height(52.dp).margin(16.dp, 8.dp, 16.dp, 0.dp), alignment = LayoutAlignment.LEFT) {
            Box(Modifier().width(40.dp).height(40.dp).margin(0.dp, 0.dp, 8.dp, 0.dp), alignment = LayoutAlignment.CENTER) {
                val icon = icons[hid]
                if (icon != null) Image(image = icon, modifier = Modifier().width(40.dp).height(26.dp))
                else Box(Modifier().width(40.dp).height(26.dp).background(C_ODD).border(1.dp, 4.dp, C_BORDER), alignment = LayoutAlignment.CENTER) {
                    Text(text = hn.take(2), color = C_DIM, fontSize = 12.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
                }
            }
            Column(Modifier().width(175.dp).height(52.dp), alignment = LayoutAlignment.LEFT) {
                Text(text = hn, color = C_TXT, fontSize = 14.dp, fontFamily = ff)
                Text(text = "$k/$d/$a", color = C_TXT2, fontSize = 15.dp, fontFamily = ff)
                Text(text = if (won) "WIN" else "LOSS", color = if (won) C_GREEN else C_RED, fontSize = 11.dp, fontFamily = ff)
            }
        }
        Row(Modifier().width(223.dp).height(28.dp).margin(2.dp, 6.dp, 2.dp, 0.dp), alignment = LayoutAlignment.LEFT) {
            mainItems.take(6).forEach { iid -> itemSlot(itemIcons[iid], 24.dp, fr = fr) }
            itemSlot(itemIcons[neutralId], 24.dp, neutral = true, fr = fr)
        }
        Row(Modifier().width(223.dp).height(28.dp).margin(2.dp, 4.dp, 2.dp, 0.dp), alignment = LayoutAlignment.LEFT) {
            cardStat("GPM", "$gpm", C_GOLD, 108, fr); Box(Modifier().width(7.dp).height(1.dp)); cardStat("XPM", "$xpm", C_TXT2, 108, fr)
        }
        Row(Modifier().width(223.dp).height(28.dp).margin(2.dp, 2.dp, 2.dp, 0.dp), alignment = LayoutAlignment.LEFT) {
            cardStat("伤害", if (dmg >= 1000) "${dmg/1000}k" else "$dmg", C_TXT, 108, fr); Box(Modifier().width(7.dp).height(1.dp)); cardStat("承伤", "-", C_DIM, 108, fr)
        }
        Row(Modifier().width(223.dp).height(28.dp).margin(2.dp, 2.dp, 2.dp, 8.dp), alignment = LayoutAlignment.LEFT) {
            cardStat("补刀", "$lh", C_TXT, 108, fr); Box(Modifier().width(7.dp).height(1.dp)); cardStat("参战", "$k/$a", C_TXT, 108, fr)
        }
    }
}

fun Layout.cardStat(label: String, value: String, color: Int, wDp: Int, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Column(Modifier().width(wDp.dp).height(28.dp).background(C_ODD).border(0.dp, 6.dp), alignment = LayoutAlignment.CENTER) {
        Text(text = label, color = C_DIM, fontSize = 10.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
        Text(text = value, color = color, fontSize = 13.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
    }
}

fun Layout.analysisSection(
    text: String, worstDetails: List<WorstDetail>, icons: Map<Int, Image?>,
    itemIcons: Map<Int, Image?>, accountId: Long, ds: Dota2Service, fr: FontRegistry = Fonts.default
) {
    val ff = fr.textTypeface?.familyName ?: ""
    val sections = text.split(Regex("(?=\\[.*?])")).filter { it.isNotBlank() }
    val rtbFn = ff
    var cardIdx = 0

    val normal = mutableListOf<String>()
    val spotlight = mutableListOf<String>()
    for (block in sections.take(5)) {
        val t = block.trim()
        val te = t.indexOfFirst { it == '\n' }.let { if (it > 0) it else t.length }
        val title = t.substring(0, te).trim()
        if ("高光" in title || "剖析" in title) spotlight.add(t)
        else normal.add(t)
    }

    for (block in normal) {
        if ("诊断" !in block && "总结" !in block) renderAnalysisBlock(block, rtbFn, fr)
    }

    if (spotlight.isNotEmpty() && worstDetails.isNotEmpty()) {
        val highTitle1 = spotlight.getOrNull(0)?.let { t ->
            val te = t.indexOfFirst { it == '\n' }.let { if (it > 0) it else t.length }
            t.substring(0, te).trim()
        } ?: "[对局高光剖析]"
        val highBody1 = spotlight.getOrNull(0)?.let { t ->
            val te = t.indexOfFirst { it == '\n' }.let { if (it > 0) it else t.length }
            t.substring(te).trim()
        } ?: ""
        val highTitle2 = spotlight.getOrNull(1)?.let { t ->
            val te = t.indexOfFirst { it == '\n' }.let { if (it > 0) it else t.length }
            t.substring(0, te).trim()
        } ?: "[对局高光剖析2]"
        val highBody2 = spotlight.getOrNull(1)?.let { t ->
            val te = t.indexOfFirst { it == '\n' }.let { if (it > 0) it else t.length }
            t.substring(te).trim()
        } ?: ""

        Row(Modifier().fillMaxWidth().height(28.dp).background(C_HDR), alignment = LayoutAlignment.LEFT) {
            Text(text = "  对局详情", color = C_RED, fontSize = 14.dp, fontFamily = ff)
        }
        sep()
        Row(Modifier().fillMaxWidth().height(252.dp)) {
            Column(Modifier().width(525.dp).height(252.dp)) {
                Box(Modifier().width(525.dp).height(24.dp), alignment = LayoutAlignment.LEFT) {
                    Text(text = "  $highTitle1", color = C_RED, fontSize = 13.dp, fontFamily = ff)
                }
                val s1 = TextStyle().setColor(C_TXT2).setFontSize(12.px).setFontFamily(rtbFn)
                val r1 = RichParagraphBuilder(s1).apply { addText(highBody1) }
                RichText(paragraph = r1.build(), modifier = Modifier().width(525.dp).margin(2.dp, 2.dp, 0.dp, 0.dp))
                Box(Modifier().width(525.dp).height(24.dp).margin(0.dp, 4.dp, 0.dp, 0.dp), alignment = LayoutAlignment.LEFT) {
                    Text(text = "  $highTitle2", color = C_RED, fontSize = 13.dp, fontFamily = ff)
                }
                val s2 = TextStyle().setColor(C_TXT2).setFontSize(12.px).setFontFamily(rtbFn)
                val r2 = RichParagraphBuilder(s2).apply { addText(highBody2) }
                RichText(paragraph = r2.build(), modifier = Modifier().width(525.dp).margin(2.dp, 2.dp, 0.dp, 0.dp))
            }
            Row(Modifier().width(535.dp).height(252.dp), alignment = LayoutAlignment.LEFT) {
                if (worstDetails.isNotEmpty()) heroMatchCard(worstDetails[0].first, icons, itemIcons, worstDetails[0].second, accountId, ds, fr)
                Box(Modifier().width(8.dp).height(1.dp))
                if (worstDetails.size >= 2) heroMatchCard(worstDetails[1].first, icons, itemIcons, worstDetails[1].second, accountId, ds, fr)
            }
        }
    }

    sections.takeLast(1).forEach { renderAnalysisBlock(it, rtbFn, fr) }
}

fun Layout.renderAnalysisBlock(block: String, rtbFn: String, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    val t = block.trim()
    val te = t.indexOfFirst { it == '\n' }.let { if (it > 0) it else t.length }
    val title = t.substring(0, te).trim()
    val body = t.substring(te).trim()
    val accent = when {
        "侧写" in title || "次要" in title -> C_GREEN
        "高光" in title || "剖析" in title -> C_RED
        "诊断" in title || "总结" in title -> C_GOLD
        else -> C_BLUE
    }
    Row(Modifier().fillMaxWidth().height(28.dp).background(C_HDR), alignment = LayoutAlignment.LEFT) {
        Text(text = "  $title", color = accent, fontSize = 14.dp, fontFamily = ff)
    }
    sep()
    val style = TextStyle().setColor(C_TXT2).setFontSize(13.px).setFontFamily(rtbFn)
    val rpb = RichParagraphBuilder(style).apply { addText(body) }
    RichText(paragraph = rpb.build(), modifier = Modifier().fillMaxWidth().padding(8.dp, 6.dp, 8.dp, 6.dp))
}

fun Layout.overviewFoot(fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Row(Modifier().fillMaxWidth().height(22.dp).background(C_ODD), alignment = LayoutAlignment.CENTER) {
        Text(text = "OpenDota API · 仅供参考", color = C_DIM, fontSize = 11.dp, fontFamily = ff, alignment = LayoutAlignment.CENTER)
    }
}

private fun fmtOvrPct(v: Double) = String.format("%.1f%%", v * 100)
private fun fmtOvrKda(k: Double) = String.format("%.1f", k)
private fun ovrDur(s: Int) = "${s / 60}:${"${s % 60}".padStart(2, '0')}"
private fun ovrTs(ts: Long): String {
    if (ts <= 0) return "?"
    val d = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.CHINA)
    d.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    return d.format(java.util.Date(ts * 1000))
}
private fun kdaC(k: Double): Int = when { k >= 3.0 -> C_GREEN; k >= 1.5 -> C_GOLD; else -> C_RED }
private fun fmtK(n: Int): String = when { n >= 100000 -> "${n / 1000}k"; n >= 10000 -> String.format("%.1f", n / 1000.0) + "k"; else -> "$n" }
