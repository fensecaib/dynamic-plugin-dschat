package top.colter.dynamic.agent.listener

import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.Dota2ReportMode
import top.colter.dynamic.agent.dota2.reportBusyMessage
import top.colter.dynamic.agent.dota2.dotaCommandHelp
import top.colter.dynamic.agent.dota2.Dota2Service
import top.colter.dynamic.agent.dota2.OverviewMode
import top.colter.dynamic.agent.dota2.overviewAccount
import top.colter.dynamic.agent.dota2.generateOverview
import top.colter.dynamic.agent.dota2.prepareDotaTargetMatch
import top.colter.dynamic.agent.dota2.resolveDotaReportTarget
import top.colter.dynamic.agent.dota2.dotaPlayerAccount
import top.colter.dynamic.agent.dota2.historyCommandHint
import top.colter.dynamic.agent.dota2.historyTextFallback
import top.colter.dynamic.agent.dota2.measureDotaStage
import top.colter.dynamic.agent.ds.DeepSeekClient
import top.colter.dynamic.agent.draw.chatDraw
import top.colter.dynamic.agent.draw.dota2MatchDraw
import top.colter.dynamic.agent.draw.dota2HistoryDraw
import top.colter.dynamic.agent.draw.dota2FullAnalyzeDraw
import top.colter.dynamic.agent.draw.dota2OverviewDraw
import top.colter.dynamic.agent.util.cacheRenderedImage
import top.colter.dynamic.agent.util.requireAccepted
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
import kotlin.coroutines.cancellation.CancellationException

/** 仅匹配完整命令前缀，避免将 `/dsa` 识别为 `/ds`。 */
internal fun commandArgument(message: String, prefix: String): String? {
    val normalizedPrefix = prefix.trim()
    if (normalizedPrefix.isEmpty() || !message.startsWith(normalizedPrefix)) return null
    val remainder = message.substring(normalizedPrefix.length)
    if (remainder.isNotEmpty() && !remainder.first().isWhitespace()) return null
    return remainder.trim()
}

