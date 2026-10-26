"""可选的真实 Hermes 源码集成：只写临时配置；授权服务器由假流程替代，不调用付费模型。"""
import json
import os
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/assets/providers/settings.py"
UPSTREAM = os.environ.get("HERMES_PROVIDER_TEST_SOURCE")
PYTHON = os.environ.get("HERMES_PROVIDER_TEST_PYTHON")


@unittest.skipUnless(UPSTREAM and PYTHON, "未配置真实 Hermes 源码与 Python 环境")
class UpstreamProviderTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hermes-provider-it-")
        self.root = Path(self.temp.name)
        allowed = ("PATH", "LANG", "LC_ALL", "TMPDIR", "SSL_CERT_FILE", "SSL_CERT_DIR", "REQUESTS_CA_BUNDLE", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY")
        self.env = {name: os.environ[name] for name in allowed if name in os.environ}
        self.env.update(HERMES_HOME=str(self.root), PYTHONPATH=UPSTREAM)

    def tearDown(self):
        self.temp.cleanup()

    def call(self, request, wrapper=None):
        command = [PYTHON, str(SOURCE)] if wrapper is None else [PYTHON, "-c", wrapper, str(SOURCE)]
        result = subprocess.run(command, input=json.dumps(request), text=True, capture_output=True, env=self.env, timeout=45)
        self.assertEqual(0, result.returncode, "运行桥接脚本失败（不回显潜在的敏感 stderr）")
        lines = [line for line in result.stdout.splitlines() if line.startswith("HMMODELS")]
        self.assertEqual(1, len(lines))
        return json.loads(lines[0][len("HMMODELS"):]), result.stdout

    def test_builtin_custom_and_local_write_real_hermes_config_without_leaking_key(self):
        first, _ = self.call({"action": "inspect"})
        self.assertTrue(first["ok"])
        self.assertEqual("device_code", next(p["flow"] for p in first["snapshot"]["providers"] if p["id"] == "openai-codex"))
        (self.root / "config.yaml").write_text("agent:\n  max_turns: 37\n")
        key = "fixture-credential-" + os.urandom(8).hex()
        result, raw = self.call(dict(action="save", provider="openrouter", model="fake-model", apiKey=key))
        self.assertTrue(result["ok"])
        self.assertNotIn(key, raw)
        self.assertIn(key, (self.root / ".env").read_text())
        self.assertIn("37", (self.root / "config.yaml").read_text())
        self.assertIn("37", (self.root / "config.yaml.before-mobile-models").read_text())
        result, raw = self.call(dict(action="save", provider="local", model="fake-local", baseUrl="http://127.0.0.1:11434/v1"))
        self.assertTrue(result["ok"])
        self.assertEqual("local", result["snapshot"]["provider"])
        result, raw = self.call(dict(action="save", provider="custom", model="fake-custom", baseUrl="https://example.invalid/v1", apiKey=key, apiMode="codex_responses"))
        self.assertTrue(result["ok"])
        self.assertEqual("codex_responses", result["snapshot"]["apiMode"])
        self.assertNotIn(key, raw)
        self.assertNotIn(key, (self.root / "config.yaml").read_text(), "自定义密钥使用 Hermes 的 env 引用，不写入 YAML")

    def test_models_use_the_real_inventory_context_signature(self):
        wrapper = """
import runpy, sys
from hermes_cli import inventory

def fake_inventory(ctx, **kwargs):
    assert isinstance(ctx, inventory.ConfigContext)
    return {"providers": [
        {"slug": "openrouter", "models": ["fixture-model-from-inventory"]},
        {"slug": "custom:mobile-custom", "models": ["fixture-custom-model"]},
        {"slug": "mobile-local", "models": ["fixture-local-model"]},
    ]}
inventory.build_model_options_payload = fake_inventory
runpy.run_path(sys.argv[1], run_name="__main__")
"""
        result, _ = self.call(dict(action="models", provider="openrouter"), wrapper)
        self.assertTrue(result["ok"])
        row = next(p for p in result["snapshot"]["providers"] if p["id"] == "openrouter")
        self.assertEqual(["fixture-model-from-inventory"], row["models"])
        rows = {p["id"]: p for p in result["snapshot"]["providers"]}
        self.assertEqual(["fixture-custom-model"], rows["custom"]["models"])
        self.assertEqual(["fixture-local-model"], rows["local"]["models"])

    def test_invalid_yaml_is_not_overwritten(self):
        config = self.root / "config.yaml"
        original = "agent: [unterminated"
        config.write_text(original)
        result, _ = self.call(dict(action="save", provider="openrouter", model="fake", apiKey="fixture-key"))
        self.assertFalse(result["ok"])
        self.assertEqual(original, config.read_text())
        self.assertFalse((self.root / ".env").exists())

    def test_login_forks_without_holding_stdout_or_configuration_lock(self):
        wrapper = '''
import asyncio, os, runpy, sys, threading, time
from pathlib import Path
from hermes_cli.web_routers import oauth
from hermes_cli.web_server_oauth import _oauth_sessions, _oauth_sessions_lock
async def fake_start(provider, profile=None):
    sid = "fixture-internal-session"
    with _oauth_sessions_lock:
        _oauth_sessions[sid] = {"status": "pending", "provider": provider}
    def complete():
        gate = Path(os.environ["HERMES_HOME"], "fixture-approve-gate")
        deadline = time.monotonic() + 15
        while not gate.exists() and time.monotonic() < deadline:
            time.sleep(0.05)
        if not gate.exists():
            return
        Path(os.environ["HERMES_HOME"], "fixture-authorization-done").write_text("fixture-private-token")
        with _oauth_sessions_lock:
            _oauth_sessions[sid]["status"] = "approved"
    threading.Thread(target=complete, daemon=True).start()
    return {"session_id": sid, "user_code": "FIXTURE-CODE", "verification_url": "https://example.invalid/authorize", "expires_in": 15}
oauth._start_device_code_flow = fake_start
runpy.run_path(sys.argv[1], run_name="__main__")
'''
        result, raw = self.call(dict(action="login", provider="openai-codex"), wrapper)
        self.assertTrue(result["ok"])
        sid = result["login"]["id"]
        state_file = self.root / ".mobile-model-logins" / (sid + ".json")
        self.assertNotEqual("approved", json.loads(state_file.read_text())["status"], "父进程应立即返回，不等待子进程授权完成")
        # 第二次命令必须在授权仍进行时拿到配置锁；若子进程继承锁未释放，此调用会阻塞。
        pending, _ = self.call(dict(action="poll", sessionId=sid))
        self.assertTrue(pending["ok"])
        self.assertIn(pending["login"]["status"], ("starting", "pending"))
        (self.root / "fixture-approve-gate").touch()
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if json.loads(state_file.read_text())["status"] == "approved":
                break
            time.sleep(0.1)
        self.assertEqual("approved", json.loads(state_file.read_text())["status"])
        approved, raw = self.call(dict(action="poll", sessionId=sid))
        self.assertEqual("approved", approved["login"]["status"])
        self.assertNotIn("fixture-private-token", raw)


if __name__ == "__main__":
    unittest.main()
