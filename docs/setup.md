# 让手机连上你的 Hermes

App 通过 Hermes 自带的 API Server 通信。每台要连接的 Hermes 都按下面做一次。

## 1. 开启 API Server

编辑 Hermes 所在机器上的 `~/.hermes/.env`，加入：

```bash
API_SERVER_ENABLED=true
# 至少 16 位的随机密钥，可以用：openssl rand -hex 32
API_SERVER_KEY=这里换成你生成的密钥
```

然后重启 gateway：`hermes gateway`（或你平时的启动方式）。
看到 `API server listening on http://127.0.0.1:8642` 就成功了。

> 新版 Hermes 要求密钥至少 16 位，太短或是示例值会拒绝启动。

## 2. 让手机能访问到

API Server 默认只监听本机（`127.0.0.1`）的 8642 端口，手机访问不到。端口可以用 `.env` 里的 `API_SERVER_PORT=<端口>` 改。按你的用法选一种：

| 用法 | 怎么设置 | App 里选 |
|---|---|---|
| **Tailscale / ZeroTier**（推荐） | `.env` 加 `API_SERVER_HOST=<这台机器的 Tailscale IP>`，例如 `100.101.22.8` | 直连，地址填这个 IP，端口 8642 |
| **家里局域网** | `API_SERVER_HOST=<局域网 IP>`，例如 `192.168.1.20` | 直连 |
| **反向代理 / Tailscale Serve** | 保持监听 `127.0.0.1`，由代理对外提供 HTTPS | HTTPS，填完整网址 |
| **SSH** | 保持监听 `127.0.0.1` | SSH 隧道（见下文） |
| **手机本机 Termux** | 保持默认 | 本机 Termux |

不建议用 `0.0.0.0` 把它暴露到公网：API Server 能在这台机器上执行命令，只靠一个密钥保护。

## 3. 在 App 里添加连接

设置 → 添加连接 → 选方式 → 填地址和 API Key → 点「测试连接」。
测试会逐项检查，失败时会告诉你卡在哪一步：

| 卡在哪一步 | 一般是什么原因 |
|---|---|
| 网络可达 | 地址或端口不对；Tailscale 没连上；`API_SERVER_HOST` 还是 `127.0.0.1` |
| 识别 Hermes | 这个端口上跑的不是 Hermes；或者 Hermes 太旧，还没有 API Server |
| 识别 Hermes（提示 hermes serve） | 端口被 `hermes serve` / `hermes dashboard`（桌面版、网页版的后端，默认 9119）占了。App 要的是 `hermes gateway` 里的 API Server，两者不是一个东西 |
| API Key 鉴权 | Key 填错了，或者和 `.env` 里的 `API_SERVER_KEY` 不一致 |
| 能力探测 | Hermes 版本太旧（早于 2026.6），没有会话接口，建议升级 |

## SSH 隧道

适合：家里的电脑/NAS 开着 SSH，不想让 Hermes 的端口直接对外。App 通过 SSH 把请求转发到那台机器的 `127.0.0.1:8642`，
Hermes 不用改 `API_SERVER_HOST`。

1. 设置 → 添加连接 → SSH 隧道，填 SSH 主机（Tailscale IP 也行）、端口、用户名。
2. 登录方式选**密钥**（推荐）：
   - 点「在手机上生成新密钥」，再点「复制命令」，在那台机器上以这个用户执行一次（把公钥加进 `~/.ssh/authorized_keys`）；
   - 或者「导入文件」/「粘贴」你已有的私钥（OpenSSH、PEM、PuTTY 格式都行；暂不支持带口令的私钥，可用 `ssh-keygen -p` 去掉口令）。
   私钥只加密保存在手机上（Android Keystore），不会发给任何人。
3. 「远端 Hermes 端口」一般是 8642，再填 API Key，点「测试连接」。
   第一次连接会显示服务器的主机指纹，可以在服务器上用 `ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub` 对一下；
   保存后 App 记住这个指纹，**以后指纹变了会拒绝连接**（防止有人冒充你的服务器）。重装过服务器的话，在连接设置里点「重置」。

断网、换网络、服务器重启后，隧道会在下一个请求时自动重新登录，不需要手动操作。

| 测试连接卡在哪一步 | 一般是什么原因 |
|---|---|
| SSH 连接 | 地址/端口不对、SSH 服务没开；或者主机指纹变了 |
| SSH 登录 | 公钥没加到 `authorized_keys`、用户名不对；密码登录被服务器关闭了 |
| 网络可达 | SSH 能登录，但那台机器上 8642 端口没有 Hermes：gateway 没启动或 API Server 没开 |

## 发图片、拍照、发文件

聊天页左下角「+」：相册（一次最多 10 张）、拍照、文件。

