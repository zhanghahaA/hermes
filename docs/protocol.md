# Hermes API Server 协议与版本兼容

App 不绑定任何 Hermes 版本：连接时先探测服务器能力，再选择走哪条接口。
这份文档记录各版本接口的差异和 App 的应对方式，依据是 `NousResearch/hermes-agent`
源码 `gateway/platforms/api_server*.py`（核对到 v0.22.2，2026-10）。

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

### 显示规则：只显示对话本身，不显示思考和工具

App 只保留：
- `role == "user"` 且有文字或图片；
- `role == "assistant"` 且 `content` 有文字（只有 `tool_calls`、没有文字的中间步骤丢弃）。

一律丢弃：`role == "tool"`、`role == "system"`、`display_kind == "hidden"`，
以及所有消息上的 `reasoning`、`reasoning_content`、`tool_calls` 字段——解析时就不读进来，
所以界面、本地缓存、引用功能都不可能碰到思考内容。

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
| `message.delta` `{delta}` | 打开“逐字显示”时拼到临时气泡里；否则只显示“正在输入” |
| `message.interim`、`subagent.*`、`reasoning.*` | 忽略（旁白、思考不显示） |
| `tool.started` / `tool.completed` | 不显示；同时清掉逐字显示里之前那段（那只是调用工具前的过程话） |
| `approval.request` | 显示审批卡片。字段：`command`、`description`、`choices`（`once/session/always/deny` 子集），新版还有 `prompt_id`、`execution_generation` |
| `approval.responded` / `approval.settled` | 卡片收起 |
| `clarify.request` | 追问卡片：`question`、`choices`、`prompt_id`、`execution_generation`、`multi_select`。`question` 末尾的 `Reply "skip"…` 提示和选项上的 `(Recommended)` 后缀 App 去掉再显示（Hermes 匹配回答时本来就忽略这个后缀） |
| `run.completed` `{output}` | `output` 就是最终回复，作为 Hermes 的消息显示 |
| `run.failed` `{error}` / `run.cancelled` / `run.interrupted` | 结束“正在输入”，失败时提示原因 |
| `replay.truncated` | 断线太久、漏掉的事件已经过期：改为查询状态 + 重新拉聊天记录 |

注释行 `: keepalive`、`: open`、`: stream closed` 跳过。思考内容（`_thinking`）服务器端就不发。

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

## 客户端说明（instructions）

`/v1/runs` 的 `instructions` 字段是只对这一轮生效的补充系统提示（Hermes 里叫 ephemeral system prompt，追加在系统提示后面，不存进聊天记录）。
Hermes 对 API 客户端的默认提示是“渲染层未知，别用 Markdown；runs 接口不处理 MEDIA:，只写纯文本路径”。App 每轮都附带一段说明，
告诉模型这个客户端能渲染 Markdown、能收任意文件（用 `MEDIA:/绝对路径`）。实测（gemini-2.5-flash）：不带说明时模型只说“已生成文件”，
带上后回复 `MEDIA:/root/sugar.md`。
