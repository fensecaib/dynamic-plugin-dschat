package top.colter.dynamic.agent.chat

import kotlinx.coroutines.*
import top.colter.dynamic.agent.agent.Agent
import top.colter.dynamic.agent.ds.*
import top.colter.dynamic.core.data.*
import top.colter.dynamic.core.plugin.PluginMessagePublishResult
import kotlin.test.*

class ChatInteractionTest {
    private fun agent(rounds: Int = 5, execute: suspend () -> String = { "已获取资料" }) = object : Agent {
        override val name = "test"
        override val maxRounds = rounds
        override val tools = listOf(Tool(function = FunctionDef("search", "search", ParametersDef("object", emptyMap()))))
        override fun shouldContinue(round: Int) = round < maxRounds
        override suspend fun prepareMessages(messages: MutableList<ChatMessage>, prompt: String, systemPrompt: String) {}
        override suspend fun executeTool(toolName: String, arguments: String, toolCallId: String) = execute()
    }
    private fun response(text: String) = Result.success(ChatResponse(choices = listOf(ChatChoice("stop", ChatMessage("assistant", text)))))
    private fun tools() = Result.success(ChatResponse(choices = listOf(ChatChoice("tool_calls", ChatMessage("assistant", toolCalls = listOf(
        ToolCall("a", function = FunctionCall("search", "{}")), ToolCall("b", function = FunctionCall("search", "{}"))))))))

    @Test fun `round exhaustion summarizes existing results without tools`() = runBlocking {
        val messages = mutableListOf(ChatMessage("user", "问题"))
        var requests = 0
        val text = runChatSearch(messages, agent(1), "model", 100, SearchBudget(5000), 1000) { request ->
            requests++
            if (requests == 1) tools() else {
                assertNull(request.tools)
                assertNull(request.toolChoice)
                assertEquals(listOf("a", "b"), request.messages.filter { it.role == "tool" }.map { it.toolCallId })
                response("依据资料的回答")
            }
        }
        assertEquals(2, requests)
        assertContains(text, "依据资料的回答")
        assertContains(text, "上限")
    }

    @Test fun `budget expiry cancels a running tool and completes remaining tool ids`() = runBlocking {
        var stopped = false
        var executed = 0
        var requests = 0
        val a = agent { executed++; try { delay(5000); "late" } finally { stopped = true } }
        val text = runChatSearch(mutableListOf(), a, "model", 100, SearchBudget(100), 1000) { request ->
            requests++
            if (requests == 1) tools() else {
                assertTrue(stopped)
                assertEquals(2, request.messages.count { it.role == "tool" })
                assertFalse(request.messages.any { it.content == "late" })
                assertNull(request.tools)
                response("有限资料答复")
            }
        }
        assertEquals(1, executed)
        assertEquals(2, requests)
        assertContains(text, "有限资料答复")
    }

    @Test fun `normal answer completes without extra summary`() = runBlocking {
        var requests = 0
        val text = runChatSearch(mutableListOf(), agent(), "model", 100, SearchBudget(1000), 1000) { requests++; response("完成") }
        assertEquals("完成", text)
        assertEquals(1, requests)
    }

    @Test fun `API failure remains a failure in initial and final model calls`() = runBlocking {
        for (rounds in listOf(1, 0)) {
            val expected = ApiCallException("server_error", 503, "AI 服务暂时异常，请稍后重试。")
            var requests = 0
            val failure = assertFailsWith<ApiCallException> {
                runChatSearch(mutableListOf(), agent(rounds), "model", 100, SearchBudget(1000), 1000) {
                    requests++
                    Result.failure(expected)
                }
            }
            assertSame(expected, failure)
            assertEquals(1, requests)
        }
    }

