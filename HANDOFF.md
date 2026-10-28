# 交接说明（Hermes 移动版）

给接手的开发者 / 编码助手看的。功能说明见 [README.zh-CN.md](README.zh-CN.md)，用户侧的设置步骤见 [docs/setup.md](docs/setup.md)，
和 Hermes 打交道的每个接口、每个坑见 [docs/protocol.md](docs/protocol.md)。改东西之前先读 protocol.md 里相关的那一节。

## 0.13.11-dev 互动与后台恢复

- 当前开发包 **0.13.11-dev / code 48**，继续 `codex/simple-install` 与草稿 PR #5。用户报告手机／电脑发起的互动卡片都收不到、后台久置后下拉刷新转圈而切换同一连接马上恢复、模型页错误提示不支持浏览器授权。
- 确认 SSH 支持 API 的 SSE 卡片；旧客户端缺少桌面共享会话订阅。新增 SessionControlFeed，复用认证的 Gateway RPC 桥，打开当前会话时 snapshot prompts + events.since 游标／epoch 回放，回答保留 Profile／session／prompt／generation，关闭页面 detach。按 prompt+generation 合并 API 和 Gateway 卡片，电脑处理后同步状态。仅当前打开会话；纯 HTTP 无 SSH 不含桌面 Gateway 共享通道，独立终端进程也不能保证共享。不能宣称各连接方式功能完全一致。
- 刷新主动 reset SSH + 清理 HTTP 空闲连接，健康探测后读列表，不清空任务和待发送消息；30 秒上限，UI finally 结束转圈且阻止重复下拉。保留切换连接原行为。
- 模型桥原先所有 ImportError 都报 OAuth 不支持，现按 inspect/login 区分；缺少可选模型目录函数时回退注册提供方与 Key 存在性摘要。未连接用户手机，不能确认截图具体缺失模块。新增显式「启用手机互动选项」：只向当前 Profile 的 API 工具集补 clarify，保留其它工具、模型与密钥，备份且检查 managed；不自动重启。服务未重读时需空闲时重启。
- 协议按上游 ee7e0f517232fb619c72e49d7e648436bddc598e 的 gateway-contract.generated.ts 与 session_pending_controls.py 核对。详细原因、文件与连接方式边界见 [互动恢复说明](docs/interactive-recovery.md)。
- 最终完整桌面 **290 项报告、274 项实际通过、0 失败**；16 项真实 Hermes/OpenSSH IT 未配置。Python **43 项报告、39 项通过、4 项真实上游跳过**。新增真实本机 MINA SSH → 假 Hermes 的刷新重登录／互动卡片与回答、HTTP 直连卡片／回答；共享 Gateway 快照、他处处理、响应身份、取消订阅使用替代 RPC，非真实桌面端到端。新测试第一次 JUnit 返回类型声明不正确，修正后整套通过。
- App／Android 自检 APK 构建通过；已检查新互动入口和提供方设置浅／深色截图。APK SHA-256：`d4b061f1545cd35986d853ff270d7f976f1c683659541b00b0e3f28444b460ed`，签名仍为 `d985fcaf…db1dc7`。下载 `/workspace/shared/downloads/hermes-mobile-0.13.11-dev.apk`，留档 `/workspace/hermes-cloud/releases/0.13.11-dev`。没有用户手机长期后台、真实桌面共享卡片或用户 Termux 版本验证；覆盖安装无需迁移，不能声称用户设备所有问题已经实测修复。

## 0.13.10-dev 配对优化

- 当前开发包 **0.13.10-dev / code 47**，继续 `codex/simple-install` 与草稿 PR #5。按用户要求直接在安卓 dev 实施，不要求另给服务端仓库。完整行为与局限见 [配对优化说明](docs/mobile-pairing-review.md)。
- setup/configure.py 保留已有 API Server Key 和所有模型／其它配置；非法旧 Key 或冲突重复配置停止而不轮换。只写差异，公钥按算法与数据去重，保留既有限制；新增授权禁用 agent/X11/PTY。新增 Profile 配对锁与用户授权锁、跨文件写入异常恢复、实际 SSH banner／地址／用户检查、健康与 Bearer 认证检查。服务正常且配置未变不重启，其余通过当前 Profile 的官方 restart 恢复；不使用 --all/--force，不能绕过上游共享实例保护。手机最终通道验连仍独立执行。
- 首次 Shell 使用 zlib 压缩并保存摘要命名的本地助手，执行过后可复制只带参数的简短重试命令；使用前检查 SHA-256，缓存缺失／被改停止并要求重新执行完整命令。不是 Hermes 原生 mobile pair CLI。SSH 不携带接口 Key，默认不在终端输出敏感回执，保留旧回执格式与安全读取。直连旧协议仍含敏感安装参数，**尚未完成所有连接类型的无密钥公开配对码**；保留不同原 Key／端口时需在手机填原值。不能宣传全部安全短码验收完成。
- 新增复制指定公钥撤销命令：服务器执行后只删该公钥，现有会话不强制断开；共用私钥的设备会受影响。删 App 连接仍不等于撤销授权。默认／命名 Profile 路径避免二次追加，命名 Profile 需已存在；不自动改变 Gateway 多路复用布局。
- 最终完整桌面套件 **283 项报告、267 项实际通过、0 失败**；16 项真实 Hermes/OpenSSH IT 未配置未执行。Python 套件 **40 项报告、36 项通过、4 项上游集成跳过**。新增重复运行文件字节／mtime、公钥限制与注释去重、旧 Key 拒绝替换、模型凭据与 Profile 隔离、写入失败恢复、本机真实 HTTP 认证探测、服务管理决策、实际生成 Shell／短助手复用和篡改拒绝、公钥安装／撤销测试。服务进程使用替代／mock，不宣称真实跨平台 Gateway 已启动成功。
- App 与 Android 自检 APK 编译成功；已检查连接配置页浅／深色截图，按需重启说明可读。开发签名仍为 `d985fcaf…db1dc7`。APK SHA-256：`04828b6816807d366b6012305f78025c25f95c7475b91e934b8ca59a2d003be3`。下载 `/workspace/shared/downloads/hermes-mobile-0.13.10-dev.apk`，留档 `/workspace/hermes-cloud/releases/0.13.10-dev`。没有真实 Termux 手机／用户 Linux 与云服务器端到端验证，不发布 Release 或合并。

