import json
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import run_go_demo_seed_tests as subject


class DemoSeedAdapterTest(unittest.TestCase):
    def setUp(self):
        self.fixture = dict(GO_TEST_POSTGRES="disposable", GO_TEST_POSTGRES_SSL_MODE="verify-full",
                            GO_TEST_POSTGRES_HOST="127.0.0.1", GO_TEST_POSTGRES_PORT="12345",
                            GO_TEST_POSTGRES_USER="go_fixture", GO_TEST_POSTGRES_DB="personalos_go_test")
        self.owned = dict(owned="true", name="/personalos-go-test-123456abcdef", running=True,
                          bindings=[dict(HostIp="127.0.0.1", HostPort="12345")])

    @patch.object(subject.subprocess, "run")
    def test_live_or_insecure_fixtures_are_rejected_before_any_process(self, run):
        for key, value in (("GO_TEST_POSTGRES", "live"), ("GO_TEST_POSTGRES_SSL_MODE", "disable"),
                           ("GO_TEST_POSTGRES_HOST", "db.example"), ("GO_TEST_POSTGRES_USER", "personalos"),
                           ("GO_TEST_POSTGRES_DB", "personal_ai_os"), ("GO_TEST_POSTGRES_PORT", "0")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                subject.OwnedDatabase("a" * 64, dict(self.fixture, **{key: value}))
        with self.assertRaises(ValueError):
            subject.OwnedDatabase("personal-ai-os-postgres", self.fixture)
        run.assert_not_called()

    @patch.object(subject.subprocess, "run")
    def test_ownership_binding_and_nonempty_database_guard(self, run):
        for changed in (dict(owned="false"), dict(running=False), dict(name="/personal-ai-os-postgres"),
                        dict(bindings=[dict(HostIp="0.0.0.0", HostPort="12345")])):
            run.return_value = SimpleNamespace(stdout=json.dumps(dict(self.owned, **changed)))
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                subject.OwnedDatabase("a" * 64, self.fixture)
        run.return_value = SimpleNamespace(stdout=json.dumps(self.owned))
        database = subject.OwnedDatabase("a" * 64, self.fixture)
        with patch.object(database, "counts", return_value={"task": 1}), self.assertRaisesRegex(ValueError, "never resets"):
            database.require_empty()
        with patch.object(database, "counts", return_value={"task": 0}):
            database.require_empty()

    def test_recipe_uses_seven_original_functions_and_cannot_reset_or_leave_target(self):
        seed = SimpleNamespace(**{name: Mock() for name in subject.RECIPE}, main=Mock(), reset=Mock())
        client, database = Mock(), Mock()
        original_reset = seed.reset
        subject.bind_recipe(seed, client, database)
        subject.run_recipe(seed)
        for name in subject.RECIPE:
            getattr(seed, name).assert_called_once_with()
        seed.main.assert_not_called()
        original_reset.assert_not_called()
        with self.assertRaises(ValueError):
            seed.reset()
        for method, path in (("DELETE", "/tasks"), ("GET", "http://external.example/tasks"), ("POST", "/export")):
            with self.assertRaises(ValueError):
                seed.api(method, path)
        client.request.assert_not_called()
        seed.api("POST", "/tasks", {"title": "fixture"})
        client.request.assert_called_once_with("/tasks", "POST", {"title": "fixture"}, status=201)
        with self.assertRaises(ValueError):
            seed.upload_document('bad\r\nname.md', "fixture", "content")

    def test_sql_uses_only_owned_container_stdin_and_sanitizes_errors(self):
        with patch.object(subject.subprocess, "run", return_value=SimpleNamespace(stdout=json.dumps(self.owned))):
            database = subject.OwnedDatabase("a" * 64, self.fixture)
        with patch.object(subject.subprocess, "run", return_value=SimpleNamespace(returncode=1)) as run:
            with self.assertRaisesRegex(RuntimeError, "Owned demo SQL failed") as raised:
                database.sql("private statement")
            self.assertNotIn("private statement", str(raised.exception))
            arguments, options = run.call_args
            self.assertEqual(arguments[0][:4], ["docker", "exec", "--interactive", "a" * 64])
            self.assertIn("--no-psqlrc", arguments[0])
            self.assertEqual(options["input"], "private statement")
            self.assertNotIn("private statement", " ".join(arguments[0]))

    def test_original_upload_title_and_persisted_manifest_are_required(self):
        seed, client = SimpleNamespace(), Mock()
        subject.bind_recipe(seed, client, Mock())
        client.request.return_value = dict(id="fixture", filename="a.md", title="a")
        with self.assertRaisesRegex(AssertionError, "filename/title"):
            seed.upload_document("a.md", "Original title", "content")
        client.request.return_value = dict(id="fixture", filename="a.md", title="Original title")
        seed.upload_document("a.md", "Original title", "content")
        self.assertEqual(seed._p8_documents, {"fixture": ("a.md", "Original title")})
