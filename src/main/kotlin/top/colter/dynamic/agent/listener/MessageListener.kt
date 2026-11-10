package top.colter.dynamic.agent.listener

import org.jetbrains.skia.Image
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.PRO_CHAT_MODEL
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.config.chatAtAvailable
import top.colter.dynamic.agent.config.chatCommandUnavailable
import top.colter.dynamic.agent.config.dotaCommandUnavailable
import top.colter.dynamic.agent.dota2.Dota2ReportMode
import top.colter.dynamic.agent.dota2.reportBusyMessage
import top.colter.dynamic.agent.dota2.dotaCommandHelp
import top.colter.dynamic.agent.dota2.OpenDotaApiException
import top.colter.dynamic.agent.dota2.DotaAnalysisUnavailableException
import top.colter.dynamic.agent.dota2.compactDotaCommand
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.dota2.OverviewMode
import top.colter.dynamic.agent.dota2.overviewAccount
import top.colter.dynamic.agent.dota2.generateOverview
import top.colter.dynamic.agent.dota2.prepareDotaTargetMatch
import top.colter.dynamic.agent.dota2.resolveDotaReportTarget
import top.colter.dynamic.agent.dota2.dotaPlayerAccount
import top.colter.dynamic.agent.dota2.dotaQueryRequiresBinding
import top.colter.dynamic.agent.dota2.hasDotaBinding
import top.colter.dynamic.agent.dota2.dotaBindingRequired
import top.colter.dynamic.agent.dota2.historyTextFallback
import top.colter.dynamic.agent.dota2.measureDotaStage
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.chat.prepareChatReply
import top.colter.dynamic.agent.chat.launchChatReply
import top.colter.dynamic.agent.draw.dota2MatchDraw
import top.colter.dynamic.agent.draw.dota2HistoryDraw
import top.colter.dynamic.agent.draw.dota2FullAnalyzeDraw
import top.colter.dynamic.agent.draw.dota2OverviewDraw
import top.colter.dynamic.agent.util.cacheRenderedImage
import top.colter.dynamic.agent.util.CacheUtils
import top.colter.dynamic.agent.util.AgentDiagnostics
import top.colter.dynamic.agent.util.AgentTrace
import top.colter.dynamic.agent.util.dotaAction
import top.colter.dynamic.agent.util.sendAgentBatches
import top.colter.dynamic.agent.util.deliverAgentBatchesOnce
import top.colter.dynamic.agent.util.withPreferredAccount
import top.colter.dynamic.core.data.MediaKind
import top.colter.dynamic.core.data.MediaRef
import top.colter.dynamic.core.data.MessageBatch
import top.colter.dynamic.core.data.MessageContent
import top.colter.dynamic.core.data.TargetAddress
import top.colter.dynamic.core.data.IncomingMessageSegment
import top.colter.dynamic.core.plugin.IncomingMessageDispatchContext
import top.colter.dynamic.core.plugin.IncomingMessageIntent
import top.colter.dynamic.core.plugin.PluginContext
import top.colter.skiko.FontRegistry
import kotlin.coroutines.cancellation.CancellationException

/** 仅匹配完整命令前缀，避免将 `/dsa` 识别为 `/ds`。 */
internal fun commandArgument(message: String, prefix: String, ignoreCase: Boolean = false): String? {
    val normalizedPrefix = prefix.trim()
    if (normalizedPrefix.isEmpty() || !message.startsWith(normalizedPrefix, ignoreCase)) return null
    val remainder = message.substring(normalizedPrefix.length)
    if (remainder.isNotEmpty() && !remainder.first().isWhitespace()) return null
    return remainder.trim()
}

/** Some adapters retain the visible @ in text; structured segments identify the actual recipient. */
internal fun mentionProArgument(ctx: IncomingMessageDispatchContext): String? {
    val message = ctx.message
    val botId = message.botAccountId ?: return null
    if (botId !in message.mentions) return null
    val text = if (message.segments.any { it is IncomingMessageSegment.Mention && it.targetId == botId && !it.all }) {
        var leading = true
        buildString {
            for (segment in message.segments) when (segment) {
                is IncomingMessageSegment.Text -> {
                    append(segment.text)
                    if (segment.text.isNotBlank()) leading = false
                }
                is IncomingMessageSegment.Link -> {
                    append(' ').append(segment.url).append(' ')
                    leading = false
                }
                is IncomingMessageSegment.Mention -> {
                    if (leading && segment.targetId == botId && !segment.all) append(' ')
                    else {
                        append(" @").append(segment.targetId).append(' ')
                        leading = false
                    }
                }
                else -> Unit
            }
        }
    } else ctx.rawText.ifBlank { message.text }
    return commandArgument(text.trimStart(), "/pro", ignoreCase = true)
}

