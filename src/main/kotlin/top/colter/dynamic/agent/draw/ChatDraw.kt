package top.colter.dynamic.agent.draw

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.*
import top.colter.dynamic.agent.chat.*
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.skiko.FontRegistry
import top.colter.skiko.Fonts
import kotlin.math.ceil

private const val WIDTH = 900f
private const val INNER = 804f
private const val MAX_BODY = 16000f
private const val CONTENT_TOP = 40f
private const val CONTENT_BOTTOM = 44f
private val INK = Color.makeRGB(37, 44, 62)
private val ACCENT = Color.makeRGB(102, 87, 190)
private val SOFT = Color.makeRGB(242, 241, 250)
private val CODE_BG = Color.makeRGB(30, 37, 55)
private val CODE_TEXT = Color.makeRGB(225, 232, 247)

private class Measured(val block: ChatBlock, val paragraphs: List<Paragraph>, val height: Float) : AutoCloseable {
    override fun close() = paragraphs.forEach { it.close() }
}
private data class Piece(val measured: Measured, val start: Float, val end: Float, val y: Float)

/** Measure before painting and render exactly one complete image. No global Dp mutation. */
internal suspend fun renderChatImage(
    document: ChatDocument, config: ImageConfig, fonts: FontRegistry = Fonts.default,
    consume: (Image, Int, Int) -> Unit,
) {
    require(document.blocks.isNotEmpty()) { "No visible Markdown blocks" }
    val allocated = mutableListOf<Measured>()
    fun paragraph(spans: List<ChatSpan>, size: Float, width: Float, bold: Boolean = false, color: Int = INK, codeBlock: Boolean = false): Paragraph {
        ParagraphStyle().use { ps ->
            ParagraphBuilder(ps, fonts.fonts).use { builder ->
                spans.ifEmpty { listOf(ChatSpan(" ")) }.forEach { span ->
                    TextStyle().use { requested ->
                        requested.setColor(if (span.link) ACCENT else color).setFontSize(if (span.code) size * .92f else size)
                            .setHeight(1.45f).setFontFamily(if (span.code) "Consolas" else fonts.textTypeface?.familyName.orEmpty())
                            .setFontStyle(when {
                                (bold || span.bold) && span.italic -> FontStyle.BOLD_ITALIC
                                bold || span.bold -> FontStyle.BOLD
                                span.italic -> FontStyle.ITALIC
                                else -> FontStyle.NORMAL
                            })
                        Paint().use { background ->
                            if (span.code && !codeBlock) { background.color = SOFT; requested.setBackground(background) }
                            fonts.resolveTextStyle(requested).use { resolved -> builder.pushStyle(resolved); builder.addText(span.text); builder.popStyle() }
                        }
                    }
                }
                val p = builder.build()
                try { p.layout(width); return p } catch (e: Throwable) { p.close(); throw e }
            }
        }
    }
    fun measure(block: ChatBlock): Measured {
        val cells = block.cells
        val width = INNER - block.indent * 22f - if (block.quote || block.code) 32f else 0f
        val size = when (block.heading) { 1 -> 38f; 2 -> 32f; 3 -> 28f; in 4..6 -> 26f; else -> if (block.code) 24f else 25f }
        val paragraphs = mutableListOf<Paragraph>()
        try {
            if (cells.isEmpty()) paragraphs.add(paragraph(block.spans, size, width, block.heading > 0,
                color = if (block.code) CODE_TEXT else INK, codeBlock = block.code))
            else cells.forEach { paragraphs.add(paragraph(it, 23f, INNER / cells.size - 24f, block.isHeader,
                color = if (block.isHeader) ACCENT else INK)) }
            return Measured(block, paragraphs, paragraphs.maxOf { it.height } + if (cells.isNotEmpty()) 24f else 0f).also { allocated.add(it) }
        } catch (e: Throwable) { paragraphs.forEach { it.close() }; throw e }
    }
    try {
        var measuredHeight = 0f
        val measured = document.blocks.map {
            currentCoroutineContext().ensureActive()
            measure(it).also { m ->
                measuredHeight += m.height
                check(measuredHeight <= MAX_BODY) { "Reply exceeds image page budget" }
            }
        }
        val pieces = mutableListOf<Piece>()
        var y = 0f
        for ((index, m) in measured.withIndex()) {
            currentCoroutineContext().ensureActive()
            val block = m.block
            if (block.cells.isEmpty() && measured.getOrNull(index - 1)?.block?.cells?.isNotEmpty() == true) y += 18f
            if (block.code) y += 38f
            pieces.add(Piece(m, 0f, m.height, y))
            y += m.height + if (block.cells.isEmpty()) 18f else 0f
            check(y <= MAX_BODY) { "Reply exceeds single image layout budget" }
        }
        val height = maxOf(140f, (pieces.lastOrNull()?.let { it.y + it.end - it.start } ?: 0f) + CONTENT_TOP + CONTENT_BOTTOM)
        val requestedScale = config.factor.takeIf { it.isFinite() && it > 0 }?.coerceIn(.75f, 2f) ?: 1f
        // A long image must not multiply native memory usage by an unrestricted scale factor.
        val scale = minOf(requestedScale, kotlin.math.sqrt(16_000_000f / (WIDTH * height)))
        currentCoroutineContext().ensureActive()
        run {
            Surface.makeRasterN32Premul(ceil(WIDTH * scale).toInt(), ceil(height * scale).toInt()).use { surface ->
                val c = surface.canvas; c.clear(Color.makeRGB(235, 238, 246)); c.scale(scale, scale)
                fun rect(x: Float, yy: Float, w: Float, h: Float, color: Int) {
                    Paint().use { it.color = color; c.drawRect(Rect.makeXYWH(x, yy, w, h), it) }
                }
                fun rounded(x: Float, yy: Float, w: Float, h: Float, radius: Float, color: Int) {
                    Paint().use { it.color = color; it.isAntiAlias = true; c.drawRRect(RRect.makeXYWH(x, yy, w, h, radius), it) }
                }
                rounded(24f, 21f, WIDTH - 48f, height - 36f, 22f, Color.makeRGB(218, 223, 236))
                rounded(24f, 16f, WIDTH - 48f, height - 36f, 22f, Color.WHITE)
                for ((pieceIndex, piece) in pieces.withIndex()) {
                    val m = piece.measured; val b = m.block
                    val yy = piece.y + CONTENT_TOP; val h = piece.end - piece.start
                    val x = 48f + b.indent * 22f
                    if (b.code) {
                        rounded(x - 8f, yy - 32f, INNER - b.indent * 22f + 16f, h + 40f, 12f, CODE_BG)
                        rounded(x + 12f, yy - 21f, 6f, 6f, 3f, Color.makeRGB(153, 139, 229))
                        paragraph(listOf(ChatSpan(b.label + if (piece.start > 0) " · 续" else "", bold = true)), 14f, INNER - 40f,
                            color = Color.makeRGB(173, 187, 212)).use { it.paint(c, x + 27f, yy - 27f) }
                        rect(x + 12f, yy - 5f, INNER - b.indent * 22f - 24f, 1f, Color.makeRGB(55, 64, 85))
                    }
                    if (b.quote) {
                        rounded(x, yy - 3f, INNER - b.indent * 22f, h + 6f, 8f, SOFT)
                        rounded(x, yy + 3f, 4f, maxOf(4f, h - 6f), 2f, ACCENT)
                    }
                    if (b.heading > 0 && !b.quote) rounded(x - 12f, yy + 10f, 4f, maxOf(6f, minOf(24f, h - 14f)), 2f, ACCENT)
                    if (b.cells.isNotEmpty()) {
                        rect(x, yy, INNER, h, if (b.isHeader) Color.makeRGB(235, 232, 249)
                            else if (pieceIndex % 2 == 0) Color.makeRGB(249, 249, 253) else Color.WHITE)
                        val cw = INNER / b.cells.size
                        m.paragraphs.forEachIndexed { i, p ->
                            rect(x + i * cw, yy, 1f, h, SOFT)
                            p.paint(c, x + i * cw + 12f, yy + 12f)
                        }
                        rect(x, yy + h - 1, INNER, 1f, Color.makeRGB(215, 223, 235))
                    } else {
                        c.save()
                        try { c.clipRect(Rect.makeXYWH(x, yy, INNER - b.indent * 22f, h)); m.paragraphs.single().paint(c, x + if (b.code || b.quote) 16f else 0f, yy - piece.start) }
                        finally { c.restore() }
                    }
                }
                surface.makeImageSnapshot().use { consume(it, 1, 1) }
            }
        }
    } finally { allocated.forEach { it.close() } }
}
