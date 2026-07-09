package top.colter.dynamic.agent.draw

import kotlinx.serialization.json.*
import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.layout.*

suspend fun dota2HistoryDraw(accountId: Long, matches: JsonArray, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): Image? {
    Dp.factor = config.factor
    val ff = fontRegistry.textTypeface?.familyName ?: ""
    return View(Modifier().width(780.dp).background(C_BG), fontRegistry = fontRegistry) {
        Column(Modifier().fillMaxWidth()) {
            Row(Modifier().fillMaxWidth().height(40.dp).background(C_HDR), alignment=LayoutAlignment.LEFT) {
                Text(text="  最近${matches.size}场战绩  ID:$accountId", color=C_BLUE, fontSize=18.dp, fontFamily=ff)
            }
            Box(Modifier().fillMaxWidth().height(1.dp).background(C_BORDER))
            Row(Modifier().fillMaxWidth().height(24.dp).background(C_ODD), alignment=LayoutAlignment.LEFT) {
                hCell("#", 26.dp, fontRegistry); hCell("比赛ID", 100.dp, fontRegistry); hCell("英雄", 70.dp, fontRegistry); hCell("K/D/A", 100.dp, fontRegistry)
                hCell("KDA", 48.dp, fontRegistry); hCell("结果", 44.dp, fontRegistry); hCell("时长", 56.dp, fontRegistry); hCell("GPM", 42.dp, fontRegistry); hCell("XPM", 42.dp, fontRegistry)
                hCell("伤害", 48.dp, fontRegistry); hCell("日期", 80.dp, fontRegistry)
            }
            Box(Modifier().fillMaxWidth().height(1.dp).background(C_BORDER))
            matches.forEachIndexed { i, m ->
                val obj = m.jsonObject
                val mid = obj["match_id"]?.jsonPrimitive?.content ?: "?"
                val heroId = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
                val slot = obj["player_slot"]?.jsonPrimitive?.intOrNull ?: 0
                val isRadiant = slot < 50
                val radiantWin = obj["radiant_win"]?.jsonPrimitive?.boolean ?: false
                val playerWon = (isRadiant == radiantWin)
                val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0
                val d = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0
                val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0
                val kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val gpm = obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0
                val xpm = obj["xp_per_min"]?.jsonPrimitive?.intOrNull ?: 0
                val dmg = obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0
                val dur = obj["duration"]?.jsonPrimitive?.intOrNull ?: 0
                val time = obj["start_time"]?.jsonPrimitive?.longOrNull ?: 0

                val bg = if (playerWon) C_GREEN.withAlpha(0.06f) else C_RED.withAlpha(0.06f)
                val accent = if (playerWon) C_GREEN else C_RED
                val durMin = dur / 60
                val durSec = dur % 60
                val durStr = "${durMin}:" + formatTwoDigit(durSec)
                val dd = java.util.Date(time * 1000)
                val sdf = java.text.SimpleDateFormat("MM-dd", java.util.Locale.CHINA)
                val dateStr = sdf.format(dd)

                Row(Modifier().fillMaxWidth().height(34.dp).background(bg), alignment=LayoutAlignment.LEFT) {
                    Box(Modifier().width(26.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        Text(text="${i+1}", color=C_DIM, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(100.dp).height(34.dp), alignment=LayoutAlignment.LEFT) {
                        Text(text="  $mid", color=C_TXT, fontSize=12.dp, fontFamily=ff)
                    }
                    Box(Modifier().width(70.dp).height(34.dp), alignment=LayoutAlignment.LEFT) {
                        Text(text="  Hero_$heroId", color=C_TXT2, fontSize=12.dp, fontFamily=ff)
                    }
                    Box(Modifier().width(100.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        Text(text="$k / $d / $a", color=C_TXT, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(48.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        val kdaC = when { kda>=3.0->C_GREEN; kda>=1.5->C_GOLD; else->C_RED }
                        Text(text=java.lang.String.format("%.1f",kda), color=kdaC, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(44.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        val winText: String = if (playerWon) "WIN" else "LOSE"
                        Text(text=winText, color=accent, fontSize=13.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(56.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        Text(text=durStr, color=C_TXT2, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(42.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        Text(text="$gpm", color=C_GOLD, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(42.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        Text(text="$xpm", color=C_TXT2, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(48.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        Text(text="$dmg", color=C_TXT, fontSize=12.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
                    }
                    Box(Modifier().width(80.dp).height(34.dp), alignment=LayoutAlignment.CENTER) {
                        Text(text="  $dateStr", color=C_DIM, fontSize=12.dp, fontFamily=ff)
                    }
                }
                Box(Modifier().fillMaxWidth().height(1.dp).background(C_BORDER.withAlpha(0.25f)))
            }
        }
    }
}

private fun Layout.hCell(txt: String, w: Dp, fr: FontRegistry = Fonts.default) {
    val ff = fr.textTypeface?.familyName ?: ""
    Box(Modifier().width(w).height(24.dp), alignment=LayoutAlignment.CENTER) {
        Text(text=txt, color=C_DIM, fontSize=11.dp, fontFamily=ff, alignment=LayoutAlignment.CENTER)
    }
}

private fun formatTwoDigit(n: Int) = if (n < 10) "0" + n else n.toString()
