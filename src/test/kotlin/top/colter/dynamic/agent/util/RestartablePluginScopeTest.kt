package top.colter.dynamic.agent.util

import kotlinx.coroutines.*
import kotlin.test.*

class RestartablePluginScopeTest {
    @Test fun `pause cancels an awaited dispatch and new dispatches work after resume`() = runBlocking {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(coroutineContext.minusKey(Job) + parentJob)
        val scope = RestartablePluginScope(parent)
        try {
            val entered = CompletableDeferred<Unit>()
            var finalized = false
            val dispatch = async {
                scope.run {
                    try {
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        finalized = true
                    }
                }
            }
            entered.await()
            scope.stop()
            assertFailsWith<CancellationException> { dispatch.await() }
            assertTrue(finalized)
            assertTrue(parentJob.isActive)
            assertFailsWith<CancellationException> { scope.run { fail("Paused dispatch executed") } }

            scope.start()
            assertEquals("resumed", scope.run { "resumed" })
        } finally {
            scope.stop()
            parentJob.cancelAndJoin()
        }
    }

    @Test fun `caller cancellation cancels its dispatch without cancelling sibling work`() = runBlocking {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(coroutineContext.minusKey(Job) + parentJob)
        val scope = RestartablePluginScope(parent)
        try {
            val entered = CompletableDeferred<Unit>()
            val finalized = CompletableDeferred<Unit>()
            val sibling = scope.launch { awaitCancellation() }
            val caller = launch(AgentTrace("caller-trace", enabled = false)) {
                scope.run {
                    try {
                        assertEquals("caller-trace", AgentDiagnostics.currentTraceId())
                        entered.complete(Unit)
                        awaitCancellation()
                    } finally {
                        finalized.complete(Unit)
                    }
                }
            }
            entered.await()
            caller.cancelAndJoin()
            withTimeout(1000) { finalized.await() }
            assertTrue(sibling.isActive)
            assertTrue(parentJob.isActive)
            assertEquals("available", scope.run { "available" })
        } finally {
            scope.stop()
            parentJob.cancelAndJoin()
        }
    }

    @Test fun `pause cancels in flight replies and cleanup but leaves host usable`() = runBlocking {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(coroutineContext.minusKey(Job) + parentJob)
        val scope = RestartablePluginScope(parent)
        try {
            val entered = CompletableDeferred<Unit>()
            val modelResponse = CompletableDeferred<String>()
            var replied = false
            var finalized = false
            val reply = scope.launch {
                try {
                    entered.complete(Unit)
                    modelResponse.await()
                    replied = true
                } finally {
                    finalized = true
                }
            }
            entered.await()
            val maintenance = scope.launch { awaitCancellation() }

            scope.stop()

            assertTrue(reply.isCancelled)
            assertTrue(maintenance.isCancelled)
            assertTrue(finalized)
            assertFalse(replied)
            assertTrue(parentJob.isActive)
            assertEquals("host works", parent.async { "host works" }.await())
        } finally {
            scope.stop()
            parentJob.cancelAndJoin()
        }
    }

    @Test fun `retained services cannot launch while paused and can resume with the same scope`() = runBlocking {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(coroutineContext.minusKey(Job) + parentJob)
        val scope = RestartablePluginScope(parent)
        // This reference models PluginContext copies retained by old registered handlers and services.
        val retained: CoroutineScope = scope
        try {
            assertEquals("loaded", retained.async { "loaded" }.await())
            scope.stop()
            var pausedWorkRan = false
            val paused = retained.launch { pausedWorkRan = true }
            paused.join()
            assertTrue(paused.isCancelled)
            assertFalse(pausedWorkRan)

            scope.start()
            assertEquals("resumed", retained.async { "resumed" }.await())
            val running = retained.launch { awaitCancellation() }
            scope.start()
            assertTrue(running.isActive)
            scope.stop()
            assertTrue(running.isCancelled)
        } finally {
            scope.stop()
            parentJob.cancelAndJoin()
        }
    }

    @Test fun `host cancellation also cancels the current generation and prevents resurrection`() = runBlocking {
        val parentJob = SupervisorJob()
        val parent = CoroutineScope(coroutineContext.minusKey(Job) + parentJob)
        val scope = RestartablePluginScope(parent)
        try {
            val child = scope.launch { awaitCancellation() }
            parentJob.cancelAndJoin()
            assertTrue(child.isCancelled)

            scope.start()
            var ran = false
            val restarted = scope.launch { ran = true }
            restarted.join()
            assertTrue(restarted.isCancelled)
            assertFalse(ran)
            scope.stop()
            scope.stop()
        } finally {
            scope.stop()
            parentJob.cancelAndJoin()
        }
    }
}
