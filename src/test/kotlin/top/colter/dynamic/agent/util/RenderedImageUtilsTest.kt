package top.colter.dynamic.agent.util

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import top.colter.dynamic.core.plugin.PluginMessagePublishResult
import java.io.IOException
import java.nio.file.Files
import kotlin.test.*

class RenderedImageUtilsTest {
    private fun image(): Image = Surface.makeRasterN32Premul(16, 16).use {
        it.canvas.clear(Color.BLUE)
        it.makeImageSnapshot()
    }

    @Test fun `images are released and repeated filenames preserve pending output`() {
        val dir = Files.createTempDirectory("rendered-image-test")
        try {
            val cache = CacheUtils(dir)
            val firstImage = image()
            val first = cacheRenderedImage(firstImage, cache, "report.png")
            assertTrue(firstImage.isClosed)
            val bytes = first.readBytes()
            val secondImage = image()
            val second = cacheRenderedImage(secondImage, cache, "report.png")
            assertTrue(secondImage.isClosed)
            assertNotEquals(first, second)
            assertContentEquals(bytes, first.readBytes())
            Image.makeFromEncoded(bytes).use { assertEquals(16, it.width) }
        } finally {
            // 仅删除本测试通过 createTempDirectory 创建的目录。
            Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }
        }
    }

    @Test fun `write failure still releases the rendered image`() {
        val file = Files.createTempFile("rendered-image-test", ".tmp")
        try {
            val rendered = image()
            assertFailsWith<IOException> { cacheRenderedImage(rendered, CacheUtils(file), "report.png") }
            assertTrue(rendered.isClosed)
        } finally { Files.delete(file) }
    }

    @Test fun `rejected publishing is a failure`() {
        PluginMessagePublishResult(accepted = true).requireAccepted()
        assertFailsWith<IOException> { PluginMessagePublishResult(accepted = false).requireAccepted() }
    }
}
