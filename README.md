# dynamic-bot-agent

基于 Dynamic-bot 开发的插件，集成了一些自用功能。欢迎使用[dynamic-bot](https://github.com/Colter23/dynamic-bot)！

基于 DeepSeek API 的多功能 Agent 插件，为 dynamic-bot 提供 AI 对话、Dota2 战报分析、联网搜索能力。

## 功能

- **AI 对话** — 多轮对话，支持记忆（轮数/Token/TTL 三层保护），长文本自动渲染为图片
- **联网搜索** — ReAct Agent 模式，三层搜索后备 (Exa MCP → Parallel MCP → DuckDuckGo)
- **Dota2 战报** — OpenDota API + DeepSeek AI 分析，MVP/战犯识别，表格图片渲染
- **Dota2 历史** — 近期战绩表格，Win/Loss 着色
- **Dota2 分析** — 双阵营全分析
- **Dota2 概览** — 玩家个人数据 + AI 位置侧写诊断

## 命令

| 命令 | 说明 |
|------|------|
| `/ds <问题>` | 发起 AI 对话 |
| `/ds clear` | 清空上下文 |
| `/dota 绑定 <9位ID>` | 绑定 Dota2 Steam 账号 |
| `/dota 战报 [比赛ID]` | 生成单场战报（不填则为最近一场） |
| `/dota 历史` | 查看最近 10 场战绩 |
| `/dota 分析 <比赛ID>` | 双阵营全分析 |
| `/dota 个人详情 [ID]` | 玩家数据 + AI 诊断 |

除命令外，`@Bot 文本` 也可触发对话（需在配置中开启 `trigger.enableAtTrigger`）。

## 安装

```powershell
.\gradlew.bat fatJar
```

把 `build/libs/dynamic-bot-agent-0.1.0-all.jar` 放入宿主 `plugins/` 目录，启动或重启 dynamic-bot 即可。

> 构建前需将 `top.colter.skiko:skiko-layout:0.0.9` 安装到本地 Maven 仓库。

## 配置

插件部署后通过 dynamic-bot 的 Web Admin 进行配置，所有字段均可在 UI 中修改并即时生效：

| 区域 | 配置项 | 类型 | 说明 |
|------|--------|------|------|
| **DeepSeek API** | API Key | SECRET | 必填，从 platform.deepseek.com 获取 |
| | API URL | TEXT | 默认 `https://api.deepseek.com/chat/completions` |
| | 模型 | SELECT | V4 Flash / V4 Pro |
| **对话** | 系统提示词 | TEXTAREA | |
| | 最大 Token | NUMBER | 256-16384 |
| | 图片阈值 | NUMBER | 超过此字数自动渲染为图片，0=始终文本 |
| | 启用记忆 | BOOLEAN | 多轮对话记忆开关 |
| | 记忆轮数 | NUMBER | 1-50 |
| | 会话超时 | NUMBER | 分钟，30 分钟无活动自动清除 |
| | 上下文 Token 限制 | NUMBER | 超限自动踢出早期轮次 |
| **触发** | 触发前缀 | TEXT | 默认 `/ds` |
| | @Bot 触发 | BOOLEAN | 开启后 @Bot 即可对话 |
| **搜索** | 启用联网搜索 | BOOLEAN | ReAct Agent 模式 |
| | 最大轮数 | NUMBER | 工具调用最大轮次 |
| | Tavily API Key | SECRET | 可选，用于 URL 预抓取 |
| **图片** | 主题色 | TEXT | `#667eea;#764ba2` 格式，分号分隔 |
| | 倍率 | NUMBER | 1.0-4.0 |

## 缓存

运行时数据存储在 `data/plugins/dynamic-agent/` 下：

- `dota2-bindings.json` — Dota2 账号绑定
- `cache/draw/` — 渲染图片
- `cache/dota2/icons/` — 英雄/物品/A杖图标
- `fonts/` — 自定义字体 (可选)
