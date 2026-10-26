"""Hermes 手机运行组件：固定目录、结构化输入、只管理自己启动的进程。"""
import fcntl
import json
import os
import re
import secrets
import shutil
import shlex
import signal
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.parse import urlsplit

ROOT = Path(os.environ.get("HERMES_MOBILE_ROOT", str(Path.home() / ".hermes-mobile")))
ID = re.compile(r"[a-z][a-z0-9-]{1,39}\Z")
REQUIREMENT = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]*(?:==[A-Za-z0-9][A-Za-z0-9.+_-]*)?\Z")


def owned(path):
    """受管理路径不允许通过符号链接写到其它位置。"""
    path = Path(path)
    relative = path.relative_to(ROOT)
    current = ROOT
    if ROOT.is_symlink():
        raise ValueError("运行目录不能是符号链接")
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("运行目录中存在符号链接，请先检查")
    return path


def atomic(path, text):
    path = owned(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix=".mobile-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def load(path, default=None):
    path = owned(path)
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def process_stamp(pid):
    # PID 可能被复用；同时校验内核启动时钟与独立会话组。
    try:
        stat = Path("/proc/%d/stat" % pid).read_text()
        fields = stat[stat.rfind(")") + 2:].split()
        if fields[0] == "Z":
            return None
        return fields[19] if os.getpgid(pid) == pid else None
    except (OSError, ValueError, IndexError):
        return None


def alive(name):
    record = load(ROOT / "processes" / (name + ".json"), {})
    pid = record.get("pid")
    return bool(isinstance(pid, int) and pid > 1 and record.get("start") and process_stamp(pid) == record["start"])


def start(name, argv, cwd, env=None):
    if alive(name):
        return
    log = owned(ROOT / "logs" / (name + ".log"))
    log.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    if log.exists() and log.stat().st_size > 1024 * 1024:
        os.replace(log, owned(log.with_suffix(".previous.log")))
    values = dict(os.environ)
    values.update(HERMES_HOME=str(ROOT), HERMES_MOBILE_MANAGED="1")
    values.update(env or {})
    with open(log, "ab", buffering=0) as output:
        child = subprocess.Popen(argv, cwd=cwd, env=values, stdin=subprocess.DEVNULL,
                                 stdout=output, stderr=output, start_new_session=True)
    stamp = process_stamp(child.pid)
    if not stamp:
        raise RuntimeError("进程启动后已退出，请查看日志")
    atomic(ROOT / "processes" / (name + ".json"), json.dumps({"pid": child.pid, "start": stamp}))
    time.sleep(0.5)
    if not alive(name):
        raise RuntimeError("进程启动后已退出，请查看日志")


def stop(name):
    record_path = ROOT / "processes" / (name + ".json")
    if alive(name):
        pid = load(record_path)["pid"]
        os.killpg(pid, signal.SIGTERM)
        for _ in range(50):
            if not alive(name):
                break
            time.sleep(0.1)
        if alive(name):
            raise RuntimeError("进程仍在退出，请稍后重试")
    owned(record_path).unlink(missing_ok=True)


def tail(name):
    log = owned(ROOT / "logs" / (name + ".log"))
    if not log.exists():
        return "暂无日志"
    with open(log, "rb") as stream:
        stream.seek(max(0, log.stat().st_size - 12000))
        text = stream.read(12000).decode("utf-8", errors="replace")
    # 本机凭据在任何日志展示之前清除；其它私密值在 Kotlin 端继续脱敏。
    for path in (ROOT / "mobile-api-key", ROOT / "config.yaml"):
        if path.name == "mobile-api-key" and path.exists():
            key = owned(path).read_text().strip()
            if key:
                text = text.replace(key, "[已隐藏]")
        elif path.exists():
            try:
                key = json.loads(owned(path).read_text()).get("model", {}).get("api_key", "")
                if key:
                    text = text.replace(key, "[已隐藏]")
            except (ValueError, AttributeError):
                pass
    env_path = owned(ROOT / ".env")
    if env_path.exists():
        for line in env_path.read_text().splitlines():
            if "=" not in line or line.lstrip().startswith("#"):
                continue
            name, value = line.split("=", 1)
            if not re.search(r"(?i)(key|token|secret|password)", name):
                continue
            try:
                pieces = shlex.split(value, comments=True)
                value = pieces[0] if pieces else ""
            except ValueError:
                value = value.strip().strip("\"'")
            if value:
                text = text.replace(value, "[已隐藏]")
    return text


def service_dir(service_id):
    if not isinstance(service_id, str) or not ID.fullmatch(service_id):
        raise ValueError("服务标识无效")
    return owned(ROOT / "services" / service_id)


def service_config(service_id):
    config = load(service_dir(service_id) / "service.json")
    if not config:
        raise ValueError("服务尚未安装")
    return config


def snapshot():
    services = []
    folder = owned(ROOT / "services")
    if folder.exists():
        for directory in sorted(folder.iterdir()):
            if ID.fullmatch(directory.name) and not directory.is_symlink():
                config = load(directory / "service.json")
                if config:
                    services.append({"id": directory.name, "name": config["name"], "running": alive("service-" + directory.name)})
    return {"toolsReady": True, "hermesInstalled": bool(shutil.which("hermes")),
            "configured": owned(ROOT / "mobile-api-key").is_file() and owned(ROOT / ".env").is_file(),
            "hermesRunning": alive("hermes"),
            "browserInstalled": bool(shutil.which("chromium") and shutil.which("termux-x11")),
            "browserRunning": alive("browser") and alive("x11"), "services": services}


def configure(model):
    if not shutil.which("hermes"):
        raise ValueError("请先安装 Hermes")
    if alive("hermes"):
        raise ValueError("请先停止本机 Hermes 再修改配置")
    if model:
        if not isinstance(model.get("model"), str) or not 0 < len(model["model"].strip()) <= 200:
            raise ValueError("模型名称无效")
        key = model.get("apiKey", "")
        if not isinstance(key, str) or not key or len(key) > 4096 or any(ord(c) < 32 for c in key):
            raise ValueError("模型凭据无效")
        endpoint = model.get("baseUrl", "https://openrouter.ai/api/v1")
        uri = urlsplit(endpoint)
        if not uri.hostname or uri.username or uri.password or uri.fragment or not (uri.scheme == "https" or uri.scheme == "http" and uri.hostname in ("localhost", "127.0.0.1", "::1")):
            raise ValueError("模型地址需要 HTTPS，本机可使用 HTTP")
        config = load(ROOT / "config.yaml", {})
        config["model"] = {"provider": "custom", "default": model["model"].strip(), "base_url": endpoint, "api_key": key}
        atomic(ROOT / "config.yaml", json.dumps(config, ensure_ascii=False, indent=2))
    key_path = owned(ROOT / "mobile-api-key")
    key = key_path.read_text().strip() if key_path.exists() else secrets.token_hex(32)
    if not re.fullmatch(r"[a-f0-9]{64}", key):
        raise ValueError("本机凭据格式异常，请先检查运行目录")
    atomic(key_path, key)
    env_path = owned(ROOT / ".env")
    previous = env_path.read_text() if env_path.exists() else ""
    lines = [line for line in previous.splitlines() if not re.match(r"\s*(?:export\s+)?API_SERVER_(?:ENABLED|HOST|PORT|KEY)\s*=", line)]
    lines += ["API_SERVER_ENABLED=true", "API_SERVER_HOST=127.0.0.1", "API_SERVER_PORT=8642", "API_SERVER_KEY=" + key]
    atomic(env_path, "\n".join(lines) + "\n")
    return key


def install_service(service):
    folder = service_dir(service["id"])
    if alive("service-" + service["id"]):
        raise ValueError("请先停止该服务再更新程序")
    if not isinstance(service.get("name"), str) or not 0 < len(service["name"].strip()) <= 80:
        raise ValueError("服务名称无效")
    source = service.get("source", "")
    if not isinstance(source, str) or not source.strip() or len(source.encode()) > 65536:
        raise ValueError("程序大小无效")
    requirements = service.get("requirements", [])
    if not isinstance(requirements, list) or len(requirements) > 20 or any(not isinstance(item, str) or not REQUIREMENT.fullmatch(item) for item in requirements):
        raise ValueError("依赖格式无效")
    # 先校验 Python 语法，不执行导入的用户代码。
    compile(source, "main.py", "exec")
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    venv = owned(folder / "venv")
    if not venv.exists():
        subprocess.run([sys.executable, "-m", "venv", str(venv)], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    if requirements:
        subprocess.run([str(venv / "bin/python"), "-m", "pip", "install", "--", *requirements], check=True,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1200)
    atomic(folder / "main.py", source)
    atomic(folder / "service.json", json.dumps({"name": service["name"], "kind": "python"}))


def install_proxy(proxy):
    folder = service_dir(proxy["id"])
    port = proxy.get("port")
    uri = urlsplit(proxy.get("target", ""))
    if type(port) is not int or port < 1024 or port > 65535 or port in (8642, 9222):
        raise ValueError("代理端口无效")
    if uri.scheme not in ("http", "https") or not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or uri.path not in ("", "/"):
        raise ValueError("代理目标格式无效")
    if not isinstance(proxy.get("name"), str) or not 0 < len(proxy["name"].strip()) <= 80:
        raise ValueError("代理名称无效")
    if alive("service-" + proxy["id"]):
        raise ValueError("请先停止该代理")
    subprocess.run(["pkg", "install", "-y", "caddy"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=1200)
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    atomic(folder / "service.json", json.dumps({"name": proxy["name"], "kind": "proxy", "port": port, "target": proxy["target"]}))


def dispatch(request):
    action = request["action"]
    output, key = "", None
    if action == "ConfigureHermes":
        key = configure(request.get("model"))
    elif action in ("StartHermes", "StopHermes", "RestartHermes", "HermesLogs", "RemoveHermes"):
        if action in ("StopHermes", "RestartHermes", "RemoveHermes"):
            stop("hermes")
        if action in ("StartHermes", "RestartHermes"):
            if not owned(ROOT / "mobile-api-key").is_file():
                raise ValueError("请先配置本机连接")
            local_key = owned(ROOT / "mobile-api-key").read_text().strip()
            start("hermes", ["hermes", "gateway", "run"], ROOT,
                  {"API_SERVER_ENABLED": "true", "API_SERVER_HOST": "127.0.0.1", "API_SERVER_PORT": "8642", "API_SERVER_KEY": local_key})
        elif action == "HermesLogs":
            output = tail("hermes")
        elif action == "RemoveHermes":
            subprocess.run(["pkg", "uninstall", "-y", "hermes-agent"], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=300)
    elif action == "InstallService":
        install_service(request["service"])
    elif action == "InstallProxy":
        install_proxy(request["proxy"])
    elif action in ("StartService", "StopService", "ServiceLogs", "RemoveService"):
        service_id = request["serviceId"]
        folder = service_dir(service_id)
        config = service_config(service_id)
        name = "service-" + service_id
        if action == "StartService":
            if config["kind"] == "python":
                argv = [str(folder / "venv/bin/python"), "-u", str(folder / "main.py")]
            else:
                argv = ["caddy", "reverse-proxy", "--from", "http://127.0.0.1:%d" % config["port"], "--to", config["target"]]
            start(name, argv, folder)
        elif action == "ServiceLogs":
            output = tail(name)
        else:
            stop(name)
            if action == "RemoveService":
                # 只移除固定的程序与元数据，保留该目录内用户运行生成的数据。
                owned(folder / "main.py").unlink(missing_ok=True)
                owned(folder / "service.json").unlink(missing_ok=True)
    elif action == "StartBrowser":
        if not shutil.which("chromium") or not shutil.which("termux-x11"):
            raise ValueError("请先安装浏览器配套包")
        browser_dir = owned(ROOT / "browser")
        browser_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        start("x11", ["termux-x11", ":17"], ROOT)
        try:
            start("browser", ["chromium", "--ozone-platform=x11", "--user-data-dir=" + str(browser_dir), "--no-first-run"], ROOT, {"DISPLAY": ":17"})
        except Exception:
            stop("x11")
            raise
    elif action == "StopBrowser":
        stop("browser")
        stop("x11")
    elif action != "Inspect":
        raise ValueError("不支持的运行操作")
    result = {"snapshot": snapshot(), "output": output}
    if key:
        result["connectionKey"] = key
    return result


def main():
    os.umask(0o077)
    try:
        owned(ROOT)
        ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
        request = json.loads(sys.stdin.read(180000))
        with open(owned(ROOT / ".manager.lock"), "a") as lock:
            try:
                fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise ValueError("另一项后台操作仍在执行，请稍后检查")
            result = dispatch(request)
        print("HMRUNTIME" + json.dumps(result, ensure_ascii=False))
    except (ValueError, SyntaxError, KeyError):
        # 不回显输入、URL、用户程序或模型密钥。
        print("HMRUNTIME_ERROR 配置或程序校验未通过，请检查输入和运行目录", file=sys.stderr)
        sys.exit(2)
    except Exception:
        print("HMRUNTIME_ERROR 运行操作未完成，请检查依赖、日志和后台状态", file=sys.stderr)
        sys.exit(3)


if __name__ == "__main__":
    main()