## 现状

- 当前开发包 **0.13.9-dev / code 46**，继续 `codex/simple-install` 与草稿 PR #5，包含已完成的国内源改动。用户新增要求：一键卸载；终端可进入 Hermes 配置但 App 开始聊天一直等待并提示本机接口未就绪，需要将配置纳入引导。截图不足以证明实际手机失败原因，不能宣称真机已修好。
- 已确认旧逻辑只凭 mobile-api-key／.env 就认为配置完成，未要求模型配置；连接失败后退回检查步骤。新增 modelConfigured：Android 传入真实 providers/settings.py，由后端在 App 的 HERMES_HOME 下读取模型／账号摘要，只有完整回包和已选提供方、模型及凭据摘要（本地端点除外）才就绪。缺失时主按钮「配置模型并继续」，说明 App 与终端默认配置目录不同，不静默复制密钥。
- 保存模型后执行 Inspect → StopHermes → ConfigureHermes → StartHermes → 验证／切换连接，同步手机接口配置及本地访问凭据，重启旧进程使新模型设置生效。只读前置检查 45 秒上限；本机 API 冷启动检查最多 90 秒，明确 401／403／缺 Key 和必需接口缺失立即固定分类，暂时超时／503 可重试。失败保持模型入口和「重启并重试」，不误标成 Termux 挂起；页面显示当前阶段。
- 「卸载本机 Hermes」移至主页面，确认范围后停止自管 Hermes 并 apt-get remove --no-auto-remove -y hermes-agent；保留 Termux、配置、账号、聊天数据库、记忆、App 连接与缓存。其它同名安装仍存在时不假报已完全卸载，不删除未知目录或终端用户数据。
- 国内源部分已在提交 962c7c7 保存（0.13.8-dev 留档），与本轮配置／卸载改动一起更新 PR。无真机连接，不能把本机假接口、脚本替代命令和编译通过当作用户设备的安装／启动验证。

- 验证：完整桌面套件 **281 项报告、265 项实际通过、0 失败**；16 项真实 Hermes／OpenSSH IT 未配置未执行。Python 套件 **30 项报告、26 项通过、4 项真实上游集成跳过**。新增真实后端模型门槛、超过旧十次限制的慢启动、401／能力失败安全分类、接口超时及重启同步流程、配置引导／卸载确认／失败恢复 UI 回归，Python 实际脚本以替代 apt-get 验证停止进程和保留数据。最终 App 和 Android 自检 APK 构建通过；已检查浅／深色模型引导、直接卸载入口及连接失败恢复截图。
- 最终 APK SHA-256：`5f41a601a91946e4faf663ab9bd6354aece291e9695ad75cf5134c83d41b9240`，版本 0.13.9-dev / code 46，签名仍为 `d985fcaf…db1dc7`。交付 `/workspace/shared/downloads/hermes-mobile-0.13.9-dev.apk`，留档 `/workspace/hermes-cloud/releases/0.13.9-dev`。仅更新草稿 PR，不发布 Release；国内真机速度、实际账号配置／冷启动／卸载仍未验证。

### 0.13.8-dev 国内镜像记录

