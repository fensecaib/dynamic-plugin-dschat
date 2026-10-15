package top.colter.dynamic.agent.chat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.draw.renderChatImage
import top.colter.dynamic.agent.util.AgentDiagnostics
import top.colter.dynamic.agent.util.CacheType
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.data.*
import top.colter.skiko.FontRegistry
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

internal typealias ChatImageRenderer = suspend (ChatDocument, ImageConfig, FontRegistry, (org.jetbrains.skia.Image, Int, Int) -> Unit) -> Unit

private val chatRenderSlots = Semaphore(2)

/** Shared by command and mention replies. Exactly one image is prepared before delivery. */
internal suspend fun prepareChatReply(
    content: String, threshold: Int, config: ImageConfig, fonts: FontRegistry, cache: CacheUtils,
    platformId: String = "",
    renderImage: ChatImageRenderer = ::renderChatImage,
): List<MessageBatch> {
    fun textBatches(text: String, markdown: Boolean = true) = splitPlainChatText(
        if (markdown) plainChatText(text) else text,
        discord = platformId.equals("discord", ignoreCase = true),
    ).map { MessageBatch(listOf(MessageContent.Text(it))) }
    if (content.isBlank()) return textBatches("(空回复)")
    if (threshold <= 0 || content.length <= threshold) return textBatches(content)
    // This scope also covers cancellation on return from withContext (prompt cancellation guarantee).
    val files = mutableListOf<java.io.File>()
    var retainFiles = false
    try {
        val result = chatRenderSlots.withPermit {
            withContext(Dispatchers.Default) {
                var phase = "parse"
                try {
                    AgentDiagnostics.stage("chat_render") {
                        val parsed = parseChatDocument(content)
                        val sources = if (parsed.links.isEmpty()) emptyList() else listOf(ChatBlock(listOf(ChatSpan("参考链接", bold = true)), heading = 3)) +
                            parsed.links.mapIndexed { i, link -> ChatBlock(listOf(ChatSpan("[${i + 1}] $link"))) }
                        val document = parsed.copy(blocks = parsed.blocks + sources)
                        val batches = mutableListOf<MessageBatch>()
                        phase = "draw"
                        renderImage(document, config, fonts) { image, page, total ->
                            check(page == 1 && total == 1 && batches.isEmpty()) { "Expected exactly one reply image" }
                            phase = "encode"
                            val data = checkNotNull(image.encodeToData()) { "图片编码失败" }
                            data.use {
                                phase = "write"
                                val file = cache.cacheFile(CacheType.DRAW, "${UUID.randomUUID()}_chat_$page.png")
                                files.add(file)
                                file.writeBytes(it.bytes)
                                batches.add(MessageBatch(listOf(MessageContent.Image(
                                    fallbackText = "AI 回复", image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png")))))
                            }
                            phase = "draw"
                        }
                        check(batches.size == 1) { "No reply image produced" }
                        batches
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    AgentDiagnostics.event("agent_image", "failed", mapOf("component" to "chat_render", "phase" to phase,
                        "error_type" to e.javaClass.simpleName), warning = true)
                    textBatches("回复图片生成失败或内容超出单张图片限制，请缩短报告后重试。", markdown = false)
                }
            }
        }
        retainFiles = result.any { batch -> batch.content.any { it is MessageContent.Image } }
        return result
    } finally {
        if (!retainFiles) files.forEach { file -> runCatching { file.delete() } }
    }
}
