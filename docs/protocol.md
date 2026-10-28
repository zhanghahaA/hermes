# Hermes API Server 协议与版本兼容

App 不绑定任何 Hermes 版本：连接时先探测服务器能力，再选择走哪条接口。
这份文档记录各版本接口的差异和 App 的应对方式，依据是 `NousResearch/hermes-agent`
源码 `gateway/platforms/api_server*.py`（0.16 的过程事件核对到 2026-10-23 上游 main）。

## 版本分档

| 档位 | 版本范围 | 有什么 | App 怎么做 |
|---|---|---|---|
| 不支持 | 早于 v2026.3.30 | 没有 API Server | 测试连接时提示“版本太旧，需要开启 API Server” |
| 基础 | v2026.3.30 – v2026.5.x | `/health`、`/v1/chat/completions`、`/v1/responses`；v2026.4 起有 `/v1/runs`；v2026.5 起有 `/v1/capabilities` 和审批事件 | 没有会话接口：会话列表为空并提示升级；聊天走 `/v1/responses`（阶段 2） |
| 完整 | v2026.6.19 起 | 会话接口 `/api/sessions/*`、`chat/stream`（`assistant.delta`）、`/v1/runs/*`、审批/追问 | 主要支持对象 |
| 增量 | v2026.8.16 起 | `/api/model/options` | 有就显示模型列表，没有就隐藏 |
| 增量 | v2026.9 起 | 会话 `pinned/archived/hidden`，消息 `display_kind` | 字段缺失时按默认值处理 |

版本号是日期式的（`v2026.9.24`），2026-10 起改为语义化版本（`v0.21.6` 起）。
App 不比较版本号，只看下面的能力探测结果；版本号只显示在诊断信息里。

## 能力探测（每个连接单独做，结果缓存）

1. `GET /health`（无需鉴权）→ `{"status":"ok","platform":"hermes-agent","version":"..."}`。
   连不上 / 不是这个结构 → 不是 Hermes 或没开 API Server。
2. `GET /v1/capabilities`（需要鉴权）→ 关键字段：
   - `features`：布尔开关，例如 `session_chat_streaming`、`approval_events`、`model_options`、`jobs_admin`。
   - `endpoints`：`名字 -> {method, path}`，**只列出当前实际可用的接口**。App 以它为准。
   - `auth.required`
3. 旧版本没有 `/v1/capabilities`（404）：退回“探测具体接口”——对 `GET /api/sessions?limit=1` 等做一次请求，
   404 视为不支持。

## 鉴权

- 所有接口（除 `/health`、`/v1/health`）都要 `Authorization: Bearer <API_SERVER_KEY>`。
- 新版本即使只监听 127.0.0.1 也强制要求 Key，且 Key 至少 16 位。
- 401 → 提示 Key 错误；其它错误体是 OpenAI 风格：`{"error":{"message","type","code","param"}}`。
- 多 Profile：路径前缀 `/p/<profile>/`，每个 Profile 用自己的 Key。

## 会话（v2026.6.19 起）

`GET /api/sessions?limit=50&offset=0` →
```json
{"object":"list","data":[Session...],"limit":50,"offset":0,"has_more":false}
```
置顶的会话会额外补在最后（超出 limit），`has_more` 只按非置顶计算。

Session 字段（都可能缺失，App 全部按可选处理）：
`id`、`source`、`model`、`title`、`started_at`、`last_active`（秒级时间戳，浮点数）、
`message_count`、`preview`、`parent_session_id`、`pinned`、`archived`、`hidden`（v2026.9 起）、
`is_internal_child`（新版本）。

- `source == "api_server"`：App 或其它 API 客户端建的会话。
- 其它值（`cli`、`tui`、`telegram`、`cron`……）：显示来源标签。能不能继续聊由服务器决定：
  发消息返回 409 `permission_denied` 时，App 把它标成只读并提供“分叉后继续”。

`GET /api/sessions/{id}` → `{"object":"hermes.session","session":{...}}`

`PATCH /api/sessions/{id}`：可改 `title`、`pinned`、`archived`、`hidden`、`unread`（布尔）。
传了服务器不认识的字段会返回 400 `unsupported_session_field`（旧版本没有 `pinned` 等），
App 遇到这个错误就隐藏对应操作。返回 `{"object":"hermes.session","session":{...}}`。

`DELETE /api/sessions/{id}`：删除会话。

## 消息

`GET /api/sessions/{id}/messages`（默认返回最新 500 条，`order=latest`）→
```json
{"object":"list","session_id":"...","data":[Message...],"pagination":{...}}
```
注意返回的 `session_id` 可能和请求的不同（会话压缩后的续接），App 以返回值为准。

Message 字段：`id`、`role`（`user` / `assistant` / `tool` / `system`）、`content`、`timestamp`、
`tool_calls`、`tool_call_id`、`tool_name`、`reasoning`、`reasoning_content`、`finish_reason`、
`display_kind`（新版本）。

### 正式历史消息与可选的实时过程

App 只保留：
- `role == "user"` 且有文字或图片；
- `role == "assistant"` 且 `content` 有文字（只有 `tool_calls`、没有文字的中间步骤丢弃）。

