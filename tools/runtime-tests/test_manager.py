"""使用临时目录和本机假程序测试运行组件，不连接手机或真实模型。"""
import fcntl
import json
import os
from pathlib import Path
import secrets
import signal
import stat
import subprocess
import sys
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parents[2] / "app/src/main/assets/runtime/manager.py"


class RuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="hermes-runtime-test-")
        self.base = Path(self.temp.name)
        self.root = self.base / "runtime"
        self.bin = self.base / "bin"
        self.bin.mkdir()
        self.env = dict(os.environ, HERMES_MOBILE_ROOT=str(self.root), PATH=str(self.bin) + os.pathsep + os.defpath)
        self.children = []

    def tearDown(self):
        for pid in self.children:
            try:
                os.killpg(pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
        self.temp.cleanup()

    def fake(self, name, body="import time\ntime.sleep(60)\n"):
        file = self.bin / name
        file.write_text("#!" + sys.executable + "\n" + body)
        file.chmod(0o700)

    def call(self, action, ok=True, **parameters):
        result = subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(dict(action=action, **parameters)),
                                text=True, capture_output=True, env=self.env, timeout=30)
        if not ok:
            self.assertNotEqual(result.returncode, 0)
            return result
        self.assertEqual(result.returncode, 0, result.stderr)
        lines = [line for line in result.stdout.splitlines() if line.startswith("HMRUNTIME")]
        self.assertEqual(len(lines), 1)
        return json.loads(lines[0][len("HMRUNTIME"):])

    def configured(self):
        self.fake("hermes")
        key = "test-" + secrets.token_hex(12)
        response = self.call("ConfigureHermes", model={"model": "fake-model", "apiKey": key, "baseUrl": "https://example.invalid/v1"})
        return key, response["connectionKey"]

    def track(self, name):
        record = json.loads((self.root / "processes" / (name + ".json")).read_text())
        self.children.append(record["pid"])
        return record

    def test_configuration_is_private_idempotent_and_preserves_other_env(self):
        self.root.mkdir()
        (self.root / ".env").write_text("UNRELATED=value\nAPI_SERVER_PORT=9999\n")
        _, first = self.configured()
        second = self.call("ConfigureHermes")["connectionKey"]
        self.assertEqual(first, second)
        contents = (self.root / ".env").read_text()
        self.assertIn("UNRELATED=value", contents)
        self.assertEqual(contents.count("API_SERVER_PORT="), 1)
        self.assertIn("API_SERVER_HOST=127.0.0.1", contents)
        for name in (".env", "mobile-api-key", "config.yaml"):
            self.assertEqual(stat.S_IMODE((self.root / name).stat().st_mode), 0o600)

    def test_provider_env_credentials_are_removed_from_runtime_log(self):
        self.fake("hermes")
        self.call("ConfigureHermes")
        secret = "fixture-secret-" + secrets.token_hex(12)
        with (self.root / ".env").open("a") as stream:
            stream.write("MOBILE_PROVIDER_API_KEY='" + secret + "'\n")
        (self.root / "logs").mkdir()
        (self.root / "logs/hermes.log").write_text("logged credential: " + secret)
        self.assertNotIn(secret, self.call("HermesLogs")["output"])

    def test_symlink_cannot_redirect_configuration(self):
        self.root.mkdir()
        outside = self.base / "outside"
        outside.write_text("keep")
        (self.root / ".env").symlink_to(outside)
        self.fake("hermes")
        self.call("ConfigureHermes", ok=False)
        self.assertEqual(outside.read_text(), "keep")

    def test_model_credentials_never_appear_in_log_response(self):
        model, api = self.configured()
        logs = self.root / "logs"
        logs.mkdir()
        (logs / "hermes.log").write_text(model + "\n" + api)
        output = self.call("HermesLogs")["output"]
        self.assertNotIn(model, output)
        self.assertNotIn(api, output)

    def test_bad_model_does_not_return_input_in_errors(self):
        self.fake("hermes")
        secret = secrets.token_hex(12)
        result = self.call("ConfigureHermes", ok=False, model={"model": "fake", "apiKey": secret, "baseUrl": "http://example.invalid"})
        self.assertNotIn(secret, result.stderr + result.stdout)

    def test_service_validation_precedes_installation(self):
        for identifier, requirements in (("../../escape", []), ("valid-id", ["--index-url"]), ("valid-id", ["https://example.invalid/package"])):
            self.call("InstallService", ok=False, service={"id": identifier, "name": "test", "source": "print('unused')", "requirements": requirements})
        self.assertFalse((self.root / "services" / "valid-id").exists())

    def test_python_service_lifecycle_and_removal_preserve_user_data(self):
        source = "from pathlib import Path\nimport time\nPath('user-data.txt').write_text('keep')\nwhile True: time.sleep(1)\n"
        self.call("InstallService", service={"id": "test-service", "name": "test", "source": source, "requirements": []})
        self.assertFalse((self.root / "services/test-service/user-data.txt").exists())
        first = self.call("StartService", serviceId="test-service")
        record = self.track("service-test-service")
        self.assertTrue(first["snapshot"]["services"][0]["running"])
        self.call("StartService", serviceId="test-service")
        self.assertEqual(record, json.loads((self.root / "processes/service-test-service.json").read_text()))
        self.call("StopService", serviceId="test-service")
        self.call("RemoveService", serviceId="test-service")
        self.assertEqual((self.root / "services/test-service/user-data.txt").read_text(), "keep")
        self.assertFalse((self.root / "services/test-service/main.py").exists())

    def test_stale_pid_record_does_not_stop_an_unrelated_process(self):
        self.configured()
        self.call("StartHermes")
        record = self.track("hermes")
        (self.root / "processes/hermes.json").write_text(json.dumps(dict(pid=record["pid"], start="wrong-clock")))
        self.call("StopHermes")
        os.kill(record["pid"], 0)

    def test_hermes_uses_loopback_and_owned_key_despite_inherited_environment(self):
        _, key = self.configured()
        self.fake("hermes", "import json,os,time\nfrom pathlib import Path\nPath('started-env.json').write_text(json.dumps({k:os.environ[k] for k in ('API_SERVER_ENABLED','API_SERVER_HOST','API_SERVER_PORT','API_SERVER_KEY')}))\ntime.sleep(60)\n")
        self.env.update(API_SERVER_HOST="0.0.0.0", API_SERVER_PORT="9999", API_SERVER_KEY="test-inherited-value")
        result = self.call("StartHermes")
        record = self.track("hermes")
        self.assertTrue(result["snapshot"]["hermesRunning"])
        values = json.loads((self.root / "started-env.json").read_text())
        self.assertEqual(values, dict(API_SERVER_ENABLED="true", API_SERVER_HOST="127.0.0.1", API_SERVER_PORT="8642", API_SERVER_KEY=key))
        self.call("StartHermes")
        self.assertEqual(record, json.loads((self.root / "processes/hermes.json").read_text()))
        self.assertFalse(self.call("StopHermes")["snapshot"]["hermesRunning"])

    def test_active_operation_lock_prevents_second_mutation(self):
        self.root.mkdir()
        with open(self.root / ".manager.lock", "a") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX)
            self.call("ConfigureHermes", ok=False)
        self.assertFalse((self.root / "mobile-api-key").exists())

    def test_proxy_is_configured_with_loopback_listener(self):
        self.fake("apt-get", "import json,os,sys\nfrom pathlib import Path\nPath(os.environ['HERMES_MOBILE_ROOT'],'apt-args.json').write_text(json.dumps(sys.argv[1:]))\n")
        self.fake("caddy", "import json,sys,time\nfrom pathlib import Path\nPath('argv.json').write_text(json.dumps(sys.argv))\ntime.sleep(60)\n")
        self.call("InstallProxy", proxy={"id": "test-proxy", "name": "proxy", "port": 8081, "target": "https://example.invalid"})
        apt_args = json.loads((self.root / "apt-args.json").read_text())
        self.assertIn("Dir::Etc::sourcelist=" + str(self.root / "apt/sources.list"), apt_args)
        self.assertIn("Dir::Etc::sourceparts=-", apt_args)
        self.assertEqual(apt_args[-3:], ["install", "-y", "caddy"])
        self.call("StartService", serviceId="test-proxy")
        self.track("service-test-proxy")
        args = json.loads((self.root / "services/test-proxy/argv.json").read_text())
        self.assertIn("http://127.0.0.1:8081", args)
        self.call("StopService", serviceId="test-proxy")

    def test_service_dependencies_use_domestic_pip_without_inherited_extra_indexes(self):
        venv = self.root / "services/test-service/venv/bin"
        venv.mkdir(parents=True)
        python = venv / "python"
        python.write_text("#!" + sys.executable + "\nimport os,json,sys\nfrom pathlib import Path\n"
                          "Path(os.environ['HERMES_MOBILE_ROOT'],'pip-call.json').write_text(json.dumps({"
                          "'args':sys.argv[1:], 'env':{k:v for k,v in os.environ.items() if k.startswith('PIP_')}}))\n")
        python.chmod(0o700)
        self.env.update(PIP_EXTRA_INDEX_URL="https://example.invalid/simple", PIP_TRUSTED_HOST="example.invalid",
                        PIP_CONFIG_FILE="/fixture/old-pip.conf", PIP_INDEX_URL="https://example.invalid/simple")
        self.call("InstallService", service={"id": "test-service", "name": "fixture", "source": "print('ok')",
                                             "requirements": ["requests==2.32.3"]})
        result = json.loads((self.root / "pip-call.json").read_text())
        self.assertEqual(result["args"], ["-m", "pip", "install", "--", "requests==2.32.3"])
        self.assertEqual(result["env"], {"PIP_CONFIG_FILE": os.devnull,
            "PIP_INDEX_URL": "https://pypi.tuna.tsinghua.edu.cn/simple", "PIP_DISABLE_PIP_VERSION_CHECK": "1"})
        self.assertEqual(self.env["PIP_EXTRA_INDEX_URL"], "https://example.invalid/simple")

    def test_browser_processes_are_owned_and_stopped_together(self):
        self.fake("termux-x11")
        self.fake("chromium")
        result = self.call("StartBrowser")
        self.track("x11")
        self.track("browser")
        self.assertTrue(result["snapshot"]["browserRunning"])
        self.assertFalse(self.call("StopBrowser")["snapshot"]["browserRunning"])


if __name__ == "__main__":
    unittest.main()
