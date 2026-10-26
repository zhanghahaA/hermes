"""用户主动粘贴执行的配置助手：保留有效密钥、备份配置、仅安装公钥。"""
import base64
import hashlib
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


def configure(root, ssh_dir, parameters):
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
    previous = env_file.read_text(encoding="utf-8") if env_file.exists() else ""
    values = {}
    assignment = re.compile(r"^\s*(?:export\s+)?(API_SERVER_[A-Z_]+)\s*=(.*)$")
    for line in previous.splitlines():
        match = assignment.match(line)
        if match:
            parts = shlex.split(match[2], comments=True)
            values[match[1]] = " ".join(parts)
    old_key = values.get("API_SERVER_KEY", "")
    key = old_key if len(old_key) >= 16 else (parameters["apiKey"] or secrets.token_hex(32))
    if not isinstance(key, str) or not 16 <= len(key) <= 4096 or any(ord(c) < 32 for c in key):
        raise ValueError("密钥格式不正确，未修改配置")
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
        no_links(ssh_dir)
        no_links(ssh_dir / "authorized_keys")
    root.mkdir(parents=True, exist_ok=True, mode=0o700)
    if env_file.exists() and not backup.exists():
        atomic(backup, previous)
    edited = [line for line in previous.splitlines() if not (assignment.match(line) and assignment.match(line)[1] in
              ("API_SERVER_ENABLED", "API_SERVER_KEY", "API_SERVER_HOST", "API_SERVER_PORT"))]
    edited += ["API_SERVER_ENABLED=true", "API_SERVER_KEY=" + json.dumps(key),
               "API_SERVER_HOST=" + json.dumps(host), "API_SERVER_PORT=" + str(port)]
    atomic(env_file, "\n".join(edited) + "\n")
    if public_key:
        ssh_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        ssh_dir.chmod(0o700)
        authorized = ssh_dir / "authorized_keys"
        existing = authorized.read_text(encoding="utf-8") if authorized.exists() else ""
        if public_key not in existing.splitlines():
            existing = existing.rstrip("\n") + ("\n" if existing else "") + public_key + "\n"
        atomic(authorized, existing)
    result = dict(parameters, apiKey=key, publicKey="", effectivePort=port)
    receipt = "HERMES-MOBILE-SETUP:" + base64.urlsafe_b64encode(json.dumps(result, ensure_ascii=False).encode()).decode()
    result_dir.mkdir(mode=0o700, exist_ok=True)
    atomic(result_dir / result_id, receipt + "\n")
    return key != parameters["apiKey"] or port != parameters["port"], receipt


def main():
    os.umask(0o077)
    try:
        parameters = json.loads(base64.b64decode(sys.argv[1]))
        if not shutil.which("hermes"):
            raise ValueError("未找到 Hermes。请先完成 Hermes 安装，再运行本命令")
        root = Path(os.environ.get("HERMES_HOME", str(Path.home() / ".hermes")))
        reused, receipt = configure(root, Path.home() / ".ssh", parameters)
        print("配置已保存，原配置备份为 .env.before-mobile-setup；私钥未上传。")
        if reused:
            print("手机会通过 SSH 自动获取密钥与端口；如使用直连或自动获取失败，请复制下方连接码，点「粘贴配置结果」。")
        print(receipt)
    except Exception:
        # 不回显参数、密钥、程序正文或可能包含凭据的异常。
        print("配置未完成：请确认 Hermes／Python 3 已安装、目标地址属于本机，且配置目录可写、没有符号链接。已有配置请先检查备份。", file=sys.stderr)
        sys.exit(2)


if __name__ == "__main__":
    main()