一律丢弃：`role == "tool"`、`role == "system"`、`display_kind == "hidden"`，
以及所有消息上的 `reasoning`、`reasoning_content`、`tool_calls` 字段——解析时就不读进来，
所以正式消息、本地历史缓存、引用功能不含思考内容。0.16 的“思考与工作过程”另从本轮 SSE
事件收集，独立展示在 `ChatItem.Activity` 中，默认关闭；不会把历史消息中的 reasoning 倒灌进正文。

`content` 可能是字符串，也可能是 OpenAI 风格的分段数组（`[{"type":"text","text":...},{"type":"image_url",...}]`），
两种都要处理。

## 聊天（阶段 2）

### 为什么走 `/v1/runs`，不走 `/api/sessions/{id}/chat/stream`

`chat/stream` 在 SSE 断开时会**中断正在运行的任务**（源码：`SSE client disconnected; interrupted live run`）。
手机切后台、锁屏、换网络都会断开，所以 App 用 `/v1/runs`：提交任务和订阅事件是分开的，
断开只是取消订阅，任务继续跑；每个事件带 `seq`，重连时用 `Last-Event-ID` 补回漏掉的事件。

### 发消息

`POST /v1/runs`，头部 `Idempotency-Key: <本地消息 id>`（网络重试不会重复执行），
`X-Hermes-Session-Id` 不需要，会话写在 body 里：
```json
{"input": "用户说的话（带引用时前面是 > 引用块）", "session_id": "<会话 id>"}
```
→ 202 `{"run_id":"run_...","status":"started","replayed":false}`。
会话第一次发消息前先 `POST /api/sessions {"title": ...}` 建会话（返回 201 和 session）。

### 事件 `GET /v1/runs/{run_id}/events`

SSE，每帧 `id: <seq>`，`data` 是 JSON，事件名在 `data.event` 里：

| 事件 | App 怎么处理 |
|---|---|
| `message.delta` `{delta}` | 打开逐字显示或过程模式时实时累积正文；已显示文字保留，下方继续显示输入圆点（可关闭） |
| `message.interim` `{text, already_streamed}` | 公开的阶段性回复；`already_streamed=true` 不重复拼接刚才的 delta，下一段另起段落 |
| `reasoning.available` `{text}` / `reasoning.delta` `{text 或 delta}` | 仅开启“思考与工作过程”时收集、显示；不进入正式回复、引用或后续请求 |
| `tool.started` / `tool.completed` / `tool.failed` | 仅分隔公开正文，绝不擦除；过程模式记录工具名、预览、完成或失败状态，用 `tool_call_id` 配对 |
| `tool.progress` | 过程模式追加工具预览；`tool_name=_thinking` 视作思考内容，不是正文分段边界 |
| `subagent.*`、未知事件 | 忽略，继续处理后续事件 |
| `approval.request` | 显示审批卡片。字段：`command`、`description`、`choices`（`once/session/always/deny` 子集），新版还有 `prompt_id`、`execution_generation` |
| `approval.responded` / `approval.settled` | 卡片收起 |
| `clarify.request` | 追问卡片：`question`、`choices`、`prompt_id`、`execution_generation`、`multi_select`。`question` 末尾的 `Reply "skip"…` 提示和选项上的 `(Recommended)` 后缀 App 去掉再显示（Hermes 匹配回答时本来就忽略这个后缀） |
| `run.completed` `{output}` | 最后一段以最终正文对齐，避免重复；此前展示的公开旁白留在 Activity 中 |
| `run.failed` `{error}` / `run.cancelled` / `run.interrupted` | 结束“正在输入”，保留已展示的正文，失败时提示原因 |
| `replay.truncated` | 保留已收到的内容；过程模式提示部分事件已过期。流结束后查询状态、结束后重新拉正式历史 |

注释行 `: keepalive`、`: open`、`: stream closed` 跳过。服务器版本/执行路径决定是否发送思考内容。
核对的上游 `_make_run_event_callback` 可发送 `reasoning.available`，但仍会过滤 `_thinking` / 子工具噪音；
有的执行路径仅提供工具开始/结束。App 不伪造没有收到的思考，也不修改服务器配置来开启它。

过程数据只在当前连接的内存中保留：每轮最多最近 100 个步骤，每步最多 8,000 字符；关闭开关立即清空已收集的步骤，
关闭期间不收集。切换连接/Bot 或重启后仅恢复服务器正式历史和未结束任务可补发的事件，已过期的过程不能恢复。
流式模式保留的阶段性旁白也属于本机 Activity，不写回服务器、不混入正式历史缓存。

### 状态、审批、追问、停止

- `GET /v1/runs/{id}` → `{"status": "queued|running|waiting_for_approval|stopping|completed|failed|cancelled|interrupted", "output", "error", "approval"}`。
  断线重连失败、或 App 被杀后重新打开，用它判断任务是否已经结束。
- 审批 `POST /v1/runs/{id}/approval`：事件里有 `execution_generation` 时发
  `{"request_id": <prompt_id>, "execution_generation": ..., "choice": ...}`，否则发 `{"choice": ...}`（有 `request_id` 就带上）。
- 追问 `POST /v1/runs/{id}/clarify`：`{"request_id", "execution_generation", "answer"}`。
  **Hermes 默认不给 API Server 追问工具**（`hermes-api-server` 工具集去掉了 clarify），要在 `config.yaml` 里加：
  ```yaml
  platform_toolsets:
    api_server: [hermes-api-server, clarify]
  ```
  新版追问工具的参数是 `questions` 数组（一次最多 5 个问题），每个问题单独发一个 `clarify.request`。
