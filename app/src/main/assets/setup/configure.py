"""用户主动粘贴执行的配置助手：保留有效密钥、备份配置、仅安装公钥。

SSH 连接走 Hermes gateway（和电脑版同一个服务），不需要 API Server：这时只授权手机公钥，不改 .env、不重启服务。"""
import base64
import hashlib
import fcntl
import getpass
import contextlib
import subprocess
import time
import urllib.request
import urllib.error
import ipaddress
import json
import os
import re
import secrets
import shlex
import shutil
import socket
import sys
import tempfile
import struct
from pathlib import Path


def no_links(path):
    for part in (path, *path.parents):
        if part.is_symlink():
            raise ValueError("目标路径含符号链接，已停止；请检查真实配置目录")


def atomic(path, contents):
    no_links(path)
    temporary = None
    try:
        handle, temporary = tempfile.mkstemp(prefix=".mobile-setup-", dir=path.parent)
        with os.fdopen(handle, "w", encoding="utf-8") as stream:
            stream.write(contents)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if temporary and os.path.exists(temporary):
            os.unlink(temporary)


def _configure(root, ssh_dir, parameters):
    no_links(root)
    nonce = parameters["nonce"]
    if not isinstance(nonce, str) or not re.fullmatch(r"[A-Za-z0-9-]{1,80}", nonce):
        raise ValueError("配置标识无效")
    result_dir = root / ".mobile-setup-results"
    result_id = hashlib.sha256(nonce.encode()).hexdigest()
    no_links(result_dir)
    no_links(result_dir / result_id)
    env_file = root / ".env"
    backup = root / ".env.before-mobile-setup"
    no_links(env_file)
    no_links(backup)
    gateway_only = parameters["type"] == "Ssh"
    previous = env_file.read_text(encoding="utf-8") if env_file.exists() else ""
    values = {}
    assignment = re.compile(r"^\s*(?:export\s+)?(API_SERVER_[A-Z_]+)\s*=(.*)$")
    for line in ([] if gateway_only else previous.splitlines()):
        match = assignment.match(line)
        if match:
            parts = shlex.split(match[2], comments=True)
            value = " ".join(parts)
            if match[1] in values and values[match[1]] != value:
                raise ValueError("存在冲突的 API Server 重复配置，请先确认要保留的值")
            values[match[1]] = value
    old_key = values.get("API_SERVER_KEY", "")
    # 已有接口密钥即使不合法也不得静默替换；模型提供商密钥不参与此逻辑。
    if gateway_only:
        key = ""
    elif "API_SERVER_KEY" in values:
        key = old_key
    else:
        key = parameters["apiKey"] or secrets.token_hex(32)
    if not gateway_only and (not isinstance(key, str) or not 16 <= len(key) <= 4096 or any(ord(c) < 32 or ord(c) == 127 for c in key)):
        raise ValueError("API Server 密钥格式不正确，已保留原值；请明确处理后重试，不要修改模型密钥")
    host = values.get("API_SERVER_HOST", "127.0.0.1")
    if parameters["type"] == "Direct":
        candidates = socket.getaddrinfo(parameters["host"], None, type=socket.SOCK_STREAM)
        host = candidates[0][4][0]
        address = ipaddress.ip_address(host)
        if address.is_unspecified or address.is_multicast:
            raise ValueError("请使用这台机器的局域网或 VPN 地址")
        # 必须属于当前机器，避免把手机填错的公网地址写入配置。
        with socket.socket(candidates[0][0], socket.SOCK_STREAM) as probe:
            probe.bind((host, 0))
    port = int(values.get("API_SERVER_PORT") or parameters["port"])
    if type(port) is not int or not 1 <= port <= 65535:
        raise ValueError("端口无效")
    public_key = parameters.get("publicKey", "")
    if public_key:
        if not re.fullmatch(r"(?:ssh-ed25519|ssh-rsa|ecdsa-sha2-[A-Za-z0-9-]+) [A-Za-z0-9+/=]+(?: [^\r\n]*)?", public_key):
            raise ValueError("公钥格式不正确")
        validate_public_key(public_key)
        no_links(ssh_dir)
        no_links(ssh_dir / "authorized_keys")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    if not gateway_only and env_file.exists() and not backup.exists():
        atomic(backup, previous)
    desired = {"API_SERVER_ENABLED": "true", "API_SERVER_KEY": key,
               "API_SERVER_HOST": host, "API_SERVER_PORT": str(port)}
    # 保留无关行、注释和已正确的赋值文本；只替换发生变化的项。
    changed_names = set() if gateway_only else {name for name, value in desired.items() if values.get(name) != value}
    edited = []
    for line in previous.splitlines(keepends=True):
        match = assignment.match(line.rstrip("\r\n"))
        if match and match[1] in changed_names:
            continue
        edited.append(line)
    contents = "".join(edited)
    if changed_names:
        if contents and not contents.endswith("\n"):
            contents += "\n"
        for name, value in desired.items():
            if name in changed_names:
                contents += name + "=" + (json.dumps(value) if name in ("API_SERVER_KEY", "API_SERVER_HOST") else value) + "\n"
    if not gateway_only:
        if contents != previous:
            atomic(env_file, contents)
        env_file.chmod(0o600)
    if public_key:
        ssh_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        ssh_dir.chmod(0o700)
        authorized = ssh_dir / "authorized_keys"
        existing = authorized.read_text(encoding="utf-8") if authorized.exists() else ""
        if authorized.exists():
            authorized.chmod(0o600)
        key_type, key_blob = public_key.split()[:2]
        # 注释不属于公钥身份；已有带限制的同一公钥也不能再添加一个无限制副本。
        identity = (key_type, key_blob)
        if not any(key_identity(line) == identity for line in existing.splitlines() if not line.lstrip().startswith("#")):
            existing = existing.rstrip("\n") + ("\n" if existing else "") + "no-agent-forwarding,no-X11-forwarding,no-pty " + public_key + "\n"
            atomic(authorized, existing)
    result = dict(parameters, apiKey=key, publicKey="", effectivePort=port, effectiveHost=host)
    receipt = "HERMES-MOBILE-SETUP:" + base64.urlsafe_b64encode(json.dumps(result, ensure_ascii=False).encode()).decode()
    result_dir.mkdir(mode=0o700, exist_ok=True)
    if not (result_dir / result_id).exists() or (result_dir / result_id).read_text() != receipt + "\n":
        atomic(result_dir / result_id, receipt + "\n")
    return key != parameters["apiKey"] or port != parameters["port"], receipt



