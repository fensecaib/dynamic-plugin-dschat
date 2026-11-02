package top.colter.dynamic.agent.command

import org.jetbrains.skia.Image
import top.colter.dynamic.agent.chat.ChatService
import top.colter.dynamic.agent.chat.SessionManager
import top.colter.dynamic.agent.config.AgentPluginConfig
import top.colter.dynamic.agent.config.ImageConfig
import top.colter.dynamic.agent.config.chatCommandUnavailable
import top.colter.dynamic.agent.config.dotaCommandUnavailable
import top.colter.dynamic.agent.dota2.Dota2ReportMode
import top.colter.dynamic.agent.dota2.reportBusyMessage
import top.colter.dynamic.agent.dota2.dotaCommandHelp
import top.colter.dynamic.agent.dota2.OpenDotaApiException
import top.colter.dynamic.agent.dota2.DotaAnalysisUnavailableException
import top.colter.dynamic.agent.dota2.DotaShortcut
import top.colter.dynamic.agent.dota2.dotaShortcuts
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
    private val imageConfig: ImageConfig,
    private val pluginContext: PluginContext,
    private val cacheUtils: CacheUtils,
    private val fontRegistry: FontRegistry,
) {
    private val handlers: List<CommandHandler> by lazy {
        val dota = DotaHandler()
        buildList {
            add(DsHandler())
            add(dota)
            dotaShortcuts.forEach { add(DotaShortcutHandler(it, dota)) }
        }
    }

    fun all(): List<CommandHandler> = handlers

    private fun ok(text: String) = CommandExecutionResult(CommandStatus.SUCCESS, listOf(MessageBatch(listOf(MessageContent.Text(text)))))
    private fun fail(text: String) = CommandExecutionResult(CommandStatus.REJECTED, listOf(MessageBatch(listOf(MessageContent.Text(text)))))

    private suspend fun imageResult(image: Image, filename: String, hint: String? = null): CommandExecutionResult {
        val file = AgentDiagnostics.stage("encode_image") { cacheRenderedImage(image, cacheUtils, filename) }
        AgentDiagnostics.event("agent_image", "prepared", mapOf("image_bytes" to file.length()))
        val batches = mutableListOf(MessageBatch(listOf(MessageContent.Image(fallbackText = "", image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png")))))
        if (hint != null) batches.add(MessageBatch(listOf(MessageContent.Text(hint))))
        return CommandExecutionResult(CommandStatus.SUCCESS, batches)
    }

    private suspend fun handleLlmChat(
        prompt: String,
        key: SessionManager.SessionKey,
        usage: String,
        platformId: String,
    ): CommandExecutionResult {
        if (prompt in listOf("clear", "重置", "新建对话", "清空上下文")) {
            sessionManager.clear(key)
            return ok("上下文已清空")
        }
        if (prompt.isBlank()) return fail(usage)
        val result = chatService.chat(prompt, key)
        return CommandExecutionResult(CommandStatus.SUCCESS,
            prepareChatReply(result.content, config.chat.textReplyThreshold, imageConfig, fontRegistry, cacheUtils, platformId = platformId))
    }

    inner class DsHandler : CommandHandler {
        override val spec = CommandSpec(
            path = listOf(config.trigger.triggerPrefix.removePrefix("/").ifBlank { "ds" }),
            aliases = listOf(listOf("chat")),
            description = "DeepSeek AI对话",
        )

        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            config.chatCommandUnavailable()?.let { return fail(it) }
            val prompt = invocation.args.joinToString(" ").trim()
            val key = SessionManager.SessionKey(invocation.context.target.stableValue(), invocation.context.senderId)
            pluginContext.launchChatReply(invocation.context.target.withPreferredAccount(invocation.context.botAccountId), invocation.replyToMessageId,
                AgentTrace.create("chat_ds", invocation.context.target.platformId.value, config.diagnostics)) {
                handleLlmChat(prompt, key, "用法: ${config.trigger.triggerPrefix.ifBlank { "/ds" }} <问题>", platformId = invocation.context.target.platformId.value).reply
            }
            return CommandExecutionResult(CommandStatus.SUCCESS, emptyList())
        }
    }

    private inner class DotaShortcutHandler(private val shortcut: DotaShortcut, private val delegate: CommandHandler) : CommandHandler {
        override val spec = CommandSpec(path = listOf(shortcut.token), aliases = emptyList(), description = shortcut.description)

        override suspend fun handle(invocation: CommandInvocation) =
            delegate.handle(invocation.copy(args = listOf(shortcut.command) + invocation.args))
    }

    inner class DotaHandler : CommandHandler {
        override val spec = CommandSpec(path = listOf("dota"),             aliases = listOf(listOf("d2")), description = "Dota2：绑定、历史、战报（关闭思考）、深度战报（开启思考）、分析、个人详情、深度个人详情")

        override suspend fun handle(invocation: CommandInvocation): CommandExecutionResult {
            return AgentDiagnostics.request(dotaAction(invocation.args.firstOrNull()), invocation.context.target.platformId.value, config.diagnostics) {
                config.dotaCommandUnavailable(invocation.args.firstOrNull())?.let {
                    AgentDiagnostics.event("agent_request", "blocked", mapOf("category" to "feature_or_configuration"))
                    return@request fail(it)
                }
                try { handleDota(invocation) } catch (e: OpenDotaApiException) {
                    AgentDiagnostics.event("agent_data", "failed", mapOf("error_type" to e.javaClass.simpleName), warning = true)
                    fail(e.userMessage)
                } catch (e: DotaAnalysisUnavailableException) {
                    AgentDiagnostics.event("agent_analysis", "unavailable", mapOf("category" to e.category), warning = true)
                    fail(e.userMessage)
                }
            }
        }

        private suspend fun handleDota(invocation: CommandInvocation): CommandExecutionResult {
            val args = invocation.args; val sid = invocation.context.senderId
            if (dotaQueryRequiresBinding(args.firstOrNull()) && !hasDotaBinding(dota2Service.getBinding(sid))) return fail(dotaBindingRequired)
            return when {
                args.size == 2 && args[0] == "绑定" -> handleBind(args[1], sid)
                args.size in 1..2 && args[0] == "历史" -> try { handleHistory(args, sid) } catch (e: IllegalArgumentException) { fail(e.message ?: "参数无效") }
                args.isNotEmpty() && OverviewMode.fromCommand(args[0]) != null -> runReport(invocation, "个人详情") {
                    handleOverview(args, invocation)
                }
                args.size == 2 && args[0] == "分析" -> runReport(invocation, "全场分析") {
                    handleFullAnalyze(args[1])
                }
                args.isNotEmpty() && Dota2ReportMode.fromCommand(args[0]) != null -> runReport(invocation, "战报") {
                    handleReport(args, invocation)
                }
                else -> fail(dotaCommandHelp)
            }
        }

        /** All analysis commands share admission, preparation errors and one final delivery attempt. */
        private suspend fun runReport(invocation: CommandInvocation, label: String, prepare: suspend () -> CommandExecutionResult) =
            dota2Service.reportTasks.runIfIdle(onBusy = { fail(reportBusyMessage) }) {
                try {
                    val result = prepare()
                    if (result.status != CommandStatus.SUCCESS) result else {
                        // Keep the slot until this delivery attempt returns; uncertain ACKs do not cause a second reply.
                        pluginContext.deliverAgentBatchesOnce(invocation.context.target.withPreferredAccount(invocation.context.botAccountId), result.reply, invocation.replyToMessageId)
                        result.copy(reply = emptyList())
                    }
                } catch (e: Exception) {
                    if (e is CancellationException || e is OpenDotaApiException || e is DotaAnalysisUnavailableException) throw e
                    fail(if (e is IllegalArgumentException) e.message ?: "参数无效" else "${label}生成失败，请稍后重试。")
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
            val img = AgentDiagnostics.stage("draw_history") { dota2HistoryDraw(aid, ms, imageConfig, fontRegistry, dota2Service) }
            if (img != null) return imageResult(img, "dota2_history.png")
            return ok(historyTextFallback(ms))
        }

        private suspend fun handleOverview(args: List<String>, invocation: CommandInvocation): CommandExecutionResult {
            val aid = overviewAccount(args, dota2Service.getBinding(invocation.context.senderId))
            val mode = requireNotNull(OverviewMode.fromCommand(args[0]))
            pluginContext.sendAgentBatches(invocation.context.target.withPreferredAccount(invocation.context.botAccountId),
                listOf(MessageBatch(listOf(MessageContent.Text("正在生成玩家 #$aid 的${mode.command}，请稍候。")))), invocation.replyToMessageId, "progress")
            dota2Service.generateOverview(aid, mode, dsClient, config.api.model).use { generated ->
                val image = AgentDiagnostics.stage("draw_overview") { dota2OverviewDraw(generated, imageConfig, fontRegistry) }
                return imageResult(image, "dota2_overview_${aid}_${mode.name.lowercase()}.png", generated.analysis.notice)
            }
        }

        private suspend fun handleFullAnalyze(midStr: String): CommandExecutionResult {
            val mid = midStr.toLongOrNull()?.takeIf { it > 0 } ?: return fail("无效比赛ID")
            val detail = measureDotaStage(mid, "detail") { dota2Service.getMatchDetail(mid) } ?: return fail("获取比赛详情失败")
            dota2Service.generateFullReport(detail, dsClient, config.api.model).use { generated ->
                val report = generated.report
                val img = AgentDiagnostics.stage("draw_full_analysis") { dota2FullAnalyzeDraw(report, imageConfig, fontRegistry) }
                if (img != null) return imageResult(img, "dota2_analyze_$mid.png")
                return ok("天辉: ${report.radiantMvp}\n天辉战犯: ${report.radiantCriminal}\n夜魇: ${report.direMvp}\n夜魇战犯: ${report.direCriminal}")
            }
        }

        private suspend fun handleReport(args: List<String>, invocation: CommandInvocation): CommandExecutionResult {
            val senderId = invocation.context.senderId
            val mode = requireNotNull(Dota2ReportMode.fromCommand(args[0]))
            val (aid, mid) = resolveDotaReportTarget(args, dota2Service.getBinding(senderId)) { dota2Service.getRecentMatches(it, 10) }
            val fetchedDetail = measureDotaStage(mid, "detail", mode) { dota2Service.getMatchDetail(mid) } ?: return fail("获取比赛详情失败")
            val (detail, won) = prepareDotaTargetMatch(fetchedDetail, mid, aid)
            pluginContext.sendAgentBatches(invocation.context.target.withPreferredAccount(invocation.context.botAccountId),
                listOf(MessageBatch(listOf(MessageContent.Text(mode.progressText(mid))))), invocation.replyToMessageId, "progress")
            dota2Service.generateMatchReport(detail, aid, dsClient, config.api.model, won, mode).use { generated ->
                val img = measureDotaStage(mid, "draw", mode) { dota2MatchDraw(generated.report, imageConfig, fontRegistry) }
                if (img != null) return measureDotaStage(mid, "encode", mode) { imageResult(img, "dota2_report_${mid}_${mode.name.lowercase()}.png") }
                return ok("比赛 #$mid (${if (won) "胜利" else "战败"})\n\n${generated.analysis?.rawResponse ?: "分析失败"}")
            }
        }

    }
}