class MessageListener(
    private val config: AgentPluginConfig,
    private val chatService: ChatService,
    private val sessionManager: SessionManager,
    private val dota2Service: Dota2Service,
    private val dsClient: DeepSeekClient,
    private val grokClient: DeepSeekClient?,
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

        val baseKey = SessionManager.SessionKey(targetId = target.stableValue(), senderId = senderId)
        val dsPrefix = config.trigger.triggerPrefix.ifBlank { "/ds" }
        val grokPrefix = config.grok.triggerPrefix.ifBlank { "/grok" }
        val dsPrompt = commandArgument(msg, dsPrefix)
        val grokPrompt = commandArgument(msg, grokPrefix)
        val dotaPrompt = commandArgument(msg, "/dota")

        if (dsPrompt != null) {
            handleChatCommand(dsPrompt, baseKey, target, replyMsgId, provider = "ds", usage = "用法: $dsPrefix <问题>")
        } else if (config.grok.enabled && grokPrompt != null) {
            handleChatCommand(grokPrompt, baseKey.copy(targetId = "${baseKey.targetId}|grok"), target, replyMsgId, provider = "grok", usage = "用法: $grokPrefix <问题>")
        } else if (dotaPrompt != null) {
            handleDotaCommand(dotaPrompt, target, senderId, replyMsgId)
        } else if (msg.isNotBlank()) {
            val botId = ctx.message.botAccountId
            if (botId != null && ctx.message.mentions.contains(botId)) {
                when {
                    config.grok.enabled && config.grok.useAsAtTrigger ->
                        handleChat(msg, baseKey.copy(targetId = "${baseKey.targetId}|grok"), target, replyMsgId, provider = "grok")
                    config.trigger.enableAtTrigger && !config.grok.useAsAtTrigger ->
                        handleChat(msg, baseKey, target, replyMsgId, provider = "ds")
                }
            }
        }
    }

    private suspend fun handleChatCommand(
        prompt: String,
        sessionKey: SessionManager.SessionKey,
        target: TargetAddress,
        replyMsgId: String,
        provider: String,
        usage: String,
    ) {
        if (prompt in listOf("clear", "重置", "新建对话", "清空上下文")) {
            sessionManager.clear(sessionKey)
            sendText(target, "上下文已清空，开始新对话。", replyMsgId)
            return
        }
        if (prompt.isBlank()) {
            sendText(target, usage, replyMsgId)
            return
        }
        handleChat(prompt, sessionKey, target, replyMsgId, provider)
    }

    private suspend fun handleChat(
        prompt: String,
        sessionKey: SessionManager.SessionKey,
        target: TargetAddress,
        replyMsgId: String,
        provider: String = "ds",
    ) {
        try {
            val selectedGrokClient = grokClient
            if (provider == "grok") {
                if (!config.grok.enabled) {
                    sendText(target, "Grok 对话未启用", replyMsgId)
                    return
                }
                if (config.grok.apiKey.isBlank() || selectedGrokClient == null) {
                    sendText(target, "请先在插件配置中填写 Grok API Key", replyMsgId)
                    return
                }
            }
            sendText(target, "请稍等...", replyMsgId)
            val options = if (provider == "grok") {
                ChatService.ChatOptions(
                    client = checkNotNull(selectedGrokClient) { "Grok client was not initialized" },
                    model = config.grok.model,
                    systemPrompt = config.grok.systemPrompt.ifBlank { config.chat.systemPrompt },
                    enableWebSearch = false,
                )
            } else null
            val result = chatService.chat(prompt, sessionKey, options)

            if (result.isLong && config.chat.textReplyThreshold > 0) {
                val image = chatDraw(result.content, imageConfig, fontRegistry)
                if (image != null) {
                    sendImage(target, image, if (provider == "grok") "chat_grok.png" else "chat_reply.png")
                    return
                }
            }
            sendText(target, result.content, replyMsgId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            sendText(target, "请求失败: ${e.message ?: "未知错误"}", replyMsgId)
        }
    }

    private suspend fun handleDotaCommand(
        cmd: String, target: TargetAddress, senderId: String, replyMsgId: String
    ) {
        val parts = cmd.split(Regex("[\\s\\u3000]+")).filter { it.isNotBlank() }

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
                val img = dota2HistoryDraw(accountId, matches, imageConfig, fontRegistry, dota2Service)
                if (img != null) sendImage(target, img, "dota2_history.png")
                else sendText(target, historyTextFallback(matches), replyMsgId)
                sendText(target, historyCommandHint(matches.size, accountId), replyMsgId)
            }
            parts.isNotEmpty() && OverviewMode.fromCommand(parts[0]) != null -> dota2Service.reportTasks.runIfIdle(onBusy = { sendText(target, reportBusyMessage, replyMsgId) }) {
                try {
                    val accountId = overviewAccount(parts, dota2Service.getBinding(senderId))
                    val mode = requireNotNull(OverviewMode.fromCommand(parts[0]))
                    sendText(target, "正在生成玩家 #$accountId 的${mode.command}，请稍候。", replyMsgId)
                    dota2Service.generateOverview(accountId, mode, dsClient, config.api.model).use { generated ->
                        val image = dota2OverviewDraw(generated, imageConfig, fontRegistry)
                        sendImage(target, image, "dota2_overview_${accountId}_${mode.name.lowercase()}.png")
                        generated.analysis.notice?.let { sendText(target, it, replyMsgId) }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    sendText(target, "个人详情生成或发送失败: ${e.message ?: "未知错误"}", replyMsgId)
                }
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
            parts.isNotEmpty() && Dota2ReportMode.fromCommand(parts[0]) != null -> dota2Service.reportTasks.runIfIdle(onBusy = { sendText(target, reportBusyMessage, replyMsgId) }) {
                try {
                    val mode = requireNotNull(Dota2ReportMode.fromCommand(parts[0]))
                    val (accountId, matchId) = resolveDotaReportTarget(parts, dota2Service.getBinding(senderId)) { dota2Service.getRecentMatches(it, 10) }

                    val fetchedDetail = measureDotaStage(matchId, "detail", mode) { dota2Service.getMatchDetail(matchId) } ?: kotlin.run { sendText(target, "获取比赛详情失败", replyMsgId); return@runIfIdle }
                    val (detail, won) = prepareDotaTargetMatch(fetchedDetail, matchId, accountId)
                    sendText(target, mode.progressText(matchId), replyMsgId)
                    dota2Service.generateMatchReport(detail, accountId, dsClient, config.api.model, won, mode).use { generated ->
                        val img = measureDotaStage(matchId, "draw", mode) { dota2MatchDraw(generated.report, imageConfig, fontRegistry) }
                        if (img != null) measureDotaStage(matchId, "encode_and_send", mode) { sendImage(target, img, "dota2_report_${matchId}_${mode.name.lowercase()}.png") }
                        else sendText(target, "比赛 #$matchId (${if (won) "胜利" else "战败"})\n\n${generated.analysis?.rawResponse ?: "分析失败"}", replyMsgId)
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    sendText(target, "战报生成或发送失败: ${e.message ?: "未知错误"}", replyMsgId)
                }
            }
            else -> sendText(target, dotaCommandHelp, replyMsgId)
        }
    }

    private suspend fun sendText(target: TargetAddress, text: String, replyTo: String = "") {
        pluginContext.messagePublisher.sendText(target, text, options = PluginMessagePublishOptions(replyToMessageId = replyTo.ifEmpty { null })).requireAccepted()
    }

    private suspend fun sendImage(target: TargetAddress, image: Image, filename: String) {
        val file = cacheRenderedImage(image, cacheUtils, filename)
        val batches = listOf(MessageBatch(listOf(MessageContent.Image(fallbackText = "", image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png")))))
        pluginContext.messagePublisher.sendBatches(target, batches).requireAccepted()
    }
}
