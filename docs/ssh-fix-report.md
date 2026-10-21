# SSH 隧道修复说明

状态：**本地复现的缺陷已修复，用户已反馈本轮改动可行。** 编码环境没有连接手机，Android 设备自检仍未执行。本次提交保持 GitHub 主线的 0.11.0 / versionCode 26，不发布 Release，不修改用户服务器。此前发给用户的 0.11.4 SSH 测试 APK 来自会话工作区，本文末尾的 SHA-256 对应该测试包；本次单独提交 SSH 修复及其诊断、测试。

## 已确认的问题和处理

| 问题 | 验证与修复 |
|---|---|
| SSH 本地 HTTP 入口可能被代理截走 | 在旧实现注入一个返回 502 的本机 HTTP 代理，SSH 连接正常但假 Hermes 收不到健康检查；SSH 客户端显式使用 `Proxy.NO_PROXY` 后，代理收到 0 个请求，假 Hermes 收到实际 `GET /health`。仅修改 SSH 客户端的代理选择。 |
| 一个转发通道被拒绝会重置整个 SSH 会话 | 旧实现拒绝第二条通道后登录次数变为 2，并影响第一条连接；现在保留仍存活的会话，登录次数为 1，第一条连接继续收发。 |
| 所有健康检查错误被覆盖为服务器无响应 | 旧实现将 HTTP 500 标成网络不可达；现在 HTTP 状态、认证失败、错误 JSON、超时、提前 EOF、明文策略拦截分别报告。正文读取超时也保留异常类别。 |
| 关闭与取消行为不完整 | 去掉下行 EOF 后固定等待两秒就关闭的行为，分别发送两边的半关闭；取消请求通过线程安全的 `Call.cancel()` 解除正文 / SSE 读取阻塞，消费线程负责释放响应，避免并发关闭同一个数据源。 |

监听和 URL 同时固定为 IPv4 回环。**尚无用户手机监听 IPv6 的证据**，不能将这一项或代理设置说成用户真机的唯一根因。

## 主要代码

- `app/src/main/java/com/hermes/mobile/data/ssh/SshTunnel.kt`：监听、通道重连判断、双向转发和释放。
- `app/src/main/java/com/hermes/mobile/data/remote/HermesClient.kt`：SSH 专用 HTTP 配置、健康检查阶段、响应取消、异常原因。
- `app/src/main/java/com/hermes/mobile/data/remote/RemoteHermesRepository.kt`：测试结果分类、临时隧道清理、诊断导出。
- `app/src/main/java/com/hermes/mobile/data/ConnectionDiagnostics.kt`：有界、仅内存、可关闭的安全诊断。只接受枚举和数字，异常只记录类名。
- `tools/ui-preview/src/test/kotlin/com/hermes/mobile/data/ssh/`：本地真 SSH 回归测试与 Android 假服务夹具；Android 自检位于 `app/src/androidTest/`。

主机指纹校验、密钥认证、SFTP 的安全机制保持原样。没有改 gateway、sshd、防火墙、API Key、预测性返回模块或网络安全策略文件。

## 验证

```bash
cd tools/ui-preview && gradle test
cd ../..
./gradlew assembleDebug assembleDebugAndroidTest
```

主线提交验证：标准套件报告 170 项、0 失败；扣除缺少环境而直接返回的 16 项真实 IT，实际执行的 154 项全部通过。本次新增 14 项 SSH 回归、2 项诊断隐私 / 上限测试和 5 项脱敏测试。`assembleDebug assembleDebugAndroidTest` 成功，设备自检仅编译未执行。此前完整会话工作区的 203 项报告还含其他排错改动的测试；本次结果对应单独整理的 SSH 提交。

本地回归包含真实 SSH 通道、健康检查请求字节、200/401/403/500、错误 JSON、超时 / EOF / 策略错误、取消、半关闭后等待超过两秒再上传 300KB、并发健康检查及 2MB POST、持续超过 61 秒的 SSE。半关闭测试直接从 MINA 测试通道发出 SSH EOF，避免其 TCP 转发器把 TCP EOF 作为整条关闭的限制。

Android 自检程序必须在设备执行才算通过，步骤见 [ssh-tests.md](ssh-tests.md)。当前 adb 没有设备，无法验证 Android 系统网络行为、用户 VPN / Wi-Fi 切换或厂商系统。真实 Hermes / OpenSSH IT 环境未配置，不能把缺少环境时直接返回的 16 项 IT 当成真实集成通过。

## 手机复测

1. 安装 `Hermes-0.11.4-ssh-fix.apk`，沿用本轮已有签名，覆盖安装时保留 App 数据，不需要先卸载。版本仍显示 0.11.4。
2. 打开原有 SSH 连接的编辑页，点一次「重新测试」。成功后切换到该连接，发送一条消息，确认能持续收到回复。
3. 如果仍失败，到设置页「导出诊断信息」，提供本次的「SSH / HTTP 连接诊断」以及失败步骤。诊断包含匿名 id 和上下行字节，不包含凭据、真实主机、HTTP 正文或聊天内容。

查看阶段：`ProbePassed` 仅表示 TCP 探测通过；`LocalAccepted` 表示请求已进入手机隧道；`UpFirstBytes` / `DownFirstBytes` 表示两向实际传输了字节；`HTTP=200` 与 `HealthParsed` 表示健康检查响应读取、解析成功。无需修改服务器或开放 8642 公网端口。

APK SHA-256：`dba6fedd0e8d28d1d5618fc4337bd1d0f109e516fa13f7cfe09d5173f77b5cf5`。
