# dynamic-bot-agent

基于 Dynamic-bot 开发的插件，集成了一些自用功能。欢迎使用[dynamic-bot](https://github.com/Colter23/dynamic-bot)！

基于 DeepSeek API 的多功能 Agent 插件，为 dynamic-bot 提供 AI 对话、Dota2 战报分析、联网搜索能力。

## 功能

- **AI 对话** — 多轮对话，支持记忆（轮数/Token/TTL 三层保护），长文本自动渲染为图片
- **联网搜索** — ReAct Agent 模式，三层搜索后备 (Exa MCP → Parallel MCP → DuckDuckGo)
- **Dota2 战报** — OpenDota API + DeepSeek AI 分析，MVP/战犯识别，表格图片渲染
- **Dota2 历史** — 近期战绩卡片，英雄头像、彩色 K/D/A、胜负汇总与实时序号查询
- **Dota2 分析** — 双阵营全分析
- **Dota2 概览** — 玩家个人数据 + AI 位置侧写诊断

## 命令

| 命令 | 说明 |
|------|------|
| `/ds <问题>` | 发起 AI 对话 |
| `/ds clear` | 清空上下文 |
| `/dota 绑定 <9位ID>` | 绑定 Dota2 Steam 账号 |
| `/dota 战报 [序号或比赛ID]` | 关闭思考，出报更快；不填为最新一场；1～10 为实时最近第 N 场；其他正整数按比赛 ID 查询 |
| `/dota 深度战报 [序号或比赛ID]` | 开启思考，预计约需 1～3 分钟（实际可能波动）；参数规则与普通战报相同 |
| `/dota 历史` | 查看最近 10 场战绩 |
| `/dota 分析 <比赛ID>` | 双阵营全分析 |
| `/dota 个人详情 [ID]` | 玩家数据 + AI 诊断 |

除命令外，`@Bot 文本` 也可触发对话（需在配置中开启 `trigger.enableAtTrigger`）。

`/dota 战报 3` 和 `/dota 深度战报 3` 均可直接查询最近第 3 场，无需先发送 `/dota 历史`。历史图片从新到旧排列，与序号查询共用排序规则。新增比赛后实时序号会顺延；精确查询旧图中的某一场请使用该行比赛 ID。最近记录不足 N 场时会明确提示，不会回退查询其他比赛。

普通战报与深度战报在同一插件实例中共用一个任务名额（跨用户、群聊和聊天入口）。已有任务时立即提示等待完成后重新发送，本次请求不排队。名额覆盖查询、分析、绘图和向宿主提交消息，失败或取消后释放。宿主接受投递后释放名额，聊天端的实际送达由宿主负责。

战报生成时，AI 分析与图片资源准备并行执行；英雄及装备资源加载最多并发 6 个，同份报告的重复资源只解码一次。编码图片字节缓存上限为 16 MiB / 256 条，仍保留磁盘缓存。两种战报只切换本次请求的思考模式，模型、提示词、token 上限和重试策略相同；不会自动切换模式，也不会影响其他用户同时发起的请求。

服务器日志可搜索 `dota_report`：`detail` 为比赛详情，`constants` 为常量准备，`ai` 为分析请求（包含可能的重试），`assets` 为资源准备，`assemble` 为报告组装，`draw` 为绘图，`encode` / `encode_and_send` 为编码或编码发送。`analysis_and_resources` 为常量准备到组装完成的耗时；`ai` 与 `assets` 并行，不应将各阶段直接相加。单队战报日志附带 `mode=normal/deep` 和 `thinking=disabled/enabled`，日志不输出提示词、密钥或模型回复。

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
