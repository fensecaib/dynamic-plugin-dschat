# dynamic-bot-agent 设计文档

> 将 `ds-chat-mirai` (colter) 项目中的 DeepSeek AI 对话、Dota2 战报、联网搜索功能，
> 移植为 `dynamic-bot` 插件 `dynamic-agent`，包名 `top.colter.dynamic.agent`。

---

## 一、项目概述

| 属性 | 值 |
|------|-----|
| 插件 ID | `dynamic-agent` |
| 主类 | `top.colter.dynamic.agent.DynamicAgentPlugin` |
| 包路径 | `top.colter.dynamic.agent` |
| 产物 | `dynamic-bot-agent-0.1.0-all.jar` |
| Kotlin | 2.4.0 |
| JVM Target | 17 (Toolchain 21) |

## 二、实现的接口

```
Plugin
├── IncomingMessageConsumerPlugin   → 消息消费
├── CommandContributor              → 命令注册
└── ConfigurablePlugin<T>           → Web UI 配置
```

不再实现原 `dynamic-weibo` 的 PublisherSource/PublisherFollow/PublisherLogin 等接口。

## 三、目录结构

```
src/main/kotlin/top/colter/dynamic/agent/
│
├── DynamicAgentPlugin.kt         # 插件入口，生命周期 + 服务装配
│
├── config/
│   └── AgentPluginConfig.kt      # 配置数据类 (YAML 序列化)
│
├── listener/
│   └── MessageListener.kt        # 消息监听 (IncomingMessageConsumerPlugin)
│
├── command/
│   └── CommandHandlers.kt        # 命令处理器 (CommandContributor)
│
├── chat/
│   ├── ChatService.kt            # DS 对话编排 (URL预抓取 → Agent路由 → API调用 → 持久化)
│   └── SessionManager.kt         # 多轮会话管理 (TTL/轮数/Token 三层保护)
│
├── ds/
│   ├── DeepSeekData.kt           # 请求/响应/Tool 数据模型 (不变)
│   └── DeepSeekClient.kt         # DeepSeek API (java.net.http.HttpClient)
│
├── agent/
│   ├── BaseAgent.kt              # Agent 接口
│   ├── ChatAgent.kt              # 纯对话 Agent
│   ├── SearchAgent.kt            # ReAct 搜索 Agent (current_time/web_search/web_fetch)
│   ├── AgentRouter.kt            # 按 webSearch.enabled 路由
│   ├── SearchClient.kt           # 三层后备: Exa MCP → Parallel MCP → DDG JSON
│   └── FetchService.kt           # URL 正文抓取 (HttpClient + jsoup) + Tavily 批量
│
├── dota2/
│   ├── Dota2Data.kt              # PlayerOverview, Dota2MatchReport, Dota2PlayerCard
│   └── Dota2Service.kt           # OpenDota API + 绑定管理 + DS AI 分析
│
├── draw/
│   ├── ColorUtils.kt             # hexColor(), parseGradientColors()
│   ├── ChatDraw.kt               # AI 长文本 → Skia 图片
│   ├── Dota2Draw.kt              # Dota2 战报表格 + 分析卡片
│   ├── Dota2HistoryDraw.kt       # Dota2 历史战绩表格
│   └── Dota2OverviewDraw.kt      # Dota2 玩家概览 + AI 分析
│
└── util/
    ├── HttpUtils.kt              # 统一 HttpClient 封装 (get/post/getBytes)
    └── CacheUtils.kt             # 文件缓存系统 (英雄/物品图标, 渲染图片)
```

## 四、消息流

### 入站

```
OneBot/Telegram → IncomingMessagePipeline
    │
    ├─ /ds ...  → MessageListener.handle()
    │   ├─ prompt 在 [clear/重置/新建对话/清空上下文] → 清空 session
    │   └─ 其他 → handleChat() → ChatService.chat()
    │
    ├─ /dota ... → MessageListener.handleDotaCommand()
    │   ├─ 绑定 <ID> → setBinding()
    │   ├─ 历史      → getRecentMatches() → dota2HistoryDraw()
    │   ├─ 战报 [ID] → analyzeMatch() → dota2MatchDraw()
    │   ├─ 分析 <ID> → analyzeFullMatch() → dota2FullAnalyzeDraw()
    │   └─ 个人详情 / 深度个人详情 → generateOverview() → 固定最近十场及详情 → 结构化分析 / 资源准备 → dota2OverviewDraw()
    │
    └─ @Bot (普通文本) → mentions.contains(botId) → handleChat()
        触发条件: config.trigger.enableAtTrigger=true 且 消息中@了Bot自身
```

