import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import run_suite


class StageEvidenceTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)
        self.identity = {"revision": "a" * 40, "source_sha256": "b" * 64}
        for stage in run_suite.STAGES:
            output = self.root / stage
            output.mkdir()
            files = {"run-0.log": "complete\n"}
            if stage == "java":
                files.update({"jacoco.xml": '<report><sessioninfo/><counter type="LINE" covered="95" missed="5"/>'
                              '<counter type="BRANCH" covered="95" missed="5"/></report>',
                              "junit/TEST-C.xml": '<testsuite><testcase classname="C" name="test"/></testsuite>'})
            else:
                files.update({"profiles/" + name: "mode: atomic\nmodule/a.go:1.1,2.1 1 1\n"
                              for name in run_suite.EXPECTED_PROFILES[stage]})
            for name, content in files.items():
                path = output / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(content)
            manifest = {**self.identity, "stage": stage, "status": "passed",
                        "files": {name: run_suite.sha(output / name) for name in files}}
            (output / "stage.json").write_text(json.dumps(manifest))

    def manifest(self, stage, edit):
        path = self.root / stage / "stage.json"
        value = json.loads(path.read_text())
        edit(value)
        path.write_text(json.dumps(value))

    def verify(self):
        with patch.object(run_suite, "source_identity", return_value=self.identity):
            return run_suite.verify_manifests(self.root)

    def test_all_stages_include_31_profiles_with_matching_sources(self):
        identity, profiles = self.verify()
        self.assertEqual(identity, self.identity)
        self.assertEqual(len(profiles), 31)

    def test_missing_stage_or_profile_is_rejected(self):
        self.manifest("postgres", lambda value: value["files"].pop("profiles/postgres-chat.out"))
        with self.assertRaisesRegex(ValueError, "Missing required profile"):
            self.verify()

    def test_tampered_evidence_is_rejected(self):
        (self.root / "default/profiles/default.out").write_text("changed")
        with self.assertRaisesRegex(ValueError, "Evidence changed"):
            self.verify()

    def test_different_revisions_or_sources_cannot_be_merged(self):
        self.manifest("redis", lambda value: value.update(source_sha256="c" * 64))
        with self.assertRaisesRegex(ValueError, "different sources"):
            self.verify()

    def test_failed_stage_cannot_publish_success(self):
        self.manifest("p6", lambda value: value.update(status="failed"))
        with self.assertRaisesRegex(ValueError, "Incomplete stage"):
            self.verify()

    def test_stage_directory_cannot_be_reused(self):
        with self.assertRaises(FileExistsError):
            run_suite.run_stage("java", self.root)

    def test_sources_changed_since_measurement_invalidate_gate(self):
        with patch.object(run_suite, "source_identity", return_value={**self.identity, "revision": "d" * 40}):
            with self.assertRaisesRegex(ValueError, "current sources"):
                run_suite.verify_manifests(self.root)
