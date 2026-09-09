package top.colter.dynamic.agent.draw

import org.jetbrains.skia.Data
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import org.jetbrains.skia.svg.SVGDOM

/**
 * 战绩卡片使用的小型通用图标。
 *
 * SVG 随插件打包，首次使用时栅格化为固定尺寸并缓存。调用方始终为图标保留固定槽位，
 * 因而资源缺失或解码失败只会隐藏图标，不会改变数据列的位置。
 */
object Dota2UiIcons {
    private const val ICON_SIZE = 24

    val kills: Image? by lazy { load("kills.svg") }
    val economy: Image? by lazy { load("economy.svg") }
    val damage: Image? by lazy { load("damage.svg") }
    val tower: Image? by lazy { load("tower.svg") }

    private fun load(name: String): Image? = runCatching {
        val path = "/dota2/ui/$name"
        val bytes = requireNotNull(javaClass.getResourceAsStream(path)) {
            "Missing Dota 2 UI resource: $path"
        }.use { it.readBytes() }
        Data.makeFromBytes(bytes).use { data ->
            SVGDOM(data).use { svg ->
                svg.setContainerSize(ICON_SIZE.toFloat(), ICON_SIZE.toFloat())
                Surface.makeRasterN32Premul(ICON_SIZE, ICON_SIZE).use { surface ->
                    surface.canvas.clear(org.jetbrains.skia.Color.TRANSPARENT)
                    svg.render(surface.canvas)
                    surface.makeImageSnapshot()
                }
            }
        }
    }.getOrNull()
}
