# Hermes 移动版（Android）

在手机上像发消息一样使用 [Hermes Agent](https://github.com/NousResearch/hermes-agent)：只显示最终回复，不显示思考链和工具调用；Hermes 需要你批准或追问时，聊天里会出现一张卡片让你点。

> **当前状态：阶段 4 进行中（已完成图片/文件、模型切换、定时任务）。** 能连接真实的 Hermes 聊天：发消息、只显示最终回复（不显示思考和工具过程）、
> 审批和追问卡片、停止、断线自动重连、App 被杀后接着等回复、后台回复通知；会话的新建/分叉/重命名/置顶/删除；
> 直连 / HTTPS / 本机 Termux / **SSH 隧道**（手机上生成密钥或导入私钥、密码登录、主机指纹锁定、断线自动重连）；
> **发图片、拍照、发文件**（PDF 转图片、文本文件附内容、其它文件经 SSH 上传或存到下载目录）。
> **切换模型**（每个对话单独锁定，或设新对话默认模型）；**定时任务**（新建/编辑/暂停/立即运行，运行结果作为对话出现并发通知）。怎么让手机连上 Hermes 见 [docs/setup.md](docs/setup.md)，接口和版本兼容见 [docs/protocol.md](docs/protocol.md)。

## 界面一览

| 页面 | 文件 | 说明 |
|---|---|---|
| 聊天列表 | `ui/chats/ChatsScreen.kt` | 大标题顶栏、连接状态胶囊、搜索、筛选、左滑删除（可撤销）、右滑置顶、长按菜单、下拉刷新 |
| 聊天 | `ui/chat/ChatScreen.kt` | 消息气泡（连续消息自动分组）、Markdown 和代码块、“正在输入”、审批/追问卡片、发图片、发送/停止按钮、回到最新 |
| 模型切换 | `ui/chat/ModelPickerSheet.kt` | 按 Provider 分组的底部面板 |
| 定时任务 | `ui/jobs/` | 任务卡片（开关、立即运行、上次结果）、新建/编辑（频率、星期、时间、cron、结果发送到） |
| 能力 | `ui/capabilities/` | 默认模型、技能、工具集，以及需要 SSH 的“更多”功能入口 |
| 设置 | `ui/settings/SettingsScreen.kt` | 连接管理、主题/动态取色、聊天行为、通知、诊断 |
| 连接编辑 | `ui/settings/ConnectionEditorScreen.kt` | 直连 / HTTPS / SSH 隧道 / 本机 Termux 四种方式，逐项“测试连接” |
| 欢迎页 | `ui/welcome/WelcomeScreen.kt` | 首次使用时选择 Hermes 在哪里运行 |

## 视觉与交互

- **液态玻璃**（`ui/glass/`，基于 [Kyant0/backdrop](https://github.com/Kyant0/AndroidLiquidGlass)）：悬浮底栏、底栏右侧的圆形操作按钮、聊天输入框、回到最新按钮是带折射和高光的玻璃；各页顶栏是磨砂玻璃，内容会滚到它们下面。
  - Android 13+：折射 + 模糊；Android 12：只有模糊；更早的系统退化为半透明底色。
- **悬浮底栏**（`ui/main/FloatingTabBar.kt`）：切换 Tab 时选中块会像液体一样拉长再回弹；右侧圆形按钮随页面变化（聊天页是“新对话”，任务页是“新建任务”）。
- **跟手返回**（`ui/navigation/`）：在页面任意位置向右滑，页面实时跟着手指走（缩成圆角卡片），上一页从左侧露出；松手按位置和速度决定返回还是弹回。动画进行中手指按下会立刻接住页面，可以继续拖或推回去。不依赖系统是否支持预测性返回，任何机型都一样；支持预测性返回的系统，侧边返回手势也会实时驱动同一套动画。
- `tools/ui-preview/src/test` 里有模拟触摸的自动化测试：返回手势（跟手、取消、中途接住、竖滑不误触、快速一甩、真实聊天页上拖动），会话列表左滑，底栏伸缩动画，引用和选中追问。

- **会话列表左滑**（`ui/components/SwipeActionsRow.kt`）：左滑露出「置顶 / 删除」，点了才执行，执行后自动收回；同一时间只展开一行，列表滚动或点内容区会收起。
- **引用**（`ui/chat/SwipeToQuote.kt`）：在 Hermes 的回复上从右往左滑，引用这条回复，输入框顶部出现引用预览。只能引用 Hermes 回复的正文，思考过程和工具调用从不进消息列表；自己的消息、正在生成的回复都不能引用。点消息里的引用块会跳回原消息并闪一下。
- **选中文字**（`ui/chat/SelectionBar.kt`）：长按消息文字进入选中，输入框上方浮出玻璃操作栏：「追问」（只对 Hermes 的回复，把选中的片段作为引用直接在本会话追问）、复制、全选、更多。

## 代码结构

```
app/src/main/java/com/hermes/mobile/
├─ MainActivity.kt            入口（edge-to-edge）
├─ platform/Platform.kt       少量 Android 专属能力（动态取色）
├─ data/
│  ├─ HermesRepository.kt     界面依赖的唯一接口
│  ├─ model/Models.kt         连接、会话、消息、审批、任务等数据模型
│  └─ fake/                   假数据实现，接入真实 API 后替换
└─ ui/
   ├─ HermesApp.kt            导航（类型安全路由）+ 全局提示
   ├─ main/MainScreen.kt      底部导航：聊天 / 任务 / 能力 / 设置
   ├─ theme/                  Material 3 配色（靛蓝 + 金）、字体、圆角
   ├─ components/             头像、状态点、正在输入、Markdown、时间格式等
   └─ chats/ chat/ jobs/ capabilities/ settings/ welcome/
```

接入真实 Hermes 时，只需要新写一个 `HermesRepository` 的实现（走 API Server 的 REST + SSE），界面代码不用改。

## 构建

需要 JDK 17+ 和 Android SDK（API 37）。

```bash
./gradlew assembleDebug     # 调试包：app/build/outputs/apk/debug/app-debug.apk（约 19 MB）
./gradlew assembleRelease   # 精简包：app/build/outputs/apk/release/app-release.apk（约 1.7 MB，暂用 debug 签名）
```

推送到 GitHub 后，`.github/workflows/android.yml` 会自动构建 APK 并渲染界面截图，在 Actions 页面的 Artifacts 里下载。

## 不装 Android SDK 预览界面

`tools/ui-preview` 是一个独立的 Compose Desktop 工程：直接编译 `app` 里的界面代码，渲染一组手机尺寸（412×892dp）的截图，覆盖浅色/深色和主要状态。

```bash
cd tools/ui-preview
gradle run            # 截图输出到 tools/ui-preview/screenshots/
gradle run --args="$(pwd)/screenshots chat"   # 只渲染名字里带 chat 的场景
gradle test           # 返回手势的自动化测试
```

在 Linux 上需要装一个中文字体（例如 `fonts-noto-cjk`），否则中文会显示成方块。

## 开发阶段

| 阶段 | 内容 | 状态 |
|---|---|---|
| 1 | 接通 Hermes：连接、测试连接、会话列表、聊天记录、重命名/置顶/删除 | ✅ |
| 2 | 聊天核心：发消息、只显示最终回复、审批/追问、停止、断线恢复、通知 | ✅ |
| 3 | SSH 隧道：密钥生成/导入、密码登录、主机指纹锁定、自动重连 | ✅ |
| 4 | 图片/拍照/文件 ✅；模型切换 ✅、定时任务 ✅；技能/工具集、Profile | 进行中 |
| 5 | 本地缓存、SSH 管理功能、打磨 | 待开发 |

### 不绑定 Hermes 版本

App 连接时先探测服务器能力（`/v1/capabilities`，旧版本逐个探测接口），按结果显示功能，不比较版本号。
`tools/ui-preview/src/test` 里的模拟服务器同时模拟新版、2026.6 版、2026.4 版和非 Hermes 服务器。
设置 `HERMES_IT_HOST` / `HERMES_IT_KEY` 环境变量后，`gradle test` 还会对真实运行的 Hermes 跑集成测试。
聊天的集成测试配合 `tools/fake-llm/fake_llm.py`（一个按剧本回复的假模型，会故意输出思考内容、触发审批和追问），不需要任何模型 Key；
再加 `HERMES_IT_REAL_MODEL=1` 会跑一个接真实模型的测试，检查服务器上存着的思考内容一个字都不出现在 App 里。
SSH 隧道的单元测试在测试里起一个真的 SSH 服务器（Apache MINA SSHD）；设置 `HERMES_IT_SSH_DIR` / `HERMES_IT_SSH_PORT` / `HERMES_IT_SSH_USER`
后还会对真正的 OpenSSH 跑互通测试（ssh-keygen 生成的 Ed25519/RSA/ECDSA 密钥、App 生成的密钥、主机指纹和 `ssh-keygen -lf` 一致）。
