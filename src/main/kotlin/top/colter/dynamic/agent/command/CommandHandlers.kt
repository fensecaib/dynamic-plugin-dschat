package top.colter.dynamic.agent.command

import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.dota2.PlayerOverview
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.draw.chatDraw
import top.colter.dynamic.agent.draw.dota2MatchDraw
import top.colter.dynamic.agent.draw.dota2HistoryDraw
import top.colter.dynamic.agent.draw.dota2FullAnalyzeDraw
import top.colter.dynamic.agent.draw.dota2OverviewDraw
import top.colter.dynamic.agent.util.CacheType
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.core.command.CommandExecutionResult
import top.colter.dynamic.core.command.CommandHandler
import top.colter.dynamic.core.command.CommandInvocation
import top.colter.dynamic.core.command.CommandSpec
import top.colter.dynamic.core.data.CommandStatus
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.skiko.FontRegistry

class CommandHandlers(
    private val config: AgentPluginConfig,
    private val chatService: ChatService,
    private val sessionManager: SessionManager,
    private val dota2Service: Dota2Service,
    private val dsClient: DeepSeekClient,
    private val imageConfig: ImageConfig,
    private val pluginContext: PluginContext,
    private val cacheUtils: CacheUtils,
    private val fontRegistry: FontRegistry,
) {
    fun all(): List<CommandHandler> = listOf(DsHandler(), DotaHandler())

    private fun ok(text: String) = CommandExecutionResult(CommandStatus.SUCCESS, listOf(MessageBatch(listOf(MessageContent.Text(text)))))
    private fun fail(text: String) = CommandExecutionResult(CommandStatus.REJECTED, listOf(MessageBatch(listOf(MessageContent.Text(text)))))

    private fun imageResult(image: Image, filename: String): CommandExecutionResult {
        val data = image.encodeToData() ?: return fail("图片编码失败")
        val file = cacheUtils.cacheFile(CacheType.DRAW, filename)
        file.writeBytes(data.bytes)
        return CommandExecutionResult(CommandStatus.SUCCESS, listOf(MessageBatch(listOf(MessageContent.Image(fallbackText = "", image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png"))))))
    }

    inner class DsHandler : CommandHandler {
        override val spec = CommandSpec(path = listOf("ds"),             aliases = listOf(listOf("chat")), description = "DeepSeek AI对话")

        override suspend fun handle(inv: CommandInvocation): CommandExecutionResult {
            val prompt = inv.args.joinToString(" ").trim()
            val key = SessionManager.SessionKey(inv.context.target.stableValue(), inv.context.senderId)

            if (prompt in listOf("clear", "重置", "新建对话", "清空上下文")) { sessionManager.clear(key); return ok("上下文已清空") }
            if (prompt.isBlank()) return fail("用法: /ds <问题>")

            return try {
                val result = chatService.chat(prompt, key)
                if (result.isLong && config.chat.textReplyThreshold > 0) {
                    val img = chatDraw(result.content, imageConfig, fontRegistry)
                    if (img != null) return imageResult(img, "chat_ds.png")
                }
                ok(result.content)
            } catch (e: Exception) { fail("请求失败: ${e.message}") }
        }
    }

    inner class DotaHandler : CommandHandler {
        override val spec = CommandSpec(path = listOf("dota"),             aliases = listOf(listOf("d2")), description = "Dota2战报分析")

        override suspend fun handle(inv: CommandInvocation): CommandExecutionResult {
            val args = inv.args; val sid = inv.context.senderId
            return when {
                args.size == 2 && args[0] == "绑定" -> handleBind(args[1], sid)
                args.size == 1 && args[0] == "历史" -> handleHistory(sid)
                args.isNotEmpty() && args[0] == "个人详情" -> handleOverview(args, sid)
                args.size == 2 && args[0] == "分析" -> handleFullAnalyze(args[1])
                args.size in 1..2 && args[0] == "战报" -> handleReport(args, sid)
                else -> fail("用法: /dota 绑定|历史|战报|分析|个人详情")
            }
        }

        private suspend fun handleBind(idStr: String, senderId: String): CommandExecutionResult {
            val id = idStr.toLongOrNull() ?: return fail("无效ID"); if (id.toString().length < 7) return fail("请输入9位ID")
            val name = dota2Service.validatePlayer(id) ?: return fail("未找到该玩家")
            dota2Service.setBinding(senderId, id); return ok("已绑定: $name (ID=$id)")
        }

        private suspend fun handleHistory(senderId: String): CommandExecutionResult {
            val aid = dota2Service.getBinding(senderId) ?: return fail("请先绑定账号")
            val ms = dota2Service.getRecentMatches(aid, 10) ?: return fail("未找到对局记录")
            val ids = ms.map { it.jsonObject["match_id"]?.jsonPrimitive?.content ?: "?" }
            val img = dota2HistoryDraw(aid, ms, imageConfig, fontRegistry)
            if (img != null) return imageResult(img, "dota2_history.png")
            return ok("最近${ms.size}场: ${ids.joinToString(" ")}")
        }

        private suspend fun handleOverview(args: List<String>, senderId: String): CommandExecutionResult {
            val aid = if (args.size >= 2) args[1].toLongOrNull() else dota2Service.getBinding(senderId) ?: return fail("请先绑定账号")
            val ov = dota2Service.getPlayerOverview(aid!!) ?: return fail("获取玩家数据失败")
            val analysisText = requestOverviewAnalysis(ov)
            val worstIdx = dota2Service.selectWorstMatches(ov, 2)
            val worstMatches = worstIdx.mapNotNull { ov.recentMatches.getOrNull(it)?.jsonObject }
            val worstDetails = worstMatches.mapNotNull { m ->
                val mid = m["match_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                val detail = dota2Service.getMatchDetail(mid)
                if (detail != null) m to detail else null
            }
            val img = dota2OverviewDraw(aid!!, dota2Service, analysisText, worstDetails, imageConfig, fontRegistry)
            if (img != null) return imageResult(img, "dota2_overview.png")
            return ok(analysisText ?: "分析失败")
        }

        private suspend fun handleFullAnalyze(midStr: String): CommandExecutionResult {
            val mid = midStr.toLongOrNull() ?: return fail("无效比赛ID")
            val detail = dota2Service.getMatchDetail(mid) ?: return fail("获取比赛详情失败")
            val dual = dota2Service.analyzeFullMatch(detail, dsClient, config.api.model)
            val report = dota2Service.buildFullReport(detail, dual)
            val img = dota2FullAnalyzeDraw(report, imageConfig, fontRegistry)
            if (img != null) return imageResult(img, "dota2_analyze_$mid.png")
            val sb = StringBuilder()
            sb.appendLine("天辉: ${report.radiantMvp}\n天辉战犯: ${report.radiantCriminal}")
            sb.appendLine("夜魇: ${report.direMvp}\n夜魇战犯: ${report.direCriminal}")
            return ok(sb.toString())
        }

        private suspend fun handleReport(args: List<String>, senderId: String): CommandExecutionResult {
            val aid = dota2Service.getBinding(senderId) ?: return fail("请先绑定账号")
            val mid = args.getOrNull(1)?.toLongOrNull() ?: run {
                val ms = dota2Service.getRecentMatches(aid, 1); if (ms.isNullOrEmpty()) return fail("未找到最近对局")
                ms[0].jsonObject["match_id"]?.jsonPrimitive?.long ?: return fail("无法获取比赛ID")
            }
            val detail = dota2Service.getMatchDetail(mid) ?: return fail("获取比赛详情失败")
            val players = detail["players"]?.jsonArray
            if (players == null || players.none { it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == aid }) return fail("该比赛中未找到你的账号")
            val me = players.find { it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == aid }!!
            val won = ((me.jsonObject["isRadiant"]?.jsonPrimitive?.boolean ?: false) == (detail["radiant_win"]?.jsonPrimitive?.boolean ?: false))
            val dsResult = dota2Service.analyzeMatch(aid, detail, dsClient, config.api.model)
            val report = dota2Service.buildReport(detail, aid, dsResult, won)
            val img = dota2MatchDraw(report, imageConfig, fontRegistry)
            if (img != null) return imageResult(img, "dota2_report_$mid.png")
            return ok("比赛 #$mid (${if (won) "胜利" else "战败"})\n\n${dsResult?.rawResponse ?: "分析失败"}")
        }

        private suspend fun requestOverviewAnalysis(ov: PlayerOverview): String? {
            val data = dota2Service.buildAnalysisData(ov)
            val call1Msg = listOf(top.colter.dynamic.agent.ds.ChatMessage("system", "你是Dota2赛后分析师。"), top.colter.dynamic.agent.ds.ChatMessage("user", "数据:\n$data"))
            val call1Req = top.colter.dynamic.agent.ds.ChatRequest(model = config.api.model, messages = call1Msg, thinking = top.colter.dynamic.agent.ds.ThinkingConfig(type = "enabled"), maxTokens = 4096)
            val part1 = dsClient.chat(call1Req).getOrNull()?.choices?.firstOrNull()?.message?.content ?: return null
            val call2Msg = listOf(top.colter.dynamic.agent.ds.ChatMessage("system", "你是Dota2主教练。"), top.colter.dynamic.agent.ds.ChatMessage("user", "分析:\n$part1\n\n数据:\n$data"))
            val call2Req = top.colter.dynamic.agent.ds.ChatRequest(model = config.api.model, messages = call2Msg, thinking = top.colter.dynamic.agent.ds.ThinkingConfig(type = "enabled"), maxTokens = 2048)
            val part2 = dsClient.chat(call2Req).getOrNull()?.choices?.firstOrNull()?.message?.content ?: return part1
            return "$part1\n\n$part2"
        }
    }
}
