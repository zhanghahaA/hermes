"""配置引导的文件与权限测试；只操作临时目录，不启动真实 Hermes。"""
import base64
import importlib.util
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import tempfile
import unittest
from unittest import mock
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import threading
import struct

SCRIPT = Path(__file__).resolve().parents[2] / "app/src/main/assets/setup/configure.py"
spec = importlib.util.spec_from_file_location("mobile_setup", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hermes-setup-test-")
        self.root = Path(self.temp.name) / "hermes"
        self.ssh = Path(self.temp.name) / "ssh"
        # 会写 API Server 配置的是直连 / 本机 Termux；SSH 走 gateway，单独测
        self.params = dict(nonce="test-nonce", type="Local", host="example.invalid", port=8642,
                           profile="", sshUser="test-user", sshPort=22,
                           apiKey=secrets.token_hex(32), publicKey="ssh-ed25519 " + base64.b64encode(struct.pack(">I", 11) + b"ssh-ed25519" + struct.pack(">I", 32) + bytes(range(32))).decode() + " test")

    def tearDown(self):
        self.temp.cleanup()

    def test_new_setup_creates_private_configuration_and_authorizes_only_public_key(self):
        reused, receipt = module.configure(self.root, self.ssh, self.params)
        self.assertFalse(reused)
        text = (self.root / ".env").read_text()
        self.assertIn("API_SERVER_ENABLED=true", text)
        self.assertIn("127.0.0.1", text)
        self.assertEqual((self.ssh / "authorized_keys").read_text(), "no-agent-forwarding,no-X11-forwarding,no-pty " + self.params["publicKey"] + "\n")
        for path in (self.root / ".env", self.ssh / "authorized_keys"):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o600)
        value = json.loads(base64.urlsafe_b64decode(receipt.split(":", 1)[1]))
        self.assertEqual(value["apiKey"], self.params["apiKey"])
        self.assertEqual(value["publicKey"], "")

    def test_existing_key_port_and_other_settings_are_preserved_and_backed_up_once(self):
        self.root.mkdir()
        old_key = secrets.token_hex(32)
        original = "# keep\nOTHER_SETTING=value\nexport API_SERVER_KEY='" + old_key + "'\nAPI_SERVER_PORT=8765\n"
        (self.root / ".env").write_text(original)
        for _ in range(2):
            reused, receipt = module.configure(self.root, self.ssh, self.params)
            self.assertTrue(reused)
        self.assertEqual((self.root / ".env.before-mobile-setup").read_text(), original)
        self.assertIn("OTHER_SETTING=value", (self.root / ".env").read_text())
        self.assertEqual((self.ssh / "authorized_keys").read_text().splitlines(), ["no-agent-forwarding,no-X11-forwarding,no-pty " + self.params["publicKey"]])
        value = json.loads(base64.urlsafe_b64decode(receipt.split(":", 1)[1]))
        self.assertEqual(value["effectivePort"], 8765)
        self.assertEqual(value["apiKey"], old_key)

    def test_missing_key_is_created_on_server_and_receipt_kept_private(self):
        _, receipt = module.configure(self.root, self.ssh, dict(self.params, apiKey=""))
        result = self.root / ".mobile-setup-results" / hashlib.sha256(b"test-nonce").hexdigest()
        self.assertEqual(result.read_text().strip(), receipt)
        self.assertEqual(stat.S_IMODE(result.stat().st_mode), 0o600)
        value = json.loads(base64.urlsafe_b64decode(receipt.split(":", 1)[1]))
        self.assertRegex(value["apiKey"], r"^[a-f0-9]{64}$")

    def test_symlink_to_external_env_or_authorized_keys_is_refused_without_writes(self):
        self.root.mkdir()
        outside = Path(self.temp.name) / "outside"
        outside.write_text("keep")
        (self.root / ".env").symlink_to(outside)
        with self.assertRaises(ValueError): module.configure(self.root, self.ssh, self.params)
        self.assertEqual(outside.read_text(), "keep")
        (self.root / ".env").unlink()
        self.ssh.mkdir(exist_ok=True)
        (self.ssh / "authorized_keys").symlink_to(outside)
        with self.assertRaises(ValueError): module.configure(self.root, self.ssh, self.params)
        self.assertFalse((self.root / ".env").exists())

    def test_invalid_key_and_public_key_fail_before_creating_configuration(self):
        for params in (dict(self.params, apiKey="bad"), dict(self.params, publicKey="ssh-ed25519 invalid\ncommand")):
            with self.assertRaises(ValueError): module.configure(self.root, self.ssh, params)
        self.assertFalse((self.root / ".env").exists())

    def test_three_runs_preserve_file_bytes_and_mtime_and_provider_keys(self):
        self.root.mkdir()
        original = "# retain\nOPENAI_API_KEY=fixture-openai\nANTHROPIC_API_KEY=fixture-anthropic\n"
        (self.root / ".env").write_text(original)
        module.configure(self.root, self.ssh, self.params)
        paths = [self.root / ".env", self.ssh / "authorized_keys"]
        before = [(p.read_bytes(), p.stat().st_mtime_ns) for p in paths]
        for _ in range(3):
            module.configure(self.root, self.ssh, self.params)
        self.assertEqual(before, [(p.read_bytes(), p.stat().st_mtime_ns) for p in paths])
        self.assertTrue((self.root / ".env").read_text().startswith(original))

    def test_invalid_existing_key_is_never_replaced_by_phone_key(self):
        self.root.mkdir()
        for value in ("short", ""):
            original = "API_SERVER_KEY=" + value + "\nOPENAI_API_KEY=fixture\n"
            (self.root / ".env").write_text(original)
            with self.assertRaises(ValueError):
                module.configure(self.root, self.ssh, self.params)
            self.assertEqual((self.root / ".env").read_text(), original)
            self.assertFalse((self.ssh / "authorized_keys").exists())

    def test_key_comment_change_does_not_add_an_unrestricted_duplicate(self):
        self.ssh.mkdir(exist_ok=True)
        authorized = self.ssh / "authorized_keys"
        original = 'restrict ' + self.params["publicKey"] + '-original\n'
        authorized.write_text(original)
        before = authorized.stat().st_mtime_ns
        module.configure(self.root, self.ssh, self.params)
        self.assertEqual(authorized.read_text(), original)
        self.assertEqual(authorized.stat().st_mtime_ns, before)

    def test_healthy_service_three_runs_never_restarts(self):
        _, receipt = module.configure(self.root, self.ssh, self.params)
        with mock.patch.object(module, "api_ready", return_value=True), mock.patch.object(module.subprocess, "Popen") as start:
            for _ in range(3):
                module.ensure_service(self.root, self.params, receipt, False)
            start.assert_not_called()

    def test_stopped_service_recovers_once_and_rechecks_authentication(self):
        _, receipt = module.configure(self.root, self.ssh, self.params)
        with mock.patch.object(module, "api_ready", side_effect=[False, True]), mock.patch.object(module.subprocess, "Popen") as start, mock.patch.object(module.time, "sleep"):
            module.ensure_service(self.root, self.params, receipt, False)
            start.assert_called_once()
            self.assertEqual(start.call_args.args[0], ["hermes", "gateway", "restart"])
            self.assertEqual(start.call_args.kwargs["env"]["HERMES_HOME"], str(self.root))

    def test_failed_authorization_write_restores_current_env_not_first_backup(self):
        module.configure(self.root, self.ssh, self.params)
        env = self.root / ".env"
        original = env.read_text().replace("API_SERVER_ENABLED=true", "API_SERVER_ENABLED=false")
        env.write_text(original)
        old = module.atomic
        def fail(path, contents):
            if path.name == "authorized_keys": raise OSError("fixture")
            old(path, contents)
        with mock.patch.object(module, "atomic", side_effect=fail):
            with self.assertRaises(OSError):
                module.configure(self.root, self.ssh, dict(self.params, publicKey="ssh-ed25519 " + base64.b64encode(struct.pack(">I", 11) + b"ssh-ed25519" + struct.pack(">I", 32) + bytes(reversed(range(32)))).decode() + " fixture"))
        self.assertEqual(env.read_text(), original)

    def test_conflicting_key_assignments_are_not_silently_normalized(self):
        self.root.mkdir()
        original = "API_SERVER_KEY=" + secrets.token_hex(32) + "\nAPI_SERVER_KEY=" + secrets.token_hex(32) + "\n"
        (self.root / ".env").write_text(original)
        with self.assertRaises(ValueError): module.configure(self.root, self.ssh, self.params)
        self.assertEqual((self.root / ".env").read_text(), original)

    def test_authorized_key_in_command_option_does_not_match(self):
        self.assertNotEqual(module.key_identity('command="echo ssh-ed25519 dGVzdA==" ssh-rsa b3RoZXI= other'), ("ssh-ed25519", "dGVzdA=="))

    def test_real_http_probe_checks_profile_route_and_auth_without_logging_key(self):
        key = secrets.token_hex(32)
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                requests.append(self.path)
                valid = self.path == "/p/work/health" or self.headers.get("Authorization") == "Bearer " + key
                self.send_response(200 if valid else 401)
                self.end_headers()
                self.wfile.write(b'{"platform":"hermes-agent"}')
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            params = dict(self.params, profile="work", port=server.server_port, apiKey=key)
            _, receipt = module.configure(self.root, self.ssh, params)
            self.assertTrue(module.api_ready(params, receipt))
            self.assertEqual(requests, ["/p/work/health", "/p/work/v1/models"])
            wrong = json.loads(base64.urlsafe_b64decode(receipt.split(":", 1)[1]))
            wrong["apiKey"] = secrets.token_hex(32)
            receipt = "HERMES-MOBILE-SETUP:" + base64.urlsafe_b64encode(json.dumps(wrong).encode()).decode()
            with self.assertRaisesRegex(ValueError, "API 认证失败"):
                module.api_ready(params, receipt)
        finally:
            server.shutdown(); server.server_close(); thread.join()

    def test_named_profile_config_does_not_touch_default_or_sibling(self):
        default = self.root
        work = default / "profiles" / "work"
        sibling = default / "profiles" / "other"
        sibling.mkdir(parents=True)
        (default / ".env").write_text("API_SERVER_KEY=default-fixture-keep\n")
        (sibling / ".env").write_text("API_SERVER_KEY=sibling-fixture-keep\n")
        before = [(p.read_bytes(), p.stat().st_mtime_ns) for p in (default / ".env", sibling / ".env")]
        for _ in range(3): module.configure(work, self.ssh, dict(self.params, profile="work"))
        self.assertEqual(before, [(p.read_bytes(), p.stat().st_mtime_ns) for p in (default / ".env", sibling / ".env")])

    def test_direct_setup_refuses_wildcard_listener_and_accepts_only_owned_address(self):
        with self.assertRaises(ValueError):
            module.configure(self.root, self.ssh, dict(self.params, type="Direct", host="0.0.0.0"))
        self.assertFalse((self.root / ".env").exists())
        module.configure(self.root, self.ssh, dict(self.params, type="Direct", host="127.0.0.1"))
        self.assertIn("127.0.0.1", (self.root / ".env").read_text())

    def test_ssh_only_authorizes_the_phone_and_never_touches_hermes_config(self):
        self.root.mkdir()
        original = "# keep\nOTHER_SETTING=value\n"
        (self.root / ".env").write_text(original)
        before = (self.root / ".env").stat().st_mtime_ns
        reused, receipt = module.configure(self.root, self.ssh, dict(self.params, type="Ssh", apiKey=""))
        self.assertFalse(reused)
        self.assertEqual((self.root / ".env").read_text(), original)
        self.assertEqual((self.root / ".env").stat().st_mtime_ns, before)
        self.assertFalse((self.root / ".env.before-mobile-setup").exists())
        self.assertEqual((self.ssh / "authorized_keys").read_text(), "no-agent-forwarding,no-X11-forwarding,no-pty " + self.params["publicKey"] + "\n")
        value = json.loads(base64.urlsafe_b64decode(receipt.split(":", 1)[1]))
        self.assertEqual(value["apiKey"], "", "SSH 回执里没有连接密钥")

    def test_ssh_setup_without_env_does_not_create_one(self):
        module.configure(self.root, self.ssh, dict(self.params, type="Ssh", apiKey=""))
        self.assertFalse((self.root / ".env").exists())

    def test_gateway_check_only_reports_and_never_restarts(self):
        for output, expected in (("running\n", "正在运行"), ("stopped:no_endpoint\n", "没检测到"), ("", "没能确认")):
            done = mock.Mock(stdout=output)
            with mock.patch.object(module, "hermes_python", return_value=["/venv/bin/python"]), \
                    mock.patch.object(module.subprocess, "run", return_value=done) as run, \
                    mock.patch.object(module.subprocess, "Popen") as start, mock.patch("builtins.print") as shown:
                module.report_gateway(self.root)
                start.assert_not_called()
                self.assertEqual(run.call_args.args[0][:2], ["/venv/bin/python", "-c"])
                self.assertIn(expected, shown.call_args.args[0])


if __name__ == "__main__":
    unittest.main()
