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

SCRIPT = Path(__file__).resolve().parents[2] / "app/src/main/assets/setup/configure.py"
spec = importlib.util.spec_from_file_location("mobile_setup", SCRIPT)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hermes-setup-test-")
        self.root = Path(self.temp.name) / "hermes"
        self.ssh = Path(self.temp.name) / "ssh"
        self.params = dict(nonce="test-nonce", type="Ssh", host="example.invalid", port=8642,
                           profile="", sshUser="test-user", sshPort=22,
                           apiKey=secrets.token_hex(32), publicKey="ssh-ed25519 dGVzdA== test")

    def tearDown(self):
        self.temp.cleanup()

    def test_new_setup_creates_private_configuration_and_authorizes_only_public_key(self):
        reused, receipt = module.configure(self.root, self.ssh, self.params)
        self.assertFalse(reused)
        text = (self.root / ".env").read_text()
        self.assertIn("API_SERVER_ENABLED=true", text)
        self.assertIn("127.0.0.1", text)
        self.assertEqual((self.ssh / "authorized_keys").read_text(), self.params["publicKey"] + "\n")
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
        self.assertEqual((self.ssh / "authorized_keys").read_text().splitlines(), [self.params["publicKey"]])
        value = json.loads(base64.urlsafe_b64decode(receipt.split(":", 1)[1]))
        self.assertEqual(value["effectivePort"], 8765)
        self.assertEqual(value["apiKey"], old_key)

    def test_ssh_creates_key_on_server_and_keeps_receipt_private(self):
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
        self.ssh.mkdir()
        (self.ssh / "authorized_keys").symlink_to(outside)
        with self.assertRaises(ValueError): module.configure(self.root, self.ssh, self.params)
        self.assertFalse((self.root / ".env").exists())

    def test_invalid_key_and_public_key_fail_before_creating_configuration(self):
        for params in (dict(self.params, apiKey="bad"), dict(self.params, publicKey="ssh-ed25519 invalid\ncommand")):
            with self.assertRaises(ValueError): module.configure(self.root, self.ssh, params)
        self.assertFalse((self.root / ".env").exists())

    def test_direct_setup_refuses_wildcard_listener_and_accepts_only_owned_address(self):
        with self.assertRaises(ValueError):
            module.configure(self.root, self.ssh, dict(self.params, type="Direct", host="0.0.0.0"))
        self.assertFalse((self.root / ".env").exists())
        module.configure(self.root, self.ssh, dict(self.params, type="Direct", host="127.0.0.1"))
        self.assertIn("127.0.0.1", (self.root / ".env").read_text())


if __name__ == "__main__":
    unittest.main()