| 发什么 | 怎么交给 Hermes | 哪些连接方式能用 |
|---|---|---|
| 图片、照片 | 压缩到长边 2048 后直接发给 Hermes。模型不能看图时，Hermes 会先用视觉辅助模型描述图片 | 全部 |
| PDF | 前 8 页渲染成图片发过去，模型能看到页面内容 | 全部 |
| 文本、代码、表格、日志、JSON 等（不超过 512 KB） | 文件内容直接附在消息里 | 全部 |
| 其它文件（Word、压缩包……） | **SSH**：上传到服务器的 `~/hermes-uploads/`；**本机 Termux**：存到手机的「下载/Hermes」。然后告诉 Hermes 文件路径，让它用工具去读 | SSH、本机 Termux |

本机 Termux 要读「下载/Hermes」里的文件，需要先在 Termux 里运行一次 `termux-setup-storage` 并允许存储权限。

### 显示和打开 Hermes 生成的图片、文件（文件互通）

Hermes 画的图、生成的文件都存在它那台机器上（回复里是一行 `MEDIA:/路径`，或者直接写出的文件路径）。
App 每次发消息都会告诉模型“这个客户端能收文件，用 `MEDIA:/绝对路径`”；模型没照做、只写了路径时，App 也会识别出来。
图片直接显示，其它文件变成卡片，点开可以「打开 / 分享 / 保存到手机」。

Hermes 的 API 不提供下载，所以 App 要能经 SSH 读那台机器上的文件：

- **SSH 隧道连接**：自动就能用。
- **本机 Termux / 直连**：在连接设置里打开「文件互通（可选）」，填那台机器的 SSH 账号。
  本机 Termux 填 Termux 自己的 sshd：端口 8022，用户名随便（Termux 不检查），推荐在 App 里生成密钥，
  把「复制命令」得到的那行在 Termux 里执行一次。聊天仍然直接连 Hermes，SSH 只用来传文件。
  打开后，发送 Word、压缩包等任意文件也会改为经 SFTP 上传到 `~/hermes-uploads/`。

### 测试连接里 SSH 的常见问题

| 提示 | 处理 |
|---|---|
| SSH 服务器不允许端口转发 | 在 sshd_config 里加 `AllowTcpForwarding local`（或 `yes`），重载 sshd。只用「文件互通」时不需要转发 |
| SSH 已登录，但服务器上 127.0.0.1:端口 没有服务在监听 | Hermes gateway 没启动、API Server 没开，或者端口填错了 |

### 直连

直连要求 Hermes 的 API Server 监听在手机能访问到的地址上。默认只监听 `127.0.0.1`，所以从别的设备直连会失败；
要直连就在那台机器的 `.env` 里设 `API_SERVER_HOST=<它的局域网或 Tailscale IP>`。在同一台手机上用 Termux 时，用「本机 Termux」就够了。

发过的图片会在手机上留一份（按内容哈希命名），以后打开聊天记录还能看到原图；换了手机或清除了 App 数据，旧消息里的图片会显示成占位块。

## 让 Hermes 能向你追问（可选）

Hermes 默认不给 API Server 用“追问”工具，遇到不确定的地方它会自己拿主意。想让它在手机上弹出追问卡片问你，
在 `~/.hermes/config.yaml` 里加上（然后重启 gateway）：

```yaml
platform_toolsets:
  api_server:
    - hermes-api-server
    - clarify
```

## 切换模型

聊天页顶部点模型名可以给这个对话换模型（之后的每一轮都用它）；“能力 → 模型”里可以设“新对话默认模型”，只影响在手机上新建的对话。
列表里只出现 Hermes 那边已经登录过的 Provider。想加 Provider，在那台机器上运行 `hermes model` 或 `hermes login`。

## 定时任务

“任务”页能新建、编辑、暂停、立即运行和删除 Hermes 的定时任务。每次运行的结果是一个新对话，出现在聊天列表里（来源“定时任务”）；
在 App 里点“立即运行”后，Hermes 一般一分钟内开始执行，跑完会发通知。要让 Hermes 按时执行，gateway 要一直在运行
（Termux 上记得 `termux-wake-lock`）。如果“任务”页显示“这台 Hermes 不支持定时任务”，说明它的版本还没有 `/api/jobs` 接口。

## 后台和通知

等 Hermes 回复时，App 会在通知栏显示“Hermes 正在回复…”，这样切到后台、锁屏也不会断。
回复到了、或者需要你批准时会发通知。第一次打开 App 时请允许通知。
如果手机厂商的省电策略比较激进，建议把本 App 设为“不限制后台”。

## 多个 Profile

如果你用 Hermes 的多 Profile，在连接里填 Profile 名字。
每个 Profile 要在自己的 `~/.hermes/profiles/<名字>/.env` 里设置单独的 `API_SERVER_KEY`。