class MessageListener(
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
    suspend fun handle(ctx: IncomingMessageDispatchContext) {
        val atAvailable = config.chatAtAvailable()
        val mentionProPrompt = if (atAvailable) mentionProArgument(ctx) else null
        // Only @Bot /pro belongs to the listener when the host classifies it as Command.
        if (ctx.intent == IncomingMessageIntent.Command && mentionProPrompt == null) return

        val msg = ctx.rawText.ifBlank { ctx.message.text }
        val target = ctx.message.target.withPreferredAccount(ctx.message.botAccountId)
        val senderId = ctx.message.senderId
        val replyMsgId = ctx.replyToMessageId

        val baseKey = SessionManager.SessionKey(targetId = target.stableValue(), senderId = senderId)
        if (mentionProPrompt != null) {
            if (mentionProPrompt.isBlank()) sendText(target, "用法: @Bot /pro <问题>", replyMsgId)
            else handleChat(mentionProPrompt, baseKey, target, replyMsgId, model = PRO_CHAT_MODEL)
            return
        }

        val dsPrefix = config.trigger.triggerPrefix.ifBlank { "/ds" }
        val proPrompt = commandArgument(msg, "/dspro")
        val dsPrompt = proPrompt ?: commandArgument(msg, dsPrefix) ?: commandArgument(msg, "/chat")
        val dotaPrompt = commandArgument(msg, "/dota") ?: commandArgument(msg, "/d2") ?: compactDotaCommand(msg)

        if (dsPrompt != null) {
            kotlinx.coroutines.withContext(AgentTrace.create("chat_ds", target.platformId.value, config.diagnostics)) {
                handleChatCommand(dsPrompt, baseKey, target, replyMsgId,
                    usage = "用法: ${if (proPrompt != null) "/dspro" else dsPrefix} <问题>",
                    model = if (proPrompt != null) PRO_CHAT_MODEL else config.api.model)
            }
        } else if (dotaPrompt != null) {
            handleDotaCommand(dotaPrompt, target, senderId, replyMsgId)
        } else if (msg.isNotBlank()) {
            val botId = ctx.message.botAccountId
            if (botId != null && ctx.message.mentions.contains(botId) && atAvailable) {
                handleChat(msg, baseKey, target, replyMsgId)
            }
        }
    }

    private suspend fun handleChatCommand(
        prompt: String,
        sessionKey: SessionManager.SessionKey,
        target: TargetAddress,
        replyMsgId: String,
        usage: String,
        model: String = config.api.model,
    ) {
        config.chatCommandUnavailable()?.let {
            sendText(target, it, replyMsgId)
            return
        }
        if (prompt in listOf("clear", "重置", "新建对话", "清空上下文")) {
            sessionManager.clear(sessionKey)
            sendText(target, "上下文已清空，开始新对话。", replyMsgId)
            return
        }
        if (prompt.isBlank()) {
            sendText(target, usage, replyMsgId)
            return
        }
        handleChat(prompt, sessionKey, target, replyMsgId, model)
    }

    private suspend fun handleChat(
        prompt: String,
        sessionKey: SessionManager.SessionKey,
        target: TargetAddress,
        replyMsgId: String,
        model: String = config.api.model,
    ) {
        pluginContext.launchChatReply(target, replyMsgId, AgentTrace.create("chat_ds", target.platformId.value, config.diagnostics)) {
            val result = chatService.chat(prompt, sessionKey, model)
            prepareChatReply(result.content, config.chat.textReplyThreshold, imageConfig, fontRegistry, cacheUtils, platformId = target.platformId.value)
        }
    }

    private suspend fun handleDotaCommand(
        cmd: String, target: TargetAddress, senderId: String, replyMsgId: String
    ) {
        AgentDiagnostics.request(dotaAction(cmd.trim().split(Regex("[\\s\\u3000]+"), limit = 2).firstOrNull()), target.platformId.value, config.diagnostics) {
            try {
                dispatchDotaCommand(cmd, target, senderId, replyMsgId)
            } catch (e: OpenDotaApiException) {
                AgentDiagnostics.event("agent_data", "failed", mapOf("error_type" to e.javaClass.simpleName), warning = true)
                sendText(target, e.userMessage, replyMsgId)
            } catch (e: DotaAnalysisUnavailableException) {
                AgentDiagnostics.event("agent_analysis", "unavailable", mapOf("category" to e.category), warning = true)
                sendText(target, e.userMessage, replyMsgId)
            }
        }
    }

    private suspend fun dispatchDotaCommand(
        cmd: String, target: TargetAddress, senderId: String, replyMsgId: String
    ) {
        val parts = cmd.split(Regex("[\\s\\u3000]+")).filter { it.isNotBlank() }

        config.dotaCommandUnavailable(parts.firstOrNull())?.let {
            sendText(target, it, replyMsgId)
            return
        }

        if (dotaQueryRequiresBinding(parts.firstOrNull()) && !hasDotaBinding(dota2Service.getBinding(senderId))) {
            sendText(target, dotaBindingRequired, replyMsgId); return
        }

        when {
            parts.size == 2 && parts[0] == "绑定" -> {
                val id = parts[1].toLongOrNull()
                if (id == null || id.toString().length < 7) { sendText(target, "请输入有效的9位数字ID", replyMsgId); return }
                val name = dota2Service.validatePlayer(id)
                if (name == null) { sendText(target, "未找到该玩家 (ID=$id)", replyMsgId); return }
                dota2Service.setBinding(senderId, id)
                sendText(target, "已绑定: $name (ID=$id)", replyMsgId)
            }
            parts.size in 1..2 && parts[0] == "历史" -> {
                val accountId = try { dotaPlayerAccount(parts.getOrNull(1), dota2Service.getBinding(senderId)) } catch (e: IllegalArgumentException) { sendText(target, e.message ?: "参数无效", replyMsgId); return }
                val matches = dota2Service.getRecentMatches(accountId, 10)
                if (matches.isNullOrEmpty()) { sendText(target, "未找到对局记录", replyMsgId); return }
                val img = AgentDiagnostics.stage("draw_history") { dota2HistoryDraw(accountId, matches, imageConfig, fontRegistry, dota2Service) }
                if (img != null) {
                    if (!sendImage(target, img, "dota2_history.png", replyMsgId)) return
                } else sendText(target, historyTextFallback(matches), replyMsgId)
            }
            parts.isNotEmpty() && OverviewMode.fromCommand(parts[0]) != null -> dota2Service.reportTasks.runIfIdle(onBusy = { sendText(target, reportBusyMessage, replyMsgId) }) {
                try {
                    val accountId = overviewAccount(parts, dota2Service.getBinding(senderId))
                    val mode = requireNotNull(OverviewMode.fromCommand(parts[0]))
                    sendText(target, "正在生成玩家 #$accountId 的${mode.command}，请稍候。", replyMsgId)
                    dota2Service.generateOverview(accountId, mode, dsClient, config.api.model).use { generated ->
                        val image = AgentDiagnostics.stage("draw_overview") { dota2OverviewDraw(generated, imageConfig, fontRegistry) }
                        if (sendImage(target, image, "dota2_overview_${accountId}_${mode.name.lowercase()}.png", replyMsgId)) {
                            generated.analysis.notice?.let { sendFinalText(target, it, replyMsgId) }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || e is OpenDotaApiException || e is DotaAnalysisUnavailableException) throw e
                    sendText(target, if (e is IllegalArgumentException) e.message ?: "参数无效" else "个人详情生成失败，请稍后重试。", replyMsgId)
                }
            }
            parts.size == 2 && parts[0] == "分析" -> dota2Service.reportTasks.runIfIdle(onBusy = { sendText(target, reportBusyMessage, replyMsgId) }) {
                val matchId = parts[1].toLongOrNull()?.takeIf { it > 0 }
                if (matchId == null) { sendText(target, "无效比赛ID", replyMsgId); return@runIfIdle }
                try {
                    sendText(target, "正在分析比赛 #$matchId ...", replyMsgId)
                    val detail = dota2Service.getMatchDetail(matchId) ?: run { sendText(target, "获取比赛详情失败", replyMsgId); return@runIfIdle }
                    dota2Service.generateFullReport(detail, dsClient, config.api.model).use { generated ->
                        val report = generated.report
                        val img = AgentDiagnostics.stage("draw_full_analysis") { dota2FullAnalyzeDraw(report, imageConfig, fontRegistry) }
                        if (img != null) sendImage(target, img, "dota2_analyze_$matchId.png", replyMsgId)
                        else sendFinalText(target, "天辉 MVP/SVP: ${report.radiantMvp}\n${report.radiantMvpReason}\n天辉 战犯: ${report.radiantCriminal}\n${report.radiantCriminalReason}\n夜魇 MVP/SVP: ${report.direMvp}\n${report.direMvpReason}\n夜魇 战犯: ${report.direCriminal}\n${report.direCriminalReason}", replyMsgId)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || e is OpenDotaApiException || e is DotaAnalysisUnavailableException) throw e
                    sendText(target, "全场分析生成失败，请稍后重试。", replyMsgId)
                }
            }
            parts.isNotEmpty() && Dota2ReportMode.fromCommand(parts[0]) != null -> dota2Service.reportTasks.runIfIdle(onBusy = { sendText(target, reportBusyMessage, replyMsgId) }) {
                try {
                    val mode = requireNotNull(Dota2ReportMode.fromCommand(parts[0]))
                    val (accountId, matchId) = resolveDotaReportTarget(parts, dota2Service.getBinding(senderId)) { dota2Service.getRecentMatches(it, 10) }

                    val fetchedDetail = measureDotaStage(matchId, "detail", mode) { dota2Service.getMatchDetail(matchId) } ?: kotlin.run { sendText(target, "获取比赛详情失败", replyMsgId); return@runIfIdle }
                    val (detail, won) = prepareDotaTargetMatch(fetchedDetail, matchId, accountId)
                    sendText(target, mode.progressText(matchId), replyMsgId)
                    dota2Service.generateMatchReport(detail, accountId, dsClient, config.api.model, won, mode).use { generated ->
                        val img = measureDotaStage(matchId, "draw", mode) { dota2MatchDraw(generated.report, imageConfig, fontRegistry) }
                        if (img != null) measureDotaStage(matchId, "encode_and_send", mode) { sendImage(target, img, "dota2_report_${matchId}_${mode.name.lowercase()}.png", replyMsgId) }
                        else sendFinalText(target, "比赛 #$matchId (${if (won) "胜利" else "战败"})\n\n${generated.analysis?.rawResponse ?: "AI 分析暂不可用"}", replyMsgId)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || e is OpenDotaApiException || e is DotaAnalysisUnavailableException) throw e
                    sendText(target, if (e is IllegalArgumentException) e.message ?: "参数无效" else "战报生成失败，请稍后重试。", replyMsgId)
                }
            }
            else -> sendText(target, dotaCommandHelp, replyMsgId)
        }
    }

    private suspend fun sendText(target: TargetAddress, text: String, replyTo: String = "") {
        pluginContext.sendAgentBatches(target, listOf(MessageBatch(listOf(MessageContent.Text(text)))), replyTo, "text")
    }

    private suspend fun sendFinalText(target: TargetAddress, text: String, replyTo: String) {
        pluginContext.deliverAgentBatchesOnce(target, listOf(MessageBatch(listOf(MessageContent.Text(text)))), replyTo)
    }

    private suspend fun sendImage(target: TargetAddress, image: Image, filename: String, replyTo: String = ""): Boolean {
        val file = AgentDiagnostics.stage("encode_image") { cacheRenderedImage(image, cacheUtils, filename) }
        val batches = listOf(MessageBatch(listOf(MessageContent.Image(fallbackText = "", image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png")))))
        return pluginContext.deliverAgentBatchesOnce(target, batches, replyTo)
    }
}
