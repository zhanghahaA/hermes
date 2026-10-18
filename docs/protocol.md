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

## 流式聊天（阶段 2 详细核对）

- `POST /api/sessions/{id}/chat/stream`：SSE，事件 `assistant.delta`（回复正文）、`assistant.commentary`、
  `tool.started/completed/failed`、`approval.request`、`run.completed/failed/cancelled`。
  App 只拼 `assistant.delta`。
- `approval.request` 带 `choices`（`once` / `session` / `always` / `deny` 的子集）和 `run_id`，
  回复走 `POST /v1/runs/{run_id}/approval`。
- 每 10 秒无数据会发 `: keepalive` 注释行。