def validate_public_key(line):
    algorithm, encoded = line.split()[:2]
    try:
        data = base64.b64decode(encoded, validate=True)
        if len(data) > 16384:
            raise ValueError()
        fields = []
        while data:
            length = struct.unpack(">I", data[:4])[0]
            if not length or length > len(data) - 4:
                raise ValueError()
            fields.append(data[4:4 + length])
            data = data[4 + length:]
        if not fields or fields[0].decode("ascii") != algorithm:
            raise ValueError()
        if algorithm == "ssh-ed25519":
            valid = len(fields) == 2 and len(fields[1]) == 32
        else:
            valid = len(fields) == 3
        if not valid:
            raise ValueError()
    except Exception:
        raise ValueError("公钥内容不完整或与算法不匹配") from None


def key_identity(line):
    # shlex 不把 command="... ssh-ed25519 ..." 内的文本误认为真正的公钥。
    try:
        fields = shlex.split(line, comments=True)
        index = 0 if fields and fields[0].startswith(("ssh-", "ecdsa-")) else 1
        if len(fields) > index + 1 and fields[index].startswith(("ssh-", "ecdsa-")):
            return tuple(fields[index:index + 2])
    except ValueError:
        pass
    return None


@contextlib.contextmanager
def locked(path):
    no_links(path)
    descriptor = os.open(path, os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, "w") as stream:
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError("已有配对任务正在运行，请等待它结束后重试")
        yield


def configure(root, ssh_dir, parameters):
    # 在涉及多个 Profile 的同一用户公钥文件上也串行，避免丢失并发授权。
    no_links(root)
    no_links(ssh_dir)
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    ssh_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    with locked(ssh_dir / ".hermes-mobile.lock"):
        paths = [root / ".env", ssh_dir / "authorized_keys"]
        originals = {}
        for path in paths:
            no_links(path)
            originals[path] = path.read_text() if path.exists() else None
        try:
            return _configure(root, ssh_dir, parameters)
        except Exception:
            # 仅还原本次写入；不从首次备份覆盖用户后续修改。
            for path, original in originals.items():
                current = path.read_text() if path.exists() else None
                if current != original:
                    if original is None:
                        path.unlink()
                    else:
                        atomic(path, original)
            raise


