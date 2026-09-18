package top.colter.dynamic.agent.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.*
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.*
import top.colter.skiko.FontRegistry
import top.colter.skiko.Fonts
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.*

private val O_BG = Color.makeRGB(11,17,28)
private val O_CARD = Color.makeRGB(19,30,45)
private val O_ACCENT = Color.makeRGB(83,221,208)
private val O_BODY = Color.makeRGB(237,243,252)
private val O_DIM = Color.makeRGB(154,174,197)
private val O_EDGE = Color.makeRGB(40,56,75)
private val O_GOLD = Color.makeRGB(233,193,123)
private val O_RED = Color.makeRGB(241,142,156)

/** Measure first, then paint. No shared Dp state and no network or data reads during drawing. */
private class OverviewCanvas(private val fr: FontRegistry) : AutoCloseable {
    private val paragraphs = mutableListOf<Paragraph>()
    val operations = mutableListOf<(Canvas) -> Unit>()
    fun text(value: String, x: Float, y: Float, width: Float, size: Float = 20f, color: Int = O_BODY, bold: Boolean = false): Float {
        val paragraph = ParagraphStyle().use { ps ->
            ParagraphBuilder(ps, fr.fonts).use { builder ->
                TextStyle().use { requested ->
                    requested.setFontFamily(ff(fr)).setFontSize(size).setColor(color).setFontStyle(if (bold) FontStyle.BOLD else FontStyle.NORMAL)
                    requested.setHeight(if (bold) 1.25f else 1.5f)
                    fr.resolveTextStyle(requested).use { style -> builder.pushStyle(style).addText(value); builder.popStyle() }
                }
                builder.build()
            }
        }
        paragraphs += paragraph; paragraph.layout(width)
        operations += { paragraph.paint(it, x, y) }
        return paragraph.height
    }
    fun card(x: Float, y: Float, w: Float, h: Float) {
        // Insert before texts already measured for this card; background is always painted in a separate pass.
        backgrounds += { c -> Paint().use {
            val shape=RRect.makeXYWH(x,y,w,h,22f)
            it.color = O_CARD; c.drawRRect(shape,it)
            it.color=O_EDGE;it.mode=PaintMode.STROKE;it.strokeWidth=2f;c.drawRRect(shape,it)
        } }
    }
    private val backgrounds = mutableListOf<(Canvas) -> Unit>()
    fun line(x: Float, y: Float, x2: Float, y2: Float, color: Int = O_EDGE, stroke: Float = 1f) {
        operations += { c -> Paint().use { it.color=color; it.strokeWidth=stroke; c.drawLine(x,y,x2,y2,it) } }
    }
    fun icon(image: Image?, x: Float, y: Float, w: Float, h: Float) {
        if (image == null) text("—",x+w/3,y+h/5,w/2,16f,O_DIM)
        else operations += { c -> c.save();try { c.clipRRect(RRect.makeXYWH(x,y,w,h,9f),true);c.drawImageRect(image,Rect.makeXYWH(x,y,w,h)) } finally { c.restore() } }
    }
    fun dot(x:Float,y:Float,color:Int,radius:Float=5f) { operations += { c -> Paint().use { it.color=color;c.drawCircle(x,y,radius,it) } } }
    fun paint(canvas: Canvas) { backgrounds.forEach { it(canvas) }; operations.forEach { it(canvas) } }
    override fun close() = paragraphs.forEach { it.close() }
}

private fun oDate(value: Long?): String = value?.let { DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneId.of("Asia/Shanghai")).format(Instant.ofEpochSecond(it)) } ?: "—"
private fun oNumber(value: Double?) = value?.let { String.format(Locale.ROOT,"%.1f",it) } ?: "—"

