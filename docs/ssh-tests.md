# SSH 隧道回归测试

不连接用户服务器，不需要真实模型 Key。测试账号、密码和 API Key 都是本机假服务的固定数据。

## 桌面测试

```bash
cd tools/ui-preview
gradle test --tests '*SshTunnelTest' --tests '*SshTunnelRegressionTest' --tests '*ConnectionDiagnosticsTest'
```

覆盖真实 SSH 转发、IPv4、代理绕行、HTTP 错误分类、取消释放、半关闭后超过两秒继续上传、2MB POST、并发请求和持续超过一分钟的 SSE。假 API 收到实际 `GET /health` 才算通过；`probeForward()` 只是 TCP 通道探测。

## Android 网络栈自检

需要通过 adb 连接一台开启 USB 调试的测试手机或模拟器。此项编译通过不代表已在设备执行。

1. 在第一个终端启动仅监听本机的假服务，保持运行：

   ```bash
   cd tools/ui-preview
   gradle androidSshFixture -PandroidSshFixtureDir=/tmp/hermes-android-ssh
   ```

2. 在第二个终端，回到仓库根目录读取端口，建立 adb 反向转发并运行：

   ```bash
   source /tmp/hermes-android-ssh/ports.properties
   adb reverse tcp:$sshTestPort tcp:$sshTestPort
   ./gradlew connectedDebugAndroidTest \
     -Pandroid.testInstrumentationRunnerArguments.class=com.hermes.mobile.data.ssh.SshTunnelSmokeTest \
     -Pandroid.testInstrumentationRunnerArguments.sshTestPort=$sshTestPort \
     -Pandroid.testInstrumentationRunnerArguments.hermesTestPort=$hermesTestPort
   adb reverse --remove tcp:$sshTestPort
   ```

Hermes API 的端口是 SSH **服务器端**的目标端口，不需要 adb 转发。测试验证 Android 的明文 HTTP 策略、真实 sshj 和 OkHttp、IPv4 监听、双向字节以及实际 HTTP 200；缺少端口参数会明确跳过。结束后在第一个终端按 Ctrl+C 关闭假服务。不要把示例参数替换为用户主机或凭据。
