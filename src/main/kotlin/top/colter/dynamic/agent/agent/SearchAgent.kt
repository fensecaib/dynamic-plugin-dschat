package top.colter.dynamic.agent.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.colter.dynamic.agent.config.WebSearchConfig
import top.colter.dynamic.agent.ds.*
import top.colter.dynamic.agent.util.HttpUtils
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class SearchAgent(config: WebSearchConfig) : Agent {
    override val name = "search"
    override val maxRounds = config.maxRounds

    private val fetchMaxChars = config.fetchMaxChars

    override val tools: List<Tool> = listOf(
        Tool(
            type = "function",
            function = FunctionDef(
                name = "current_time",
                description = "获取当前精确日期和时间。用户询问日期、时间、星期几时必须调用。返回北京时间。",
                parameters = ParametersDef(
                    type = "object",
                    properties = mapOf(
                        "dummy" to PropertyDef("string", "忽略，不需要传参")
                    ),
                    required = emptyList()
                )
            )
        ),
        Tool(
            type = "function",
            function = FunctionDef(
                name = "web_search",
                description = "辅助澄清工具。仅在以下情况使用：1)用户未提供URL时的事实查找入口；2)web_fetch完成后用于术语翻译/概念解释/背景补充。用户已提供URL时严禁先于web_fetch调用。",
                parameters = ParametersDef(
                    type = "object",
                    properties = mapOf(
                        "query" to PropertyDef("string", "搜索关键词。不要编造日期或数字，不知道时用泛称如'recent''latest'")
                    ),
                    required = listOf("query")
                )
            )
        ),
        Tool(
            type = "function",
            function = FunctionDef(
                name = "web_fetch",
                description = "读取用户指定网页的唯一工具。用户消息中含URL时，必须最先调用本工具获取页面正文，所有分析结论必须基于本工具返回的内容。不可跳过本工具转而用web_search查找第三方摘要替代用户指定的URL。",
                parameters = ParametersDef(
                    type = "object",
                    properties = mapOf(
                        "url" to PropertyDef("string", "要获取的网页URL")
                    ),
                    required = listOf("url")
                )
            )
        )
    )

    override suspend fun prepareMessages(
        messages: MutableList<ChatMessage>,
        prompt: String,
        systemPrompt: String
    ) {
        val hint = """
        |可用工具: current_time(当前时间) web_search(仅辅助纠错) web_fetch(读页面/URL场景唯一入口)
        |
        |===URL直给模式=== 用户消息中含有 http:// 或 https:// 完整链接，或user消息中已有[系统已预先抓取]标记时：
        |规则:
        |- 用户指定URL的内容已通过预抓取或正在通过web_fetch获取中，你直接基于这些内容完成任务。
        |- web_search只在此模式下用于：①翻译专有名词 ②解释你不理解的概念 ③补充背景知识。
        |- 严禁用web_search结果质疑或覆盖用户指定URL的原始内容。
        |- 多URL场景：逐条web_fetch全部抓完后再综合总结，抓取过程不穿插web_search。
        |
        |===泛搜模式=== 用户消息中不含URL时：
        |规则:
        |- **策略** → 精准查询。中英文混合。关键词："2026"、SOTA。
        |- **日期/时间** → 先调 current_time，禁止猜测。
        |- **不确定的事实** → web_search 验证，至少两次不同角度查询交叉确认。
        |- **语言权重** → 强制混合中英文搜索以打破信息孤岛。官方文档/Stack Overflow采用英文；本地化/社区方案采用中文。
        |- **搜到版本号/Hash/下载链** → 必须 web_fetch 官方页面确认，不以聚合站/论坛为准。
        |- **时间锚点** → 关键词必须包含"2026"或"最新"。拒绝过时模式。
        """.trimMargin()

        val original = messages.firstOrNull { it.role == "system" } ?: return
        val idx = messages.indexOf(original)
        messages[idx] = ChatMessage("system", "${original.content}\n\n$hint")
    }

    override fun shouldContinue(round: Int): Boolean = round < maxRounds

    override suspend fun executeTool(
        toolName: String,
        arguments: String,
        toolCallId: String
    ): String? {
        return try {
            when (toolName) {
                "current_time" -> {
                    val now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"))
                    val fmt = DateTimeFormatter.ofPattern("yyyy年MM月dd日 EEEE HH:mm:ss (zzzz)")
                    now.format(fmt)
                }
                "web_search" -> {
                    val query = parseArg(arguments, "query")
                        ?: return "错误: 缺少搜索关键词"
                    val result = SearchClient.search(query)
                    "===网络搜索===\n$result"
                }
                "web_fetch" -> {
                    val url = parseArg(arguments, "url")
                        ?: return "错误: 缺少URL"
                    val result = FetchService.fetch(url, fetchMaxChars)
                    "===网页内容=== [来源: $url]\n$result"
                }
                else -> "未知工具: $toolName"
            }
        } catch (e: Exception) {
            "执行 $toolName 失败: ${e.message}"
        }
    }

    private fun parseArg(argsJson: String, key: String): String? {
        return try {
            val obj = HttpUtils.json.decodeFromString(JsonObject.serializer(), argsJson)
            obj[key]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        }
    }
}