- 当时开发包 **0.13.8-dev / code 45**，继续 `codex/simple-install` 与草稿 PR #5。用户要求面向国内客户使用国内软件源，并确认暂时没有国内存储／CDN，先接入现有国内镜像；不擅自创建镜像服务器、费用或替代 Hermes 签名。
- 新增 `DomesticPackageSources`：App 的 APT 安装、修复、Python 管理环境、浏览器与代理依赖使用清华 Termux main/root/x11 镜像；显式使用 `~/.hermes-mobile/apt` 独立源列表及索引缓存，保留原全局源配置，不读其它源旧索引、不在失败后自动回退海外。Hermes 已验证的官方源独立纳入；未知第三方源不导入，不能承诺通过该流程升级第三方包。升级 Hermes 使用 install --only-upgrade 限定目标包。
- Python 服务依赖改用清华 PyPI，只设置当前 pip／构建子进程环境，清理旧 PIP_* 和 pip 配置影响，不覆盖全局 pip 配置。保持 TLS、Termux 签名和 Hermes 固定公钥验证。页面在安装／修复前说明来源，无额外确认点击；APK 下载和 Hermes 包仍用官方海外地址，不能宣传为全程国内。
- 清华源已通过 Termux 官方镜像名单核对，3 个 APT InRelease 均 HTTP 200 且为签名文档，PyPI 索引 200；此为云环境连通证据，不代表国内手机速度或实际包安装验证。Hermes 官方 stable 的 404 仍未解决。
- 桌面完整套件 **273 项报告、257 项实际通过、0 失败**；16 项真实 Hermes／OpenSSH IT 未配置未执行。新增国内源隔离／失败停止脚本回归，更新真实安装、修复和命令占位回归；第一次发现旧测试仍匹配 pkg，改为验证实际 hm_apt 修改命令，最终完整套件重跑通过。
- App 与 Android 自检 APK 构建通过；Python 完整套件 29 项，其中 25 项执行通过、4 项真实上游集成未配置跳过。最初默认沙箱不允许本机假 HTTP 服务打开套接字，允许后重跑通过；不将其误报为产品错误。APK SHA-256：`b9385815b34ef6e631f3473eac36005f690dd9031e871ab5c31a29d0ec196344`，签名仍为 `d985fcaf…db1dc7`。交付 `/workspace/shared/downloads/hermes-mobile-0.13.8-dev.apk`，留档 `/workspace/hermes-cloud/releases/0.13.8-dev`。

### 0.13.7-dev 官方源诊断记录

- 当时开发包 **0.13.7-dev / code 44**，继续 `codex/simple-install` 和草稿 PR #5。用户截图 curl 已恢复，安装在下载官方公钥时返回 404。已实测 stable/key.asc、stable/dists/hermes-stable/InRelease 均为 404；canary 两项 200，nightly 两项 404。上游文档 `69f73ec5f11f5a945cc864f29c4e2ad2307eaec3/website/docs/getting-started/termux.md` 明确警告 Termux 包当前不能运行。不能把 App 改进说成已经解决官方发布问题，也不能把 canary HTTP 200 说成手机可用。
- 安装先获取所选通道公钥和 InRelease，再安装 GnuPG 并验证固定主公钥指纹、通过签名 APT 安装 Hermes，最后补齐 App 管理所需 Python。curl 缺失时只先补 curl。404／410 使用固定标记和退出码 44 映射 RepositoryUnavailable，界面说明「官方安装源暂不可用」，明确重试是继续安装。失败不写软件源、不切通道、不要求重新初始化；其它 HTTP／签名失败仍停止。
- 验证：完整桌面套件 **272 项报告、256 项实际通过、0 失败**；16 项真实 Hermes／OpenSSH IT 未配置未执行。新增 4 项安装源脚本／后端回归和 1 项界面重试回归，覆盖 key/index 404、410、503、明确选择通道、错误指纹拒绝及失败不安装 Python。
- APK SHA-256：`7b296d19e33963a5ebb7aa31944d6db6dfd9f7102f836b511f1282967cb377b6`；签名沿用 `d985fcaf…db1dc7`。App 与 Android 自检 APK 构建通过；真实 Termux 安装仍未验证，官方 stable 目前仍不可安装。交付 `/workspace/shared/downloads/hermes-mobile-0.13.7-dev.apk`，留档 `/workspace/hermes-cloud/releases/0.13.7-dev`，包含 HTTP 核实证据。

### 0.13.6-dev 软件包冲突修复记录

- 当时开发包 **0.13.6-dev / code 43**，继续 `codex/simple-install` 与 PR #5。用户新截图已收到 shell 错误：curl 找不到 OpenSSL 符号，说明命令回包已送达，但 Termux 软件包版本冲突；镜像选择提示不是此链接错误的根因，不要求重新初始化或卸载。
- 新增安装前只读 curl 本地检查与 PackageMismatch 分类；主按钮「修复并继续安装」说明将更新 Termux 现有软件包，用户点击才执行。直接 APT 避开 pkg 对 curl 的依赖，update 使用 Error-Mode=any；dist-upgrade 使用 --no-remove 和 --force-confold，保留配置、禁止移除包、不绕过签名。curl 恢复后连续安装，任一步失败停止。已有可用 Hermes 不因安装工具探测阻断聊天。
- 验证：完整桌面套件 **267 项报告、251 项实际通过、0 失败**；另 16 项真实 Hermes／OpenSSH IT 未配置未执行。新增 5 项包冲突／真实修复脚本（替代命令）回归和 1 项界面修复连续安装回归；App 与 Android 自检 APK 构建通过。无手机执行证据，真实 Termux 软件包更新与原生安装仍未实测。
- APK SHA-256：`0cac7e99a68c9e602c4b03889c396ed0cd4c77d0151d27af341f449bc3cd037b`；签名仍为 `d985fcaf…db1dc7`。留档 `/workspace/hermes-cloud/releases/0.13.6-dev`，下载 `/workspace/shared/downloads/hermes-mobile-0.13.6-dev.apk`。仅更新草稿 PR，不发布 Release。

### 0.13.5-dev 检查超时恢复记录