internal fun dota2OverviewDraw(report: GeneratedOverview, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): Image {
    val s=report.snapshot;val a=report.analysis;val assets=report.assets;val width=1600f
    fun percent(value:Double?)=value?.let { oNumber(it)+"%" } ?: "—"
    OverviewCanvas(fontRegistry).use { p -> with(p) {
        text("DOTA 2  /  PLAYER REPORT",48f,32f,1000f,20f,O_ACCENT,true)
        card(1270f,30f,282f,38f);text(report.mode.command,1285f,34f,252f,20f,O_ACCENT)
        icon(assets.avatar,48f,83f,108f,108f)
        val nameH=text(s.player,184f,76f,1360f,46f,O_BODY,true)
        val subtitleY=max(142f,76f+nameH+6f)
        text("近期表现诊断  ·  最近 ${s.matches.size} 场",185f,subtitleY,1320f,27f,O_DIM)
        val dateY=max(215f,subtitleY+63)
        val dateH=text("账号 ${s.accountId}   /   ${oDate(s.matches.firstOrNull()?.start)} — ${oDate(s.matches.lastOrNull()?.start)}  北京时间",48f,dateY,1504f,21f,O_DIM)
        var y=max(267f,dateY+dateH+18)
        val stats=listOf(
            Triple("近期战绩","${s.wins} 胜 ${s.losses} 负",if(s.wins+s.losses>0) "胜率 ${s.wins*100/(s.wins+s.losses)}%" else "结果暂无"),
            Triple("场均 K / D / A","${s.average("kills").display()} / ${s.average("deaths").display()} / ${s.average("assists").display()}","击杀 / 死亡 / 助攻"),
            Triple("场均 GPM / XPM","${s.average("gold_per_min").display(0)} / ${s.average("xp_per_min").display(0)}","每分钟金钱 / 经验"),
            Triple("场均击杀参与率",percent(s.average("kill_participation").value),"逐场参与率的算术平均")
        )
        stats.forEachIndexed { i,(label,value,note) -> val x=48f+i*381;card(x,y,361f,141f);text(label,x+22,y+15,317f,21f,O_DIM);text(value,x+22,y+51,317f,32f,if(i==0) O_ACCENT else O_BODY,true);text(note,x+22,y+102,317f,19f,O_DIM) }
        y+=165
        a.notice?.let { notice -> val h=text(notice,73f,y+14,1454f,23f,O_GOLD);card(48f,y,1504f,h+30);y+=h+54 }
        val top=y
        text("01  近期表现总览",76f,y+24,784f,30f,O_BODY,true)
        text("近期状态与表现侧写",76f,y+76,784f,28f,O_ACCENT,true)
        val overviewH=text(a.body("overview"),76f,y+125,784f,24f)+155
        card(48f,y,840f,overviewH)
        val heroY=y+overviewH+20
        val groups=s.matches.groupBy { it.heroId }.values.sortedWith(compareByDescending<List<OverviewMatch>> { it.size }.thenBy { it.first().heroId })
        val heroH=91f+groups.size*57+40
        card(48f,heroY,840f,heroH)
        text("英雄样本分布",76f,heroY+20,300f,26f,O_BODY,true)
        text("场次 / 战绩",400f,heroY+29,220f,20f,O_DIM);text("GPM 均值",635f,heroY+29,220f,20f,O_DIM)
        groups.forEachIndexed { i,group ->
            val yy=heroY+76+i*57
            icon(assets.heroes[group.first().heroId],76f,yy,64f,37f);text(group.first().hero,158f,yy+4,235f,23f)
            text("${group.size} 场 / ${group.count { it.won==true }} 胜 ${group.count { it.won==false }} 负",400f,yy+4,220f,22f,O_DIM)
            val values=group.mapNotNull { it.values["gold_per_min"] }
            text(values.takeIf { it.isNotEmpty() }?.average()?.let { String.format(Locale.ROOT,"%.0f",it) } ?: "—",655f,yy+4,170f,23f,O_ACCENT)
        }
        text("小样本记录，不据此判定英雄熟练度。",76f,heroY+heroH-37,780f,20f,O_DIM)
        card(912f,top,640f,548f);text("同英雄基准雷达",940f,top+24,580f,28f,O_BODY,true)
        text("近期各场百分位的均值  ·  0—100",940f,top+70,580f,21f,O_DIM)
        val cx=1232f;val cy=top+296;val radius=141f
        val angles=(0..4).map { -PI/2+it*2*PI/5 };val bm=overviewMetrics.keys.map { s.average(it,true) }
        for (scale in listOf(.25f,.5f,.75f,1f)) for(i in angles.indices) {
            val j=(i+1)%5;line(cx+cos(angles[i]).toFloat()*radius*scale,cy+sin(angles[i]).toFloat()*radius*scale,cx+cos(angles[j]).toFloat()*radius*scale,cy+sin(angles[j]).toFloat()*radius*scale,stroke=2f)
        }
        angles.forEach { z -> line(cx,cy,cx+cos(z).toFloat()*radius,cy+sin(z).toFloat()*radius) }
        if(bm.all { it.value!=null }) operations += { c ->
            PathBuilder().use { path ->
                angles.forEachIndexed { i,z ->
                    val x=cx+cos(z).toFloat()*radius*bm[i].value!!.toFloat()/100
                    val yy=cy+sin(z).toFloat()*radius*bm[i].value!!.toFloat()/100
                    if(i==0)path.moveTo(x,yy) else path.lineTo(x,yy)
                }
                path.closePath();path.detach().use { shape -> Paint().use { it.color=Color.makeARGB(40,83,221,208);c.drawPath(shape,it) } }
            }
        }
        angles.forEachIndexed { i,z ->
            val tx=cx+cos(z).toFloat()*(radius+63)-65;val ty=cy+sin(z).toFloat()*(radius+42)-17
            text("${overviewMetrics.values.toList()[i]} ${bm[i].display()}",tx,ty,145f,21f,O_BODY)
            val next=(i+1)%5
            if(bm[i].value!=null) {
                val px=cx+cos(z).toFloat()*radius*bm[i].value!!.toFloat()/100;val py=cy+sin(z).toFloat()*radius*bm[i].value!!.toFloat()/100
                dot(px,py,O_ACCENT)
                if(bm[next].value!=null)line(px,py,cx+cos(angles[next]).toFloat()*radius*bm[next].value!!.toFloat()/100,cy+sin(angles[next]).toFloat()*radius*bm[next].value!!.toFloat()/100,O_ACCENT,4f)
            }
        }
        val coverage=bm.map { it.count }.distinct()
        text("有效样本 ${if(coverage.size==1) "${coverage.single()}" else "${coverage.min()}—${coverage.max()}"}/${s.matches.size} 场；非操作或意识评分。",940f,top+488,584f,21f,O_DIM)
        val trend=top+568;card(912f,trend,640f,424f)
        text("逐场表现趋势",940f,trend+22,584f,28f,O_BODY,true)
        text("经济 / 输出 / 推进三项百分位均值",940f,trend+69,584f,21f,O_DIM)
        for(tick in listOf(0,50,100)){val yy=trend+280-tick*1.47f;line(968f,yy,1520f,yy);text("$tick",929f,yy-13,40f,18f,O_DIM)}
        val step=522f/(s.matches.size-1).coerceAtLeast(1)
        s.matches.forEachIndexed { i,m ->
            val x=981f+i*step;val score=m.selectionScore;val previous=s.matches.getOrNull(i-1)?.selectionScore
            if(score!=null){ val yy=trend+280-score.toFloat()*1.47f;dot(x,yy,when(m.won){true->O_ACCENT;false->O_RED;null->O_DIM},6f)
                if(previous!=null)line(x-step,trend+280-previous.toFloat()*1.47f,x,yy,O_ACCENT,3f) }
            icon(assets.heroes[m.heroId],x-23,trend+294,46f,28f);text("${i+1}",x-8,trend+328,40f,18f,when(m.won){true->O_ACCENT;false->O_RED;null->O_DIM})
        }
        text("旧 → 新   ·   青：胜 / 红：负   ·   非 IMP",940f,trend+373,584f,20f,O_DIM)
        y=max(heroY+heroH,top+992)+28
        text("02  多维度深入分析",48f,y,1504f,30f,O_BODY,true);y+=62
        overviewDimensions.entries.chunked(2).forEach { entries ->
            var bottom=y
            entries.forEachIndexed { j,(id,title) ->
                val x=48f+j*764; text(title,x+26,y+22,674f,29f,O_ACCENT,true)
                val metric=when(id){
                    "economy"->"GPM ${s.average("gold_per_min").display(0)} / XPM ${s.average("xp_per_min").display(0)}"
                    "output"->"场均英雄伤害 ${s.average("hero_damage").display(0)} / 建筑伤害 ${s.average("tower_damage").display(0)}"
                    "survival"->"场均死亡 ${s.average("deaths").display()} / 击杀参与率 ${percent(s.average("kill_participation").value)}"
                    else->"${groups.size} 个英雄 / 最近 ${s.matches.size} 场" }
                var yy=y+75;yy+=text(metric,x+26,yy,674f,21f,O_GOLD)+20
                yy+=text(a.body(id),x+26,yy,674f,24f)+30;bottom=max(bottom,yy)
            }
            entries.indices.forEach { card(48f+it*764,y,740f,bottom-y) };y=bottom+22
        }
        text("03  代表对局 · ${s.representatives.size} 场",48f,y,1504f,30f,O_BODY,true);y+=51
        y+=text("按经济、输出、推进三项同英雄百分位均值选取最高与最低样本。",48f,y,1504f,23f,O_DIM)+28
        if(s.representatives.isEmpty()) y+=text("同英雄基准不足，暂不选择代表局；不使用低经济或低伤害替代评分。",48f,y,1504f,24f,O_DIM)+30
        s.representatives.forEachIndexed { i,rep ->
            val m=rep.match;val cardTop=y;val accent=if(i==0) O_ACCENT else O_RED
            icon(assets.heroes[m.heroId],76f,y+25,179f,101f)
            text("${rep.label}  /  ${m.hero}",277f,y+22,1200f,29f,accent,true)
            text("${when(m.won){true->"胜利";false->"战败";null->"结果未知"}}   ·   ${m.duration?.let { "%d:%02d".format(Locale.ROOT,it/60,it%60) } ?: "—"}   ·   ${oDate(m.start)}   ·   比赛 ${m.id}",277f,y+70,1200f,21f,O_DIM)
            line(76f,y+147,1524f,y+147,stroke=2f)
            text(if(s.representatives.size>1 && i==0) "经济、输出与推进的优势样本" else "值得进一步复盘的样本",76f,y+171,891f,27f,accent,true)
            val bh=text(a.body("match_${m.id}"),76f,y+218,891f,24f)
            text("K / D / A   ${m.number("kills")} / ${m.number("deaths")} / ${m.number("assists")}",1028f,y+170,496f,27f,O_BODY,true)
            text("GPM ${m.number("gold_per_min")}   XPM ${m.number("xp_per_min")}   参战 ${percent(m.values["kill_participation"])}",1028f,y+218,496f,22f,O_DIM)
            text("样本选择指标  ${oNumber(m.selectionScore)} / 100",1028f,y+258,496f,22f,accent)
            text("终局六格装备",1028f,y+299,496f,20f,O_DIM)
            m.items.forEachIndexed { j,id ->icon(assets.items[id],1028f+j*81,y+336,72f,53f) }
            val bottom=y+max(435f,248f+bh);line(1000f,y+170,1000f,bottom-25,stroke=2f)
            card(48f,cardTop,1504f,bottom-cardTop);y=bottom+25
        }
        text("04  最近 ${s.matches.size} 场 · 样本明细",48f,y,1504f,30f,O_BODY,true)
        text("与趋势图一致：按时间从旧到新排列",1030f,y+8,510f,21f,O_DIM);y+=61
        val tableTop=y
        listOf(76f to "序号 / 英雄",360f to "时间",570f to "结果 / 时长",786f to "K / D / A",1035f to "GPM / XPM",1320f to "参战率").forEach { (x,t)->text(t,x,y+18,210f,21f,O_DIM) }
        y+=62
        s.matches.forEachIndexed { i,m ->
            if(i%2==0){val yy=y;operations += { c->Paint().use { it.color=Color.makeRGB(25,38,56);c.drawRRect(RRect.makeXYWH(62f,yy-3,1476f,55f,6f),it) } } }
            text("%02d".format(i+1),77f,y+9,50f,21f,O_DIM);icon(assets.heroes[m.heroId],120f,y+7,63f,37f);text(m.hero,198f,y+9,158f,23f)
            text(oDate(m.start),360f,y+9,205f,22f,O_DIM)
            text("${when(m.won){true->"胜利";false->"战败";null->"未知"}}  ${m.duration?.let { "%d:%02d".format(Locale.ROOT,it/60,it%60) } ?: "—"}",570f,y+9,210f,22f,when(m.won){true->O_ACCENT;false->O_RED;null->O_DIM})
            text("${m.number("kills")} / ${m.number("deaths")} / ${m.number("assists")}",786f,y+9,245f,23f)
            text("${m.number("gold_per_min")} / ${m.number("xp_per_min")}",1035f,y+9,280f,23f)
            text(percent(m.values["kill_participation"]),1320f,y+9,200f,23f);y+=58
        }
        card(48f,tableTop,1504f,y-tableTop+24);y+=57
        text("05  综合诊断与行动建议",76f,y+22,1450f,30f,O_BODY,true)
        text("结合数据，对照复盘，再验证改进效果。",76f,y+76,1450f,29f,O_ACCENT,true)
        // Preserve sentence order and wording, spread the conclusion across three balanced reading columns.
        val conclusion=a.body("conclusion")
        val punchline=Regex("\n— [^\n]+$").find(conclusion)?.value.orEmpty()
        val sentences=overviewTextSegments(conclusion.removeSuffix(punchline))
        val columnCount=sentences.size.coerceIn(1,3)
        val groupsText=MutableList(columnCount) { "" };val total=sentences.sumOf { it.length };var column=0
        sentences.forEachIndexed { i,t->if(column<columnCount-1 && groupsText[column].isNotEmpty() && (groupsText[column].length>=total/columnCount || sentences.size-i==columnCount-1-column))column++;groupsText[column]+=t }
        var end=y+250
        val columnWidth=1488f/columnCount
        groupsText.forEachIndexed { i,t ->val x=76f+i*columnWidth;text("0${i+1}  行动建议",x,y+144,columnWidth-50,25f,O_GOLD,true);end=max(end,y+190+text(t,x,y+190,columnWidth-50,24f)+35) }
        if(punchline.isNotEmpty()) end+=text(punchline.trim(),76f,end,1448f,24f,O_GOLD)+28
        card(48f,y,1504f,end-y);y=end+29
        text("数据与阅读说明",48f,y,1504f,23f,O_BODY,true);y+=44
        y+=text("基础详情 ${s.details}/${s.matches.size} 场；未知胜负 ${s.matches.size-s.wins-s.losses} 场。场均值仅使用有效数据，缺失显示 —。同英雄基准不是同段位，趋势的三项均值不是综合实力评分。",48f,y,1504f,21f,O_DIM)+13
        y+=text("未使用录像、分路、购买记录或经济时间线。AI 解释仅供复盘参考；相同数据与布局用于普通 / 深度个人详情。",48f,y,1504f,21f,O_DIM)
        line(48f,y+25,1552f,y+25);text("DYNAMIC BOT   /   个人详情报告",48f,y+45,1000f,20f,O_DIM);text("OpenDota",1380f,y+45,172f,20f,O_DIM);y+=100
        val requested=config.factor.takeIf { it.isFinite() && it>0 } ?: 1f
        val scale=min(requested.coerceAtMost(2f),sqrt(16_000_000f/(width*y)))
        return Surface.makeRasterN32Premul(ceil(width*scale).toInt(),ceil(y*scale).toInt()).use { surface ->surface.canvas.clear(O_BG);surface.canvas.scale(scale,scale);paint(surface.canvas);surface.makeImageSnapshot() }
    } }
}
