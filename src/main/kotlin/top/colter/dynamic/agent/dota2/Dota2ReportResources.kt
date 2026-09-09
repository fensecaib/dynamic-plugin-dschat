package top.colter.dynamic.agent.dota2

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.skia.Image

internal class Dota2ReportAssets(val heroes: Map<Int, Image?>, val items: Map<Int, Image?>) : AutoCloseable {
    override fun close() { (heroes.values + items.values).filterNotNull().forEach { it.close() } }
}

internal class GeneratedDota2Report(
    val analysis: DsAnalysisResult?, val report: Dota2MatchReport, private val assets: Dota2ReportAssets,
) : AutoCloseable {
    override fun close() = assets.close()
}

private val reportLogger = KotlinLogging.logger("Dota2ReportTiming")

/** 仅输出阶段、比赛 ID 和耗时，不记录提示词、密钥或模型回复。并行阶段耗时不可直接相加。 */
internal suspend fun <T> measureDotaStage(matchId: Long, stage: String, mode: Dota2ReportMode? = null, block: suspend () -> T): T {
    val start = System.nanoTime()
    var completed = false
    try { return block().also { completed = true } } finally {
        val ms = (System.nanoTime() - start) / 1_000_000
        reportLogger.info { "dota_report match=$matchId stage=$stage elapsed_ms=$ms completed=$completed${mode?.let { " mode=${it.name.lowercase()} thinking=${it.thinkingType}" } ?: ""}" }
    }
}