- 当时开发包 **0.13.5-dev / code 42**，继续 `/workspace/hermes` 的 `codex/simple-install`，更新 PR #5。用户截图已显示初始化命令执行完成，App 报「上一次 Termux 任务还未返回」；这是进程内命令占位在检查超时后仍保留，造成后续检查被拒绝。截图不能证明回包为何丢失，不把此修复说成已经验证用户手机链路正常。
- 提取 Android 真正使用的 `TermuxCommandTracker`：只读检查等待超时／取消时注销自己的 PendingIntent、释放自己的占位；安装、配置和模型设置默认不可重入，等待取消后仍保留到实际回包。每次命令独立标识，迟到／重复旧回包不能完成或解锁新命令。PendingIntent 创建或启动服务失败也完成回包并清理占位；不开放导出的广播入口，不落盘回包或凭据。
- 后端显式标记只读的 Python／Hermes 可用性探测和 Inspect 状态读取；软件包安装、配置、模型设置默认保持修改操作保护。安装页对检查超时显示「打开 Termux 后重试」，并提供「重新发送检查」与后台设置；返回会重新检查，不再要求盲目重复初始化。未知安装结果仍提示先查看 Termux 进度。
- 验证：完整桌面套件 **261 项报告，245 项实际通过，0 失败**，16 项真实 Hermes／OpenSSH IT 未配置未执行。新增 7 项共享回调登记回归（丢失回包、迟到旧回包、安装超时保护、创建／启动失败、取消清理、真实后端只读分类、manager 重试恢复）和 1 项界面超时恢复回归；App 与 Android 自检 APK 构建通过。浅／深色检查超时及安装等待截图已检查。adb 仍无设备，用户 vivo 的回包送达原因、真实后台限制及原生安装未实测。
- APK SHA-256：`ba42e616e31d0bdf3deb47e892643a618b5edc4d6c7c08cb20234334616d17b8`；签名仍为 `d985fcaf…db1dc7`。留档 `/workspace/hermes-cloud/releases/0.13.5-dev`，下载 `/workspace/shared/downloads/hermes-mobile-0.13.5-dev.apk`，覆盖同签名旧版无需卸载或清除数据。未发布正式 Release。

### 0.13.4 简化安装记录


- 当前开发包 **0.13.4-dev / code 41**，在 `/workspace/hermes`、分支 `codex/simple-install`，基于已合并 0.13.3 的 main `275b327`。设置入口改为「在手机上安装 Hermes」；安装页只展示当前下一步，检查详情与高级服务默认折叠。环境就绪后一次点击安装，连续检查、准备缺失依赖、安装缺失程序、配置手机连接，再自动进入模型设置。本机「保存并开始聊天」直接保存、启动和验连；远程保存仍保留确认。
- 修复 **Termux 成功回包误判**：Termux v0.118.3 `err=-1` 是 Android RESULT_OK，旧版按 `err!=0` 判失败。Android 接收器现在调用与测试共用的解析器，区分成功、拒绝、缺失结果和 shell 非零退出。错误分类与数字码可复制，原始 errmsg 不暴露。仅明确的外部调用开关拒绝展示初始化命令；从 Termux 返回时权限没变化也会重查。未知后台状态的超时仍保留并发保护，不擅自重跑安装。
- 安装前重新探测实际状态；失败重试复用已准备的依赖与已安装 Hermes，不盲目重复下载。同步进入 busy 状态以避免连续点击排入重复操作。未授权时不执行命令，进入页面只读检查不安装；安装主按钮旁直接说明修改范围，去掉重复确认弹窗。
- 最终完整桌面套件 **253 项报告、237 项实际通过、0 失败**，16 项真实 Hermes／OpenSSH IT 未配置未执行。4 项 Termux 协议解析测试、6 项安装界面流程测试、安装失败重试／前置失败／低空间已安装回归、本机模型一次保存测试均通过；远程保存确认回归保留。App 与 Android 自检 APK 构建通过。浅／深色安装、缺权限、初始化、已安装、缺 Termux 截图已检查；无 adb 手机，真实权限、跨 App 返回、原生 arm64 安装和真实模型授权未实测。首次完整测试因同时渲染重新编译输出导致类加载失败，最终固定源码、串行测试全部通过。
- APK SHA-256：`827fced9662f89465c00b1f1f245e0888a6cce673c92e9ae76f4ee821d8c5b49`；签名仍为 `d985fcaf…db1dc7`，可覆盖同签名低版本，勿卸载丢弃数据。交付留档 `/workspace/hermes-cloud/releases/0.13.4-dev`，下载 `/workspace/shared/downloads/hermes-mobile-0.13.4-dev.apk`。本轮不改 Python 脚本，不宣称重复运行此前真实上游账号集成。未发布正式 Release。

### 0.13.3 诊断版本记录

