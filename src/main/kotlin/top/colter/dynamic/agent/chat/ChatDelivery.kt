package top.colter.dynamic.agent.chat

import kotlinx.coroutines.*
import top.colter.dynamic.core.data.*
import top.colter.dynamic.core.plugin.*
import top.colter.dynamic.agent.config.DiagnosticsConfig
import top.colter.dynamic.agent.ds.ApiCallException
import top.colter.dynamic.agent.util.AgentDiagnostics
import top.colter.dynamic.agent.util.AgentTrace
import top.colter.dynamic.agent.util.sendAgentBatches
import kotlin.coroutines.EmptyCoroutineContext

/** A request computes one terminal result and attempts delivery once. An uncertain ACK must not trigger a second message. */
internal suspend fun deliverChatOnce(
    prepare: suspend () -> List<MessageBatch>,
    send: suspend (List<MessageBatch>) -> PluginMessagePublishResult,
    report: (String) -> Unit,
) {
    val batches = try { prepare() } catch (e: Exception) {
        if (e is CancellationException) throw e
        report("对话生成失败：${e.message}")
        val message = (e as? ApiCallException)?.message?.takeIf { it.isNotBlank() }
            ?: "回复生成失败，请稍后重试。"
        listOf(MessageBatch(listOf(MessageContent.Text(message))))
    }
    currentCoroutineContext().ensureActive()
    if (batches.isEmpty()) return
    try {
        val result = send(batches)
        if (!result.accepted) report("对话投递未获确认，不自动补发：messageId=${result.messageId}，原因=${result.message.ifBlank { "宿主未提供原因" }}")
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        report("对话投递异常，不自动补发：${e.message}")
    }
}

internal fun PluginContext.launchChatReply(target: TargetAddress, replyTo: String, trace: AgentTrace? = null, prepare: suspend () -> List<MessageBatch>): Job =
    scope.launch(trace ?: EmptyCoroutineContext) {
        AgentDiagnostics.request("chat_reply", target.platformId.value, DiagnosticsConfig()) {
            var deliveryFailed = false
            deliverChatOnce({ AgentDiagnostics.stage("chat_generate", block = prepare) }, { batches ->
                sendAgentBatches(target, batches, replyTo, "chat_result")
            }, { _ -> deliveryFailed = true })
            if (deliveryFailed) AgentDiagnostics.event("agent_chat_delivery", "failed", warning = true)
        }
    }
