package top.colter.dynamic.agent.util

import org.jetbrains.skia.Image
import top.colter.dynamic.core.plugin.PluginMessagePublishResult
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginMessagePublishOptions
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
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
    if (!accepted) throw IOException("宿主未接受消息，请检查宿主日志中的 agent_send 记录")
}

/** Preserve the reply envelope and report delivery results without logging platform response bodies. */
internal suspend fun PluginContext.sendAgentBatches(
    target: TargetAddress,
    batches: List<MessageBatch>,
    replyToMessageId: String = "",
    purpose: String = "result",
): PluginMessagePublishResult {
    val images = batches.flatMap { it.content }.filterIsInstance<MessageContent.Image>()
    val imageBytes = images.sumOf { image ->
        runCatching { File(image.image.uri).takeIf { it.isFile }?.length() ?: 0L }.getOrDefault(0L)
    }
    val fields = mapOf<String, Any?>("purpose" to purpose, "batches" to batches.size, "image_count" to images.size,
        "image_bytes" to imageBytes, "has_reply_id" to replyToMessageId.isNotEmpty())
    return AgentDiagnostics.stage("send_$purpose", fields) {
        AgentDiagnostics.event("agent_send", "start", fields)
        try {
            val result = messagePublisher.sendBatches(target, batches,
                options = PluginMessagePublishOptions(replyToMessageId = replyToMessageId.ifEmpty { null }))
            val codes = Regex("(?i)(?:code|错误码|error_code)[\"']?\\s*[:=：]?\\s*[\"']?(\\d{3,12})")
                .findAll(result.message).map { it.groupValues[1] }.distinct().take(3).joinToString(",").ifEmpty { "-" }
            AgentDiagnostics.event("agent_send", if (result.accepted) "accepted" else "rejected",
                fields + mapOf("platform_error_code" to codes), warning = !result.accepted)
            result.requireAccepted()
            result
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            AgentDiagnostics.event("agent_send", "cancelled", fields, warning = true)
            throw e
        } catch (e: Exception) {
            AgentDiagnostics.event("agent_send", "failed", fields + ("error_type" to e.javaClass.simpleName), warning = true)
            // SDK exceptions can embed credentials or raw upstream responses. Do not forward them into chat replies.
            throw IOException("消息发送失败，请检查宿主日志中的 agent_send 记录")
        }
    }
}

/** A final delivery has one attempt; uncertain acknowledgement must not trigger a second terminal message. */
internal suspend fun PluginContext.deliverAgentBatchesOnce(
    target: TargetAddress,
    batches: List<MessageBatch>,
    replyToMessageId: String = "",
): Boolean = try {
    sendAgentBatches(target, batches, replyToMessageId)
    true
} catch (e: kotlin.coroutines.cancellation.CancellationException) {
    throw e
} catch (_: Exception) {
    // sendAgentBatches has already emitted safe diagnostic details.
    false
}

internal fun dotaAction(command: String?): String = when (command) {
    "绑定" -> "dota_bind"
    "历史" -> "dota_history"
    "战报", "深度战报" -> "dota_report"
    "个人详情", "深度个人详情" -> "dota_overview"
    "分析" -> "dota_full_analysis"
    else -> "dota_help"
}

internal fun TargetAddress.withPreferredAccount(accountId: String?): TargetAddress {
    val preferred = accountId?.trim()?.takeIf { it.isNotEmpty() } ?: return this
    return copy(accountId = preferred)
}
