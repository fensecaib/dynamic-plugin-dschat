package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.*
import kotlin.test.*

class Dota2ReportTaskGateTest {
    @Test fun `busy requests are rejected immediately and never queued`() = runBlocking {
        val gate = Dota2ReportTaskGate()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.runIfIdle(onBusy = { error("first must start") }) {
                started.complete(Unit)
                release.await()
                "done"
            }
        }
        started.await()
        val rejections = withTimeout(2000) {
            (1..20).map {
                async(Dispatchers.Default) {
                    gate.runIfIdle(onBusy = { "busy" }) { error("must not queue or execute") }
                }
            }.awaitAll()
        }
        assertTrue(rejections.all { it == "busy" })
        release.complete(Unit)
        assertEquals("done", first.await())
        assertEquals("next", gate.runIfIdle(onBusy = { "busy" }) { "next" })
    }

    @Test fun `failure and cancellation release the slot`() = runBlocking {
        val gate = Dota2ReportTaskGate()
        assertFailsWith<IllegalStateException> {
            gate.runIfIdle(onBusy = { error("busy") }) { error("request failed") }
        }
        val started = CompletableDeferred<Unit>()
        val job = launch {
            gate.runIfIdle(onBusy = { error("slot leaked after failure") }) {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        started.await()
        job.cancelAndJoin()
        assertEquals("next", gate.runIfIdle(onBusy = { "busy" }) { "next" })
    }

    @Test fun `both modes share index resolution and preserve command in errors`() = runBlocking {
        assertEquals(Dota2ReportMode.NORMAL, Dota2ReportMode.fromCommand("战报"))
        assertEquals(Dota2ReportMode.DEEP, Dota2ReportMode.fromCommand("深度战报"))
        assertNull(Dota2ReportMode.fromCommand("深度战报3"))
        val recent = kotlinx.serialization.json.Json.parseToJsonElement("""[
            {"match_id":100,"start_time":1}, {"match_id":200,"start_time":2}
        ]""") as kotlinx.serialization.json.JsonArray
        for (mode in Dota2ReportMode.entries) {
            assertEquals(200L, resolveDotaReportMatchId(null, mode) { recent })
            assertEquals(100L, resolveDotaReportMatchId("2", mode) { recent })
            assertEquals(8988982914L, resolveDotaReportMatchId("8988982914", mode) { error("must not fetch") })
            val error = assertFailsWith<IllegalArgumentException> {
                resolveDotaReportMatchId("abc", mode) { error("must not fetch") }
            }
            assertContains(error.message!!, "/dota ${mode.command}")
        }
    }
}
