package top.colter.dynamic.agent.util

import kotlinx.coroutines.*
import top.colter.dynamic.agent.config.DiagnosticsConfig
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*

class AgentDiagnosticsTest {
    @Test fun `one request and concurrent stages preserve the same trace`() = runBlocking<Unit> {
        val logs = CopyOnWriteArrayList<String>()
        withContext(AgentTrace("request-trace", "dota_report", "qq", sink = logs::add)) {
            AgentDiagnostics.request("dota_report", "qq", DiagnosticsConfig()) {
                assertEquals("request-trace", AgentDiagnostics.currentTraceId())
                coroutineScope {
                    listOf("match", "assets").map { name -> async {
                        AgentDiagnostics.stage(name, mapOf("match_id" to 9044880093L)) {
                            yield()
                            assertEquals("request-trace", AgentDiagnostics.currentTraceId())
                            AgentDiagnostics.event("agent_fixture", "success")
                        }
                    } }.awaitAll()
                }
            }
        }
        assertEquals(8, logs.size)
        assertTrue(logs.all { "trace_id=request-trace" in it && "action=dota_report" in it && "platform=qq" in it })
        for (name in listOf("match", "assets")) {
            assertEquals(1, logs.count { "event=agent_stage" in it && "stage=$name" in it && "status=start" in it })
            assertEquals(1, logs.count { "event=agent_stage" in it && "stage=$name" in it && "status=completed" in it })
        }
    }

    @Test fun `concurrent requests keep separate trace contexts`() = runBlocking<Unit> {
        val logs = CopyOnWriteArrayList<String>()
        coroutineScope {
            (1..2).map { number -> async(AgentTrace("trace-$number", action = "action-$number", sink = logs::add)) {
                AgentDiagnostics.request("ignored", "test", DiagnosticsConfig()) {
                    AgentDiagnostics.stage("stage-$number") { yield(); assertEquals("trace-$number", AgentDiagnostics.currentTraceId()) }
                }
            } }.awaitAll()
        }
        assertEquals(8, logs.size)
        for (number in 1..2) {
            val own = logs.filter { "trace_id=trace-$number" in it }
            assertEquals(4, own.size)
            assertTrue(own.all { "action=action-$number" in it })
            assertFalse(own.any { "stage=stage-${3 - number}" in it })
        }
    }

    @Test fun `failed and cancelled requests and stages never log exception messages or bodies`() = runBlocking<Unit> {
        val logs = CopyOnWriteArrayList<String>()
        val secret = "secret-token-and-raw-body"
        val failed = IllegalStateException("$secret\nbody=private-prompt")
        val caught = assertFailsWith<IllegalStateException> {
            withContext(AgentTrace("failed-trace", sink = logs::add)) {
                AgentDiagnostics.request("dota_report", "qq", DiagnosticsConfig()) {
                    AgentDiagnostics.stage("ai") { throw failed }
                }
            }
        }
        assertEquals(failed.message, caught.message)
        val cancelled = CancellationException("$secret\nresponse=private-answer")
        val cancellation = assertFailsWith<CancellationException> {
            withContext(AgentTrace("cancelled-trace", sink = logs::add)) {
                AgentDiagnostics.request("dota_report", "qq", DiagnosticsConfig()) {
                    AgentDiagnostics.stage("ai") { throw cancelled }
                }
            }
        }
        assertEquals(cancelled.message, cancellation.message)
        assertEquals(2, logs.count { "trace_id=failed-trace" in it && "status=failed" in it })
        assertEquals(2, logs.count { "trace_id=cancelled-trace" in it && "status=cancelled" in it })
        val text = logs.joinToString("\n")
        listOf(secret, "private-prompt", "private-answer").forEach { assertFalse(it in text) }
        assertTrue(logs.filter { "trace_id=failed-trace" in it && "status=failed" in it }.all { "error_type=IllegalStateException" in it })
    }

    @Test fun `disabled diagnostics produce no events across request and stages`() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        withContext(AgentTrace("disabled-trace", enabled = false, sink = logs::add)) {
            AgentDiagnostics.request("dota_report", "qq", DiagnosticsConfig(enabled = false)) {
                AgentDiagnostics.stage("ai") { AgentDiagnostics.event("agent_ai", "success", mapOf("total_tokens" to 10)) }
            }
            assertFailsWith<IllegalStateException> { AgentDiagnostics.stage("failed") { error("hidden") } }
        }
        assertTrue(logs.isEmpty())
    }

    @Test fun `sink output rejects sensitive fields metadata overrides and newline injection`() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        withContext(AgentTrace("safe-trace\nforged=true", action = "action\r\nforged=true", sink = logs::add)) {
            AgentDiagnostics.event("agent_ai\nforged=true", "start\r\nforged=true", mapOf(
                "model" to "deepseek-flash\nstatus=failed",
                "unsafe\nkey" to "line-one\r\nline-two",
                "trace_id" to "override-trace",
                "API_KEY" to "secret-key",
                "Authorization" to "secret-authorization",
                "request_body" to "secret-request-body",
                "response_body" to "secret-response-body",
                "prompt" to "secret-prompt",
                "content" to "secret-content",
                "url" to "https://example.invalid/?token=secret-url",
            ))
        }
        val line = logs.single()
        assertFalse('\n' in line)
        assertFalse('\r' in line)
        assertFalse("override-trace" in line)
        assertFalse("secret-" in line)
        assertContains(line, "trace_id=safe-trace_forged_true")
        assertContains(line, "model=deepseek-flash_status_failed")
    }

    @Test fun `slow stages emit one waiting event then complete or cancel within the same trace`() = runBlocking<Unit> {
        val logs = CopyOnWriteArrayList<String>()
        val completingWait = CompletableDeferred<Unit>()
        val cancellingWait = CompletableDeferred<Unit>()
        val sink: (String) -> Unit = { line ->
            logs += line
            if ("status=waiting" in line && "stage=slow_completed" in line) completingWait.complete(Unit)
            if ("status=waiting" in line && "stage=slow_cancelled" in line) cancellingWait.complete(Unit)
        }
        withTimeout(6500) {
            val completed = async(AgentTrace("slow-trace", slowStageWarningSeconds = 5, sink = sink)) {
                AgentDiagnostics.stage("slow_completed") { completingWait.await(); "complete" }
            }
            val cancelled = launch(AgentTrace("slow-trace", slowStageWarningSeconds = 5, sink = sink)) {
                AgentDiagnostics.stage("slow_cancelled") { awaitCancellation() }
            }
            cancellingWait.await()
            assertEquals("complete", completed.await())
            cancelled.cancelAndJoin()
        }
        assertTrue(logs.all { "trace_id=slow-trace" in it })
        for (stage in listOf("slow_completed", "slow_cancelled")) {
            assertEquals(1, logs.count { "stage=$stage" in it && "status=waiting" in it })
        }
        assertEquals(1, logs.count { "stage=slow_completed" in it && "status=completed" in it })
        assertEquals(1, logs.count { "stage=slow_cancelled" in it && "status=cancelled" in it })
    }
}
