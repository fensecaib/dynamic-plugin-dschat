package top.colter.dynamic.agent.dota2

import kotlinx.coroutines.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.ds.DeepSeekClient
import kotlin.coroutines.cancellation.CancellationException

internal class GeneratedOverview(val snapshot: OverviewSnapshot, val analysis: OverviewAnalysis, val mode: OverviewMode, val assets: Dota2ReportAssets) : AutoCloseable {
    override fun close() = assets.close()
}
private suspend fun Dota2Service.overviewAssets(snapshot: OverviewSnapshot): Dota2ReportAssets {
    val heroes = mutableMapOf<Int, Image?>(); val items = mutableMapOf<Int, Image?>()
    var avatar: Image? = null
    try {
        ensureConstants()
        for (id in snapshot.matches.map { it.heroId }.distinct()) heroes[id] = loadHeroIcon(id)
        for (id in snapshot.representatives.flatMap { it.match.items }.filter { it > 0 }.distinct()) items[id] = loadItemIcon(id)
        avatar = loadSteamAvatar(snapshot.avatarUrl)
        return Dota2ReportAssets(heroes, items, avatar)
    } catch (e: Exception) {
        (heroes.values + items.values + avatar).filterNotNull().forEach { it.close() }
        if (e is CancellationException) throw e
        return Dota2ReportAssets(emptyMap(), emptyMap())
    }
}
internal suspend fun Dota2Service.generateOverview(accountId: Long, mode: OverviewMode, client: DeepSeekClient, model: String): GeneratedOverview {
    val reportMode = if (mode == OverviewMode.DEEP) Dota2ReportMode.DEEP else Dota2ReportMode.NORMAL
    val snapshot = measureDotaStage(accountId, "overview_snapshot", reportMode) { getOverviewSnapshot(accountId) }
    var assets: Dota2ReportAssets? = null
    var transferred = false
    try {
        return coroutineScope {
            val pendingAssets = async { overviewAssets(snapshot).also { assets = it } }
            val analysis = measureDotaStage(accountId, "overview_ai", reportMode) { analyzeOverview(snapshot, mode, client, model) }
            GeneratedOverview(snapshot, analysis, mode, pendingAssets.await())
        }.also { transferred = true }
    } finally { if (!transferred) assets?.close() }
}