    @Test fun `unknown API failure discards sensitive exception text`() = runBlocking {
        val failure = assertFailsWith<ApiCallException> {
            runChatSearch(mutableListOf(), agent(), "model", 100, SearchBudget(1000), 1000) {
                Result.failure(IllegalStateException("secret-key raw-response"))
            }
        }
        assertEquals("chat_request_failed", failure.category)
        assertEquals("AI 请求失败，请稍后重试。", failure.message)
        assertNull(failure.cause)
    }

    @Test fun `cancellation inside an API failure never becomes an answer`() = runBlocking {
        val expected = CancellationException("cancelled")
        val failure = assertFailsWith<CancellationException> {
            runChatSearch(mutableListOf(), agent(), "model", 100, SearchBudget(1000), 1000) {
                Result.failure(expected)
            }
        }
        assertSame(expected, failure)
    }

    @Test fun `finalization timeout ends work with one terminal result`() = runBlocking {
        var stopped = false
        val failure = assertFailsWith<ApiCallException> {
            runChatSearch(mutableListOf(), agent(0), "model", 100, SearchBudget(1000), 30) {
                try { delay(1000); response("迟到回复") } finally { stopped = true }
            }
        }
        assertTrue(stopped)
        assertContains(failure.message!!, "本次任务已结束")
        assertFalse(failure.message!!.contains("迟到回复"))
    }

    private val reply = listOf(MessageBatch(listOf(MessageContent.Text("最终回复"))))

    @Test fun `uncertain delivery logs original reason and never sends another error`() = runBlocking {
        var sends = 0
        val logs = mutableListOf<String>()
        deliverChatOnce({ reply }, { sends++; PluginMessagePublishResult(accepted = false, message = "OneBot 未收到响应") }, logs::add)
        assertEquals(1, sends)
        assertContains(logs.single(), "OneBot 未收到响应")
    }

    @Test fun `transport exception does not cause a second send`() = runBlocking {
        var sends = 0
        val logs = mutableListOf<String>()
        deliverChatOnce({ reply }, { sends++; error("ack timeout") }, logs::add)
        assertEquals(1, sends)
        assertContains(logs.single(), "ack timeout")
    }

    @Test fun `generation failure delivers only one terminal error`() = runBlocking {
        var sends = 0
        deliverChatOnce({ error("generation failed") }, { batches ->
            sends++
            assertContains((batches.single().content.single() as MessageContent.Text).fallbackText, "生成失败")
            PluginMessagePublishResult(accepted = true)
        }, {})
        assertEquals(1, sends)
    }

    @Test fun `safe API generation failure delivers its message exactly once`() = runBlocking {
        var sends = 0
        val message = "AI 服务暂时异常（HTTP 503），请稍后重试。"
        deliverChatOnce({ throw ApiCallException("server_error", 503, message) }, { batches ->
            sends++
            assertEquals(message, (batches.single().content.single() as MessageContent.Text).fallbackText)
            PluginMessagePublishResult(accepted = true)
        }, {})
        assertEquals(1, sends)
    }

    @Test fun `unknown generation failure never exposes its exception in the reply`() = runBlocking {
        var sends = 0
        deliverChatOnce({ error("secret-key raw-response") }, { batches ->
            sends++
            assertEquals("回复生成失败，请稍后重试。", (batches.single().content.single() as MessageContent.Text).fallbackText)
            PluginMessagePublishResult(accepted = true)
        }, {})
        assertEquals(1, sends)
    }

    @Test fun `parent cancellation prevents finalization and delivery`() = runBlocking {
        var sends = 0
        var modelCalls = 0
        val started = CompletableDeferred<Unit>()
        val job = launch {
            deliverChatOnce({
                runChatSearch(mutableListOf(), agent(), "model", 100, SearchBudget(5000), 1000) {
                    modelCalls++; started.complete(Unit); delay(5000); response("迟到回复")
                }
                reply
            }, { sends++; PluginMessagePublishResult(accepted = true) }, {})
        }
        started.await()
        job.cancelAndJoin()
        assertEquals(0, sends)
        assertEquals(1, modelCalls)
    }
}
