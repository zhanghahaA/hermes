package com.hermes.mobile.data.local

import com.hermes.mobile.data.store.DiagnosticRedactor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

/** 外部 Termux 后端。固定路径、APT 和 Intent 都留在这里，供以后整体替换。 */
class TermuxRuntimeBackend(private val transport: TermuxTransport, private val program: String, private val modelProgram: String? = null) : LocalRuntimeBackend {
    override val name = "Termux"
    override val externalApps = true
    override val hermesInstallWarning: String = PackageWarning
    override val initializationCommand: String = EnableExternalApps
    override fun environment() = transport.environment()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val privateValues = mutableSetOf<String>()

    override suspend fun execute(request: RuntimeRequest, progress: (String) -> Unit): RuntimeResponse {
        val env = environment()
        check(env.blockers.isEmpty()) { env.blockers.joinToString("\n") }
        request.model?.apiKey?.let { privateValues += it }
        if (request.action in setOf(RuntimeAction.InstallHermes, RuntimeAction.UpgradeHermes)) {
            check(request.acceptExperimental) { PackageWarning }
            progress("正在验证官方软件源并安装 Hermes，可能需要几分钟")
            if (request.action == RuntimeAction.InstallHermes) {
                val existing = checkedRun(Header + "\nif command -v hermes >/dev/null 2>&1 && hermes --version >/dev/null 2>&1; then echo HM_HERMES_READY; fi")
                if ("HM_HERMES_READY" !in existing.stdout.lines()) checkedRun(installScript(request.channel, false))
            } else checkedRun(installScript(request.channel, true))
        } else if (request.action == RuntimeAction.RepairEnvironment) {
            progress("正在更新 Termux 软件包以修复版本冲突，可能需要几分钟")
            checkedRun(Header + "\n" + RepairPackages)
        } else if (request.action == RuntimeAction.Prepare) {
            progress("正在准备 Python 服务环境，可能需要几分钟")
            checkedRun(Header + "\n" + DomesticPackageSources.setup() + "\n" + DomesticPackageSources.Update + "\nhm_apt install -y python")
        } else if (request.action == RuntimeAction.InstallBrowser) {
            progress("正在安装 Chromium 与 X11 配套包，可能需要较长时间")
            checkedRun(Header + "\n" + DomesticPackageSources.setup() + "\n" + DomesticPackageSources.Update + "\nhm_apt install -y termux-x11-nightly chromium")
        } else if (request.action == RuntimeAction.InstallProxy) {
            progress("正在通过清华镜像准备代理软件源")
            checkedRun(Header + "\n" + DomesticPackageSources.setup() + "\n" + DomesticPackageSources.Update)
        }
        val operation = if (request.action in setOf(RuntimeAction.Prepare, RuntimeAction.RepairEnvironment, RuntimeAction.InstallHermes, RuntimeAction.UpgradeHermes, RuntimeAction.InstallBrowser))
            RuntimeRequest(RuntimeAction.Inspect) else request
        if (operation.action == RuntimeAction.StartBrowser) check(env.viewerInstalled) { "请先安装并打开独立的 Termux:X11 应用" }
        val probe = checkedRun(Header + "\nif command -v python3 >/dev/null 2>&1; then echo HM_PYTHON_READY; else echo HM_PYTHON_MISSING; fi", readOnly = true)
        if ("HM_PYTHON_MISSING" in probe.stdout.lines()) {
            check(operation.action == RuntimeAction.Inspect) { "请先准备 Python 服务环境" }
            val basic = checkedRun(Header + "\ncommand -v hermes >/dev/null 2>&1 && echo HM_HERMES_READY || true", readOnly = true)
            checkInstaller()
            return RuntimeResponse(RuntimeSnapshot(hermesInstalled = "HM_HERMES_READY" in basic.stdout.lines()))
        }
        val encoded = Base64.getEncoder().encodeToString(program.toByteArray(Charsets.UTF_8))
        val loader = "import base64;exec(compile(base64.b64decode('$encoded'),'hermes-runtime','exec'))"
        val script = Header + "\npython3 -c " + quote(loader)
        val result = checkedRun(script, json.encodeToString(RuntimeRequest.serializer(), operation), readOnly = operation.action == RuntimeAction.Inspect)
        val receipts = result.stdout.lineSequence().filter { it.startsWith(Receipt) }.toList()
        check(receipts.size == 1) { "运行组件返回的状态不完整，请重新检查" }
        val receipt: JsonObject = try { json.parseToJsonElement(receipts.single().removePrefix(Receipt)).jsonObject }
            catch (_: Exception) { error("运行组件状态解析失败，请重新检查") }
        val snapshot = try { json.decodeFromJsonElement(RuntimeSnapshot.serializer(), receipt["snapshot"] ?: error("missing")) }
            catch (_: Exception) { error("运行组件缺少完整状态，请重新检查") }
        val key = receipt["connectionKey"]?.jsonPrimitive?.content
        if (operation.action == RuntimeAction.ConfigureHermes) check(key != null && Key.matches(key)) { "没有收到完整的本机连接凭据" }
        else check(key == null) { "运行组件返回了不匹配的操作结果" }
        key?.let { privateValues += it }
        val output = safe(receipt["output"]?.jsonPrimitive?.content.orEmpty())
        if (operation.action == RuntimeAction.Inspect && !snapshot.hermesInstalled) checkInstaller()
        if (operation.action == RuntimeAction.StartBrowser) transport.openViewer()
        val configuredSnapshot = if (operation.action == RuntimeAction.Inspect && snapshot.hermesInstalled && snapshot.configured && modelProgram != null) {
            val raw = runModelSettings(modelProgram, """{"action":"inspect"}""", readOnly = true)
            val line = raw.lineSequence().singleOrNull { it.startsWith("HMMODELS") }
            val settings = runCatching {
                json.decodeFromString(com.hermes.mobile.data.providers.ModelSettingsResponse.serializer(),
                    checkNotNull(line).removePrefix("HMMODELS"))
            }.getOrNull()
            val model = settings?.snapshot
            snapshot.copy(modelConfigured = settings?.ok == true && model != null &&
                model.provider.isNotBlank() && model.model.isNotBlank() &&
                (model.provider == "local" || model.hasKey || model.providers.any { it.id == model.provider && it.authenticated }))
        } else snapshot
        return RuntimeResponse(configuredSnapshot, output, key)
    }

