"""模型桥的本机安全回归，不需要真实账号或模型 Key。"""
import importlib.util
import json
import os
import stat
import tempfile
import time
import unittest
import types
import sys
import subprocess
from unittest import mock
from pathlib import Path

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/assets/providers/settings.py"
spec = importlib.util.spec_from_file_location("mobile_providers", SOURCE)
bridge = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bridge)


class ProviderFilesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.before = bridge.ROOT
        bridge.ROOT = Path(self.temp.name) / "hermes"
        bridge.ROOT.mkdir()

    def tearDown(self):
        bridge.ROOT = self.before
        self.temp.cleanup()

    def test_missing_model_catalog_does_not_disable_provider_selection(self):
        auth = types.ModuleType("hermes_cli.auth")
        auth.PROVIDER_REGISTRY = {"fixture": types.SimpleNamespace(name="Fixture", auth_type="api_key", api_key_env_vars=("FIXTURE_KEY",))}
        config = types.ModuleType("hermes_cli.config")
        config.get_env_value = lambda name: "fixture-secret"
        modules = {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.auth": auth,
                   "hermes_cli.config": config, "hermes_cli.models": types.ModuleType("hermes_cli.models")}
        with mock.patch.dict(sys.modules, modules), mock.patch.object(bridge, "config_read", return_value={"model": {"provider": "fixture", "default": "fixture-model"}}):
            result = bridge.snapshot()
        self.assertTrue(next(row for row in result["providers"] if row["id"] == "fixture")["authenticated"])
        self.assertNotIn("fixture-secret", json.dumps(result))

    def test_enabling_clarify_only_adds_tool_and_is_idempotent(self):
        config = types.ModuleType("hermes_cli.config")
        cfg = {"model": {"provider": "fixture", "api_key": "fixture-secret"},
               "platform_toolsets": {"api_server": ["hermes-api-server", "existing"], "telegram": ["other"]}}
        config.is_managed = lambda: False
        config.save_config = mock.Mock()
        with mock.patch.dict(sys.modules, {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.config": config}), mock.patch.object(bridge, "config_read", return_value=cfg):
            bridge.enable_clarify(); bridge.enable_clarify()
        config.save_config.assert_called_once()
        self.assertEqual(cfg["platform_toolsets"]["api_server"], ["hermes-api-server", "existing", "clarify"])
        self.assertEqual(cfg["platform_toolsets"]["telegram"], ["other"])
        self.assertEqual(cfg["model"]["api_key"], "fixture-secret")

    def test_import_error_is_classified_by_operation_without_secret_output(self):
        for action in ("inspect", "login"):
            result = subprocess.run([sys.executable, "-I", str(SOURCE)], input=json.dumps({"action": action}), text=True,
                                    capture_output=True, env=dict(os.environ, HERMES_HOME=str(bridge.ROOT)), timeout=10)
            payload = json.loads(result.stdout.removeprefix("HMMODELS"))
            self.assertFalse(payload["ok"])
            self.assertEqual("不支持浏览器授权" in payload["error"], action == "login")
            # 系统 Python 里没有 Hermes：报错带上缺的模块名，方便判断是不是没找到 Hermes 自带的 Python。
            self.assertTrue(payload["error"].endswith("（缺少：hermes_cli）"), payload["error"])
            self.assertEqual(result.stderr, "")

    def test_private_receipts_are_atomic_and_not_world_readable(self):
        path = bridge.login_path("a" * 32)
        bridge.atomic(path, {"status": "pending", "code": "fixture-code"})
        self.assertEqual(0o600, stat.S_IMODE(path.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(path.parent.stat().st_mode))
        bridge.atomic(path, {"status": "approved"})
        self.assertEqual({"status": "approved"}, json.loads(path.read_text()))
        self.assertFalse(list(path.parent.glob(".mobile-*")))

    def test_symlink_never_writes_outside_managed_home(self):
        outside = Path(self.temp.name) / "outside"
        outside.mkdir()
        (bridge.ROOT / ".mobile-model-logins").symlink_to(outside, target_is_directory=True)
        with self.assertRaises(ValueError):
            bridge.atomic(bridge.login_path("b" * 32), {"code": "fixture"})
        self.assertEqual([], list(outside.iterdir()))

    def test_login_ids_cannot_traverse_or_read_another_receipt(self):
        for sid in ("../config.yaml", "x" * 32, "", "a" * 33):
            with self.assertRaises(ValueError):
                bridge.login_path(sid)
        bridge.atomic(bridge.login_path("a" * 32), {"id": "b" * 32, "status": "pending"})
        with self.assertRaises(ValueError):
            bridge.read_login("a" * 32)

    def test_expired_login_does_not_claim_success(self):
        sid = "c" * 32
        bridge.atomic(bridge.login_path(sid), {"id": sid, "status": "pending", "expiresAt": time.time() - 1})
        self.assertEqual("expired", bridge.read_login(sid)["status"])

    def modules_for_probe(self, result, calls, saved_env=None):
        """假的 Hermes 模块：validate_custom_endpoint 照真实返回的形状回包，并记下收到的地址和密钥。"""
        class Update:
            model_fields = {"models": None}

            def __init__(self, **fields):
                self.__dict__.update(fields)
                calls.append(fields)

        async def validate(body):
            return result
        web_models = types.ModuleType("hermes_cli.web_models")
        web_models.CustomEndpointUpdate = Update
        config_env = types.ModuleType("hermes_cli.web_routers.config_env")
        config_env.validate_custom_endpoint = validate
        config = types.ModuleType("hermes_cli.config")
        config.get_env_value = lambda name: (saved_env or {}).get(name)
        return {"hermes_cli": types.ModuleType("hermes_cli"), "hermes_cli.web_models": web_models,
                "hermes_cli.web_routers": types.ModuleType("hermes_cli.web_routers"),
                "hermes_cli.web_routers.config_env": config_env, "hermes_cli.config": config}

    def test_probe_reads_models_from_the_typed_endpoint_without_echoing_the_key(self):
        calls = []
        result = {"ok": True, "reachable": True, "models": ["m-1", "m-2", "bad\nname", "m-1"],
                  "resolved_base_url": "http://192.168.1.10:8000/v1", "message": ""}
        with mock.patch.dict(sys.modules, self.modules_for_probe(result, calls)), mock.patch.object(bridge, "config_read", return_value={}):
            found = bridge.probe({"provider": "custom", "baseUrl": "http://192.168.1.10:8000", "apiKey": "fixture-secret", "apiMode": "chat_completions"})
        self.assertEqual("http://192.168.1.10:8000", calls[0]["base_url"])
        self.assertEqual("fixture-secret", calls[0]["api_key"])
        self.assertEqual({"status": "ok", "models": ["m-1", "m-2"], "baseUrl": "http://192.168.1.10:8000/v1", "code": 0}, found)
        self.assertNotIn("fixture-secret", json.dumps(found))

    def test_probe_reuses_the_saved_key_only_for_the_same_address(self):
        cfg = {"providers": {"mobile-custom": {"base_url": "https://saved.example.invalid/v1", "key_env": "FIXTURE_KEY_ENV"}}}
        env = {"FIXTURE_KEY_ENV": "fixture-saved-secret"}
        ok = {"ok": True, "reachable": True, "models": ["m"], "resolved_base_url": "", "message": ""}
        for url, expected in (("https://saved.example.invalid/v1/", "fixture-saved-secret"), ("https://other.example.invalid/v1", None)):
            calls = []
            with mock.patch.dict(sys.modules, self.modules_for_probe(ok, calls, env)), mock.patch.object(bridge, "config_read", return_value=cfg):
                bridge.probe({"provider": "custom", "baseUrl": url})
            self.assertEqual(expected, calls[0]["api_key"], url)

    def test_probe_failures_are_reported_as_categories_only(self):
        cases = [({"ok": False, "reachable": False, "message": "Could not reach x", "models": []}, "unreachable", 0),
                 ({"ok": False, "reachable": True, "message": "The endpoint rejected the API key.", "models": []}, "rejected", 0),
                 ({"ok": False, "reachable": True, "message": "Endpoint returned HTTP 404.", "models": []}, "http", 404),
                 ({"ok": False, "reachable": True, "message": "Connected to x, but it advertised no models.", "models": []}, "empty", 0),
                 ({"ok": False, "reachable": True, "message": "POST /chat/completions returned HTTP 404", "models": ["m"]}, "transport", 404)]
        for result, status, code in cases:
            with mock.patch.dict(sys.modules, self.modules_for_probe(result, [])), mock.patch.object(bridge, "config_read", return_value={}):
                found = bridge.probe({"provider": "local", "baseUrl": "http://127.0.0.1:11434/v1"})
            self.assertEqual((status, code), (found["status"], found["code"]), result["message"])
            self.assertNotIn("message", found)

    def test_models_action_for_an_endpoint_returns_the_probed_list(self):
        found = {"status": "ok", "models": ["m-1"], "baseUrl": "https://x.example.invalid/v1", "code": 0}
        snap = {"providers": [{"id": "custom", "models": []}, {"id": "local", "models": []}]}
        with mock.patch.object(bridge, "probe", return_value=found), mock.patch.object(bridge, "snapshot", return_value=snap):
            result = bridge.dispatch({"action": "models", "provider": "custom", "baseUrl": "https://x.example.invalid/v1"})
        self.assertEqual(["m-1"], next(r for r in result["snapshot"]["providers"] if r["id"] == "custom")["models"])
        self.assertEqual([], next(r for r in result["snapshot"]["providers"] if r["id"] == "local")["models"])
        self.assertEqual(found, result["probe"])

    def test_saving_an_endpoint_keeps_the_probed_models_and_discovery(self):
        calls = []
        modules = self.modules_for_probe({}, calls)
        modules["hermes_cli.config"].save_config = mock.Mock()
        modules["hermes_cli.config"].is_managed = lambda: False
        auth = types.ModuleType("hermes_cli.auth")
        auth.PROVIDER_REGISTRY = {}
        modules["hermes_cli.auth"] = auth
        modules["hermes_cli.web_routers.config_env"]._write_custom_endpoint = lambda cfg, body: None
        with mock.patch.dict(sys.modules, modules), mock.patch.object(bridge, "config_read", return_value={}), mock.patch.object(bridge, "snapshot", return_value={}):
            bridge.save({"provider": "custom", "model": "m-1", "baseUrl": "https://x.example.invalid/v1", "models": ["m-1", "m-2"]})
        self.assertEqual(["m-1", "m-2"], calls[0]["models"])
        self.assertTrue(calls[0]["discover_models"])
        self.assertIsNone(calls[0]["api_key"], "留空密钥要保留已有的，不能传空字符串让 Hermes 删掉")

    def test_private_http_and_credential_urls_are_rejected(self):
        for url in ("http://example.invalid/v1", "https://user:secret@example.invalid/v1", "https://example.invalid/v1?key=fixture", "file:///etc/passwd"):
            with self.assertRaises(ValueError):
                bridge.endpoint(url)
        self.assertEqual("http://127.0.0.1:11434/v1", bridge.endpoint("http://127.0.0.1:11434/v1/"))
        # 局域网里的电脑、Tailscale、.local 可以用 http；公网 IP 不行。
        for url in ("http://192.168.1.10:11434/v1", "http://10.0.0.2/v1", "http://172.20.1.1:8000/v1", "http://100.100.1.1/v1",
                    "http://desktop.local:1234/v1", "http://[fd00::1]:8000/v1"):
            self.assertEqual(url, bridge.endpoint(url))
        for url in ("http://8.8.8.8/v1", "http://172.32.0.1/v1", "http://192.169.0.1/v1"):
            with self.assertRaises(ValueError):
                bridge.endpoint(url)

    def test_backup_keeps_first_original_and_refuses_symlink(self):
        path = bridge.ROOT / "config.yaml"
        path.write_text("first original")
        bridge.backup()
        path.write_text("second")
        bridge.backup()
        self.assertEqual("first original", (bridge.ROOT / "config.yaml.before-mobile-models").read_text())
        self.assertEqual(0o600, stat.S_IMODE((bridge.ROOT / "config.yaml.before-mobile-models").stat().st_mode))
        (bridge.ROOT / ".env.before-mobile-models").symlink_to(path)
        with self.assertRaises(ValueError):
            bridge.backup()


if __name__ == "__main__":
    unittest.main()
