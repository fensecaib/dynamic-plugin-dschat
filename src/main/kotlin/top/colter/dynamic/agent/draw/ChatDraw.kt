package top.colter.dynamic.agent.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.skiko.*
import top.colter.skiko.data.Gradient
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.layout.*

suspend fun chatDraw(content: String, config: ImageConfig, fontRegistry: FontRegistry = Fonts.default): org.jetbrains.skia.Image? {
    val colors = parseGradientColors(config.defaultColor)
    Dp.factor = config.factor

    val gradient = if (colors.size >= 2) {
        Gradient(LayoutAlignment.LEFT_TOP, LayoutAlignment.RIGHT_BOTTOM, listOf(colors[0], colors[1]))
    } else null

    val displayText = if (content.length > 8000) {
        content.take(8000) + "\n\n---\n(...)"
    } else content

    val ff = fontRegistry.textTypeface?.familyName ?: ""
    val style = TextStyle().setColor(Color.BLACK).setFontSize(30.px)
    if (ff.isNotEmpty()) style.setFontFamily(ff)

    val paragraph = RichParagraphBuilder(style)
    paragraph.addText(displayText)

    val bgColor = if (colors.isNotEmpty()) colors[0] else Color.makeRGB(102, 126, 234)

    return View(
        modifier = Modifier().width(800.dp).padding(30.dp).background(color = bgColor, gradient = gradient),
        fontRegistry = fontRegistry,
    ) {
        Column(
            modifier = Modifier().fillMaxWidth().padding(25.dp)
                .background(Color.WHITE.withAlpha(0.85f)).border(3.dp, 15.dp)
                .shadows(top.colter.skiko.data.Shadow.ELEVATION_2)
        ) {
            RichText(paragraph = paragraph.build(), modifier = Modifier().margin(20.dp).fillMaxWidth())
        }
    }
}