    override suspend fun modelSettings(program: String, input: String): String {
        val action = runCatching { json.parseToJsonElement(input).jsonObject["action"]?.jsonPrimitive?.content }.getOrNull()
        return runModelSettings(program, input, readOnly = action in setOf("inspect", "models"))
    }

    private suspend fun runModelSettings(program: String, input: String, readOnly: Boolean): String {
        check(environment().blockers.isEmpty()) { "请先完成 Termux 执行授权" }
        val command = com.hermes.mobile.data.remote.HermesAdmin.runtimePython(
            "/data/data/com.termux/files/home/.hermes-mobile", program, emptyList())
        return checkedRun(Header + "\n" + command, input, readOnly).stdout
    }

    private fun safe(value: String): String = DiagnosticRedactor.redact(value, privateValues)
        .replace(Regex("(?i)\\bsk-[a-z0-9_-]{10,}\\b"), "[已隐藏]").takeLast(12_000)

    /** 安装前只运行本地版本检查，不访问网络，也不自动更新用户的软件包。 */
    private suspend fun checkInstaller() {
        checkedRun(Header + "\nif command -v curl >/dev/null 2>&1; then curl --version >/dev/null; fi", readOnly = true)
    }

    private suspend fun checkedRun(script: String, input: String = "", readOnly: Boolean = false): TermuxCommandResult {
        val result = transport.run(script, input, readOnly)
        result.issue?.let { throw RuntimeCommandException(it, result.resultCode) }
        if (result.exitCode != 0) {
            if (input.isEmpty() && result.exitCode == 44 &&
                result.stderr.lineSequence().any { it == "HM_REPOSITORY_UNAVAILABLE" }) {
                throw RuntimeCommandException(RuntimeIssue.RepositoryUnavailable, result.exitCode)
            }
            val raw = result.stderr + "\n" + result.stdout
            if (input.isEmpty() && raw.contains("CANNOT LINK EXECUTABLE", ignoreCase = true) &&
                raw.contains("curl", ignoreCase = true) && raw.contains("cannot locate symbol", ignoreCase = true)) {
                throw RuntimeCommandException(RuntimeIssue.PackageMismatch, result.exitCode)
            }
            // 有结构化输入的操作不显示原始回包：失败时也不能把 Key 或导入的程序泄露给界面。
            val detail = if (input.isNotEmpty()) "运行操作未完成，请检查配置、依赖或日志；已有数据已保留"
            else safe((result.stderr + "\n" + result.stdout).lineSequence().filterNot { it.startsWith(Receipt) }.joinToString("\n"))
                .ifBlank { "Termux 命令未完成，请检查执行授权和后台状态" }
            throw RuntimeCommandException(RuntimeIssue.CommandFailed, result.exitCode, detail)
        }
        return result
    }