- 新版（session authority）的 `GET /v1/runs/{id}` 有 `pending_controls`（等待中的审批/追问，格式同事件）和顶层 `execution_generation`；
  App 重连、重启后用它补上卡片。
- 停止 `POST /v1/runs/{id}/stop` → `{"status":"stopping"}`，随后事件流收到 `run.cancelled`。

### 其它

- 并发上限 429、gateway 繁忙 503（带 `Retry-After`）：按服务器给的时间退避重试。
- 不允许从 API 继续的会话：提交返回 409 `permission_denied` → App 把会话标为只读，提供“分叉后继续”。
  409 `session_active_turn`：这个会话正在别处回复，提示稍后再发。
- 分叉 `POST /api/sessions/{id}/fork {"title"?}` → 201，复制整个会话（不能只复制到某条消息）。

### App 端的实现要点

- 新建会话先是本地草稿，第一条消息发出时才 `POST /api/sessions`；界面一直用草稿 id，内部映射到服务器 id。
- 本地还没被服务器记录确认的条目（自己刚发的消息、刚收到的回复、审批/追问卡片）和服务器记录合并显示；
  一轮结束后重新拉记录，以服务器为准。卡片不在服务器记录里，按“它前面有几条消息”插回原位。
- 服务器记录里 `finish_reason == "tool_calls"` 的回复是调用工具的中间步骤，不显示。
- 用户消息开头是 `> ` 引用块的，拆成引用样式显示，并找回被引用的那条回复。
- 等待中的轮次（连接 id、会话 id、run id）存在手机上；App 被杀后重新打开会接着订阅（不带 `Last-Event-ID`，服务器从头补发），
  或直接从状态接口拿结果。
- Android：有轮次在等时开前台服务（`dataSync` 类型）保持连接；App 不在前台时回复到了、需要审批都会发通知，点通知直接打开那个对话；
  系统报告网络恢复时立刻重连，不等退避时间。

## 图片和文件（阶段 4）

- `/v1/runs` 的 `input` 可以是消息数组：`[{"role":"user","content":[{"type":"text","text":...},{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]}]`。
  只收 png/jpeg/gif/webp，一次最多 10 张（`session_api_media._IMAGE_LIMIT`）。
- Hermes 把图片存到 `<HERMES_HOME>/cache/documents/native-inputs/<图片内容的 SHA-256>/api_<前 32 位>.<扩展名>`，
  聊天记录里的用户消息只留文字，后面追加 `[Image attached at: <路径>]` 和 `[screenshot]` 两行。
  App 发图时按同一个 SHA-256 在本机缓存一份，读记录时据此找回原图。新版存档里的 `hermes-retained:<sha256>` 也按这个规则找。
- 模型不支持看图时，Hermes 先调视觉辅助模型描述图片，主模型收到的是描述文字。
- API 不收其它文件（`/v1/artifacts/upload` 是浏览器扩展用的，默认关闭，模型也读不到）。App 的做法：
  文本文件把内容写进消息（`📎 附件：<名字>` + 代码块）；PDF 渲染成图片；其它文件 SSH 用 SFTP 传到 `~/hermes-uploads/`，
  本机 Termux 存到 `/storage/emulated/0/Download/Hermes/`，消息里写 `📎 附件：<名字>（已保存到 <路径>，需要时请用工具读取）`。
  读记录时这些段落拆回文件卡片。

## 模型（阶段 4）

- `GET /api/model/options`：`{model, provider, providers:[{slug, name, is_current, authenticated, models:[...], capabilities}]}`。
  App 只列出已登录（`authenticated`）或当前在用的 Provider，当前的排最前，标“Hermes 默认”。
  注意：这个接口会去查各 Provider 的模型清单，某些 Provider（如 bedrock）可能触发 Hermes **按需安装依赖**到 `~/.hermes/installs`。
  测试环境里遇到过装完后 Python 环境被切走、gateway 起不来的情况；不想要的话在 `config.yaml` 里设 `security.allow_lazy_installs: false`。
- `POST /api/sessions/{id}/model` `{"model":..., "provider":...}`：锁定这个会话之后每一轮用的模型（Hermes 叫 session model lock）。
  App 在草稿上选的模型，会在第一次发送、服务器建好会话之后、发第一条消息之前锁上。
- Hermes 自己的默认模型 API 改不了。App 的“新对话默认模型”存在手机上（按连接分别存），新建对话时用上面的接口锁定。

## 定时任务（阶段 4）

- `/api/jobs` **不在 `/v1/capabilities` 里**，只能试：`GET /api/jobs?include_disabled=true` 能读到就显示“任务”页，404/501 就隐藏。
- `POST /api/jobs` `{name, prompt, schedule, deliver}` 新建；`PATCH /api/jobs/{id}` 修改；`DELETE` 删除；
  `POST /api/jobs/{id}/pause|resume|run`。返回 `{"job": {...}}`，出错时是 `{"error": "..."}`（App 原样显示）。
- `schedule` 是一个字符串：cron 表达式（`0 8 * * 1-5`）、间隔（`every 30m`、`every 2h`）、或 ISO 时间（只跑一次）。
  读回来是 `{kind: cron|interval|once, expr, minutes, run_at, display}`。App 把常见写法换成“每天/每周几/间隔/一次”的选择器，
  其它写法放在“自定义”里原样保留。
