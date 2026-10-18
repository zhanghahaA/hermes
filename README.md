# Hermes 移动版（Android）

在手机上像发消息一样使用 [Hermes Agent](https://github.com/NousResearch/hermes-agent)：只显示最终回复，不显示思考链和工具调用；Hermes 需要你批准或追问时，聊天里会出现一张卡片让你点。

> **当前状态：界面骨架。** 所有界面和交互都已经搭好，数据来自 `FakeHermesRepository`（假数据），还没有连接真实的 Hermes。标注为“占位”的功能点击后会弹出提示。

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

需要 JDK 17 和 Android SDK（API 36）。

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
```

在 Linux 上需要装一个中文字体（例如 `fonts-noto-cjk`），否则中文会显示成方块。

## 下一步（见计划 M0–M5）

1. M0：读 Hermes 源码，核对 API Server 的事件格式
2. M1–M2：真实的连接层和聊天（SSE、断线恢复、幂等重试、审批）
3. M3：SSH 隧道
4. M4–M5：图片上传、cron、通知、SSH 管理功能
