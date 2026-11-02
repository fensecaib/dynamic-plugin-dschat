package top.colter.dynamic.agent.util

import kotlinx.coroutines.*
import top.colter.dynamic.core.data.*
import top.colter.dynamic.core.plugin.*
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.test.*

class AgentDeliveryDiagnosticsTest {
    private val target = TargetAddress.of("qq", TargetKind.GROUP, "private-group-id")
    private val batches = listOf(MessageBatch(listOf(MessageContent.Text("private-message-body"))))

    @Test fun `rejected JSON SDK result logs numeric code without leaking reply envelope or body`() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        var sends = 0
        val context = context { targets, sent, options ->
            sends++
            assertEquals(listOf(target), targets)
            assertEquals(batches, sent)
            assertEquals("private-reply-id", options.replyToMessageId)
            PluginMessagePublishResult(accepted = false, message = """{"code":40054010,"message":"private-api-key private-response-body"}""")
        }
        val error = assertFailsWith<IOException> {
            withContext(AgentTrace("send-trace", sink = logs::add)) {
                context.sendAgentBatches(target, batches, "private-reply-id", "dota_report")
            }
        }
        assertEquals(1, sends)
        assertFalse("private-" in error.toString())
        assertTrue(logs.any { "event=agent_send" in it && "status=rejected" in it && "platform_error_code=40054010" in it })
        assertTrue(logs.any { "event=agent_send" in it && "has_reply_id=true" in it && "image_count=0" in it })
        assertTrue(logs.all { "trace_id=send-trace" in it })
        assertFalse("private-" in logs.joinToString("\n"))
    }

    @Test fun `accepted SDK result never logs its sensitive body`() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        val context = context { _, _, options ->
            assertNull(options.replyToMessageId)
            PluginMessagePublishResult(accepted = true, message = "private-api-key private-response-body")
        }
        val result = withContext(AgentTrace("accepted-trace", sink = logs::add)) {
            context.sendAgentBatches(target, batches)
        }
        assertTrue(result.accepted)
        assertTrue(logs.any { "event=agent_send" in it && "status=accepted" in it && "has_reply_id=false" in it })
        assertFalse("private-" in logs.joinToString("\n"))
    }

    @Test fun `SDK exceptions and cancellation preserve control flow without logging messages`() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        val failure = IOException("private-api-key\nprivate-response-body")
        val failing = context { _, _, _ -> throw failure }
        val caught = assertFailsWith<IOException> {
            withContext(AgentTrace("failed-send", sink = logs::add)) { failing.sendAgentBatches(target, batches) }
        }
        assertFalse("private-" in caught.toString())
        // Coroutine stack recovery may copy the safe exception and link the original safe instance.
        generateSequence<Throwable>(caught) { it.cause }.forEach { assertFalse("private-" in it.toString()) }
        assertTrue(logs.any { "event=agent_send" in it && "status=failed" in it && "error_type=IOException" in it })
        val cancellation = CancellationException("private-api-key\nprivate-response-body")
        val cancelling = context { _, _, _ -> throw cancellation }
        val caughtCancellation = assertFailsWith<CancellationException> {
            withContext(AgentTrace("cancelled-send", sink = logs::add)) { cancelling.sendAgentBatches(target, batches) }
        }
        assertEquals(cancellation.message, caughtCancellation.message)
        assertTrue(logs.any { "event=agent_send" in it && "status=cancelled" in it && "trace_id=cancelled-send" in it })
        assertFalse(logs.any { "trace_id=cancelled-send" in it && "status=failed" in it })
        assertFalse("private-" in logs.joinToString("\n"))
    }

    @Test fun `terminal delivery makes one attempt and returns false for uncertain SDK failures`() = runBlocking<Unit> {
        var sends = 0
        val logs = mutableListOf<String>()
        val failing = context { _, _, _ ->
            sends++
            throw IOException("private-upstream-body")
        }
        val accepted = withContext(AgentTrace("terminal-send", sink = logs::add)) {
            failing.deliverAgentBatchesOnce(target, batches, "private-reply-id")
        }
        assertFalse(accepted)
        assertEquals(1, sends)
        assertTrue(logs.any { "event=agent_send" in it && "status=failed" in it })
        assertFalse("private-" in logs.joinToString("\n"))
    }

    @Test fun `terminal delivery preserves cancellation and never makes a second attempt`() = runBlocking<Unit> {
        var sends = 0
        val expected = CancellationException("cancelled")
        val cancelling = context { _, _, _ -> sends++; throw expected }
        val caught = assertFailsWith<CancellationException> { cancelling.deliverAgentBatchesOnce(target, batches) }
        assertEquals(expected.message, caught.message)
        assertEquals(1, sends)
    }

    private fun context(send: suspend (List<TargetAddress>, List<MessageBatch>, PluginMessagePublishOptions) -> PluginMessagePublishResult) = PluginContext(
        pluginId = "send-diagnostics-test",
        descriptor = PluginDescriptor(id = "send-diagnostics-test", name = "test", version = "test", mainClass = "test", apiVersion = "4.0.0"),
        configService = unused(), dataStore = unused(), scope = CoroutineScope(SupervisorJob()),
        taskScheduler = unused(), sourceUpdatePublisher = unused(), sourceStateStore = unused(), subscriptionQueryService = unused(),
        messagePublisher = object : PluginMessagePublisher {
            override suspend fun sendBatches(
                targets: List<TargetAddress>, batches: List<MessageBatch>, renderVariant: String?, options: PluginMessagePublishOptions,
            ) = send(targets, batches, options)
        },
    )

    private inline fun <reified T> unused(): T = Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, _ ->
        error("Unused host service was called: ${T::class.simpleName}.${method.name}")
    } as T
}