- “立即运行”只是把 `next_run_at` 改成现在，Hermes 的调度器下一轮（约 1 分钟内）才真正执行。App 隔几秒查一次 `last_run_at`，变了就刷新会话列表、发通知。
- 每次运行在会话列表里多出一个 `source: "cron"`、标题为 `<任务名> · <日期>` 的会话；第一条用户消息前面有一大段 `[IMPORTANT: You are running as a scheduled cron job …]`，
  App 在预览和聊天里都去掉。模型回复 `[SILENT]` 表示没有新内容。
- `deliver`：`local`/`origin` 只存在 Hermes 里（App 里能看到）；`telegram`、`discord`、`slack` 等需要 Hermes 那边配好对应平台。

## 技能和工具集（阶段 4）

- `GET /v1/skills` → `{"data":[{name, description, category}]}`。**v0.22 canary 里这个接口是坏的**：
  它调用 `_find_all_skills(include_editorial=True)`，而 `tools/skills_tool.py` 里的函数没有这个参数，所以总是 500 "Failed to enumerate skills"。
  App 遇到 500 时，如果连接配了 SSH，就直接读 `~/.hermes/skills/<分类>/<名字>/SKILL.md` 开头的 `name` / `description`
  （Profile 是 `~/.hermes/profiles/<名字>/skills`）；没有 SSH 就显示一句说明。
- API 不展开 `/技能名` 这种命令（只有聊天软件那条路和桌面版的 WebSocket 会）。App 的做法：消息以 `/技能名` 开头时，
  在这一轮的 `instructions` 里加一句“用 skill_view 加载这个技能并照着做”，聊天记录里仍是你打的原话。
- `GET /v1/toolsets` → `{"data":[{name, label, description, enabled, configured, tools:[...]}]}`，只读。
  开关要改 `config.yaml`：App 经 SSH 执行 `hermes [-p <profile>] tools enable|disable <名字> --platform api_server`。
  Hermes 每一轮都重新读配置，不用重启，下一条消息起生效。

## 插话（steer）

- `POST /v1/runs/{run_id}/steer` `{"input": "..."}`：Hermes 还在回复（比如在跑工具）时补充一句，不打断它；
  它在下一次调用模型前看到。这一轮已经不在运行时返回 409 `run_not_accepting_steer`。
- 插话存进聊天记录是一条单独的用户消息，`display_kind: "steer"`，内容包着
  `[OUT-OF-BAND USER MESSAGE — …]` … `[/OUT-OF-BAND USER MESSAGE]`，App 显示时去掉。
- 这一轮在交给模型之前就结束了的话，结束事件和运行状态里带 `pending_steer`，Hermes 不会自动补发，App 把它作为下一条消息发出去。
- App 里：Hermes 回复时输入框空着，按钮是“停止”；打了字就变成“发送”（插话）。带图片/文件或引用的消息不能插话，等这一轮结束再发。

## Profile

- `/p/<profile>/...` 访问另一个 Profile。每个 Profile 用**它自己的** `API_SERVER_KEY`，不会退回默认 Profile 的 Key。
- 没有列出 Profile 的接口。名字不对（或没开 `gateway.multiplex_profiles`）时返回 404 `{"error":"Unknown or unconfigured profile"}`，App 会直接说明。
- 单 Profile 的 gateway 上，`/p/default/` 只在它本来就跑在默认 Profile 时可用。

## SSH 隧道与连接诊断

- 本地监听和 HTTP URL 一律使用 IPv4 `127.0.0.1`；SSH 目标仍是服务器自己的 `127.0.0.1:<API 端口>`，不需要把 API 暴露到公网。
- 仅 SSH 隧道客户端显式设置 `Proxy.NO_PROXY`，REST 和 SSE 都直达本地入口；直连、HTTPS、本机 Termux 的代理配置保持原行为。主机指纹、认证和 SFTP 不变。
- `probeForward()` 只开关一条 direct-tcpip 通道，不发送 HTTP。真正的网络检查必须由 OkHttp 发送 `GET /health` 并读取响应。收到 401/403/404/5xx 或不符合预期的 JSON，不能再统一提示远端端口没有监听。
- 单条通道被拒绝时，只有 SSH 会话确实失效才重连，避免打断同会话中的聊天；旧会话的失败不能清掉已建立的新会话。转发双向分别处理 EOF，去掉固定两秒后截断仍在上传的行为；关闭隧道时主动关闭已接受的 socket 和通道。
- `ConnectionDiagnostics` 仅在内存保留最近 4 次 SSH 测试 / 正式连接，每次最多 180 个事件；构造参数 `enabled=false` 可关闭记录。记录匿名 id、相对耗时、回环端口、HTTP 状态、代理类型、上下行首批 / 总字节、结束类型和异常类名，绝不记录真实主机、凭据、Header、正文、聊天、文件名或异常 message。设置中「导出诊断信息」包含这些事件。
- 看不到 `LocalAccepted`：请求尚未进入本地转发器；有通道但无 `UpFirstBytes`：尚未转发请求字节；有上行无下行：检查远端响应和 SSH 通道；有 `HTTP=200` 后失败：检查读取 / JSON 解析阶段。不要把仅有 TCP 握手当成健康检查已到达。
- HTTP 收到响应头后的正文读取和 SSE 也响应协程取消，释放响应并解除阻塞；正文超时保留原始异常类别。Android 实机自检步骤见 [ssh-tests.md](ssh-tests.md)。JVM 测试通过不能代替 Android / 用户网络验证。

