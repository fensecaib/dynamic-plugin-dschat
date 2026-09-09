package top.colter.dynamic.agent.util

import org.jetbrains.skia.Image
import top.colter.dynamic.core.plugin.PluginMessagePublishResult
import java.io.File
import java.io.IOException
import java.util.UUID

/** 接管绘图结果的所有权；编码或写盘失败时也释放 Image 和编码 Data。 */
internal fun cacheRenderedImage(image: Image, cache: CacheUtils, filename: String): File = image.use {
    val data = it.encodeToData() ?: throw IOException("图片编码失败")
    data.use { encoded ->
        // 宿主异步读取文件，每次发送必须使用独立路径，避免下一次绘图覆盖待发送图片。
        cache.cacheFile(CacheType.DRAW, "${UUID.randomUUID()}_$filename").also { file ->
            file.writeBytes(encoded.bytes)
        }
    }
}

/** accepted 仅表示宿主接受投递，不代表聊天端已经送达。 */
internal fun PluginMessagePublishResult.requireAccepted() {
    if (!accepted) throw IOException("宿主未接受消息，请稍后重试")
}
