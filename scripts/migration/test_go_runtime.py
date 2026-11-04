import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import go_runtime as runtime


class GoRuntimeTest(unittest.TestCase):
    def test_incomplete_identity_is_never_owned(self):
        for value in (None, {}, {"pid": -1}, {"pid": None, "identity": None}, {"pid": 1, "identity": {}}):
            self.assertFalse(runtime.owned(value))

    def test_explicit_environment_never_loads_dotenv_and_selects_worker_profile(self):
        values = dict(POSTGRES_HOST="fixture.invalid", POSTGRES_PORT="5432", POSTGRES_DB="fixture",
                      POSTGRES_USER="fixture", POSTGRES_PASSWORD="synthetic", AUTH_ENABLED="true",
                      APP_SECURITY_CSRF_ENABLED="true", GO_DOTENV_ENABLED="true")
        with self.assertRaises(ValueError):
            runtime.environment({}, "api")
        result = runtime.environment(values, "index")
        self.assertEqual(result["GO_DOTENV_ENABLED"], "false")
        self.assertEqual(result["SPRING_PROFILES_ACTIVE"], "monitoring,worker-index")
        self.assertEqual(values["GO_DOTENV_ENABLED"], "true")
        self.assertNotIn("POSTGRES_PASSWORD", str(runtime.ROLES))
        self.assertEqual(runtime.environment(values, "api")["SPRING_PROFILES_ACTIVE"], "monitoring,runtime-api")
        values["APP_SECURITY_CSRF_ENABLED"] = "false"
        with self.assertRaises(ValueError):
            runtime.environment(values, "api")

    def test_stale_identity_never_signals_a_reused_pid(self):
        with tempfile.TemporaryDirectory() as temporary:
            with runtime.locked(Path(temporary)) as directory:
                runtime.private_json(directory / "index.json", {"formatVersion": 1, "role": "index", "pid": 100,
                                     "identity": {"started": "old", "command": "fixture"}})
                with patch.object(runtime, "identity", return_value={"started": "new", "command": "fixture"}), patch.object(os, "kill") as kill:
                    runtime.stop(directory, "index")
                    kill.assert_not_called()
                self.assertFalse((directory / "index.json").exists())

    def test_worker_discovery_contains_only_owned_workers(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for role in ("index", "review"):
                runtime.private_json(directory / (role + ".json"), {"formatVersion": 1, "role": role, "pid": 100,
                                     "managementPort": runtime.ROLES[role][1]})
            with patch.object(runtime, "owned", side_effect=lambda value: value and value["role"] == "index"):
                runtime.publish_targets(directory)
            self.assertEqual((directory / "worker-targets.json").stat().st_mode & 0o777, 0o600)
            self.assertEqual(__import__("json").loads((directory / "worker-targets.json").read_text()),
                             [{"targets": ["127.0.0.1:18093"], "labels": {"role": "index"}}])

    def test_state_symlinks_and_insecure_directories_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            (directory / "api.json").symlink_to(directory / "fixture")
            (directory / "fixture").write_text("{}")
            with self.assertRaises(ValueError):
                runtime.current(directory, "api")
            directory.chmod(0o755)
            with self.assertRaises(ValueError):
                with runtime.locked(directory):
                    self.fail("insecure directory accepted")


if __name__ == "__main__":
    unittest.main()