def validate_target(parameters):
    host = parameters["host"]
    if not isinstance(host, str) or not re.fullmatch(r"[A-Za-z0-9.:_-]+", host):
        raise ValueError("地址无效，请填写真实主机名或 IP，不要填写网址或占位文本")
    for name in ("port", "sshPort"):
        if type(parameters[name]) is not int or not 1 <= parameters[name] <= 65535:
            raise ValueError("连接端口无效")
    try:
        socket.getaddrinfo(host, None, type=socket.SOCK_STREAM)
    except OSError:
        raise ValueError("目标地址无法解析，请检查手机填写的主机地址")
    if parameters["type"] == "Ssh":
        if parameters["sshUser"] != getpass.getuser():
            raise ValueError("SSH 用户名与当前终端用户不同，请使用手机填写的用户执行")
        # 检查的是当前机器的 SSH，不将公网回环/NAT 可达性当成手机可达性。
        try:
            with socket.create_connection(("127.0.0.1", parameters["sshPort"]), timeout=3) as channel:
                channel.settimeout(3)
                if not channel.recv(255).startswith(b"SSH-"):
                    raise OSError()
        except OSError:
            raise ValueError("当前机器的 SSH 服务未就绪，请检查 sshd 和 SSH 端口；配置未修改")
        print("SSH 服务已响应；授权和隧道仍需手机验证。")


def api_ready(parameters, receipt):
    result = json.loads(base64.urlsafe_b64decode(receipt.split(":", 1)[1]))
    host = result.get("effectiveHost", parameters["host"]) if parameters["type"] == "Direct" else "127.0.0.1"
    if ":" in host:
        host = "[" + host + "]"
    prefix = "/p/" + parameters["profile"] if parameters.get("profile") else ""
    base = "http://" + host + ":" + str(result["effectivePort"]) + prefix
    # 本机探测不能经系统代理转发凭据；禁止 HTTP 重定向。
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *args, **kwargs):
            return None
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        with client.open(base + "/health", timeout=2) as response:
            health = json.loads(response.read(65536))
            if not isinstance(health, dict) or health.get("platform") != "hermes-agent":
                raise ValueError("目标端口不是可验证的 Hermes 服务，未对其执行重启")
        request = urllib.request.Request(base + "/v1/models", headers={"Authorization": "Bearer " + result["apiKey"]})
        with client.open(request, timeout=2) as response:
            return response.status == 200
    except urllib.error.HTTPError as error:
        if error.code in (401, 403):
            raise ValueError("API 认证失败，原密钥已保留；请检查服务实际使用的 Profile 和配置")
        return False
    except (OSError, ValueError) as error:
        if isinstance(error, ValueError):
            raise
        return False


