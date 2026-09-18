package top.colter.dynamic.agent.command

import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.dota2.Dota2ReportMode
import top.colter.dynamic.agent.dota2.reportBusyMessage
import top.colter.dynamic.agent.dota2.dotaCommandHelp
import top.colter.dynamic.agent.dota2.OpenDotaApiException
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
import kotlin.coroutines.cancellation.CancellationException

class CommandHandlers(
    private val config: AgentPluginConfig,
    private val chatService: ChatService,
    private val sessionManager: SessionManager,
    private val dota2Service: Dota2Service,
    private val dsClient: DeepSeekClient,
    private val grokClient: DeepSeekClient?,
    private val imageConfig: ImageConfig,
    private val pluginContext: PluginContext,
    private val cacheUtils: CacheUtils,
    private val fontRegistry: FontRegistry,
) {
    fun all(): List<CommandHandler> = buildList {
        add(DsHandler())
        if (config.grok.enabled) add(GrokHandler())
        add(DotaHandler())
    }

    private fun ok(text: String) = CommandExecutionResult(CommandStatus.SUCCESS, listOf(MessageBatch(listOf(MessageContent.Text(text)))))
    private fun fail(text: String) = CommandExecutionResult(CommandStatus.REJECTED, listOf(MessageBatch(listOf(MessageContent.Text(text)))))

    private fun imageResult(image: Image, filename: String, hint: String? = null): CommandExecutionResult {
        val file = cacheRenderedImage(image, cacheUtils, filename)
        val batches = mutableListOf(MessageBatch(listOf(MessageContent.Image(fallbackText = "", image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png")))))
        if (hint != null) batches.add(MessageBatch(listOf(MessageContent.Text(hint))))
        return CommandExecutionResult(CommandStatus.SUCCESS, batches)
    }

    private suspend fun handleLlmChat(
        prompt: String,
        key: SessionManager.SessionKey,
        provider: String,
        usage: String,
    ): CommandExecutionResult {
        if (prompt in listOf("clear", "重置", "新建对话", "清空上下文")) {
            sessionManager.clear(key)
            return ok("上下文已清空")
        }
        if (prompt.isBlank()) return fail(usage)
        return try {
            val options = if (provider == "grok") {
                if (config.grok.apiKey.isBlank() || grokClient == null) {
                    return fail("请先在插件配置中填写 Grok API Key")
                }
                ChatService.ChatOptions(
                    client = grokClient,
                    model = config.grok.model,
                    systemPrompt = config.grok.systemPrompt.ifBlank { config.chat.systemPrompt },
                    enableWebSearch = false,
                )
            } else null
            val result = chatService.chat(prompt, key, options)
            if (result.isLong && config.chat.textReplyThreshold > 0) {
                val img = chatDraw(result.content, imageConfig, fontRegistry)
                if (img != null) return imageResult(img, if (provider == "grok") "chat_grok.png" else "chat_ds.png")
            }
            ok(result.content)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            fail("请求失败: ${e.message}")
        }
    }

    inner class DsHandler : CommandHandler {
        override val spec = CommandSpec(
            path = listOf(config.trigger.triggerPrefix.removePrefix("/").ifBlank { "ds" }),
            aliases = listOf(listOf("chat")),
            description = "DeepSeek AI对话",
        )

        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            val prompt = invocation.args.joinToString(" ").trim()
            val key = SessionManager.SessionKey(invocation.context.target.stableValue(), invocation.context.senderId)
            return handleLlmChat(prompt, key, "ds", "用法: ${config.trigger.triggerPrefix.ifBlank { "/ds" }} <问题>")
        }
    }

    inner class GrokHandler : CommandHandler {
        override val spec = CommandSpec(
            path = listOf(config.grok.triggerPrefix.removePrefix("/").ifBlank { "grok" }),
            aliases = emptyList(),
            description = "Grok AI对话",
        )

        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            if (!config.grok.enabled) return fail("Grok 对话未启用")
            val prompt = invocation.args.joinToString(" ").trim()
            val key = SessionManager.SessionKey("${invocation.context.target.stableValue()}|grok", invocation.context.senderId)
            return handleLlmChat(prompt, key, "grok", "用法: ${config.grok.triggerPrefix.ifBlank { "/grok" }} <问题>")
        }
    }

    inner class DotaHandler : CommandHandler {
        override val spec = CommandSpec(path = listOf("dota"),             aliases = listOf(listOf("d2")), description = "Dota2：绑定、历史、战报（关闭思考）、深度战报（开启思考）、分析、个人详情、深度个人详情")

        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            return try { handleDota(invocation) } catch (e: OpenDotaApiException) {
                fail(e.userMessage)
            }
        }

        private suspend fun handleDota(invocation: CommandInvocation): CommandExecutionResult {
            val args = invocation.args; val sid = invocation.context.senderId
            if (dotaQueryRequiresBinding(args.firstOrNull()) && !hasDotaBinding(dota2Service.getBinding(sid))) return fail(dotaBindingRequired)
            return when {
                args.size == 2 && args[0] == "绑定" -> handleBind(args[1], sid)
                args.size in 1..2 && args[0] == "历史" -> try { handleHistory(args, sid) } catch (e: IllegalArgumentException) { fail(e.message ?: "参数无效") }
                args.isNotEmpty() && OverviewMode.fromCommand(args[0]) != null -> dota2Service.reportTasks.runIfIdle(onBusy = { fail(reportBusyMessage) }) {
                    try {
                        val result = handleOverview(args, invocation)
                        pluginContext.messagePublisher.sendBatches(invocation.context.target, result.reply,
                            options = top.colter.dynamic.core.plugin.PluginMessagePublishOptions(replyToMessageId = invocation.replyToMessageId.ifEmpty { null })).requireAccepted()
                        result.copy(reply = emptyList())
                    } catch (e: Exception) {
                        if (e is CancellationException || e is OpenDotaApiException) throw e
                        fail("个人详情生成或发送失败: ${e.message ?: "未知错误"}")
                    }
                }
                args.size == 2 && args[0] == "分析" -> handleFullAnalyze(args[1])
                args.isNotEmpty() && Dota2ReportMode.fromCommand(args[0]) != null -> dota2Service.reportTasks.runIfIdle(onBusy = { fail(reportBusyMessage) }) {
                    try {
                        val result = handleReport(args, invocation)
                        // 保留名额直到宿主接受投递；返回空回复避免重复发送。
                        pluginContext.messagePublisher.sendBatches(invocation.context.target, result.reply,
                            options = top.colter.dynamic.core.plugin.PluginMessagePublishOptions(
                                replyToMessageId = invocation.replyToMessageId.ifEmpty { null }
                            )).requireAccepted()
                        result.copy(reply = emptyList())
                    } catch (e: Exception) {
                        if (e is CancellationException || e is OpenDotaApiException) throw e
                        fail("战报生成或发送失败: ${e.message ?: "未知错误"}")
                    }
                }
                else -> fail(dotaCommandHelp)
            }
        }

        private suspend fun handleBind(idStr: String, senderId: String): CommandExecutionResult {
            val id = idStr.toLongOrNull() ?: return fail("无效ID"); if (id.toString().length < 7) return fail("请输入9位ID")
            val name = dota2Service.validatePlayer(id) ?: return fail("未找到该玩家")
            dota2Service.setBinding(senderId, id); return ok("已绑定: $name (ID=$id)")
        }

        private suspend fun handleHistory(args: List<String>, senderId: String): CommandExecutionResult {
            val aid = dotaPlayerAccount(args.getOrNull(1), dota2Service.getBinding(senderId))
            val ms = dota2Service.getRecentMatches(aid, 10) ?: return fail("未找到对局记录")
            if (ms.isEmpty()) return fail("未找到对局记录")
            val hint = historyCommandHint(ms.size, aid)
            val img = dota2HistoryDraw(aid, ms, imageConfig, fontRegistry, dota2Service)
            if (img != null) return imageResult(img, "dota2_history.png", hint)
            return ok("${historyTextFallback(ms)}\n\n$hint")
        }

        private suspend fun handleOverview(args: List<String>, invocation: CommandInvocation): CommandExecutionResult {
            val aid = overviewAccount(args, dota2Service.getBinding(invocation.context.senderId))
            val mode = requireNotNull(OverviewMode.fromCommand(args[0]))
            pluginContext.messagePublisher.sendText(invocation.context.target,
                "正在生成玩家 #$aid 的${mode.command}，请稍候。",
                options = top.colter.dynamic.core.plugin.PluginMessagePublishOptions(replyToMessageId = invocation.replyToMessageId.ifEmpty { null })).requireAccepted()
            dota2Service.generateOverview(aid, mode, dsClient, config.api.model).use { generated ->
                val image = dota2OverviewDraw(generated, imageConfig, fontRegistry)
                return imageResult(image, "dota2_overview_${aid}_${mode.name.lowercase()}.png", generated.analysis.notice)
            }
        }

        private suspend fun handleFullAnalyze(midStr: String): CommandExecutionResult {
            val mid = midStr.toLongOrNull() ?: return fail("无效比赛ID")
            val detail = measureDotaStage(mid, "detail") { dota2Service.getMatchDetail(mid) } ?: return fail("获取比赛详情失败")
            val dual = dota2Service.analyzeFullMatch(detail, dsClient, config.api.model)
            val report = dota2Service.buildFullReport(detail, dual)
            val img = dota2FullAnalyzeDraw(report, imageConfig, fontRegistry)
            if (img != null) return imageResult(img, "dota2_analyze_$mid.png")
            val sb = StringBuilder()
            sb.appendLine("天辉: ${report.radiantMvp}\n天辉战犯: ${report.radiantCriminal}")
            sb.appendLine("夜魇: ${report.direMvp}\n夜魇战犯: ${report.direCriminal}")
            return ok(sb.toString())
        }

        private suspend fun handleReport(args: List<String>, invocation: CommandInvocation): CommandExecutionResult {
            val senderId = invocation.context.senderId
            val mode = requireNotNull(Dota2ReportMode.fromCommand(args[0]))
            val (aid, mid) = resolveDotaReportTarget(args, dota2Service.getBinding(senderId)) { dota2Service.getRecentMatches(it, 10) }
            val fetchedDetail = measureDotaStage(mid, "detail", mode) { dota2Service.getMatchDetail(mid) } ?: return fail("获取比赛详情失败")
            val (detail, won) = prepareDotaTargetMatch(fetchedDetail, mid, aid)
            pluginContext.messagePublisher.sendText(invocation.context.target, mode.progressText(mid),
                options = top.colter.dynamic.core.plugin.PluginMessagePublishOptions(replyToMessageId = invocation.replyToMessageId.ifEmpty { null })).requireAccepted()
            dota2Service.generateMatchReport(detail, aid, dsClient, config.api.model, won, mode).use { generated ->
                val img = measureDotaStage(mid, "draw", mode) { dota2MatchDraw(generated.report, imageConfig, fontRegistry) }
                if (img != null) return measureDotaStage(mid, "encode", mode) { imageResult(img, "dota2_report_${mid}_${mode.name.lowercase()}.png") }
                return ok("比赛 #$mid (${if (won) "胜利" else "战败"})\n\n${generated.analysis?.rawResponse ?: "分析失败"}")
            }
        }

    }
}
