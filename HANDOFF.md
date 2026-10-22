# 交接说明（Hermes 移动版）

给接手的开发者 / 编码助手看的。功能说明见 [README.zh-CN.md](README.zh-CN.md)，用户侧的设置步骤见 [docs/setup.md](docs/setup.md)，
和 Hermes 打交道的每个接口、每个坑见 [docs/protocol.md](docs/protocol.md)。改东西之前先读 protocol.md 里相关的那一节。

## 现状

- 当前工作区版本 **0.11.1**（`app/build.gradle.kts`：`versionCode = 27`）。本轮修复已完成常规测试和调试 APK 构建，尚未创建 GitHub Release；真实 Hermes / OpenSSH 集成环境未配置，相关 IT 没有实际执行。
- 0.11.1：切换连接 / Bot 时清理旧客户端、取消关联操作，拒绝旧响应；定时任务按任务串行提交，防止旧响应和失败回滚覆盖后续修改；诊断导出及崩溃落盘前统一脱敏；归档测试等待异步状态传播。
- 最近几版做的：Bot（列表、切换、开通手机访问、新建 / 编辑 / 删除）、群聊（0.11.0）；闪退日志、审批后闪退修复（0.10.5）；Bot 屏幕接管时不误触返回（0.10.4）；
  项目的新建文件夹 / 改名 / 删除（0.10.3）；Markdown 渲染（0.10.x）；Bot 屏幕（0.10.0）。
- GitHub 仓库 `zhanghahaA/hermes`（私有）。之前的会话里推 tag、建 Release 没有权限，Release 都是用户手动建的（见「发布」）。

## 用户和他的环境

- **用中文回复用户**。用户不是开发者，说明要讲清楚“要改什么、为什么、怎么用”，少用术语。
- Hermes 跑在局域网里的一台电脑上，手机经 **SSH** 连（SSH 隧道 + 在那台电脑上执行命令）。Hermes 是 **v0.22 canary、新式源码安装**
  （`hermes` 启动器是“自带 Python + `-I` + 引导代码”，裸跑那个 Python import 不到 Hermes，见下面的 hermesPython）。
- 手机是 vivo。用户也用 Hermes 电脑版（Desktop），希望手机和电脑版互通（同一份 bot、群聊、项目）。
- 用户定下的规矩：
  - **要改服务器配置时，先在 App 里说明要改什么，用户点了按钮才改**（例如打开 `gateway.multiplex_profiles`、重启 gateway、给 bot 写 Key）。
  - 隐私：仓库里不能有任何 Key、密码、私钥、用户的主机名 / IP。诊断信息导出也不能带这些。bot 的 Key 只在需要时读，脚本不打印到日志。
  - vivo 原子岛（实况通知）用户说“先不用做”。Bot 屏幕“后续需要的话继续开发”。
- 用户手里有过一个自己的 OpenRouter Key 给测试用。**不要把任何 Key 写进仓库或测试文件**；测试一律用 `tools/fake-llm`（假模型，不要 Key）。

## 代码结构

单 Activity + Compose（Material 3），玻璃效果用 Kyant0/backdrop。界面只依赖 `data/HermesRepository.kt` 这一个接口。

| 位置 | 内容 |
|---|---|
| `data/HermesRepository.kt` | 界面用的唯一接口（很多方法有默认实现，演示版只实现一部分） |
| `data/remote/RemoteHermesRepository.kt` | 真实实现：连接、会话、聊天（`/v1/runs` + SSE）、缓存、bot 切换、群聊列表…… 很大，按 `// ---- xxx ----` 分节 |
| `data/remote/HermesClient.kt`、`Sse.kt`、`RunEvents.kt`、`Capabilities.kt` | Hermes API Server（8642）的 REST / SSE；按服务器能力探测，不比版本号 |
| `data/remote/HermesAdmin.kt` | **所有经 SSH 的功能**：记忆、MCP、config、日志、项目、Bot、Bot 屏幕、群聊桥。Python 脚本都在它的 companion 里 |
| `data/remote/RoomClient.kt` | 群聊：经 SSH 上的桥调 gateway 的 `groups.*` JSON-RPC |
| `data/ssh/` | sshj：隧道、执行命令、SFTP、长连接通道（`RemoteStream`） |
| `data/store/` | 本地状态（`LocalStore`，Key 用 Keystore 加密）、离线缓存（`ChatCache`）、闪退日志（`CrashLog`） |
| `data/fake/` | 演示数据（截图和界面测试用） |
| `platform/` | 依赖 Android 的少量代码；`tools/ui-preview` 里有同名的桌面版实现 |
| `ui/` | 各页面：`chats`（列表、项目）、`chat`（聊天）、`bots`（Bot 列表、编辑、群聊）、`admin`（管理、Bot 屏幕）、`jobs`、`capabilities`、`settings`…… |
| `tools/ui-preview` | Compose Desktop 工程，直接编译 App 的界面代码：渲染截图 + 跑全部单元 / 界面 / 集成测试 |
| `tools/fake-llm` | 假模型（OpenAI 兼容），按关键词走固定剧本（思考、审批、追问、慢速输出、图片） |
| `tools/novnc` | Bot 屏幕用的 noVNC 打包脚本 |
| `tools/it-env` | 集成测试环境的启动脚本 |