def ensure_service(root, parameters, receipt, changed, timeout=90):
    ready = api_ready(parameters, receipt)
    if ready and not changed:
        print("API 认证通过，配置未变化，保留正在运行的服务。")
        return
    print("正在按当前 Profile 恢复 Gateway，并等待 API 认证检查……")
    # 官方 restart 会选择已安装的服务管理器；没有服务时按其前台回退逻辑运行。
    # 不使用 --all / --force，不越过上游的会话和多 Profile 保护。
    environment = dict(os.environ, HERMES_HOME=str(root))
    for name in tuple(environment):
        if name.startswith("API_SERVER_"):
            del environment[name]
    process = subprocess.Popen(["hermes", "gateway", "restart"], env=environment,
                               stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                               stderr=subprocess.DEVNULL, start_new_session=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        time.sleep(1)
        if api_ready(parameters, receipt):
            print("目标 API 服务已就绪，认证通过。")
            return
        if process.poll() not in (None, 0):
            raise ValueError("Gateway 拒绝启动或重启；请检查当前 Profile 的服务状态、模型配置或正在进行的会话")
    raise ValueError("等待 API 超时，未认定配对成功；配置和密钥已保留，可检查服务后重新执行")


GATEWAY_PROBE = """
from hermes_constants import get_hermes_home
from hermes_cli.gateway_runtime import discover_gateway_endpoint, attachable, gateway_profile_home
found = discover_gateway_endpoint(gateway_profile_home(get_hermes_home()))
print("running" if attachable(found) else "stopped:" + str(found.reason_code or found.state))
"""


def hermes_python():
    # 找 Hermes 自己用的 Python（和 App 经 SSH 找的办法一样）：新版 hermes 能直接说；
    # 旧版顺着 hermes 命令找：符号链接、同目录的 python、第一行 #!、包装脚本里 exec 的那个文件
    try:
        done = subprocess.run(["hermes", "--print-runtime-command"], stdin=subprocess.DEVNULL,
                              capture_output=True, text=True, timeout=20)
        lines = done.stdout.strip().splitlines()
        command = json.loads(lines[-1]) if done.returncode == 0 and lines else None
        if isinstance(command, list) and command and all(isinstance(part, str) for part in command):
            return command[:1]
    except (OSError, ValueError, subprocess.SubprocessError):
        pass
    path = shutil.which("hermes")
    for _ in range(5):
        if not path:
            break
        path = os.path.realpath(path)
        for candidate in ("python", "python3"):
            nearby = os.path.join(os.path.dirname(path), candidate)
            if os.access(nearby, os.X_OK):
                return [nearby]
        try:
            with open(path, "rb") as stream:
                text = stream.read(65536).decode("utf-8", "replace")
        except OSError:
            break
        first = text.splitlines()[0] if text else ""
        if first.startswith("#!") and "python" in first:
            return shlex.split(first[2:])
        found = re.search(r'exec "?(/[^ "]+)', text)
        path = found[1] if found else None
    return None


def report_gateway(root):
    # 只看一眼，不启动、不重启：gateway 由用户自己在电脑上管
    command = hermes_python()
    state = None
    if command:
        try:
            done = subprocess.run(command + ["-c", GATEWAY_PROBE], env=dict(os.environ, HERMES_HOME=str(root)),
                                  stdin=subprocess.DEVNULL, capture_output=True, text=True, timeout=30)
            state = (done.stdout.strip().splitlines() or [""])[-1]
        except (OSError, subprocess.SubprocessError):
            state = None
    if state == "running":
        print("Hermes gateway 正在运行，手机会经 SSH 连到它。")
    elif state and state.startswith("stopped"):
        print("提示：没检测到正在运行的 Hermes gateway。手机经 SSH 连的是 gateway，请在电脑上运行 hermes gateway（或安装为服务）后再连接。")
    else:
        print("提示：没能确认 Hermes gateway 的状态（这个版本的 Hermes 可能还不支持）。手机经 SSH 连的是 gateway，请确认它在运行。")


def main():
    os.umask(0o077)
    stage = "读取配对参数"
    try:
        parameters = json.loads(base64.b64decode(sys.argv[1]))
        profile = parameters.get("profile", "")
        if profile and not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", profile):
            raise ValueError("Profile 名称无效")
        if not shutil.which("hermes"):
            raise ValueError("未找到 Hermes，请先安装")
        print("已检测到 Hermes 命令。")
        stage = "检查 SSH 与地址"
        validate_target(parameters)
        root = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))).absolute()
        no_links(root)
        if profile not in ("", "default") and not root.is_dir():
            raise ValueError("所选 Profile 不存在，请先在 Hermes 中创建；不会自动改默认 Profile")
        root.mkdir(parents=True, exist_ok=True, mode=0o700)
        with locked(root / ".mobile-pair.lock"):
            stage = "保存配置与授权"
            env_file = root / ".env"
            no_links(env_file)
            before = env_file.read_bytes() if env_file.exists() else None
            reused, receipt = configure(root, Path.home() / ".ssh", parameters)
            if parameters["type"] == "Ssh":
                print("SSH 授权已检查；没有修改 Hermes 配置，也没有重启服务。")
                stage = "检查 Hermes gateway"
                report_gateway(root)
            else:
                print("API 配置与 SSH 授权已检查，已有密钥保持不变。")
                stage = "检查服务与 API 认证"
                ensure_service(root, parameters, receipt, before != env_file.read_bytes())
            if "--show-result" in sys.argv[2:]:
                # 仅显式要求兼容旧客户端时导出敏感回执；不写日志。
                print(receipt)
            elif parameters["type"] != "Ssh" and reused:
                print("已保留服务器原密钥。直连请填写原 API Server Key；旧版敏感连接码仅保存在当前 Profile 的私有结果目录，不会打印到日志。")
            print("服务端检查完成。请返回手机点「检查并连接」，验证实际通道和最终连接。")
    except ValueError as error:
        # 仅本文件固定错误可展示，解析器等异常不能带出敏感输入。
        safe = str(error) if str(error).startswith(("API ", "API Server", "目标", "当前", "SSH ", "连接端口", "地址", "所选", "Profile", "未找到", "已有配对", "Gateway", "等待 API", "存在冲突")) else "参数或配置无效，已停止，请检查原配置"
        print(stage + "失败：" + safe, file=sys.stderr)
        sys.exit(2)
    except Exception:
        print(stage + "失败：请检查权限、文件内容和服务状态；不会输出凭据。", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
