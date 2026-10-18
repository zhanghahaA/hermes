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

## 后台和通知

等 Hermes 回复时，App 会在通知栏显示“Hermes 正在回复…”，这样切到后台、锁屏也不会断。
回复到了、或者需要你批准时会发通知。第一次打开 App 时请允许通知。
如果手机厂商的省电策略比较激进，建议把本 App 设为“不限制后台”。

## 多个 Profile

如果你用 Hermes 的多 Profile，在连接里填 Profile 名字。
每个 Profile 要在自己的 `~/.hermes/profiles/<名字>/.env` 里设置单独的 `API_SERVER_KEY`。
