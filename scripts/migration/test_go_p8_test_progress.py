import copy
import json
import unittest
from unittest.mock import patch

import go_p8_test_progress as subject


class P8DeclarationMappingTest(unittest.TestCase):
    def setUp(self):
        self.inventory = json.loads((subject.SPEC / "inventory.json").read_text())
        self.progress = json.loads((subject.SPEC / "progress.json").read_text())

    def test_each_explicit_declaration_and_file_is_mapped_without_releasing_gates(self):
        expected = subject.mappings()
        self.assertEqual(len(expected), 144)
        selected = {entry["id"] for entry in self.inventory["java_test_cases"]
                    if entry["path"].split("/")[-1][:-5] + "." + entry["handler"]["name"] in expected}
        before = copy.deepcopy(self.progress)
        for key in selected:
            before["items"].pop(key, None)
        preserved = next(iter(selected))
        before["items"][preserved] = dict(status="verified", evidence=["prior-evidence"], custom="keep")
        after, markdown = subject.apply(self.inventory, before)
        self.assertEqual(before["release_gates"], after["release_gates"])
        self.assertEqual(after["items"][preserved]["status"], "verified")
        self.assertEqual(after["items"][preserved]["custom"], "keep")
        self.assertIn("prior-evidence", after["items"][preserved]["evidence"])
        paths = {entry["path"] for entry in self.inventory["java_test_cases"] if entry["id"] in selected}
        selected_files = {entry["id"] for entry in self.inventory["files"] if entry["path"] in paths}
        self.assertEqual(len(selected), 144)
        for key in selected - {preserved}:
            self.assertEqual(after["items"][key]["status"], "implemented", key)
            self.assertTrue(after["items"][key]["evidence"], key)
        for key in before["items"].keys() - selected - selected_files:
            self.assertEqual(before["items"][key], after["items"][key], key)
        for name in expected:
            self.assertIn("`" + name + "`", markdown)
        again, repeated_markdown = subject.apply(self.inventory, after)
        self.assertEqual(after, again)
        self.assertEqual(markdown, repeated_markdown)

    def test_changed_original_scope_is_rejected(self):
        inventory = copy.deepcopy(self.inventory)
        inventory["java_test_cases"] = [entry for entry in inventory["java_test_cases"]
            if entry["handler"]["name"] != "conflictingEvidenceBypassesAnswerModelAndPersistsBothSources"]
        with self.assertRaisesRegex(ValueError, "scope changed"):
            subject.apply(inventory, self.progress)

    def test_missing_concrete_evidence_is_rejected(self):
        with patch.dict(subject.TESTS, response="TestMissingP8Evidence"):
            with self.assertRaisesRegex(ValueError, "Expected unique Go test"):
                subject.resolve_tests()