## 管理功能（阶段 5，经 SSH）

API 管不到的都经 SSH 做，尽量用 `hermes` 自己的命令（`-p <profile>` 跟连接设置走）：

| 功能 | 做法 |
|---|---|
| 找到 Hermes 目录 | `hermes config path`（输出 config.yaml 的绝对路径，Profile 也对） |
| 记忆 | 直接读写 `<目录>/memories/USER.md`、`MEMORY.md`：条目之间是 `\n§\n`；上限来自 `hermes config get memory --json`（默认 1375 / 2200 字）。写入先写临时文件再 `mv` |
| MCP | `hermes config get mcp_servers --json` 读；`config set mcp_servers.<名>.url/command/args/enabled`、`config unset` 改。`hermes mcp add` 会停下来问问题，不用它。gateway 会自动按新配置重连 |
| MCP 测试 | `hermes mcp test <名> < /dev/null`，输出原样显示 |
| 配置文件 | SFTP 读写 config.yaml。保存前把新内容放进 `mktemp -d` 的临时目录，用 `HERMES_HOME=<临时目录> hermes config get model --json` 让 Hermes 读一遍：格式有错它会输出 “formatting error … at line N … Details: …”（**退出码仍是 0**），这时不覆盖；没问题才备份为 `config.yaml.mobile-backup` 再替换 |
| 日志 | `hermes logs -n 200` |

`hermes config get` 对没设过的键输出 “Config key not set” 并以非 0 退出，按“空”处理。

Gateway 状态走 API：`GET /health/detailed` 有 `readiness.checks`（数据库、配置、模型、磁盘……）、`platforms`（各聊天平台的连接状态和错误）、`metrics_today`、`active_agents`。

## 图片的原文件

不同版本的 Hermes 对 API 收到的图片处理不一样：新版存到 `<HERMES_HOME>/cache/documents/native-inputs/<sha256>/`，
并在消息后面加 `[Image attached at: <路径>]`；有的版本只留 `[screenshot]`，主模型不支持看图时只拿到辅助模型写的文字描述。
所以只要连接能传文件（SSH，或本机 Termux 的下载目录），App 发图时还会把原文件传一份到 `~/hermes-uploads/`，
在消息里加一行 `📎 图片文件：<路径>（……的原文件，需要上传、保存或用工具处理图片时用这个路径）`，界面上不显示这一行。

图片编码：长边不超过 2048；只有真有透明像素才用 PNG（Android 几乎把所有 PNG 都标成有透明通道，截图存 PNG 会很大），
单张超过 3.5 MB 就降质量再缩小。

## 会话分组（项目）

API 的会话列表里没有项目信息。Hermes 桌面版的分组来自两处：`<HERMES_HOME>/projects.db`（项目和它们的文件夹）
和 `state.db` 里每个会话的工作目录（`cwd`、`git_repo_root`）。App 经 SSH 用 `python3` 只读地查这两个库：
工作目录落在某个项目的文件夹里（取路径最长的）就归到项目名下，否则按工作目录（git 仓库根优先）的文件夹名，
都没有的归到“默认”。

**新建项目**：经 SSH 运行 `hermes project create <名字> <文件夹>`（文件夹在服务器上一层层点选，`cd 目录; pwd; ls -1p` 列子文件夹）。

**在 Hermes 自己的 Python 环境里跑脚本**（移会话、删项目、接管屏幕、屏幕画面桥都要 import Hermes 的代码）：
新的源码安装里 `hermes` 启动器是“自带的 Python + `-I` + 引导代码（`hermes_bootstrap`）”，直接用那个 Python 是 import 不到 Hermes 的。
App 先跑 `hermes --print-runtime-command`，拿到 Hermes 启动自己的那条命令（`[python, "-I", "-c", 引导代码]`），把引导代码最后的
`runpy.run_module("hermes_cli.main")` 换成运行 App 的脚本；旧的安装方式（venv / pip）没有这个参数，就顺着启动脚本找 venv 里的 Python。
脚本先写进临时文件，读完就删，stdin/stdout 留给脚本。

**删除 / 重命名项目**：`hermes_cli.projects_db.delete_project` / `update_project`（桌面版的 `projects.delete` / `projects.update` 调的也是它们）。
删除只删项目记录和它的文件夹登记，对话的工作目录不变。

**把会话移进项目**：和桌面版拖动会话一样，改的是会话的工作目录。桌面版调用的是
`SessionDB().update_session_cwd(id, cwd, branch, repo_root, replace_git_meta=True)`；App 经 SSH 找到 `hermes` 命令所用的 Python
（顺着启动脚本找 venv 里的 python），用 `HERMES_HOME` 跑同一个函数；导入不了 Hermes 的代码时退回直接改 `state.db` 的
`cwd / git_branch / git_repo_root / git_metadata_generation`。移出项目就是把工作目录清空。会话 id 先校验（只允许字母数字和 `_.:-`），不进 shell。

