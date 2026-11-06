import os
from pathlib import Path
import tempfile
import unittest

from java_runtime import java_commands


class JavaRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.home = Path(self.temp.name) / "jdk with spaces"
        (self.home / "bin").mkdir(parents=True)
        for name in ("java", "javac"):
            file = self.home / "bin" / name
            file.write_text("#!/bin/sh\nexit 0\n")
            file.chmod(0o755)

    def test_java_home_wins_and_updates_subprocess_path(self):
        env = {"JAVA_HOME": str(self.home), "PATH": "/unrelated"}
        self.assertEqual(java_commands(env), tuple(str(self.home / "bin" / name) for name in ("java", "javac")))
        self.assertEqual(env["PATH"], str(self.home / "bin") + os.pathsep + "/unrelated")

    def test_path_discovery_without_platform_specific_home(self):
        env = {"PATH": str(self.home / "bin")}
        self.assertEqual(java_commands(env), tuple(str(self.home / "bin" / name) for name in ("java", "javac")))
        self.assertNotIn("JAVA_HOME", env)

    def test_invalid_explicit_home_and_missing_compiler_fail_early(self):
        (self.home / "bin" / "javac").unlink()
        for env in ({"JAVA_HOME": str(self.home)}, {"PATH": str(self.home / "bin")}, {"JAVA_HOME": "/missing"}):
            with self.subTest(env=env), self.assertRaises(RuntimeError):
                java_commands(env)


if __name__ == "__main__":
    unittest.main()