- 当前为 **0.13.3-dev / code 40**，工作区 `/workspace/hermes-easy-setup`、分支 `codex/runtime-diagnostics`，基于已合并 PR #3 的 main 5aa6883。本机运行页新增逐项环境检查：安装、版本与命令入口、执行权限是否登记／授予、arm64、空间、实际命令回包和 Python 服务环境。失败项默认展开，提供对应授权、下载、存储、初始化入口；灰色准备／安装按钮旁直接说明原因。空间不足仅限制安装，不限制只读检查。
- Android 读取实际版本、权限登记和服务入口；版本解析移至共享函数并测试。只有实际收到后端回包才标记命令连通；检查失败、未检查、超时分开，超时保留“后台结果未知”的边界。权限等基础条件变化会使旧检查失效并重查，可用空间的正常浮动不会清除已通过的命令结果。「复制检查结果」仅包含 App 版本和白名单检查项，不复制原始日志、账号、地址、密钥或初始化命令。
- 完整桌面套件报告 244 项，228 项实际通过、0 失败，16 项真实 Hermes／OpenSSH IT 无环境未执行；包含 5 项诊断数据／版本／状态测试和 2 项新界面流程回归。App 与 Android 自检 APK 构建成功。没有连接真实手机，Android 权限读取、Termux 包识别与用户手机实际阻塞原因仍需设备检查，不能宣称已修好该手机的所有部署问题。APK SHA-256 为 341b2a1fabcf548539cf321ce264e73400b8027437d24b7dd0b6437fe3a70e03，签名沿用 d985fcaf…db1dc7。
- 浅／深色缺权限、未安装和环境已就绪截图已检查；留档 `/workspace/hermes-cloud/releases/0.13.3-dev`，覆盖安装包 `/workspace/shared/downloads/hermes-mobile-0.13.3-dev.apk`。本轮未改 Python 服务脚本，未重复宣称运行此前的真实上游 Python 账号集成。GitHub 发布状态以实际 PR／Release 为准，0.13.2 的附件上传此前返回 401，当前安装包直接在云端交付。

### 0.13.2 部署与模型设置记录

- 当前开发为 **0.13.2-dev / code 39**，在 `/workspace/hermes-easy-setup`、`codex/easy-setup` 继续，目标 PR #3 → main。通知页应用扫描改为仅打开选择器时在 IO 线程执行、短时缓存及懒列表；“检查环境”在缺执行权限时仍可点击，拒绝或不弹窗时提供系统 App 权限入口，返回自动重查。
- 一键安装不再提前要求模型 Key；自动准备 Python、安装或复用原生 Hermes、配置手机接口，再进入模型设置。模型设置支持目标 Hermes 的 API Key 服务、订阅账号（复用设备码流程或带 HERMES_HOME 的 CLI 登录）、命名自定义端点及已有 Ollama／LM Studio／llama.cpp 接口。本机保存后启动、验连并等待正式连接完成才进入聊天，等待不持有运行锁，避免与自动恢复互相等待。不同连接不会自动复制密钥，同一配置目录／外部 CLI 全局账号的共享边界需如实说明。
- `assets/providers/settings.py` 在 Hermes 自带 Python 下运行；API Key 通过 SSH stdin 或 Termux 命令输入传入，现有 Key／token 不回到手机；修改前保留首次私有备份，损坏 YAML 不覆盖。账号授权的后台工作进程脱离命令桥，私有状态文件保存授权码和状态；不持有 stdout 或配置锁，具有取消检查。详见 [模型设置与部署](docs/model-settings.md)。
- 用户已确认：按正常原生 Termux 方式开发，不新增 proot/Linux 兼容层；本机与远程模型设置均需支持，优先复用 Hermes 已有订阅登录。不得把模拟授权或云端测试描述成真机授权成功。
- 验证：完整桌面套件报告 237 项、0 失败，221 项实际通过，16 项真实 Hermes／OpenSSH IT 未配置未执行；28 项 Python 测试通过，其中 4 项使用真实上游源码及临时配置，账号授权仍为假服务商流程。上游核对基线为 edfa1c2a135532a8b64f7825c0ac0af6cfe73d4d。App 与 Android 自检 APK 构建通过，相关浅／深色、账号授权与 Termux 缺权限截图已检查。没有 adb 设备，vivo 冷启动耗时、执行授权、arm64 安装、跨 App 返回、真实账号授权及首条回复均未实测。交付留档为 `/workspace/hermes-cloud/releases/0.13.2-dev`，安装包复制到 `/workspace/shared/downloads`；发布结果以 GitHub 实际状态为准。
- 最终模型清单修复与授权码文案调整后，10 项相关桌面回归、28 项 Python 回归及 App／Android 自检构建再次通过；新截图已复查。APK SHA-256 为 272420beb2086a8955d8679684ec623900cb92f1f0d508f9260a7232f6a96099，证书仍为 d985fcaf…db1dc7，code 39，可覆盖同签名旧包。

### 0.13.1 引导整理记录

