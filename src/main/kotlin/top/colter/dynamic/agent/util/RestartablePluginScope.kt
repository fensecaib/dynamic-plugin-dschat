package top.colter.dynamic.agent.util

import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.CoroutineContext

/** Keeps background replies inside a lifecycle that can pause without cancelling the host scope. */
internal class RestartablePluginScope(parent: CoroutineScope) : CoroutineScope {
    private val parentJob = parent.coroutineContext[Job]
    private val inheritedContext = parent.coroutineContext.minusKey(Job)
    @Volatile private var workJob: CompletableJob = SupervisorJob(parentJob)

    // Services retain this scope across configuration changes and stop/start cycles.
    override val coroutineContext: CoroutineContext
        get() = inheritedContext + workJob

    @Synchronized
    fun start() {
        if (!workJob.isActive) workJob = SupervisorJob(parentJob)
    }

    /** A dispatch belongs to this lifecycle while retaining its caller's trace and cancellation. */
    suspend fun <T> run(block: suspend () -> T): T {
        val callerContext = currentCoroutineContext()
        callerContext.ensureActive()
        val task = async(callerContext.minusKey(Job)) { block() }
        return try {
            task.await()
        } finally {
            task.cancel()
        }
    }

    suspend fun stop() {
        workJob.cancelAndJoin()
    }
}
