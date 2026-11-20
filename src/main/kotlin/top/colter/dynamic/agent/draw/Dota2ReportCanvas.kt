package top.colter.dynamic.agent.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.Paragraph
import org.jetbrains.skia.paragraph.ParagraphBuilder
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.skiko.FontRegistry
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

private val shortDate = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.ROOT).withZone(ZoneId.of("Asia/Shanghai"))
internal fun matchDate(start: Long?) = start?.let { shortDate.format(Instant.ofEpochSecond(it)) } ?: "—"
internal fun matchDuration(seconds: Int?) = seconds?.let { "%d:%02d".format(Locale.ROOT, it / 60, it % 60) } ?: "—"
internal fun resultColor(won: Boolean?) = when (won) { true -> C_GREEN; false -> C_RED; null -> C_DIM }
internal fun resultLabel(won: Boolean?) = when (won) { true -> "胜利"; false -> "战败"; null -> "未知" }

internal fun renderReportPage(height: Float, scale: Float, fonts: FontRegistry, draw: Dota2ReportCanvas.() -> Unit): Image =
    Surface.makeRasterN32Premul(ceil(pageWidth * scale).toInt(), ceil(height * scale).toInt()).use { surface ->
        surface.canvas.clear(C_BG)
        surface.canvas.scale(scale, scale)
        Paint().use { paint ->
            paint.isAntiAlias = true
            Dota2ReportCanvas(surface.canvas, fonts, paint).draw()
        }
        surface.makeImageSnapshot()
    }

internal fun reportParagraph(
    fonts: FontRegistry, value: String, width: Float, size: Float, color: Int,
    bold: Boolean = false, alignment: Alignment = Alignment.LEFT, singleLine: Boolean = true,
): Paragraph = ParagraphStyle().use { style ->
    style.alignment = alignment
    if (singleLine) {
        style.maxLinesCount = 1
        style.ellipsis = "…"
    }
    ParagraphBuilder(style, fonts.fonts).use { builder ->
        TextStyle().use { requested ->
            requested.setFontFamily(ff(fonts)).setFontSize(size).setColor(color)
                .setFontStyle(if (bold) FontStyle.BOLD else FontStyle.NORMAL)
            fonts.resolveTextStyle(requested).use { builder.pushStyle(it).addText(value) }
        }
        builder.build().also { it.layout(width) }
    }
}

internal class Dota2ReportCanvas(val canvas: Canvas, private val fonts: FontRegistry, private val shapePaint: Paint) {
    fun panel(x: Float, y: Float, w: Float, h: Float) {
        box(x, y, w, h, C_BORDER, 8f)
        box(x + 1, y + 1, w - 2, h - 2, C_HDR, 7f)
    }

    fun section(title: String, y: Float, note: String = "") {
        box(inset, y + 6, 4f, 24f, C_GOLD, 2f)
        text(title, inset + 15, y, 235f, 24f, C_TXT, true)
        line(258f, y + 19, if (note.isEmpty()) pageWidth - inset else 744f, y + 19, C_BORDER)
        if (note.isNotEmpty()) text(note, 753f, y + 8, 403f, 14f, C_DIM, align = Alignment.RIGHT)
    }

    fun text(value: String, x: Float, y: Float, w: Float, size: Float, color: Int = C_TXT,
             bold: Boolean = false, align: Alignment = Alignment.LEFT) {
        reportParagraph(fonts, value, w, size, color, bold, align).use { it.paint(canvas, x, y) }
    }

    fun textOnBaseline(value: String, x: Float, baseline: Float, w: Float, size: Float, color: Int,
                       bold: Boolean = false, align: Alignment = Alignment.LEFT, badge: Int? = null) {
        reportParagraph(fonts, value, w, size, color, bold, align).use { paragraph ->
            val y = baseline - paragraph.alphabeticBaseline
            if (badge != null) box(x, y - 1f, w, paragraph.height + 2f, badge, 4f)
            paragraph.paint(canvas, x, y)
        }
    }

    fun box(x: Float, y: Float, w: Float, h: Float, color: Int, radius: Float = 0f) {
        shapePaint.color = color
        canvas.drawRRect(RRect.makeXYWH(x, y, w, h, radius), shapePaint)
    }

    fun line(x: Float, y: Float, x2: Float, y2: Float, color: Int, stroke: Float = 1f) {
        shapePaint.color = color
        shapePaint.strokeWidth = stroke
        canvas.drawLine(x, y, x2, y2, shapePaint)
    }

    fun fade(x: Float, y: Float, w: Float, h: Float, from: Int, to: Int, vertical: Boolean = false) {
        val colors = Gradient.Colors(arrayOf(Color4f(from), Color4f(to)), tileMode = FilterTileMode.CLAMP)
        val end = if (vertical) Point(x, y + h) else Point(x + w, y)
        Shader.makeLinearGradient(Point(x, y), end, Gradient(colors)).use { shader ->
            Paint().use { paint ->
                paint.shader = shader
                canvas.drawRect(Rect.makeXYWH(x, y, w, h), paint)
            }
        }
    }

    // 中心裁切到槽位的比例；中立物品的圆形和主装备共用这条绘图路径。
    fun image(image: Image, x: Float, y: Float, w: Float, h: Float, radius: Float = 0f) {
        val scale = maxOf(w / image.width, h / image.height)
        val sw = w / scale
        val sh = h / scale
        val src = Rect.makeXYWH((image.width - sw) / 2f, (image.height - sh) / 2f, sw, sh)
        val save = canvas.save()
        try {
            if (radius > 0f) canvas.clipRRect(RRect.makeXYWH(x, y, w, h, radius), true)
            canvas.drawImageRect(image, src, Rect.makeXYWH(x, y, w, h), SamplingMode.LINEAR, null, true)
        } finally {
            canvas.restoreToCount(save)
        }
    }

    fun at(x: Float, y: Float, block: Dota2ReportCanvas.() -> Unit) {
        val save = canvas.save()
        try {
            canvas.translate(x, y)
            block()
        } finally {
            canvas.restoreToCount(save)
        }
    }
}