- 上一轮为 **0.13.1-dev / code 38**，基于已合并 main 的 8f78c69，独立工作区 `/workspace/hermes-easy-setup`，分支 `codex/easy-setup`。欢迎页、本机授权、连接配置、通知助手和屏幕安装均增加直接可用的操作引导；SSH 主流程为填写地址和账号后「生成并复制配置命令 → 在服务器执行 → 检查并连接」，自动生成手机密钥、经 SSH 读取实际 Key／端口、检查通过后保存切换。详情与边界见 [简化配置说明](docs/easy-setup.md)。
- 配置命令保留有效 API Key 和端口、备份 `.env`，只安装公钥，不发送手机私钥；SSH 模式在服务器生成 API Key。配置结果以私有文件暂存，手机只发送随机配置标识的摘要，回包匹配后才采用凭据与指纹。未保存的敏感输入不进入 SavedInstanceState；Android 复制操作标记敏感，粘贴只由按钮触发。首次终端执行、系统权限和模型服务商 Key 仍需用户操作；本轮未实现 PowerShell 引导。
- 回归复现原有任务操作回包覆盖后续状态的问题，补了按连接／任务串行提交及删除确认保护，并增加可控延迟回包测试；工具集先发布功能标记再发布列表。
- 最终验证：桌面套件 228 项报告、212 项实际通过、0 失败，16 项真实 Hermes／OpenSSH IT 无环境未执行；另 17 项 Python 运行与配置测试通过。App 和 Android 自检 APK 构建成功；浅／深色、新手连接、Termux、通知与屏幕安装提示截图已检查。没有 adb 设备，真实服务器、Android 授权／剪贴板／跨应用恢复与 macOS 尚未实测。签名与旧包一致，APK SHA-256 为 6dabb234ac1457399db00192d17b37319de0e04c9274eb9917db4a6fba4f83a2；留档在 `/workspace/hermes-cloud/releases/0.13.1-dev`，下载包在 `/workspace/shared/downloads/hermes-mobile-0.13.1-dev.apk`。图片恢复、乐观置顶等旧测试改为等待实际异步结果或使用受控回包时机，保留原断言；最终整套串行通过。

## 0.13 交付记录

- 当前开发 **0.13.0-dev / code 37**，基于 0.12 的 d3b400e，工作区 `/workspace/hermes-runtime-0.13`，分支 `codex/local-runtime`。已接入「设置 → 本机运行与服务」、外部 Termux 命令桥、签名 APT 安装、独立目录配置、凭据存储与连接检查、Python／Caddy 服务管理、Chromium 与独立 Termux:X11 启停。业务使用可替换 `LocalRuntimeBackend`，为以后内置合并保留接口。详见 [0.13 说明](docs/0.13.md)。
- 验证：标准套件 217 项报告、201 项实际通过、0 失败，16 项真实 IT 无环境未执行；另 11 项 Python 运行测试通过。App 与 Android 自检 APK 构建成功，浅／深色及缺少环境截图已检查，无 adb 设备。签名证书仍为 d985fcaf…db1dc7；APK SHA-256 为 728c6c3813203f0927447f2c8c65cd5086c9ffcb58515fa708b7b0089cbdc1ab。安装包在 `/workspace/shared/downloads/hermes-mobile-0.13.0-dev.apk`，留档在 `/workspace/hermes-cloud/releases/0.13.0-dev`。
- 0.13 沿用已有通知采集，新增按每个连接独立开关、白名单、目标、加密队列和摘要；旧固定目标只迁移给所属连接。切换取消手机端旧等待并拒绝旧采集回调，删除连接清除档案；已提交的服务器任务不能靠切换撤回。本版没有新增独立读取短信数据库。
- 2026-10-24 重新核对 Hermes 上游 Termux 文档仍标注安装包 broken，故安装入口为实验功能并要求确认。本环境没有 adb 设备，不能宣称真实 Termux 权限／回调、Hermes arm64 安装、X11 显示输入或 vivo 后台已经可用。原版 Termux 首次仍需粘贴执行一次初始化命令；本轮不改变主项目许可证，不接入订阅／广告，也不发布正式 Release。

## 0.12 整理记录

- 用户已确认重新按交付顺序编号：**0.12 整理已有 dev 功能，本机部署顺延 0.13**。本轮基于 GitHub `dev` 的 a6dbe47，候选版本为 **0.12.0 / code 36**，生产功能及测试源码与基线逐文件核对一致。重新执行标准套件报告 202 项、0 失败，186 项实际通过，16 项真实 IT 未配置未执行；App 与 Android 自检 APK 构建成功，无 adb 设备。签名证书仍为 d985fcaf…db1dc7，APK SHA-256 为 67cb1a87de88803ac8b83b0a0daf6ee05a77b5a79bc485c5d9cd2bd1ac7a522d。构建／测试留档在 `/workspace/hermes-cloud/releases/0.12.0`，下载包在 `/workspace/shared/downloads`；未创建 Release。历史记录与包不改写，新说明见 [docs/0.12.md](docs/0.12.md)，计划见 [docs/roadmap.md](docs/roadmap.md)。
- 0.13 的运行模块曾先以独立草稿保存，不包含在 0.12；现已在 `codex/local-runtime` 接入，设备验证边界见上方现状。基础免费开源，可选订阅／部署／广告资格后续接入；不擅自更改主项目许可或公开仓库。
- 当前可见 dev 源码包含通知助手，未发现独立读取短信数据库的实现。0.12 不新增或重写这部分；按连接独立隐私档案和切换隔离纳入 0.13，不能把现有固定目标发送行为当成已经完成新隔离方案。

## 历史开发与发布记录