### 出站 (图片)

```
ChatDraw / Dota2Draw / Dota2HistoryDraw / Dota2OverviewDraw / Dota2FullAnalyzeDraw
    │ Skia 渲染 → Image
    ▼
encodeToData() → bytes → cacheFile(CacheType.DRAW, filename)
    │
    ▼
MediaRef(absolutePath, kind=IMAGE) → MessageContent.Image → MessageBatch
    │
    ▼
PluginMessagePublisher.sendBatches(target, batches)
    │
    ▼
OutboundMediaService (宿主) → MessageSinkPlugin → 目标平台
```

## 五、关键适配

### 5.1 Ktor → HttpClient

所有 HTTP 请求统一使用 `java.net.http.HttpClient`。

| 原 colter (Ktor) | 新 (HttpClient) |
|---|---|
| `client.post(url) { setBody(json) }` | `HttpUtils.httpPost(url, body, headers)` |
| `client.get(url) { parameter(k, v) }` | `HttpUtils.httpGet(url, params=map)` |
| `response.body<ByteArray>()` | `HttpUtils.httpGetBytes(url)` |
| Bearer auth in defaultRequest | 每次请求手动传 `Authorization: Bearer` header |

### 5.2 skiko-layout v0.0.2 → v0.0.9

| 旧 (v0.0.2) | 新 (v0.0.9) |
|---|---|
| `LayoutAlignment.CENTER_LEFT` | `LayoutAlignment.LEFT` |
| `LayoutAlignment.CENTER_RIGHT` | `LayoutAlignment.RIGHT` |
| `FontUtils.defaultFont?.familyName` | `fontRegistry.textTypeface?.familyName` |
| `FontUtils.loadTypeface(path)` | `fontRegistry.loadTextTypeface(path)` |
| `Gradient(angle, colors)` | `Gradient(LayoutAlignment.start, LayoutAlignment.end, colors)` |
| `Color.makeRGB("#FFF")` | `hexColor("#FFF")` (自实现) |
| `font.close()` / `paint.close()` | 移除 (非 AutoCloseable) |
| `View(modifier)` | `View(modifier, fontRegistry = fontRegistry)` |

### 5.3 用户标识适配

| | 原 colter (mirai) | 新 (dynamic-bot) |
|---|---|---|
| 用户 ID 类型 | `Long` (QQ号) | `String` (senderId) |
| 群组 ID 类型 | `Long` (groupId) | `TargetAddress.stableValue()` |
| Session 键 | `SessionKey(groupId, senderId)` | `SessionKey(target.stableValue(), senderId)` |
| Dota2 绑定键 | `Long` (QQ号) | `String` (senderId) |

### 5.4 图片发送

```kotlin
// 1. 渲染
val image = dota2MatchDraw(report, imageConfig, fontRegistry)
// 2. 编码并写入缓存文件
val data = image.encodeToData()!!
val file = cacheUtils.cacheFile(CacheType.DRAW, "dota2_report_xxx.png")
file.writeBytes(data.bytes)
// 3. 构建消息 — 使用绝对路径，非 file:// URI
MessageContent.Image(
    fallbackText = "",
    image = MediaRef(uri = file.absolutePath, kind = MediaKind.IMAGE, mimeType = "image/png")
)
```

### 5.5 @Bot 触发

```kotlin
// 旧 colter: 检查消息中是否有 @Bot
val botId = chain.bot.id
val hasAt = chain.any { it is At && it.target == botId }

// 新: 检查 mentions 是否包含 Bot 自身账号
val botId = ctx.message.botAccountId
ctx.message.mentions.contains(botId)
```

### 5.6 配置热更新

`applyConfig()` 会重建 `DeepSeekClient`, `SessionManager`, `ChatService`, `MessageListener`, `CommandHandlers`，
API Key/模型变更无需重启插件。

