package top.colter.dynamic.agent.listener

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
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.dynamic.core.plugin.PluginMessagePublishOptions
import top.colter.skiko.FontRegistry
import java.io.File
import java.nio.file.Path

class MessageListener(
    private val config: AgentPluginConfig,
    private val chatService: ChatService,
    private val sessionManager: SessionManager,
    private val dota2Service: Dota2Service,
    private val dsClient: DeepSeekClient,
    private val imageConfig: ImageConfig,
    private val pluginContext: PluginContext,
    private val dataDir: Path,
    private val cacheUtils: CacheUtils,
    private val fontRegistry: FontRegistry,
) {
    suspend fun handle(ctx: IncomingMessageDispatchContext) {
        val msg = ctx.rawText.ifBlank { ctx.message.text }
        val target = ctx.message.target
        val senderId = ctx.message.senderId
        val replyMsgId = ctx.replyToMessageId

        val sessionKey = SessionManager.SessionKey(targetId = target.stableValue(), senderId = senderId)

        if (msg.startsWith("/ds")) {
            val prompt = msg.removePrefix("/ds").trim()
            if (prompt in listOf("clear", "重置", "新建对话", "清空上下文")) {
                sessionManager.clear(sessionKey)
                sendText(target, "上下文已清空，开始新对话。", replyMsgId)
                return
            }
            if (prompt.isBlank()) {
                sendText(target, "用法: /ds <问题>", replyMsgId)
                return
            }
            handleChat(prompt, sessionKey, target, replyMsgId)
        } else if (msg.startsWith("/dota")) {
            handleDotaCommand(msg.removePrefix("/dota").trim(), target, senderId, replyMsgId)
        } else if (msg.isNotBlank() && config.trigger.enableAtTrigger) {
            val botId = ctx.message.botAccountId
            if (botId != null && ctx.message.mentions.contains(botId)) {
                handleChat(msg, sessionKey, target, replyMsgId)
            }
        }
    }

    private suspend fun handleChat(
        prompt: String, sessionKey: SessionManager.SessionKey,
        target: TargetAddress, replyMsgId: String
    ) {
        try {
            sendText(target, "请稍等...", replyMsgId)
            val result = chatService.chat(prompt, sessionKey)

            if (result.isLong && config.chat.textReplyThreshold > 0) {
                val image = chatDraw(result.content, imageConfig, fontRegistry)
                if (image != null) {
                    sendImage(target, image, "chat_reply.png")
                    return
                }
            }
            sendText(target, result.content, replyMsgId)
        } catch (e: Exception) {
            sendText(target, "请求失败: ${e.message ?: "未知错误"}", replyMsgId)
        }
    }

    private suspend fun handleDotaCommand(
        cmd: String, target: TargetAddress, senderId: String, replyMsgId: String
    ) {
        val parts = cmd.split(" ", "\u3000").filter { it.isNotBlank() }

        when {
            parts.size == 2 && parts[0] == "绑定" -> {
                val id = parts[1].toLongOrNull()
                if (id == null || id.toString().length < 7) { sendText(target, "请输入有效的9位数字ID", replyMsgId); return }
                val name = dota2Service.validatePlayer(id)
                if (name == null) { sendText(target, "未找到该玩家 (ID=$id)", replyMsgId); return }
                dota2Service.setBinding(senderId, id)
                sendText(target, "已绑定: $name (ID=$id)", replyMsgId)
            }
            parts.size == 1 && parts[0] == "历史" -> {
                val accountId = dota2Service.getBinding(senderId) ?: kotlin.run { sendText(target, "请先绑定账号", replyMsgId); return }
                val matches = dota2Service.getRecentMatches(accountId, 10)
                if (matches.isNullOrEmpty()) { sendText(target, "未找到对局记录", replyMsgId); return }
                val ids = matches.map { it.jsonObject["match_id"]?.jsonPrimitive?.content ?: "?" }
                sendText(target, "最近${matches.size}场: ${ids.joinToString(" ")}", replyMsgId)
                val img = dota2HistoryDraw(accountId, matches, imageConfig, fontRegistry)
                if (img != null) sendImage(target, img, "dota2_history.png")
            }
            parts.isNotEmpty() && parts[0] == "个人详情" -> {
                val accountId = if (parts.size >= 2) parts[1].toLongOrNull() else dota2Service.getBinding(senderId)
                    ?: kotlin.run { sendText(target, "请先绑定账号", replyMsgId); return }
                val ov = dota2Service.getPlayerOverview(accountId!!) ?: kotlin.run { sendText(target, "获取玩家数据失败", replyMsgId); return }
                sendText(target, "正在分析中，约需15秒...", replyMsgId)
                val analysisText = requestOverviewAnalysis(ov)
                val worstIdx = dota2Service.selectWorstMatches(ov, 2)
                val worstMatches = worstIdx.mapNotNull { ov.recentMatches.getOrNull(it)?.jsonObject }
                val worstDetails = worstMatches.mapNotNull { m ->
                    val mid = m["match_id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
                    val detail = dota2Service.getMatchDetail(mid)
                    if (detail != null) m to detail else null
                }
                val img = dota2OverviewDraw(accountId!!, dota2Service, analysisText, worstDetails, imageConfig, fontRegistry)
                if (img != null) sendImage(target, img, "dota2_overview.png")
                else sendText(target, analysisText ?: "分析失败", replyMsgId)
            }
            parts.size == 2 && parts[0] == "分析" -> {
                val matchId = parts[1].toLongOrNull() ?: return
                sendText(target, "正在分析比赛 #$matchId ...", replyMsgId)
                val detail = dota2Service.getMatchDetail(matchId) ?: kotlin.run { sendText(target, "获取比赛详情失败", replyMsgId); return }
                val dualResult = dota2Service.analyzeFullMatch(detail, dsClient, config.api.model)
                val report = dota2Service.buildFullReport(detail, dualResult)
                val img = dota2FullAnalyzeDraw(report, imageConfig, fontRegistry)
                if (img != null) sendImage(target, img, "dota2_analyze_$matchId.png")
                else {
                    val sb = StringBuilder()
                    sb.appendLine("天辉 MVP/SVP: ${report.radiantMvp}\n${report.radiantMvpReason}")
                    sb.appendLine("天辉 战犯: ${report.radiantCriminal}\n${report.radiantCriminalReason}")
                    sb.appendLine("夜魇 MVP/SVP: ${report.direMvp}\n${report.direMvpReason}")
                    sb.appendLine("夜魇 战犯: ${report.direCriminal}\n${report.direCriminalReason}")
                    sendText(target, sb.toString(), replyMsgId)
                }
            }
            parts.size in 1..2 && parts[0] == "战报" -> {
                val accountId = dota2Service.getBinding(senderId) ?: kotlin.run { sendText(target, "请先绑定账号", replyMsgId); return }
                val matchId: Long = parts.getOrNull(1)?.toLongOrNull() ?: kotlin.run {
                    val ms = dota2Service.getRecentMatches(accountId, 1)
                    if (ms.isNullOrEmpty()) return@run null
                    ms[0].jsonObject["match_id"]?.jsonPrimitive?.long
                } ?: kotlin.run { sendText(target, "未找到最近对局", replyMsgId); return }

                try {
                    val detail = dota2Service.getMatchDetail(matchId) ?: kotlin.run { sendText(target, "获取比赛详情失败", replyMsgId); return }
                    val players = detail["players"]?.jsonArray
                    if (players == null || players.none { it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == accountId }) {
                        sendText(target, "该比赛中未找到你的账号", replyMsgId); return
                    }
                    val me = players.find { it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == accountId }!!
                    val isRadiant = me.jsonObject["isRadiant"]?.jsonPrimitive?.boolean ?: false
                    val radiantWin = detail["radiant_win"]?.jsonPrimitive?.boolean ?: false
                    val won = (isRadiant == radiantWin)
                    sendText(target, "正在分析比赛 #$matchId ...", replyMsgId)
                    val dsResult = dota2Service.analyzeMatch(accountId, detail, dsClient, config.api.model)
                    val report = dota2Service.buildReport(detail, accountId, dsResult, won)
                    val img = dota2MatchDraw(report, imageConfig, fontRegistry)
                    if (img != null) sendImage(target, img, "dota2_report_$matchId.png")
                    else sendText(target, "比赛 #$matchId (${if (won) "胜利" else "战败"})\n\n${dsResult?.rawResponse ?: "分析失败"}", replyMsgId)
                } catch (e: Exception) {
                    sendText(target, "战报生成失败: ${e.message}", replyMsgId)
                }
            }
            else -> sendText(target, "用法:\n/dota 绑定 <9位ID>\n/dota 历史\n/dota 战报\n/dota 战报 <比赛ID>\n/dota 分析 <比赛ID>\n/dota 个人详情", replyMsgId)
        }
    }

    private suspend fun requestOverviewAnalysis(ov: PlayerOverview): String? {
        val data = dota2Service.buildAnalysisData(ov)
        val sys1 = "你是Dota2赛后分析师。基于近10局数据输出分析。"
        val call1Msg = listOf(top.colter.dynamic.agent.ds.ChatMessage("system", sys1), top.colter.dynamic.agent.ds.ChatMessage("user", "玩家数据:\n$data"))
        val call1Req = top.colter.dynamic.agent.ds.ChatRequest(model = config.api.model, messages = call1Msg, thinking = top.colter.dynamic.agent.ds.ThinkingConfig(type = "enabled"), maxTokens = 4096)
        val part1 = dsClient.chat(call1Req).getOrNull()?.choices?.firstOrNull()?.message?.content ?: return null
        val sys2 = "你是Dota2主教练。请写出最终诊断总结。"
        val call2Msg = listOf(top.colter.dynamic.agent.ds.ChatMessage("system", sys2), top.colter.dynamic.agent.ds.ChatMessage("user", "分析:\n$part1\n\n数据:\n$data"))
        val call2Req = top.colter.dynamic.agent.ds.ChatRequest(model = config.api.model, messages = call2Msg, thinking = top.colter.dynamic.agent.ds.ThinkingConfig(type = "enabled"), maxTokens = 2048)
        val part2 = dsClient.chat(call2Req).getOrNull()?.choices?.firstOrNull()?.message?.content ?: return part1
        return "$part1\n\n$part2"
    }

    private suspend fun sendText(target: TargetAddress, text: String, replyTo: String = "") {
        pluginContext.messagePublisher.sendText(target, text, options = PluginMessagePublishOptions(replyToMessageId = replyTo.ifEmpty { null }))
    }

    private suspend fun sendImage(target: TargetAddress, image: Image, filename: String) {
        val data = image.encodeToData() ?: return
        val file = cacheUtils.cacheFile(CacheType.DRAW, filename)
        file.writeBytes(data.bytes)
        val batches = listOf(MessageBatch(listOf(MessageContent.Image(fallbackText = "", image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png")))))
        pluginContext.messagePublisher.sendBatches(target, batches)
    }
}