### 经 SSH 跑 Hermes 自己的 Python（重要）

`HermesAdmin.hermesPython(dir, script, args)` 生成一条 shell 命令：把脚本写进临时文件，用 `hermes --print-runtime-command` 拿到
新式安装启动自己的那条命令，把最后的 `runpy.run_module(...)` 换成运行我们的脚本；旧式安装（venv / pip）退回去找 Python。
所有要 import Hermes 代码的功能都用它。写新脚本时注意：

- 脚本放在 Kotlin 的 `"""` 原始字符串里：**不能出现 `$` 和 `"""`**。
- 结果用一行带前缀的 JSON 输出（`BOTS{…}`、`EDIT{…}` / `EDITERR{…}`、`ACCESS{…}`），Kotlin 端按前缀找最后一行，别的输出不管。
- `import tui_gateway.server` 会把 `sys.stdout` 换成 stderr，要先 `os.dup(1)` 留一份真 stdout（见 `BotEditScript`）。
- Hermes 的 venv 里没有 PyYAML，用 `hermes_yaml`（`import hermes_yaml as yaml`，失败再退回 `yaml`）。
- 当前连的可能是某个 bot 的目录（`HERMES_HOME=…/profiles/<bot>`）；管理 bot / 群聊的脚本先把 `HERMES_HOME` 设成 `get_default_hermes_root()`。
- 参数里有大块数据（头像）时，编成 base64 直接替换进脚本（`@@PARAMS@@`），不走命令行参数。
- `hermes gateway restart` 在没装成系统服务时会在当前进程前台跑，SSH 一断 gateway 就没了：要 `setsid nohup … &` 脱离（见 `restartGateway`）。
- 删 profile 必须用 `delete_profile()`（会写墓碑、先停它的进程）；直接 `rm -rf` 会被正在跑的 gateway 重新建出来。

### Bot 和群聊

- Bot = Hermes Profile。手机和某个 bot 聊用 `/p/<bot>/...` + 那个 bot 自己 `.env` 里的 `API_SERVER_KEY`（主 Key 在别的 Profile 上是 401）。
  Repository 里按 `"$连接id#$bot"` 区分缓存、运行中的回复、模型；bot 的 Key 存在 `keys["bot:$连接id:$bot"]`。
- 新建 / 编辑 bot 调的是电脑版用的 `tui_gateway` 里的 `profiles.create / describe / configure / set_asset`（`BotEditScript`）。
  显示名的顺序和电脑版一样：`ui_meta["hermes-bots"].title` → `display_name` → 默认 bot 叫 Hermes → id。
- 群聊是 Hermes 的 hosted room，轮次全在 gateway 里跑。只有 gateway 的 `/api/ws` 能访问：`GroupBridgeScript` 用 Hermes 的
  `gateway_client` 经本机控制 socket 换 `scope: "host"` 的票据，连上后 stdin/stdout 一行一个 JSON-RPC。没有推送，App 轮询 `groups.log` / `groups.state`。
  细节和字段都在 protocol.md 的「群聊」。

## 构建和测试

需要 JDK 17+、Android SDK（API 37）。

```bash
./gradlew assembleDebug            # app/build/outputs/apk/debug/app-debug.apk
cd tools/ui-preview
gradle compileKotlin               # 只编译（很快能发现编译错误）
gradle run --args="$(pwd)/screenshots 37"   # 渲染名字含 37 的截图场景（场景列表在 src/main/kotlin/.../preview/Render.kt）
gradle test                        # 单元 + 界面 + 集成测试（没设 HERMES_IT_* 时集成测试自动跳过）
../it-env/run_it.sh                # 带真实 Hermes 和真实 OpenSSH 的全部测试（要先搭好下面的环境）
```

- 改了界面就渲染相关场景看一眼截图（`tools/ui-preview/screenshots/`，不进仓库）。Linux 上要装 CJK 字体（`fonts-noto-cjk`）。
- 测试 JVM 的默认编码不是 UTF-8：**测试里的中文文件名和命令行参数会坏**，这类测试用 ASCII（源码里的中文字符串没问题）。
- `RepositoryRobustnessTest` 和 `DiagnosticRedactorTest` 覆盖连接隔离、任务响应顺序、局部回滚、诊断隐私。0.11.1 本轮标准套件报告 163 项，其中实际执行的常规测试 147 项全部通过；另 16 项真实 IT 在缺少环境配置时直接返回，不能按真实集成通过来报告。
- 杀 gradle 进程别用 `pkill -f gradle`（会匹配到自己的 shell），用 `pgrep -f "[G]radle..."`。

### 集成测试环境（`tools/it-env`）

