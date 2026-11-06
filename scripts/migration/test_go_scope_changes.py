import contextlib
import copy
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import go_inventory
import go_scope_changes as subject


class ScopeChangesTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.java, self.go = "backend/Service.java", "backend-go/service.go"
        self.original = b"original"
        self.data = {self.java: b"Java fix", self.go: b"Go fix"}
        for name, data in self.data.items():
            path = self.root / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        (self.root / "evidence.md").write_text("Unit tests passed; live parity still pending")
        self.inventory = {"baseline_commit": "a" * 40, "files": [
            {"id": "file:" + self.java, "path": self.java, "sha256": self.sha(self.original)}],
            "http_routes": [], "tools": [], "runtime_hooks": [], "java_test_cases": []}
        self.change = {"id": "scope:fix", "status": "in_progress", "source_commit": "b" * 40,
                       "summary": "paired fix", "limitations": ["database parity pending"],
                       "evidence": ["evidence.md"], "parity": {"java": [self.java], "go": [self.go]},
                       "files": [{"path": name, "baseline_sha256": self.sha(self.original) if name == self.java else None,
                                  "candidate_sha256": self.sha(data)} for name, data in self.data.items()]}
        self.scope = {"schema_version": 1, "baseline_commit": "a" * 40, "changes": [self.change]}

    @staticmethod
    def sha(data):
        return hashlib.sha256(data).hexdigest()

    def validate(self):
        return subject.validate(self.inventory, self.scope, self.root, lambda revision, path: self.data[path])

    def test_original_oracle_is_preserved_and_candidate_recorded_as_pending(self):
        before = copy.deepcopy(self.inventory)
        result = self.validate()
        self.assertEqual(self.inventory, before)
        self.assertEqual(result[self.java]["status"], "in_progress")
        self.assertEqual(result[self.go]["candidate_sha256"], self.sha(self.data[self.go]))

    def test_live_drift_is_rejected_for_either_backend(self):
        for name in self.data:
            with self.subTest(name=name):
                path = self.root / name
                path.write_bytes(b"unreviewed")
                with self.assertRaisesRegex(ValueError, "candidate file drift"):
                    self.validate()
                path.write_bytes(self.data[name])

    def test_refreshing_a_hash_without_committing_the_reviewed_source_fails(self):
        (self.root / self.java).write_bytes(b"new candidate")
        self.change["files"][0]["candidate_sha256"] = self.sha(b"new candidate")
        with self.assertRaisesRegex(ValueError, "differs from source commit"):
            self.validate()

    def test_baseline_hash_cannot_be_refreshed(self):
        self.change["files"][0]["baseline_sha256"] = self.sha(b"replacement oracle")
        with self.assertRaisesRegex(ValueError, "Baseline file drift"):
            self.validate()

    def test_sql_migrations_cannot_be_amended(self):
        self.change["files"][0]["path"] = "backend/src/main/resources/db/migration/V1.sql"
        with self.assertRaisesRegex(ValueError, "SQL migrations cannot be amended"):
            self.validate()

    def add_migration(self, name, baseline=False):
        data = b"SELECT 1;\n" if baseline else b"SELECT 2;\n"
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        if baseline:
            self.inventory["files"].append({"id": "file:" + name, "path": name, "sha256": self.sha(data)})
        else:
            self.data[name] = data
            self.change["files"].append({"path": name, "baseline_sha256": None, "candidate_sha256": self.sha(data)})

    def test_only_appended_sql_versions_can_be_registered(self):
        original = "backend/src/main/resources/db/migration/V42__original.sql"
        appended = "backend/src/main/resources/db/migration/V43__workspace.sql"
        self.add_migration(original, baseline=True)
        self.add_migration(appended)
        self.assertIn(appended, self.validate())
        (self.root / appended).write_bytes(b"unreviewed")
        with self.assertRaisesRegex(ValueError, "candidate file drift"):
            self.validate()

    def test_duplicate_appended_migration_versions_fail_closed(self):
        self.add_migration("backend/src/main/resources/db/migration/V42__original.sql", baseline=True)
        self.add_migration("backend/src/main/resources/db/migration/V43__workspace.sql")
        self.add_migration("backend/src/main/resources/db/migration/V43__duplicate.sql")
        with self.assertRaisesRegex(ValueError, "Duplicate appended SQL migration version"):
            self.validate()

    def test_old_versions_and_nonstandard_migration_paths_stay_rejected(self):
        self.add_migration("backend/src/main/resources/db/migration/V42__original.sql", baseline=True)
        for suffix in ("V41__changed.sql", "V42__renamed.sql", "V043__workspace.sql", "nested/V43__workspace.sql", "V43__nested/workspace.sql", "R__repeat.sql"):
            with self.subTest(path=suffix):
                self.add_migration("backend/src/main/resources/db/migration/" + suffix)
                with self.assertRaisesRegex(ValueError, "append-only"):
                    self.validate()
                self.change["files"].pop()

    def test_original_sql_stays_immutable_even_with_matching_source_hash(self):
        name = "backend/src/main/resources/db/migration/V42__original.sql"
        self.add_migration(name, baseline=True)
        self.add_migration(name)
        self.change["files"][-1]["baseline_sha256"] = self.inventory["files"][-1]["sha256"]
        with self.assertRaisesRegex(ValueError, "Original SQL migrations cannot be amended"):
            self.validate()

    def test_new_java_file_requires_both_backends_and_content_binding(self):
        self.change["parity"]["go"] = []
        with self.assertRaisesRegex(ValueError, "both backend"):
            self.validate()
        self.change["parity"]["go"] = ["evidence.md"]
        with self.assertRaisesRegex(ValueError, "missing candidate hash"):
            self.validate()

    def test_duplicate_files_and_changes_fail_closed(self):
        self.change["files"].append(copy.deepcopy(self.change["files"][0]))
        with self.assertRaisesRegex(ValueError, "Duplicate scope file"):
            self.validate()
        self.change["files"].pop()
        self.scope["changes"].append(copy.deepcopy(self.change))
        with self.assertRaisesRegex(ValueError, "duplicate scope id"):
            self.validate()

    def test_missing_evidence_and_unsafe_paths_fail_closed(self):
        for name in ("/etc/passwd", "../outside", ".env", "./evidence.md", "missing.md"):
            self.change["evidence"] = [name]
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.validate()

    def test_symlink_evidence_is_rejected(self):
        (self.root / "link.md").symlink_to(self.root / "evidence.md")
        self.change["evidence"] = ["link.md"]
        with self.assertRaisesRegex(ValueError, "unsafe scope file"):
            self.validate()

    def test_verified_requires_exact_candidate_report(self):
        self.change["status"] = "verified"
        with self.assertRaises(ValueError):
            self.validate()
        self.change["verification_report"] = "verified.json"
        report = {"scope_id": "scope:fix", "baseline_commit": "a" * 40, "source_commit": "b" * 40,
                  "status": "passed", "file_sha256": {name: self.sha(data) for name, data in self.data.items()},
                  "checks": ["paired replay"]}
        (self.root / "verified.json").write_text(json.dumps(report))
        self.assertEqual(self.validate()[self.java]["status"], "verified")
        report["file_sha256"][self.go] = "0" * 64
        (self.root / "verified.json").write_text(json.dumps(report))
        with self.assertRaisesRegex(ValueError, "exact candidate"):
            self.validate()

    def test_regular_check_accepts_registered_scope_but_completion_stays_closed(self):
        spec = self.root / "specs/033-go-rewrite"
        spec.mkdir(parents=True)
        (spec / "scope-changes.json").write_text(json.dumps(self.scope))
        for name in ("inventory.md", "surface.md"):
            (spec / name).write_text("doc")
        progress = {"baseline_commit": "a" * 40, "items": {"file:" + self.java: {
            "status": "verified", "evidence": ["evidence.md"]}}, "release_gates": {}}
        (spec / "progress.json").write_text(json.dumps(progress))
        def git(*args):
            return self.data[args[1].split(":", 1)[1]] if args[0] == "show" else (self.java + "\n" + self.go).encode()
        with patch.multiple(go_inventory, ROOT=self.root, SPEC=spec, PROGRESS=spec / "progress.json"), \
                patch.object(go_inventory, "collect", return_value=self.inventory), \
                patch.object(go_inventory, "git", side_effect=git), \
                patch.object(go_inventory, "markdown", return_value="doc"), \
                patch.object(go_inventory, "surface_markdown", return_value="doc"), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()) as errors:
            self.assertEqual(go_inventory.check(self.inventory), 0)
            self.assertEqual(go_inventory.check(self.inventory, complete=True), 1)
            self.assertIn("scope:fix", errors.getvalue())
            # No local overlay or untracked change may make an unregistered file pass.
            (self.root / self.java).write_bytes(b"extra unreviewed fix")
            self.assertEqual(go_inventory.check(self.inventory), 1)


if __name__ == "__main__":
    unittest.main()
