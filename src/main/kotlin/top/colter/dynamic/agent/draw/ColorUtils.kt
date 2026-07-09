package top.colter.dynamic.agent.draw

import org.jetbrains.skia.Color

fun hexColor(hex: String): Int {
    val h = hex.trimStart('#')
    return when (h.length) {
        6 -> Color.makeRGB(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        8 -> { val a = h.substring(0, 2).toInt(16); val r = h.substring(2, 4).toInt(16); val g = h.substring(4, 6).toInt(16); val b = h.substring(6, 8).toInt(16); (a shl 24) or (r shl 16) or (g shl 8) or b }
        else -> Color.makeRGB(128, 128, 128)
    }
}

fun parseGradientColors(colorConfig: String): List<Int> {
    return colorConfig.split(";").filter { it.isNotBlank() }.map { hexColor(it.trim()) }
}