之前的会话在 `/home/user/ref`（`IT_ROOT`）下搭了这些，容器换了就要重搭：

1. `hermes-agent/`：`git clone https://github.com/NousResearch/hermes-agent`（测试用的是 `06671b7`）；`python3 -m venv hvenv && hvenv/bin/pip install -e hermes-agent`。
2. `hermes-home/`（`HERMES_HOME`）：
   - `.env`：`API_SERVER_ENABLED=true`、`API_SERVER_KEY=<随机 32 位以上>`、`API_SERVER_HOST=127.0.0.1`、`API_SERVER_PORT=8642`；同一个 Key 写进 `IT_ROOT/hermes-key.txt`。
   - `config.yaml` 的模型指向假模型：`model: {provider: custom, base_url: http://127.0.0.1:9900/v1, api_key: none, default: fake-model}`，
     `gateway: {multiplex_profiles: true}`。
   - 一个测试 bot：`HERMES_HOME=… hvenv/bin/hermes profile create testbot`，`profile.yaml` 的 `description: A test bot for Hermes Mobile`，
     它的 `.env` 写一个自己的 `API_SERVER_KEY`（或者让 `botsOverRealSsh` 测试替它开通）。
3. `sshtest/`：`ssh-keygen` 生成 `host_ed25519`、`user_ed25519`（再加 `user_rsa`、`user_ecdsa`，和一个带口令的 `user_protected`：测试只检查它给出的提示，口令随意），
   公钥都放进 `authorized_keys`；`sshd_config`：`Port 2222`、`ListenAddress 127.0.0.1`、`HostKey`/`AuthorizedKeysFile` 指向这里、
   `PasswordAuthentication no`、`AllowTcpForwarding yes`、`StrictModes no`、`UsePAM no`、`Subsystem sftp /usr/lib/openssh/sftp-server`、
   `SetEnv HERMES_HOME=<IT_ROOT>/hermes-home`。登录用户要能在 PATH 里找到 `hermes`（之前是 `~/.local/bin/hermes` 这个两行的包装：`exec <IT_ROOT>/hvenv/bin/hermes "$@"`）。
4. `tools/it-env/ensure_env.sh` 启动假模型、gateway、sshd（哪个没在跑就启动哪个）。**这个环境会时不时整个停掉，跑 IT 前先跑它。**
5. Bot 屏幕的 IT 需要 Xvnc / Xfce（`apt install tigervnc-standalone-server xfce4`），没有就跳过那部分。

## 发布

1. 改 `app/build.gradle.kts` 的 `versionCode`（+1）和 `versionName`。
2. `./gradlew assembleDebug`，复制成仓库根目录下的 `hermes-mobile-<版本>.apk`（`*.apk` 不进仓库），算 `sha256sum`。
3. 全部测试通过后提交、推 `main`。提交信息用英文。
4. GitHub Release：tag `v<版本>`，附上 APK，说明照以前的格式（英文，「New since …」+「Install」+ SHA-256）。之前的会话没有权限推 tag / 建 Release，是用户手动建的；
   如果你有权限就直接 `gh release create`。
5. APK 现在是 debug 签名（升级安装没问题）；以后要换正式签名，用户手机上得先卸载旧版。

## 闪退日志

0.10.5 起有：
- App 闪退后**下次打开会弹「上次 App 意外退出了」**，里面是出错位置，有「复制」按钮。
- **设置页最底下「导出诊断信息」**：分享出去的文字里带最近几条闪退 / 后台出错的记录（不含 Key、密码、主机）。
- 文件在手机的 App 私有目录 `files/crash-log.txt`（debug 包可以 `adb shell run-as com.hermes.mobile cat files/crash-log.txt`）。
- 0.10.5 之前的版本没有记录。

## 没做完 / 待确认

- **手机上建的项目电脑版看不到**：还没定位。最可能是电脑版连的不是同一个 Hermes 或不是同一个 Profile；等用户在电脑上跑 `hermes project list` 和 `hermes config path` 对一下。
- **中文名项目闪退**：没复现出来；0.10.5 加了防护和闪退日志，等用户的日志。
- **Bot 和群聊没在用户自己的电脑上验证过**（只在测试环境里跑通）。重点看：新式安装下 `import tui_gateway.server` 是否正常、开通访问后 gateway 重启、
  群聊桥能不能连上 gateway（`discover_gateway_endpoint` / 控制 socket）。
- **群聊的归属**：房间属于建它的连接身份（同一台机器同一个系统用户）。如果用户的电脑版连的是另一台机器（比如云主机），两边的群互相看不到。
- 群聊里手机还不能发附件（收到的附件只显示个数）；电脑版的旧式群聊（legacy groups，存在 `ui_meta['hermes-bots-groups']`）不显示。
- 选了“贵的模型”时的确认流程（`confirm_required`）没在测试里触发过。
- 头像只做了居中裁剪，没有手动裁剪界面。
- Bot 屏幕：用户满意，后续按需继续。vivo 原子岛：暂不做。
