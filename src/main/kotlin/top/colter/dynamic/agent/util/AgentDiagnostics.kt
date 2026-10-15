package top.colter.dynamic.agent.util

import kotlinx.coroutines.*
import top.colter.dynamic.agent.config.DiagnosticsConfig
import top.colter.dynamic.core.tools.loggerFor
import java.util.UUID
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** A request owns one trace across data fetches, model calls, rendering and delivery. */
internal class AgentTrace(
    val id: String,
    val action: String = "test",
    val platform: String = "test",
    val enabled: Boolean = true,
    val slowStageWarningSeconds: Int = 30,
    val sink: ((String) -> Unit)? = null,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AgentTrace> {
        fun create(action: String, platform: String, config: DiagnosticsConfig) = AgentTrace(
            UUID.randomUUID().toString().take(12), action, platform, config.enabled, config.slowStageWarningSeconds,
        )
    }
}

internal object AgentDiagnostics {
    private val log = loggerFor<AgentDiagnostics>()
    private val forbidden = setOf("api_key", "apikey", "authorization", "secret", "password", "token", "prompt", "content", "request_body", "response_body", "url")

    suspend fun currentTraceId(): String? = currentCoroutineContext()[AgentTrace]?.id

    /** Fields are operational metadata only. Never pass request/response bodies or exception messages. */
    suspend fun event(event: String, status: String, fields: Map<String, Any?> = emptyMap(), warning: Boolean = false) {
        val trace = currentCoroutineContext()[AgentTrace]
        if (trace?.enabled == false) return
        val metadata = linkedMapOf<String, Any?>(
            "event" to event, "trace_id" to (trace?.id ?: "unscoped"), "action" to (trace?.action ?: "internal"),
            "platform" to (trace?.platform ?: "internal"), "status" to status,
        )
        fields.filterKeys { it.lowercase() !in forbidden && it !in metadata }.forEach { (key, value) -> metadata[key] = value }
        val line = metadata.entries.joinToString(" ") { (key, value) -> "${label(key)}=${label(value?.toString() ?: "-")}" }
        trace?.sink?.let { it(line); return }
        if (warning) log.warn { line } else log.info { line }
    }

    suspend fun <T> request(action: String, platform: String, config: DiagnosticsConfig, block: suspend () -> T): T {
        val trace = currentCoroutineContext()[AgentTrace] ?: AgentTrace.create(action, platform, config)
        return withContext(trace) {
            val started = System.nanoTime()
            event("agent_request", "start")
            try {
                block().also { event("agent_request", "completed", mapOf("elapsed_ms" to elapsed(started))) }
            } catch (e: CancellationException) {
                event("agent_request", "cancelled", mapOf("elapsed_ms" to elapsed(started)), warning = true)
                throw e
            } catch (e: Exception) {
                event("agent_request", "failed", mapOf("elapsed_ms" to elapsed(started), "error_type" to e.javaClass.simpleName), warning = true)
                throw e
            }
        }
    }

    suspend fun <T> stage(stage: String, fields: Map<String, Any?> = emptyMap(), block: suspend () -> T): T = coroutineScope {
        val started = System.nanoTime()
        val metadata = fields + ("stage" to stage)
        event("agent_stage", "start", metadata)
        val trace = currentCoroutineContext()[AgentTrace]
        val watcher = if (trace?.enabled == true) launch(Dispatchers.Default) {
            val interval = trace.slowStageWarningSeconds.coerceIn(5, 300) * 1000L
            while (isActive) {
                delay(interval)
                event("agent_stage", "waiting", metadata + ("elapsed_ms" to elapsed(started)), warning = true)
            }
        } else null
        try {
            block().also { event("agent_stage", "completed", metadata + ("elapsed_ms" to elapsed(started))) }
        } catch (e: CancellationException) {
            event("agent_stage", "cancelled", metadata + ("elapsed_ms" to elapsed(started)), warning = true)
            throw e
        } catch (e: Exception) {
            event("agent_stage", "failed", metadata + mapOf("elapsed_ms" to elapsed(started), "error_type" to e.javaClass.simpleName), warning = true)
            throw e
        } finally {
            watcher?.cancel()
        }
    }

    private fun elapsed(started: Long) = (System.nanoTime() - started) / 1_000_000
    private fun label(value: String) = value.take(128).replace(Regex("[^\\p{L}\\p{N}_.:/-]"), "_")
}