- 开发源码通过 GitHub `dev` 分支管理：历史中分别保存 0.15.0-dev.2 通知助手/全局导航和 0.16.0-dev 流式正文/工作过程/Dock 改进，分支当前版本为 0.16.0-dev（code 35）。通知助手已包含在其中；正式发布仍单独使用对应版本标签和 Release。源码核对与已验证的 0.16 留档一致，之后仅有交接文档更新；构建/测试结果沿用下方已执行记录，不把未运行的真实 IT 或真机检查算作通过。
- 0.11.5 发布材料已重新备齐：核对 GitHub 已有 v0.11.5 标签，tag object 5481ed3b7403141563bfecad5d0e0402a3c777f1 指向 0f346ce400686e8fd2d43ea77490b91b7d2f7442。从标签独立导出源码到 /workspace/hermes-cloud/releases/0.11.5/source，源码逐文件校验一致，未修改标签代码；用外部 Gradle init 脚本指定保留的调试签名。APK versionName 0.11.5 / code 31，SHA256 d4848734e41bc6da73ad76af00d85aa7e758af39fd98262f079de3a90e4ecf6a，签名与已发布 0.11.1 实包一致。本次重新构建成功，170 项测试报告中 154 项实际通过、16 项真实 IT 未配置未执行，无 adb 设备。发布 POST 请求再次返回 Forbidden，未创建 Release；GitHub 仍仅有 0.11.1、0.10.2。可手动发布的 APK、校验文件、双语说明、填写步骤打包为 /workspace/shared/downloads/hermes-0.11.5-release-kit.zip，单独文档与 APK 同目录可用。
- 用户的发布文案要求：中英文双语，以已发布的上一版本为比较基准，只写当前发布的改进，不提其它已开发但未发布的版本；表达持续完善产品，不编造发布日期或更新记录。0.11.5 文案聚焦 SSH 稳定性、长回复/取消、准确错误和匿名诊断，基准为已发布 0.11.1；手动发布时选择已有标签 v0.11.5，并设为最新正式 Release。
- dev.2 验证：176 项实际执行测试通过，另 16 项真实 IT 未执行；新增 2 项通知助手导航回归通过，进入 / 返回中间帧已检查，APK 构建和同签名校验通过。安装包为 /workspace/shared/downloads/hermes-mobile-0.15.0-dev.2.apk，验证记录保留于 /workspace/hermes-cloud/releases/0.15.0-dev.2。
- 0.15.0-dev.2：通知助手改用全局 NotificationAssistantRoute + Navigator / SwipeBackHost，设置入口 push，返回按钮 pop；移除设置页内直接替换内容和通知页自带的 SystemBackHandler。进入、退出、右滑跟手 / 取消统一走全局动画，返回后保留设置页的 Tab 和滚动位置。新增真实入口的动画中间进度与手势回归测试。
- 0.11.1 已按留档补丁恢复源码并推送标签 v0.11.1（源码提交 90124d35a0317ee309b5fb602d0088699ed74500）。用户决定自行在浏览器发布 Release；发布资料包位于 /workspace/shared/downloads/hermes-0.11.1-release-kit.zip，包含原 APK、校验文件、中英文双语更新说明和填写步骤。
- 当前工作区开发版 **0.16.0-dev / code 35**：基于 0.15.0-dev.2，修复流式公开正文在工具/审批边界消失；正文与三个点同时显示。增加设置「思考与工作过程」（默认关闭、自动流式、宽版正文、可展开收起），仅收集服务器实际发来的 reasoning/tool 事件；关闭立即清除步骤，过程和公开阶段旁白仅留当前连接内存，不入正式消息缓存/引用/模型请求。Dock 支持跟手拖动、图标预览、松手吸附切页及玻璃回弹，拖动期间不切页。详见 [docs/0.16.md](docs/0.16.md) 和更新后的协议。完整套件 202 项报告、0 失败，186 项实际通过，16 项真实 IT 未配置未执行；APK 构建成功，签名仍为 d985fcaf…db1dc7，SHA256 ba393c1e99db2b52de956bddbb53a30f724c2e816f390dd12ae09585ac727328。没有 adb 设备，真机手势和折射未验证。交付 APK/ZIP/双语说明在 /workspace/shared/downloads，完整验证和源码补丁在 /workspace/hermes-cloud/releases/0.16.0-dev；源码保存于 dev 分支，尚未创建开发版 Release。
- 0.15 基线 **0.15.0-dev.2 / code 34**：按用户在「设置 hermes」会话确认的范围，提前开发通知助手（授权、白名单、本地加密队列、固定连接 / Bot / 会话、周期批量摘要和到达提醒）。入口在「设置 → 通知助手 · 开发版」，默认关闭，0.16 继续保留。实现细节及限制见 [docs/notification-assistant.md](docs/notification-assistant.md)。Termux、其它扩展和长期记忆未开发；验证结果见该文档末尾。
- 首个 0.15.0-dev 验证：174 项实际执行测试通过，另 16 项真实 IT 未执行；浅 / 深色截图已检查，App 和 Android 自检 APK 已构建，adb 无设备。开发版尚未推送或创建 Release。Gradle 属性 hermesDebugKeystore 可显式选择原调试签名，当前云环境 env.sh 已指定原文件；证书与既有 d985fcaf…db1dc7 一致。安装包和 ZIP 位于 /workspace/shared/downloads，验证报告与源码补丁保留于 /workspace/hermes-cloud/releases/0.15.0-dev。
- 0.11.6 / code 32：修复电脑创建的中文项目不能新建 / 继续对话。原因是把 Unicode 目录直接放进 `X-Hermes-Workspace` 被 OkHttp 拒绝；中文目录保留在 JSON `instructions`，仅合法 ASCII 目录保留兼容请求头，不修改目录、不要求改名。发送准备参数异常也会恢复空闲，失败消息不再阻塞下一条发送。新增 `WorkspaceChatTest` 覆盖真实 HTTP、项目草稿、旧会话、非 ASCII 路径与异常恢复；编码环境仍无手机，设备效果需复测。 本次标准套件报告 175 项、0 失败，实际执行 159 项通过，另 16 项真实 IT 无环境未执行；App 与 Android 自检 APK 构建成功。
- SSH 隧道修复：用户已反馈本轮改动可行。修复仅 SSH 本地入口使用 IPv4 与直连代理、单通道拒绝误重置整个 SSH 会话、HTTP 错误被覆盖成网络不可达；补全双向半关闭、正文 / SSE 取消、匿名阶段和字节诊断。设置里「导出诊断信息」可查看安全记录。设备自检已加入，但编码环境没有 adb 设备，尚未在设备上执行。SSH 修复提交时主线为 0.11.0 / code 26；用户随后授权创建 Release，发布准备版本为 0.11.5 / code 31，保证可覆盖 code 30 的测试包。此前 0.11.4 测试 APK 来自会话工作区。本次提交范围为 SSH 修复、配套隐私诊断、测试与文档，详见 [docs/ssh-fix-report.md](docs/ssh-fix-report.md)。 本次独立提交验证：标准套件 170 项报告、0 失败，实际执行 154 项通过，另 16 项真实 IT 因缺少环境直接返回；App 与 Android 自检 APK 构建成功。
- 当前发布准备版本 **0.11.6**（`app/build.gradle.kts`：`versionCode = 32`），包含 SSH 与中文项目修复，用户已授权创建 GitHub Release。0.11.5 的代码和标签已推送，但发布 API 被云环境网络策略拦截（CONNECT 403），Release 尚未创建；已保存仅开放 `api.github.com` / `uploads.github.com` 的配置草稿，需要用户在环境设置保存并发布后再继续。发布结果以 GitHub Release 实际存在并附上 APK 为准。
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
5. APK 使用 debug 签名；覆盖升级必须沿用已安装版本的同一签名，不能仅凭“都是 debug”判断可覆盖。用户已允许后续版本继续使用本轮云环境签名，证书 SHA-256 为 `d985fcaf013e9f9b87f575c776b38af9c560984d07b2a27b17abbaf876db1dc7`，签名文件不进仓库。用户提供的原版 0.10.2 证书为 `7c55603a0c9ec7849a74d3168f3b1e2ec2164bfafd93ac7b7f53d08548c8a773`，与本轮不同，不能直接覆盖它；不要让用户为排查问题直接卸载而丢失数据。

