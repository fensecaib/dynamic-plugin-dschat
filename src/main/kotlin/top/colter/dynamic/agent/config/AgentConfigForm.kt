package top.colter.dynamic.agent.config

import top.colter.dynamic.core.config.ConfigFieldSpec
import top.colter.dynamic.core.config.ConfigFieldType
import top.colter.dynamic.core.config.ConfigFormSpec
import top.colter.dynamic.core.config.ConfigNumberKind

/** 配置路径保持稳定，旧配置可直接升级；除命令前缀外，保存后对新请求生效。 */
internal val agentConfigFormSpec = ConfigFormSpec(
    title = "多功能 Agent 插件配置",
    fields = listOf(
        ConfigFieldSpec("chat.enabled", "启用 AI 对话", ConfigFieldType.BOOLEAN, section = "对话与触发", description = "AI 对话总开关，控制 DeepSeek 和 Grok 对话；Dota2 可独立启用"),
        ConfigFieldSpec("trigger.enableCommandTrigger", "启用 DeepSeek 命令对话", ConfigFieldType.BOOLEAN, section = "对话与触发", description = "控制 DeepSeek 命令前缀（默认 /ds）及 /chat；关闭后仍可使用 Dota2 指令"),
        ConfigFieldSpec("trigger.enableAtTrigger", "启用 DeepSeek @Bot 对话", ConfigFieldType.BOOLEAN, section = "对话与触发", description = "控制单独 @Bot 是否发起 DeepSeek 对话；与 Grok @Bot 互斥，@Bot /dota 指令仍可使用"),
        ConfigFieldSpec("trigger.triggerPrefix", "DeepSeek 命令前缀", ConfigFieldType.TEXT, section = "对话与触发", description = "默认 /ds；修改后需要重启插件，其他开关保存后生效", restartRequired = true),
        ConfigFieldSpec("grok.enabled", "启用 Grok 对话", ConfigFieldType.BOOLEAN, section = "对话与触发", description = "仍受 AI 对话总开关控制；需要在 Grok API 分区配置 Key"),
        ConfigFieldSpec("grok.enableCommandTrigger", "启用 Grok 命令对话", ConfigFieldType.BOOLEAN, section = "对话与触发", description = "控制 Grok 命令前缀（默认 /grok）；可独立关闭并保留 @Bot 对话"),
        ConfigFieldSpec("grok.useAsAtTrigger", "启用 Grok @Bot 对话", ConfigFieldType.BOOLEAN, section = "对话与触发", description = "开启后自动关闭 DeepSeek @Bot 对话；同时需要启用 Grok 对话并配置 Key"),
        ConfigFieldSpec("grok.triggerPrefix", "Grok 命令前缀", ConfigFieldType.TEXT, section = "对话与触发", description = "默认 /grok；修改后需要重启插件", restartRequired = true),

        ConfigFieldSpec("dota2.enabled", "启用 Dota2 功能", ConfigFieldType.BOOLEAN, section = "Dota2 功能", description = "Dota2 总开关，控制 /dota、/d2 全部指令；与 AI 对话开关独立"),
        ConfigFieldSpec("dota2.enableBinding", "启用账号绑定", ConfigFieldType.BOOLEAN, section = "Dota2 功能", description = "控制 /dota 绑定；暂停后保留已绑定账号，无需 DeepSeek Key"),
        ConfigFieldSpec("dota2.enableHistory", "启用历史查询", ConfigFieldType.BOOLEAN, section = "Dota2 功能", description = "控制 /dota 历史；无需 DeepSeek Key"),
        ConfigFieldSpec("dota2.enableAnalysis", "启用 AI 战报与个人分析", ConfigFieldType.BOOLEAN, section = "Dota2 功能", description = "控制战报、深度战报、分析、个人详情和深度个人详情，需配置 DeepSeek Key；普通模式关闭模型思考，深度模式启用思考、通常更慢。QQ 官方渠道被动回复窗口为 5 分钟，数据读取、重试和绘图也计入等待时间"),

        ConfigFieldSpec("api.apiKey", "DeepSeek API Key（选填）", ConfigFieldType.SECRET, section = "DeepSeek API", required = false, description = "留空时暂停 DeepSeek 对话及 Dota2 AI 分析；Dota2 绑定、历史和已配置的 Grok 仍可使用"),
        ConfigFieldSpec("api.apiUrl", "DeepSeek / 兼容服务请求地址", ConfigFieldType.TEXT, section = "DeepSeek API", description = "官方默认 https://api.deepseek.com/chat/completions；第三方填完整的 OpenAI 兼容 /chat/completions 地址，并使用该服务的 Key 和模型名称"),
        ConfigFieldSpec("api.model", "请求模型名称", ConfigFieldType.TEXT, section = "DeepSeek API", description = "官方推荐 deepseek-flash（V4.1 Flash）；Pro 可填 deepseek-v4-pro。仅官方地址自动迁移旧 Flash 别名，第三方模型名保持原值"),
        ConfigFieldSpec("api.requestTimeoutSeconds", "单次 AI 请求超时（秒）", ConfigFieldType.NUMBER, section = "DeepSeek API", description = "默认 120 秒，适用于 DeepSeek 对话和 Dota2 AI；这是每次请求的时限，对话仍受搜索及最终回答的总时限约束，重试会增加总耗时。QQ 渠道建议减少重试并优先普通战报", numberKind = ConfigNumberKind.INTEGER, min = 10, max = 300),
        ConfigFieldSpec("api.maxRetries", "失败后的最大重试次数", ConfigFieldType.NUMBER, section = "DeepSeek API", description = "默认 2；0 表示只请求一次。网络错误及 HTTP 5xx 服务端错误可自动重试，最多发起 1 + 本次数的请求；HTTP 429 会直接提示限流", numberKind = ConfigNumberKind.INTEGER, min = 0, max = 3),

        ConfigFieldSpec("grok.apiKey", "Grok API Key（选填）", ConfigFieldType.SECRET, section = "Grok API", required = false, description = "仅在使用 Grok 对话时需要；独立于 DeepSeek Key"),
        ConfigFieldSpec("grok.apiUrl", "Grok 兼容服务请求地址", ConfigFieldType.TEXT, section = "Grok API", description = "完整的 OpenAI 兼容地址，需要包含 /chat/completions"),
        ConfigFieldSpec("grok.model", "Grok 请求模型名称", ConfigFieldType.TEXT, section = "Grok API", description = "填写所用服务支持的模型名称，例如 grok-4.5"),
        ConfigFieldSpec("grok.systemPrompt", "Grok 系统提示词", ConfigFieldType.TEXTAREA, section = "Grok API"),
        ConfigFieldSpec("grok.requestTimeoutSeconds", "Grok 单次请求超时（秒）", ConfigFieldType.NUMBER, section = "Grok API", description = "默认 120 秒；独立于 DeepSeek，这是每次请求的时限，对话仍受搜索及最终回答的总时限约束，重试会增加总耗时", numberKind = ConfigNumberKind.INTEGER, min = 10, max = 300),
        ConfigFieldSpec("grok.maxRetries", "Grok 最大重试次数", ConfigFieldType.NUMBER, section = "Grok API", description = "默认 2；0 表示只请求一次", numberKind = ConfigNumberKind.INTEGER, min = 0, max = 3),

        ConfigFieldSpec("chat.systemPrompt", "DeepSeek 系统提示词", ConfigFieldType.TEXTAREA, section = "对话回复与记忆"),
        ConfigFieldSpec("chat.maxTokens", "对话最大输出 Token", ConfigFieldType.NUMBER, section = "对话回复与记忆", description = "仅控制普通对话的输出上限；越大可能等待越久，Dota2 分析使用独立预算", numberKind = ConfigNumberKind.INTEGER, min = 256, max = 16384),
        ConfigFieldSpec("chat.textReplyThreshold", "长回复转图片阈值（字符）", ConfigFieldType.NUMBER, section = "对话回复与记忆", description = "回复超过此长度时绘制成图片；0 表示始终发送文字", numberKind = ConfigNumberKind.INTEGER, min = 0, max = 50000),
        ConfigFieldSpec("chat.enableMemory", "启用多轮对话记忆", ConfigFieldType.BOOLEAN, section = "对话回复与记忆"),
        ConfigFieldSpec("chat.memoryRounds", "保留对话轮数", ConfigFieldType.NUMBER, section = "对话回复与记忆", numberKind = ConfigNumberKind.INTEGER, min = 1, max = 50),
        ConfigFieldSpec("chat.memoryTtlMinutes", "会话空闲过期时间（分钟）", ConfigFieldType.NUMBER, section = "对话回复与记忆", numberKind = ConfigNumberKind.INTEGER, min = 1, max = 1440),
        ConfigFieldSpec("chat.maxContextTokens", "对话上下文 Token 上限", ConfigFieldType.NUMBER, section = "对话回复与记忆", description = "超过后移除较早的对话轮次；应符合模型的上下文限制", numberKind = ConfigNumberKind.INTEGER, min = 1024, max = 256000),

        ConfigFieldSpec("webSearch.enabled", "启用联网搜索", ConfigFieldType.BOOLEAN, section = "联网搜索", description = "允许 AI 对话检索网页；搜索耗时另受下面的时限控制"),
        ConfigFieldSpec("webSearch.maxRounds", "搜索工具最大轮数", ConfigFieldType.NUMBER, section = "联网搜索", description = "默认 5，建议不超过 10；轮数越高可能等待越久，保存后对新请求生效", numberKind = ConfigNumberKind.INTEGER, min = 1, max = 50),
        ConfigFieldSpec("webSearch.totalTimeoutSeconds", "搜索阶段总时限（秒）", ConfigFieldType.NUMBER, section = "联网搜索", description = "搜索及工具循环的总预算；最终回答另使用下方时限", numberKind = ConfigNumberKind.INTEGER, min = 10, max = 300),
        ConfigFieldSpec("webSearch.finalizeTimeoutSeconds", "搜索后最终回答时限（秒）", ConfigFieldType.NUMBER, section = "联网搜索", numberKind = ConfigNumberKind.INTEGER, min = 10, max = 180),
        ConfigFieldSpec("webSearch.searchTimeout", "单次网页搜索时限（秒）", ConfigFieldType.NUMBER, section = "联网搜索", description = "限制一次搜索工具调用的总等待时间，包括搜索后端切换", numberKind = ConfigNumberKind.INTEGER, min = 1, max = 60),
        ConfigFieldSpec("webSearch.fetchTimeout", "单次网页读取时限（秒）", ConfigFieldType.NUMBER, section = "联网搜索", numberKind = ConfigNumberKind.INTEGER, min = 1, max = 60),
        ConfigFieldSpec("webSearch.fetchMaxChars", "工具读取网页的最大字符数", ConfigFieldType.NUMBER, section = "联网搜索", description = "限制 web_fetch 工具返回的正文长度；不控制 Tavily URL 预读取内容", numberKind = ConfigNumberKind.INTEGER, min = 1000, max = 50000),
        ConfigFieldSpec("webSearch.tavilyApiKey", "Tavily API Key（选填）", ConfigFieldType.SECRET, section = "联网搜索", required = false, description = "用于 URL 预读取；未配置时使用默认网页读取方式"),

        ConfigFieldSpec("image.defaultColor", "图片主题色", ConfigFieldType.TEXT, section = "图片与存储", description = "多个渐变色用分号分隔，例如 #667eea;#764ba2"),
        ConfigFieldSpec("image.factor", "图片渲染倍率", ConfigFieldType.NUMBER, section = "图片与存储", description = "倍率越高图片越大；绘图图片的自动清理时间由下方保留时间控制", numberKind = ConfigNumberKind.DECIMAL, min = 1, max = 4),
        ConfigFieldSpec("image.drawCacheRetentionHours", "绘图产物保留时间（小时）", ConfigFieldType.NUMBER, section = "图片与存储", description = "默认 72 小时（3 天）。插件启动及每 6 小时检查并清理 draw 目录内超过此时长的绘图图片产物；图标、头像等素材缓存不删除", numberKind = ConfigNumberKind.INTEGER, min = 1, max = 8760),

        ConfigFieldSpec("diagnostics.enabled", "启用流程诊断日志", ConfigFieldType.BOOLEAN, section = "日志与排障", description = "在宿主日志记录命令、数据读取、AI 请求、素材下载、绘图和消息发送各环节及耗时，使用请求编号关联；不记录 API Key、提示词或回答正文"),
        ConfigFieldSpec("diagnostics.slowStageWarningSeconds", "环节慢请求提醒阈值（秒）", ConfigFieldType.NUMBER, section = "日志与排障", description = "默认 30 秒；单个环节耗时达到阈值时记录警告，便于定位长时间无结果的问题", numberKind = ConfigNumberKind.INTEGER, min = 5, max = 300),
    ),
)