**项目里的新对话**：API 新建会话不能指定工作目录，所以第一轮发出之后再按上面的办法移进去；同时每一轮 `/v1/runs`
带上 `X-Hermes-Workspace` 请求头（Hermes 还没合并，见 PR #78923，合并后就生效），并在 `instructions` 里告诉模型这个对话的工作文件夹。
Hermes 的 API 运行目前不读会话的 `cwd`（issue #29531），说明里的文件夹就是让模型知道该在哪里干活。

**中文 / Unicode 目录**：OkHttp 的普通请求头只接受可打印 ASCII。`X-Hermes-Workspace` 仅在目录非空且全部为可打印 ASCII 时保留；中文、重音字符、emoji、换行等路径不放进该兼容头，完整路径仍通过 UTF-8 JSON 的 `instructions` 传递。不要擅自 URL 编码或用不安全 Header API 绕过校验：服务端没有约定的解码逻辑，会造成目录变化。该修复覆盖项目中新会话的首条消息和已有会话续聊，不要求改名或修改服务器。

发送准备阶段的 `IllegalArgumentException` 要转为消息发送失败并恢复空闲，提示中不包含原异常 message（可能带路径或凭据）。失败消息仍保留原附件供重试，但不会阻塞后续消息等待 60 秒。测试见 `WorkspaceChatTest`，使用本机假 API 和真实 OkHttp；不等同于真实 Hermes 工作目录执行验证。

## 归档

`PATCH /api/sessions/{id}` 带 `{"archived": true}`。Hermes 的会话列表不返回归档的会话（也没有参数能列出来），
所以 App 在本机记住自己归档的会话（`archived.json`），有 SSH 时再从 `state.db` 读出所有 `archived = 1` 的会话，
合起来显示在“已归档”里。取消归档就是 `{"archived": false}`。刷新列表时如果归档的会话又出现了，说明在别处取消了归档，App 也跟着取消。

## 客户端说明（instructions）

`/v1/runs` 的 `instructions` 字段是只对这一轮生效的补充系统提示（Hermes 里叫 ephemeral system prompt，追加在系统提示后面，不存进聊天记录）。
Hermes 对 API 客户端的默认提示是“渲染层未知，别用 Markdown；runs 接口不处理 MEDIA:，只写纯文本路径”。App 每轮都附带一段说明，
告诉模型这个客户端能渲染 Markdown、能收任意文件（用 `MEDIA:/绝对路径`）。实测（gemini-2.5-flash）：不带说明时模型只说“已生成文件”，
带上后回复 `MEDIA:/root/sugar.md`。

## Bot 屏幕

Hermes 在 Linux 主机上给每个 Profile 开一个 TigerVNC（Xvnc）+ Xfce 的桌面，只通过 `<HERMES_HOME>/bot-desktop/rfb.sock`（0600 的 Unix socket，不要密码）
输出 RFB。电脑版经 dashboard 的 `/api/ws`（`display.observe` 换一次性票据）→ `/api/display/ws` 转成 WebSocket 给 noVNC。
那是 dashboard 的内部接口，鉴权复杂且要 dashboard 在跑，所以 App 走 SSH：

- 状态和启动：`hermes computer-use screen status --json` / `hermes computer-use screen start`。
- 画面：SSH 命令通道里跑一个小 Python 桥（用 Hermes 自己的 Python），`runtime.rfb_socket_path()` 接到 socket；屏幕在沙盒里时用 `runtime.open_rfb_stream()`。
  stdin/stdout 就是 RFB 字节。
- 接管 / 交还：调 Hermes 的 `tools.bot_desktop.lease.acquire(viewer_id)` / `release(viewer_id)`（强制交还是 `release(None)`），和电脑版写的是同一个 `lease.json`。
  Hermes 只公开 `viewer_id` 的 sha256 前 12 位，App 用它认出是不是自己接管的；每 4 秒查一次，别处接管了就回到只看。
  直接接 socket 绕过了 dashboard 那层按 lease 过滤输入的网关，所以 App 自己保证没接管时不发输入（noVNC `viewOnly`）；Hermes 文档也说明了 lease 是工具层的约束，同一系统用户本来就能直接连 socket。
- 查看器：noVNC 1.7.0（电脑版也是这个版本），用 `tools/novnc/build.sh` 打包成一个 ES 模块内联进 `assets/screen/screen.html`（有顶层 await，WebView 的 file:// 加载不了外部模块）。
  noVNC 支持把“像 WebSocket 的对象”当通道，页面里的 `NativeChannel` 把字节 base64 后交给 `HermesScreen.send`，App 收到的字节用 `hm.recv` 送回页面。页面不联网（`blockNetworkLoads`）。

## Bot（Profile）

电脑版的 Bot 就是 Profile，固定对话是标题恰好为 `Bot Chat` 的会话。App 经 SSH 用 Hermes 自己的 Python 读写：

- 列表：`hermes_cli.profiles.list_profiles()` + `profile.yaml`（`display_name`、`description`、`ui_meta["hermes-bots"]` 里的 `title` / `color`）
  + `assets/avatar.*` + 各自 `state.db` 里的 `Bot Chat` 和最近的会话。显示名的顺序和电脑版一样：`title` → `display_name` → 默认 bot 叫 Hermes → id。