## 闪退日志

0.10.5 起有：
- App 闪退后**下次打开会弹「上次 App 意外退出了」**，里面是出错位置，有「复制」按钮。
- **设置页最底下「导出诊断信息」**：分享出去的文字里带最近几条闪退 / 后台出错的记录（不含 Key、密码、主机）。
- 文件在手机的 App 私有目录 `files/crash-log.txt`（debug 包可以 `adb shell run-as com.hermes.mobile cat files/crash-log.txt`）。
- 0.10.5 之前的版本没有记录。

## 接下来的计划

用户定的方向（2026-10）：集成 Termux 一键安装本地 Hermes，然后做可选扩展（智能家居、ADB 操作手机、本地 Chromium、读取通知）和“越用越懂你”。
版本已重新安排，拆分、做法、风险、验证方式见 [docs/roadmap.md](docs/roadmap.md)。通知助手及工作过程已纳入 0.12；0.13 已接入外部 Termux 运行模块，当前引导与模型设置进展见本文顶部，其它阶段仍属计划。

## 没做完 / 待确认

- **手机上建的项目电脑版看不到**：还没定位。最可能是电脑版连的不是同一个 Hermes 或不是同一个 Profile；等用户在电脑上跑 `hermes project list` 和 `hermes config path` 对一下。
- **中文名项目闪退**：没复现出来；0.10.5 加了防护和闪退日志，等用户的日志。
- **Bot 和群聊没在用户自己的电脑上验证过**（只在测试环境里跑通）。重点看：新式安装下 `import tui_gateway.server` 是否正常、开通访问后 gateway 重启、
  群聊桥能不能连上 gateway（`discover_gateway_endpoint` / 控制 socket）。
- **群聊的归属**：房间属于建它的连接身份（同一台机器同一个系统用户）。如果用户的电脑版连的是另一台机器（比如云主机），两边的群互相看不到。
- 群聊里手机还不能发附件（收到的附件只显示个数）；电脑版的旧式群聊（legacy groups，存在 `ui_meta['hermes-bots-groups']`）不显示。
- 选了“贵的模型”时的确认流程（`confirm_required`）没在测试里触发过。
- 头像只做了居中裁剪，没有手动裁剪界面。
- **系统返回手势（预测性返回）没有任何测试**：`tools/ui-preview` 里的 `SystemBackHandler` 是空实现，`SwipeBackTest` 只测了应用内右滑，
  截图 23b/23c 是直接设进度摆的。之前给用户的说明里把这条路说成能用，是没验证过的。要补：桌面版做一个能模拟系统手势的假实现，测拖到一半取消、
  松手提交、聊天页搜索时先关搜索；另外系统返回不检查页面是否正在退出（连按两次返回可能只退一页）。vivo 等系统可能根本不传手势进度，要真机确认。
- Bot 屏幕：用户满意，后续按需继续。vivo 原子岛：暂不做。
