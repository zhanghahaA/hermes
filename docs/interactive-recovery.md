# 0.13.11-dev：互动卡片、刷新恢复与模型设置

## 已核实的问题

1. SSH 隧道是 TCP 转发，能够承载 API 的 SSE 互动事件。旧手机已经处理 `clarify.request` / `approval.request`，但只跟踪手机提交的 run，没有订阅桌面发起的会话的共享卡片；这是客户端覆盖缺口，不是 SSH 天生不支持。
2. Hermes 的 API 工具集可能默认不启用 clarify。桌面可调用追问工具不代表 API 会生成同样的卡片。只修手机事件解析不能让未启用的工具产生事件。
3. `refreshSessions()` 以前仅以 client 对象非空判断连接可用，直接用旧连接读取；后台留下半断开的 SSH 时会继续等待。界面也没有 finally 复位刷新状态。
4. 模型配置桥把所有 ImportError 统一报成「不支持浏览器授权」，inspect 或模型目录接口的缺失也会命中。截图只能证明命中了此错误分支，不能证明具体缺哪个模块或用户确实没有 OAuth 功能。

## 改动

- `SessionControlFeed.kt` 复用既有 `RoomClient` 的认证 Gateway 通道，在打开会话时调用 `session.resume` 获取 prompts，并通过 `session.events.since` 按序号和 epoch 回放请求／已处理事件。响应使用准确的 session、Profile、prompt_id 和 execution_generation；离开会话释放订阅。未支持该协议的旧 Gateway 不伪造成功。
- `RemoteHermesRepository.kt` 将共享卡片接入已有卡片 UI 与回答动作，合并相同 prompt/generation 的 API 和 Gateway 卡片。电脑处理后更新手机卡片。此功能针对手机当前打开的会话，不是订阅整台电脑所有会话的后台通知。
- `RoomClient.kt` 允许调用方明确指定 Profile，原群聊默认 Profile 不变；并发请求写入按流串行，避免 JSON 行互相交错。
- 下拉刷新重置 SSH 传输、清理 HTTP 空闲连接，重新探测健康并读取会话。保留现有任务与待发送内容，不调用会清空它们的切换连接流程。刷新等待上限 30 秒，界面通过 finally 收起转圈，并阻止重复发起。
- 模型桥在旧版缺少模型目录函数时回退到已注册提供方和 API Key 存在性摘要，不回传凭据；浏览器授权错误只用于 login 操作，核心配置导入失败另行报告。缺少实际核心模块／依赖仍需修复 Hermes，不能靠客户端伪造提供方能力。
- 模型设置页新增「启用手机互动选项」，点击前说明会添加 API 的 clarify 工具。只补充缺少的工具项，保留其它平台工具配置、模型设置和密钥；首次备份、检查受管理配置，不自动重启。新会话尝试；旧服务若尚未重新读取配置，需要空闲时重启。

## 支持边界

| 场景 | 本版处理 |
| --- | --- |
| 手机发起，SSH 连接 | 原 API 卡片及回答继续支持，新增真实本机 SSH 转发回归 |
| 手机发起，HTTP 直连 | 同样支持 API 卡片及回答，新增本机 HTTP 回归；依赖服务启用 clarify |
| 电脑发起，手机 SSH 打开同一会话 | 新增共享 Gateway 快照／事件／回答，需要服务器提供 canonical prompts 协议 |
| 电脑发起，仅 HTTP 直连且无 SSH 管理通道 | 未接入桌面 Gateway 共享卡片，不宣称与 SSH 能力相同 |
| 独立终端进程而非共享 Gateway 管理的会话 | 不能保证同步；该进程须将卡片公开到可连接的 Gateway |

协议核对：NousResearch/hermes-agent `ee7e0f517232fb619c72e49d7e648436bddc598e` 的 `apps/shared/src/gateway-contract.generated.ts`、`gateway/session_pending_controls.py`。复用上游的共享提示身份，不从历史文本猜选项，不自动同意审批，也不复制密钥到其它 Profile。

## 验证与迁移

新增真实本机 Apache MINA SSH 服务转发到假 Hermes API 的测试：下拉刷新重新登录、收到互动选项并回答；HTTP 直连也验证完整卡片／回答请求。跨桌面 Gateway 使用合同形状的替代 RPC，验证初始快照、generation 回填、回放游标、他处已处理、回复路由与取消订阅。Python 使用隔离临时目录验证缺少模型目录时仍可列提供方、缺失模块错误按操作分类、启用 clarify 幂等且保留其它配置。

实际用户 Termux、桌面 Gateway 和后台长时间挂起没有连接到本环境，不能把本机 SSH 服务或替代 RPC 说成用户真机端到端已修好。完整测试统计、APK 校验与交付位置见 HANDOFF.md。

同签名覆盖安装即可，无需删除连接或迁移数据。clarify 配置必须由用户点击新入口才修改；旧版 Hermes 若不支持共享卡片协议，需要升级服务器后才能使用桌面同步。

## 0.13.12-dev：直连附加 SSH 与新会话

用户明确确认当前直连的「文件互通（SSH）」已开启。源码核对表明这条路径原本也将 SSH 保存为管理通道，不能把问题归咎于直连模式。新发现的缺陷是：新会话 UI id 初始为 draft，旧 watcher 立即退出，后续 id 绑定成正式会话也不再订阅；若进入页面时仍在建立连接，同样会过早退出。另一个问题是共享订阅异常被静默吞掉。

本版在页面仍打开时等待正式会话和连接就绪，随后启用共享卡片；SSH／Gateway 失败或共享协议不支持会显示固定的安全提示。手机自己提交的 run 还增加独立状态检查，即使 SSE 没结束也可补上 pending_controls；轮次已结束的迟到状态不会应用到其它任务。未自动修改 clarify 配置或重启服务器。

新增测试实际使用 HTTP 直连假 API + 本机真实 MINA SSH exec JSON 行桥，验证先打开草稿、发送后自动订阅正式会话、显示选项并走 SSH 提交答案。远端 Python／真实桌面 Gateway 用替代桥，不是用户电脑真实集成验证。仍需目标 Hermes 启用相应工具并提供共享协议。