- 聊天：同一个 gateway 的 `/p/<bot>/...`，用 bot 自己 `.env` 里的 `API_SERVER_KEY`（主 key 在别的 Profile 上是 401）。key 是每次请求现读的，
  只有第一次打开 `gateway.multiplex_profiles` 要重启 gateway。没装成系统服务时 `hermes gateway restart` 会在当前进程前台跑，所以 App 用 `setsid nohup … &` 脱离 SSH 会话。
- 新建 / 编辑：`import tui_gateway.server` 后调它的 `_methods["profiles.create" | "profiles.describe" | "profiles.configure" | "profiles.set_asset"]`，
  就是电脑版 Bot 用的 JSON-RPC 方法（新建默认 `clone_from: "default"`、`share_auth: true`，和电脑版一样）。这个模块一 import 就把 `sys.stdout` 换成 stderr，
  脚本先 `dup` 一份真正的 stdout 再输出结果。`ui_meta["hermes-bots"]` 是整个替换的，App 先读出原来的再合并。
  `profiles.configure` 改模型失败时只回 `false`，App 再直接调 `_write_profile_model` 拿 Hermes 给的原因。贵的模型会回 `confirm_required`，用户确认后带 `confirm_expensive_model` 重发。
- 管理 bot 的脚本把 `HERMES_HOME` 设成 `get_default_hermes_root()`：当前用的可能是某个 bot 的目录，而新建要从主目录复制设置。
- 删除：`hermes_cli.profiles.delete_profile(name, yes=True)`（先停它的 gateway、写墓碑再删目录；直接 `rm -rf` 会被正在跑的 gateway 重新建出来）。

## 群聊（hosted rooms）

Hermes 现在的群聊（电脑版叫 canonical group）完全在 gateway 进程里跑：房间和记录存在 `hosted_rooms` / `hosted_room_events` 表里，
每一轮由 gateway 的 driver 调度（先回答 `@` 到的，没 `@` 就所有人；之后只有被别的 bot `@` 到的再答；最多 3 轮、10 条；回 `(pass)` 算不说话）。
客户端只发消息、读记录。接口只有 gateway 的 `/api/ws` JSON-RPC（`groups.*`），API Server（8642）上没有。

App 的做法：SSH 命令通道里跑一个小桥（Hermes 自己的 `hermes_cli.gateway_client`）：

- `discover_gateway_endpoint()` 找正在跑的 gateway（不会去启动它），经本机控制 socket 的 `session-ticket` 换一张一次性票据。
  票据要 `scope: "host"`：只有 host 范围的连接才能把别的 Profile 拉进群（否则 `groups.create` 报 `permission_denied`：`only a host-scoped connection seats a served profile`）。
- 连上 `/api/ws` 后，stdin 一行一个 `{id, method, params}`，stdout 一行一个 `{id, result | error}`，第一行是 `{ready}`。一条 SSH 通道一直开着，断了下次调用时重开。
- 所有调用带 `profile: "default"`，房间建在默认 Profile 的库里；房间的主人是连接的身份（同一台机器的同一个用户，和电脑版一样），别的身份建的群看不到。
- 用到的方法：`groups.list` / `create`（`room_id` 客户端生成的 UUID，成员 `{member_id, profile, handle, display_name, target:{kind:"local", profile}}`，默认 bot 的 handle 是 `hermes`）/
  `state`（`driver_status.working`、`pending_actions`）/ `log`（`since_seq` 增量）/ `send`（`payload: {text, thread_id}`，和电脑版一样 `thread_id = event_id`）/
  `stop` / `rename` / `disband` / `approve` / `retry` / `discard`。没有推送，App 有 bot 在回复时 1.5 秒读一次，平时 4 秒。
- 显示：`message.user` / `message.member` 是消息；`turn.failed` / `turn.cancelled` / `turn.deferred` / `room.activity`（`status: bounded`）等显示成一行提示；其它事件不显示。
- 每个 bot 在自己的 Profile 里有一个隐藏会话（标题 `Group: <room_id>`，来源 `bot_room`）跑它的回合。


## Termux 命令回包（0.13.4）

