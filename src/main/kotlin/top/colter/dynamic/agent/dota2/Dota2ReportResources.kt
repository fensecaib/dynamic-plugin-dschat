package top.colter.dynamic.agent.dota2

import org.jetbrains.skia.Image
import top.colter.dynamic.agent.util.AgentDiagnostics

internal class Dota2ReportAssets(val heroes: Map<Int, Image?>, val items: Map<Int, Image?>, val avatar: Image? = null) : AutoCloseable {
    override fun close() { (heroes.values + items.values + avatar).filterNotNull().forEach { it.close() } }
}

internal class GeneratedDota2Report(
    val analysis: DsAnalysisResult?, val report: Dota2MatchReport, private val assets: Dota2ReportAssets,
) : AutoCloseable {
    override fun close() = assets.close()
}

/** 仅输出阶段、比赛 ID 和耗时，不记录提示词、密钥或模型回复。并行阶段耗时不可直接相加。 */
internal suspend fun <T> measureDotaStage(matchId: Long, stage: String, mode: Dota2ReportMode? = null, block: suspend () -> T): T {
    val idField = if (stage.startsWith("overview")) "player_id" else "match_id"
    val fields = mapOf<String, Any?>(idField to matchId, "component" to "dota_report") +
        (mode?.let { mapOf("mode" to it.name.lowercase(), "thinking" to it.thinkingType) } ?: emptyMap())
    return AgentDiagnostics.stage(stage, fields, block)
}
