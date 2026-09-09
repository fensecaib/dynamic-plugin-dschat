package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.sync.Mutex

internal const val reportBusyMessage = "当前有战报正在生成，请等待当前任务完成后重新发送指令。本次请求未排队。"

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
