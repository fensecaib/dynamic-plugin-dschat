package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.sync.Mutex

internal const val reportBusyMessage = "报告生成中，请稍后重试（不排队）。"

/** 所有聊天入口共用，忙碌时立即拒绝；异常和协程取消均释放名额。 */
internal class Dota2ReportTaskGate {
    private val mutex = Mutex()

    suspend fun <T> runIfIdle(onBusy: suspend () -> T, task: suspend () -> T): T {
        if (!mutex.tryLock()) return onBusy()
        try {
            return task()
        } finally {
            mutex.unlock()
        }
    }
}
