# AGENTS.md

Hermes 移动版：和 Hermes Agent 聊天的 Android App（Kotlin + Jetpack Compose）。

**开始之前先读 [HANDOFF.md](HANDOFF.md)**：现状、用户的环境和规矩、代码结构、构建测试、集成测试环境、发布流程、没做完的事都在里面。
和 Hermes 打交道的接口细节在 [docs/protocol.md](docs/protocol.md)。

最要紧的几条：
- 用中文回复用户，讲清楚要改什么、为什么。
- 改服务器（用户电脑上的 Hermes）配置的功能：先在 App 里说明，用户点了才改。
- 仓库里不能出现任何 Key、密码、私钥、用户的主机名 / IP；测试用 `tools/fake-llm`，不用真的模型 Key。
- 改完跑 `cd tools/ui-preview && gradle test`（能搭集成环境就跑 `tools/it-env/run_it.sh`），改了界面渲染截图看一眼；`./gradlew assembleDebug` 要能过。
- 代码注释和界面文字用中文，提交信息用英文；和周围代码保持一样的写法。
