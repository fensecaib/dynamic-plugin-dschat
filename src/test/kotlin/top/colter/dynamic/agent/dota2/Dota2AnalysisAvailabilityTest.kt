package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.*
import top.colter.dynamic.agent.ds.ApiCallException
import kotlin.test.*

class Dota2AnalysisAvailabilityTest {
    @Test fun `service failures explain unavailability without upstream body or credentials`() {
        val cases = mapOf(
            "unauthorized" to "认证失败", "payment_required" to "余额不足", "rate_limited" to "限流",
            "server_error" to "服务暂时异常", "network_timeout" to "超时", "network_error" to "无法连接",
            "response_incomplete" to "完整有效", "analysis_format" to "完整有效",
        )
        for ((category, expected) in cases) {
            val failure = analysisUnavailable(ApiCallException(category, 503, "private-key private-body"))
            assertContains(failure.userMessage, "AI 分析暂不可用")
            assertContains(failure.userMessage, expected)
            assertContains(failure.userMessage, "/dota历史")
            assertFalse("private-" in failure.toString())
            assertNull(failure.cause)
        }
        val unknown = analysisUnavailable(IllegalStateException("private-body"), "private-category")
        assertEquals("analysis_error", unknown.category)
        assertFalse("private-" in unknown.toString())
    }

    @Test fun `analysis deadline cancels children and returns explicit unavailable message`() = runBlocking<Unit> {
        var childStopped = false
        val failure = assertFailsWith<DotaAnalysisUnavailableException> {
            withDotaAnalysisBudget(100) {
                coroutineScope {
                    launch { try { awaitCancellation() } finally { childStopped = true } }
                    awaitCancellation()
                }
            }
        }
        assertTrue(childStopped)
        assertEquals("analysis_timeout", failure.category)
        assertContains(failure.userMessage, "AI 分析暂不可用")
    }

    @Test fun `successful nullable values and external cancellation keep original semantics`() = runBlocking<Unit> {
        assertNull(withDotaAnalysisBudget<String?>(1000) { null })
        assertEquals(42, withDotaAnalysisBudget(1000) { 42 })
        val started = CompletableDeferred<Unit>()
        val job = launch {
            withDotaAnalysisBudget(10_000) { started.complete(Unit); awaitCancellation() }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFailsWith<CancellationException> { analysisUnavailable(CancellationException("cancelled")) }
    }
}