```kotlin
override fun applyConfig(next: AgentPluginConfig): ConfigApplyResult {
    this.config = next
    context.configService.save(configId, next)
    rebuildServices()
    return ConfigApplyResult(changed = true)
}
```

## 六、配置结构

配置通过 `context.configService` 持久化到 `config/dynamic-agent.yml`，全部字段通过 Web UI 可配：

| 区域 | 字段 | 类型 | 说明 |
|------|------|------|------|
| **DeepSeek API** | `api.apiKey` | SECRET | 必填 |
| | `api.apiUrl` | TEXT | 默认 https://api.deepseek.com/chat/completions |
| | `api.model` | SELECT | V4 Flash / V4 Pro |
| **对话** | `chat.systemPrompt` | TEXTAREA | 系统提示词 |
| | `chat.maxTokens` | NUMBER | 256-16384 |
| | `chat.textReplyThreshold` | NUMBER | 0=始终文本, >0 时超阈值转图片 |
| | `chat.enableMemory` | BOOLEAN | 多轮记忆开关 |
| | `chat.memoryRounds` | NUMBER | 1-50 轮 |
| | `chat.memoryTtlMinutes` | NUMBER | 1-1440 分钟 |
| | `chat.maxContextTokens` | NUMBER | 1024-256000 |
| **触发** | `trigger.triggerPrefix` | TEXT | 默认 /ds |
| | `trigger.enableAtTrigger` | BOOLEAN | @Bot 触发 |
| **搜索** | `webSearch.enabled` | BOOLEAN | ReAct 联网搜索 |
| | `webSearch.maxRounds` | NUMBER | 1-50 轮 |
| | `webSearch.tavilyApiKey` | SECRET | 可选，用于 URL 预抓取 |
| **图片** | `image.defaultColor` | TEXT | `#667eea;#764ba2` 格式 |
| | `image.factor` | NUMBER | 1.0-4.0 |

## 七、依赖

```kotlin
// compileOnly (宿主提供)
compileOnly("top.colter.dynamic:dynamic-bot-core:0.0.3")
compileOnly("io.github.oshai:kotlin-logging-jvm:8.0.4")
compileOnly("org.jetbrains.skiko:skiko-awt:0.148.1")
compileOnly("top.colter.skiko:skiko-layout:0.0.9")

// implementation (打包进 fatJar)
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
implementation("org.jsoup:jsoup:1.18.1")
implementation("com.google.zxing:core:3.5.4")
implementation("com.google.zxing:javase:3.5.4")
```

## 八、数据文件 (运行时)

| 路径 (相对 data/plugins/dynamic-agent/) | 内容 |
|---|---|
| `dota2-bindings.json` | senderId → Dota2 账号绑定 |
| `font.ttf` | 自定义字体 (可选) |
| `fonts/*.ttf` / `*.otf` | 额外回退字体 (可选) |
| `cache/draw/` | 渲染图片缓存 |
| `cache/dota2/icons/heroes/` | 英雄图标 |
| `cache/dota2/icons/items/` | 物品图标 |
| `cache/dota2/icons/aghs/` | A杖/魔晶图标 |
| `cache/dota2/icons/avatars/` | Steam 玩家头像 |

## 九、已移除的功能 (对比原 dynamic-weibo)

| 模块 | 原因 |
|------|------|
| WeiboPublisherRuntime / WeiboDynamicMapper / WeiboCursorStore | 不再作为 PublisherSource 插件 |
| WeiboClient / WeiboLinkResolver / 微博 Draw 组件 | 微博功能未移植 |
| PublisherSourcePlugin / PublisherLookupPlugin / PublisherFollowPlugin / PublisherLoginProvider | 角色不再需要 |

## 十、已知限制

1. **字体**: 宿主 `DrawFonts` 提供内置字体 (HarmonyOS Sans + Noto Color Emoji)。插件额外加载 `data/plugins/dynamic-agent/font.ttf` 和 `fonts/` 目录下的文件作为回退
2. **skiko-layout v0.0.9**: 需安装到本地 Maven 仓库 (`top.colter.skiko:skiko-layout:0.0.9`)
3. **图片消息**: 图片通过宿主 `OutboundMediaService` 投递，不使用 `file://` URI，直接用绝对文件路径