    companion object {
        const val Receipt = "HMRUNTIME"
        const val PackageWarning = "从 Hermes 官方签名软件源安装到 Termux，需要网络和存储空间；保留已有配置。安装失败时可查看原因并重试。"
        const val DownloadTermux = "https://github.com/termux/termux-app/releases"
        const val DownloadX11 = "https://github.com/termux/termux-x11/releases/tag/nightly"
        const val Fingerprint = "C572B5FDD1A29CCFA9A912B6840B0848E139156D"
        val EnableExternalApps = "mkdir -p ~/.termux && printf '\\nallow-external-apps=true\\n' >> ~/.termux/termux.properties && termux-reload-settings && printf '准备完成，请返回 Hermes 继续授权和安装。\\n' && (am start -n com.hermes.mobile/.MainActivity >/dev/null 2>&1 || true)"
        internal fun quote(text: String) = "'" + text.replace("'", "'\"'\"'") + "'"
        private val Key = Regex("[a-f0-9]{64}")
        internal val Header = """
            set -eu
            umask 077
            export HERMES_MOBILE_ROOT="§HOME/.hermes-mobile"
            export HERMES_HOME="§HERMES_MOBILE_ROOT"
            export PATH="§PREFIX/bin:§PATH"
            [ "§PREFIX" = /data/data/com.termux/files/usr ] || { echo '需要标准 Termux 环境' >&2; exit 20; }
            [ ! -L "§HERMES_MOBILE_ROOT" ] || { echo '运行目录不能是符号链接' >&2; exit 21; }
        """.trimIndent().replace('§', '$')

        // pkg 的镜像探测依赖 curl；curl 本身损坏时直接使用 APT 修复。
        // 拒绝移除已有软件包，保留已有配置文件；更新失败或 curl 仍不可运行时停止。
        internal val RepairPackages = DomesticPackageSources.setup() + "\n" + """
            export DEBIAN_FRONTEND=noninteractive
            hm_apt -o APT::Update::Error-Mode=any update
            hm_apt -y --no-remove -o Dpkg::Options::=--force-confold dist-upgrade
            curl --version >/dev/null
        """.trimIndent()

        internal fun installScript(channel: ReleaseChannel, upgrade: Boolean): String = Header + "\n" +
            (if (upgrade) """
                hm_source=§(cat "§PREFIX/etc/apt/sources.list.d/hermes-agent.list")
                case "§hm_source" in
                    *'/canary hermes-canary main') hm_channel=canary ;;
                    *'/stable hermes-stable main') hm_channel=stable ;;
                    *) echo '无法识别已有安装通道，请重新选择部署通道' >&2; exit 24 ;;
                esac
            """.trimIndent().replace('§', '$') else "hm_channel=" + channel.id) + "\n" + """
            [ "§(uname -m)" = aarch64 ] || exit 22
            # 先检查选中通道，再安装其它依赖；不因稳定版缺失而自动切换预览版。
            @@DOMESTIC_ONLY@@
            if ! command -v curl >/dev/null 2>&1; then
                hm_apt -o APT::Update::Error-Mode=any update
                hm_apt install -y curl
            fi
            hm_fetch() {
                hm_status=§(curl --proto '=https' --proto-redir '=https' --tlsv1.2 -fsSL --connect-timeout 20 --max-time 120 -w '%{http_code}' "§1" -o "§2") || {
                    hm_result=§?
                    case "§hm_status" in
                        404|410) echo HM_REPOSITORY_UNAVAILABLE >&2; exit 44 ;;
                        *) exit "§hm_result" ;;
                    esac
                }
            }
            hm_key=§(mktemp)
            trap 'rm -f "§hm_key"' EXIT
            hm_fetch "https://hermes-assets.nousresearch.com/releases/termux/§hm_channel/key.asc" "§hm_key"
            hm_fetch "https://hermes-assets.nousresearch.com/releases/termux/§hm_channel/dists/hermes-§hm_channel/InRelease" /dev/null
            hm_apt -o APT::Update::Error-Mode=any update
            hm_apt install -y gnupg
            hm_fingerprint=§(gpg --batch --with-colons --show-keys "§hm_key" | awk -F: '§1 == "fpr" {print §10; exit}')
            hm_primary_count=§(gpg --batch --with-colons --show-keys "§hm_key" | awk -F: '§1 == "pub" {n++} END {print n+0}')
            [ "§hm_primary_count" = 1 ] || { echo '仓库公钥数量异常，已停止' >&2; exit 23; }
            [ "§hm_fingerprint" = '@@FINGERPRINT@@' ] || { echo '仓库公钥指纹不符，已停止' >&2; exit 23; }
            mkdir -p "§PREFIX/etc/apt/keyrings" "§PREFIX/etc/apt/sources.list.d"
            cp "§hm_key" "§PREFIX/etc/apt/keyrings/hermes-agent.asc"
            printf '%s\n' "deb [signed-by=§PREFIX/etc/apt/keyrings/hermes-agent.asc] https://hermes-assets.nousresearch.com/releases/termux/§hm_channel hermes-§hm_channel main" > "§PREFIX/etc/apt/sources.list.d/hermes-agent.list"
            @@DOMESTIC_HERMES@@
            hm_apt -o APT::Update::Error-Mode=any update
            hm_apt @@OPERATION@@ -y hermes-agent
            hermes --version
        """.trimIndent().replace('§', '$')
            .replace("@@DOMESTIC_ONLY@@", DomesticPackageSources.setup(false))
            .replace("@@DOMESTIC_HERMES@@", DomesticPackageSources.setup())
            .replace("@@FINGERPRINT@@", Fingerprint).replace("@@OPERATION@@", if (upgrade) "install --only-upgrade" else "install")
    }
}
