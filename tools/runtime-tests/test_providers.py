"""模型桥的本机安全回归，不需要真实账号或模型 Key。"""
import importlib.util
import json
import os
import stat
import tempfile
import time
import unittest
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

    def test_private_http_and_credential_urls_are_rejected(self):
        for url in ("http://example.invalid/v1", "https://user:secret@example.invalid/v1", "https://example.invalid/v1?key=fixture", "file:///etc/passwd"):
            with self.assertRaises(ValueError):
                bridge.endpoint(url)
        self.assertEqual("http://127.0.0.1:11434/v1", bridge.endpoint("http://127.0.0.1:11434/v1/"))

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
