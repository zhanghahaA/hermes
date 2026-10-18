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

API Server 默认只监听本机（`127.0.0.1`），手机访问不到。按你的用法选一种：

| 用法 | 怎么设置 | App 里选 |
|---|---|---|
| **Tailscale / ZeroTier**（推荐） | `.env` 加 `API_SERVER_HOST=<这台机器的 Tailscale IP>`，例如 `100.101.22.8` | 直连，地址填这个 IP，端口 8642 |
| **家里局域网** | `API_SERVER_HOST=<局域网 IP>`，例如 `192.168.1.20` | 直连 |
| **反向代理 / Tailscale Serve** | 保持监听 `127.0.0.1`，由代理对外提供 HTTPS | HTTPS，填完整网址 |
| **SSH**（阶段 3） | 保持监听 `127.0.0.1` | SSH 隧道 |
| **手机本机 Termux** | 保持默认 | 本机 Termux |

不建议用 `0.0.0.0` 把它暴露到公网：API Server 能在这台机器上执行命令，只靠一个密钥保护。

## 3. 在 App 里添加连接

设置 → 添加连接 → 选方式 → 填地址和 API Key → 点「测试连接」。
测试会逐项检查，失败时会告诉你卡在哪一步：

| 卡在哪一步 | 一般是什么原因 |
|---|---|
| 网络可达 | 地址或端口不对；Tailscale 没连上；`API_SERVER_HOST` 还是 `127.0.0.1` |
| 识别 Hermes | 这个端口上跑的不是 Hermes；或者 Hermes 太旧，还没有 API Server |
| API Key 鉴权 | Key 填错了，或者和 `.env` 里的 `API_SERVER_KEY` 不一致 |
| 能力探测 | Hermes 版本太旧（早于 2026.6），没有会话接口，建议升级 |

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