- `RUN_COMMAND_PENDING_INTENT` 返回 `result` Bundle。`err` 使用 Android 的结果码：`-1`（`Activity.RESULT_OK`）表示调用成功；shell 自身的 `exitCode=0` 才表示命令成功。两者不能混用。缺失 `err` 或成功回包缺失 `exitCode` 视为不完整结果。
- `RuntimeResultReceiver` 将实际 Bundle 字段交给共享 `decodeTermuxResult`，桌面测试直接验证同一解析器。`err=0` 是拒绝／取消，不当作成功。依据：[Termux v0.118.3 Errno](https://github.com/termux/termux-app/blob/v0.118.3/termux-shared/src/main/java/com/termux/shared/models/errors/Errno.java)、[回包字段](https://github.com/termux/termux-app/blob/v0.118.3/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java)。
- 回包错误只保留固定分类及数字码。包含 `allow-external-apps` 的拒绝显示一次初始化入口，其它失败不要求盲目重复粘贴命令。原始 `errmsg` 可能含命令／凭据，不写到界面或复制报告；shell 失败继续按原有脱敏规则展示，结构化输入操作不回显原始输出。
- 页面进入／从 Termux 或权限设置返回时只执行检查，不自动安装。安装主按钮旁说明修改范围；一次点击连续检查、准备缺失依赖、安装缺失程序并配置连接。失败重试重新探测状态，已完成的软件安装复用。0.13.5 起，只读检查超时／取消会注销回调并释放自己的占位，新检查使用新的标识；迟到旧回包不能完成或释放新命令。安装、配置、模型设置等修改操作超时仍保留“后台任务状态未知”的保护，直到实际回包，不靠重试强制解除。

### Termux 软件包版本冲突（0.13.6）

未安装 Hermes 时，Inspect 额外运行本地 `curl --version`（只读、无网络）。只有无结构化输入的失败命令同时出现 curl、`CANNOT LINK EXECUTABLE` 和 `cannot locate symbol` 才映射为 PackageMismatch；显示固定说明，诊断承认命令回包已送达。用户点击明确说明更新范围的修复按钮后，执行 `apt-get -o APT::Update::Error-Mode=any update` 与 `apt-get -y --no-remove -o Dpkg::Options::=--force-confold dist-upgrade`，再检查 curl。保留配置并拒绝移除包，不绕过签名，不自动切源；任一步失败就停止，不继续安装。修复属于修改操作，保留等待实际回包的防重入保护。脚本测试使用替代 APT/curl 程序，不能作为真实 Termux 包升级已验证的依据。

### 官方安装源不可用（0.13.7）

安装脚本先获取当前通道的 key.asc 和 InRelease（仅检查可下载，不将 HTTP 200 视为签名或运行通过）。curl 的明确 404／410 映射为退出码 44 与固定标记 HM_REPOSITORY_UNAVAILABLE；后端同时核对码与 stderr 整行标记，再映射为 RepositoryUnavailable。普通 HTTP 失败、超时和其它程序的 404 不套用此分类。失败前不安装 GnuPG、不写入软件源、不切换通道；仅 curl 本身缺失时需先补齐。manager 先安装自带运行时的 Hermes，再准备 App 的 Python 管理环境，避免源不可用时先做无效准备。

可下载后仍验证单一主公钥及固定指纹，再由 APT 验签安装。不会绕过签名或自动切换到 canary。重试入口明确是「重试安装」，成功后可继续修改环境，不把它伪装成只读检查。上游 stable 当前返回 404，且文档警告 Termux 包暂时无法运行；文件可下载及脚本替代命令测试都不等于真机安装成功。

### 国内依赖镜像（0.13.8）

`DomesticPackageSources` 为 App 的包安装命令生成独立 `~/.hermes-mobile/apt/sources.list` 和 `lists` 缓存；显式传递 `Dir::Etc::sourcelist`、`Dir::Etc::sourceparts=-`、`Dir::State::lists`，避免读取全局其它源与旧缓存。清华 Termux main/root/x11 来源已与 Termux 官方 mirrors/chinese_mainland 名单核对。安装 Python、GnuPG、Chromium、Caddy 与包冲突修复全部通过该 APT 入口，源更新使用 Error-Mode=any，失败不继续安装，不使用 pkg 自动选择镜像。Hermes 自身仍使用经过指纹校验的官方签名源；下载其公钥和索引前的镜像初始化不纳入已有 Hermes 源，避免旧通道影响前置工具下载。

原全局 Termux／pip 源配置不覆盖。独立列表只加入三类 Termux 源及既有 Hermes 源，不自动复制未知第三方源；因此不能宣称会升级所有第三方软件。Python 服务的 pip 子进程清除继承的 PIP_* 参数，设置 PIP_CONFIG_FILE=/dev/null、清华 PIP_INDEX_URL 及禁用版本检查；构建子进程继承相同设置，不污染服务器、模型端点或服务运行环境。没有添加 trusted=yes、allow-unauthenticated 或关闭 TLS 验证。镜像可访问和假命令脚本测试不等于真实手机安装已验证。

### 本机配置门槛与连接恢复（0.13.9）

`RuntimeSnapshot.modelConfigured` 与手机接口的 `configured` 分开。Android 将 providers/settings.py 注入实际 Termux 后端，Inspect 在同一个 HERMES_HOME 下通过 Hermes 自带 Python 读取 HMMODELS 摘要，要求有效回包、提供方与模型，以及账号／Key 摘要（本地端点除外）；未知或不完整模型回包不标记就绪。模型 inspect/models 命令标为只读，可在超时后释放等待；save/login 仍是修改命令。未配置时 connect 不启动服务，页面进入配置步骤，不自动读取终端默认目录中的密钥。

本机保存后 connect(restart=true) 依次 Inspect → StopHermes → ConfigureHermes → StartHermes → 验证并激活连接。只读前置检查 45 秒超时，接口冷启动重试最多 90 秒；鉴权与能力失败立即报告固定分类，不回显服务响应或凭据。取消／切换连接不强制切回本机。连接锁释放后才等待正式连接完成，避免 ensureRunning 重入死锁。已有未配置／不完整 Hermes 不会被当作可聊天状态。

卸载入口从高级功能移到主页面，保留一次明确范围确认。Python 停止自己记录的 Hermes 进程，再执行 apt-get remove --no-auto-remove -y hermes-agent，不触网、不清理配置／会话／其它服务。manager 核对卸载后的程序检测结果，仍有其它 Hermes 安装时报告未完全移除；保留 App 的连接信息和缓存供重新安装后复用。
