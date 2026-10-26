"""手机模型设置桥：在目标 Hermes 自己的 Python 中运行，凭据只从 stdin 接收。"""
import asyncio
import contextlib
import fcntl
import importlib.util
import json
import os
import re
import secrets
import shutil
import sys
import tempfile
import threading
import time
from pathlib import Path
from urllib.parse import urlsplit

RECEIPT = "HMMODELS"
ROOT = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes"))).absolute()
SID = re.compile(r"[a-f0-9]{32}\Z")
SLUG = re.compile(r"[A-Za-z0-9][A-Za-z0-9:_-]{0,79}\Z")


def owned(path):
    path = Path(path)
    relative = path.relative_to(ROOT)
    current = ROOT
    if ROOT.is_symlink():
        raise ValueError("运行目录不能是符号链接")
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise ValueError("模型配置路径不能是符号链接")
    return path


def atomic(path, payload):
    path = owned(path)
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    fd, temporary = tempfile.mkstemp(prefix=".mobile-", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(payload, stream, ensure_ascii=False)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def login_path(sid, suffix=".json"):
    if not isinstance(sid, str) or not SID.fullmatch(sid):
        raise ValueError("授权会话无效")
    return owned(ROOT / ".mobile-model-logins" / (sid + suffix))


def read_login(sid):
    path = login_path(sid)
    if not path.is_file() or path.stat().st_size > 16384:
        raise ValueError("授权会话不存在")
    result = json.loads(path.read_text())
    if result.get("id") != sid:
        raise ValueError("授权会话不匹配")
    if time.time() > result.get("expiresAt", 0) and result.get("status") in ("starting", "pending"):
        result["status"] = "expired"
    return result


def endpoint(value):
    if not isinstance(value, str) or len(value) > 2048:
        raise ValueError("模型端点无效")
    uri = urlsplit(value)
    if not uri.hostname or uri.username or uri.password or uri.query or uri.fragment or not (
        uri.scheme == "https" or uri.scheme == "http" and uri.hostname in ("127.0.0.1", "localhost", "::1")
    ):
        raise ValueError("模型端点无效")
    return value.rstrip("/")


def scope():
    try:
        from hermes_cli.web_server_profiles import _config_profile_scope
    except ImportError:
        return contextlib.nullcontext()
    return _config_profile_scope(None)


def config_read():
    from hermes_cli.config import load_config
    path = owned(ROOT / "config.yaml")
    if path.is_file():
        # load_config 某些旧版本解析失败时回退默认值；写入前必须单独检查原文件。
        try:
            try:
                import hermes_yaml as yaml
            except ImportError:
                import yaml
            raw = yaml.safe_load(path.read_text())
            if raw is not None and not isinstance(raw, dict):
                raise ValueError("配置根节点无效")
        except Exception as exc:
            raise ValueError("模型配置文件无法读取，请先修复，已有配置未覆盖。") from exc
    return load_config()


def snapshot(include_models=False):
    from hermes_cli.auth import PROVIDER_REGISTRY
    from hermes_cli.models import list_available_providers
    cfg = config_read()
    current = cfg.get("model") or {}
    if not isinstance(current, dict):
        current = {"default": str(current)}
    availability = {row["id"]: row for row in list_available_providers()}
    accounts = {}
    try:
        from hermes_cli.web_routers.oauth import _build_oauth_catalog
        accounts = {row["id"]: row for row in _build_oauth_catalog()}
        # 没有启动器就不显示不能执行的浏览器入口。
        from hermes_cli.web_routers.oauth import _start_device_code_flow
    except ImportError:
        accounts = {key: dict(row, flow="external") for key, row in accounts.items()}
    models = {}
    if include_models:
        try:
            from hermes_cli.inventory import build_model_options_payload, load_picker_context
            for row in build_model_options_payload(load_picker_context(), include_unconfigured=True).get("providers", []):
                models[row["slug"]] = [m for m in row.get("models", []) if isinstance(m, str)][:200]
        except Exception:
            pass
        models["custom"] = models.get("mobile-custom", models.get("custom:mobile-custom", []))
        models["local"] = models.get("mobile-local", models.get("custom:mobile-local", []))
    rows = []
    for slug, provider in PROVIDER_REGISTRY.items():
        if not SLUG.fullmatch(slug) or slug not in availability:
            continue
        account = accounts.get(slug, {})
        has_key = getattr(provider, "auth_type", "") == "api_key" and bool(getattr(provider, "api_key_env_vars", ()))
        rows.append({"id": slug, "name": str(getattr(provider, "name", slug)),
                     "apiKey": has_key, "authenticated": bool(availability.get(slug, {}).get("authenticated")),
                     "flow": account.get("flow", "external" if not has_key else ""), "models": models.get(slug, [])})
    for slug, account in accounts.items():
        if slug not in {row["id"] for row in rows} and SLUG.fullmatch(slug):
            rows.append({"id": slug, "name": str(account.get("name", slug)), "apiKey": False,
                         "authenticated": bool(availability.get(slug, {}).get("authenticated")),
                         "flow": account.get("flow", "external"), "models": models.get(slug, [])})
    if not any(row["id"] == "openrouter" for row in rows):
        rows.append({"id": "openrouter", "name": "OpenRouter", "apiKey": True, "flow": "external",
                     "authenticated": bool(availability.get("openrouter", {}).get("authenticated")), "models": models.get("openrouter", [])})
    rows.append({"id": "custom", "name": "自定义端点", "apiKey": True, "flow": "", "models": models.get("custom", [])})
    rows.append({"id": "local", "name": "本地模型 · Ollama / LM Studio / llama.cpp", "apiKey": False, "flow": "", "models": models.get("local", [])})
    preferred = ["openrouter", "openai-codex", "deepseek", "anthropic", "openai-api", "custom", "local"]
    rows.sort(key=lambda row: (preferred.index(row["id"]) if row["id"] in preferred else len(preferred), row["name"]))
    provider = str(current.get("provider") or "")
    base_url = str(current.get("base_url") or "")
    # 输出到手机的只含配置摘要，不返回现有 API Key 或 OAuth token。
    try:
        endpoint(base_url)
    except ValueError:
        base_url = ""
    return {"home": str(ROOT), "providers": rows, "provider": {"mobile-custom": "custom", "mobile-local": "local"}.get(provider, provider), "model": str(current.get("default") or ""),
            "apiMode": str((cfg.get("providers") or {}).get(provider, {}).get("api_mode") or "chat_completions"), "baseUrl": base_url, "hasKey": bool(current.get("api_key") or current.get("key_env") or availability.get(provider, {}).get("authenticated"))}


def backup():
    for name in ("config.yaml", ".env"):
        path = owned(ROOT / name)
        copy = owned(ROOT / (name + ".before-mobile-models"))
        if path.exists() and not copy.exists():
            data = path.read_bytes()
            fd = os.open(copy, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as stream:
                stream.write(data)


def save(request):
    from hermes_cli.config import save_config, is_managed
    from hermes_cli.auth import PROVIDER_REGISTRY
    cfg = config_read()
    if is_managed():
        raise ValueError("受管理配置不能修改")
    slug = request.get("provider", "")
    model = request.get("model", "").strip()
    key = request.get("apiKey")
    if not SLUG.fullmatch(slug) or not model or len(model) > 200 or any(ord(c) < 32 for c in model):
        raise ValueError("提供方或模型无效")
    if key is not None and (not isinstance(key, str) or len(key) > 4096 or any(ord(c) < 32 for c in key)):
        raise ValueError("模型密钥无效")
    owned(ROOT / ".env")
    backup()
    if slug in ("custom", "local"):
        url = endpoint(request.get("baseUrl", ""))
        mode = request.get("apiMode", "chat_completions")
        if mode not in ("chat_completions", "codex_responses", "anthropic_messages"):
            raise ValueError("接口格式无效")
        # 使用 Hermes 的命名端点入口，避免旧的 bare custom 形状丢失认证头。
        from hermes_cli.web_models import CustomEndpointUpdate
        from hermes_cli.web_routers.config_env import _write_custom_endpoint
        body = CustomEndpointUpdate(id="mobile-local" if slug == "local" else "mobile-custom",
                                    name="手机本地模型" if slug == "local" else "手机自定义端点",
                                    base_url=url, model=model, api_key=key, api_mode=mode,
                                    discover_models=False, make_default=True)
        _write_custom_endpoint(cfg, body)
    else:
        provider = PROVIDER_REGISTRY.get(slug)
        if provider is None and slug != "openrouter":
            raise ValueError("未找到提供方")
        env_var = "OPENROUTER_API_KEY" if slug == "openrouter" else (getattr(provider, "api_key_env_vars", ()) or ("",))[0]
        if key:
            if slug != "openrouter" and (getattr(provider, "auth_type", "") != "api_key" or not env_var):
                raise ValueError("该提供方不接受 API Key")
            try:
                from hermes_cli.credential_lifecycle import save_provider_env_credential
            except ImportError:
                from hermes_cli.config import save_env_value as save_provider_env_credential
            save_provider_env_credential(env_var, key)
        elif not next((r.get("authenticated") for r in snapshot()["providers"] if r["id"] == slug), False):
            raise ValueError("请先连接账号或填写模型密钥")
        current = cfg.get("model") if isinstance(cfg.get("model"), dict) else {}
        for field in ("api_key", "key_env", "base_url", "api_mode", "transport"):
            current.pop(field, None)
        current.update(provider=slug, default=model)
        cfg["model"] = current
    save_config(cfg)
    return {"snapshot": snapshot()}


def login_worker(sid, provider):
    path = login_path(sid)
    finished = threading.Event()
    try:
        from hermes_cli.web_routers.oauth import _start_device_code_flow
        from hermes_cli.web_server_oauth import _oauth_sessions, _oauth_sessions_lock
        def watch_cancel():
            while not finished.wait(0.1):
                if login_path(sid, ".cancel").exists():
                    with _oauth_sessions_lock:
                        for session in _oauth_sessions.values():
                            if session.get("provider") == provider and session.get("status") != "approved":
                                session["cancelled"] = True
                                session["status"] = "cancelled"
        threading.Thread(target=watch_cancel, daemon=True).start()
        # 复用 Hermes 的服务商流程与凭据保存，不在手机自行交换或持有订阅 token。
        response = asyncio.run(_start_device_code_flow(provider))
        internal = response["session_id"]
        url = response["verification_url"]
        uri = urlsplit(url)
        if uri.scheme != "https" or not uri.hostname or uri.username or uri.password:
            raise ValueError("授权地址无效")
        public = {"id": sid, "provider": provider, "status": "pending", "code": str(response["user_code"]),
                  "url": url, "expiresAt": int(time.time() + min(900, max(1, response.get("expires_in", 900))))}
        atomic(path, public)
        while time.time() < public["expiresAt"]:
            with _oauth_sessions_lock:
                session = _oauth_sessions.get(internal, {})
                if login_path(sid, ".cancel").exists() and session.get("status") != "approved":
                    session["cancelled"] = True
                    session["status"] = "cancelled"
                public["status"] = session.get("status", "error")
            atomic(path, public)
            if public["status"] != "pending":
                return
            time.sleep(1)
        public["status"] = "expired"
        atomic(path, public)
    except Exception:
        # 绝不把服务商错误、账号、token 或网络请求明文写入状态文件。
        atomic(path, {"id": sid, "provider": provider, "status": "cancelled" if login_path(sid, ".cancel").exists() else "error", "expiresAt": int(time.time())})
    finally:
        finished.set()


def login(provider):
    from hermes_cli.web_routers.oauth import _build_oauth_catalog
    from hermes_cli.web_routers.oauth import _start_device_code_flow
    catalog = {row["id"]: row for row in _build_oauth_catalog()}
    if not SLUG.fullmatch(provider) or catalog.get(provider, {}).get("flow") != "device_code":
        raise ValueError("此提供方使用原有命令行登录")
    sid = secrets.token_hex(16)
    public = {"id": sid, "provider": provider, "status": "starting", "expiresAt": int(time.time()) + 900}
    atomic(login_path(sid), public)
    # 父进程立即返回；子进程脱离 SSH/Termux 回调，登录不长期占住手机命令桥。
    pid = os.fork()
    if pid == 0:
        try:
            os.close(REPLY_FD)
            os.setsid()
            with open(os.devnull, "r+b", buffering=0) as sink:
                for fd in (0, 1, 2):
                    os.dup2(sink.fileno(), fd)
            login_worker(sid, provider)
        finally:
            os._exit(0)
    return {"login": public}


def dispatch(request):
    action = request.get("action")
    with scope():
        if action in ("inspect", "models"):
            return {"snapshot": snapshot(action == "models")}
        if action == "save":
            return save(request)
        if action == "login":
            return login(request.get("provider", ""))
        if action in ("poll", "cancel"):
            sid = request.get("sessionId", "")
            public = read_login(sid)
            if action == "cancel" and public.get("status") in ("starting", "pending"):
                atomic(login_path(sid, ".cancel"), {})
                public["status"] = "cancelled"
                atomic(login_path(sid), public)
            return {"login": public, "snapshot": snapshot() if public.get("status") == "approved" else None}
    raise ValueError("未知模型设置操作")


def main():
    global REPLY_FD
    os.umask(0o077)
    # 独立持有 stdout；Hermes 的导入及日志不能混入配置回包或泄露服务商数据。
    REPLY_FD = os.dup(1)
    output = os.fdopen(REPLY_FD, "w", encoding="utf-8", buffering=1)
    with open(os.devnull, "w") as sink:
        os.dup2(sink.fileno(), 1)
        os.dup2(sink.fileno(), 2)
    result = {}
    try:
        owned(ROOT)
        ROOT.mkdir(parents=True, exist_ok=True, mode=0o700)
        request = json.loads(sys.stdin.read(32769))
        # 每个 Hermes 数据目录单独串行配置，多个手机也不会互相覆盖读改写。
        with open(owned(ROOT / ".mobile-model-settings.lock"), "a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            result = dispatch(request)
            fcntl.flock(lock, fcntl.LOCK_UN)
        result["ok"] = True
    except ImportError:
        result = {"ok": False, "error": "当前 Hermes 不支持浏览器授权，请升级 Hermes 或使用下方登录命令。"}
    except Exception as exc:
        message = str(exc)
        result = {"ok": False, "error": message if message == "模型配置文件无法读取，请先修复，已有配置未覆盖。" else
                  "模型设置未完成：请检查模型、密钥和端点，修改前的配置备份已保留。"}
    output.write(RECEIPT + json.dumps(result, ensure_ascii=False) + "\n")


if __name__ == "__main__":
    main()
