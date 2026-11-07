import json
from pathlib import Path
import tempfile
import unittest

import scenarios


class ScenarioEvidenceTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)

    def test_java_parameterized_skip_or_failure_cannot_be_hidden_by_a_pass(self):
        path = self.root / "tests.xml"
        for status in ("skipped", "failure", "error"):
            with self.subTest(status=status):
                path.write_text('<testsuite><testcase classname="C" name="method(String)[1]"/>'
                                f'<testcase classname="C" name="method(String)[2]"><{status}/></testcase></testsuite>')
                self.assertFalse(scenarios.java_results([path])["C#method"])

    def test_go_json_and_verbose_logs_require_pass_and_never_accept_skip(self):
        path = self.root / "go.log"
        path.write_text(json.dumps({"Action": "pass", "Test": "TestDefault"}) + "\n"
                        "--- PASS: TestIntegration (1.00s)\n"
                        "--- SKIP: TestSkipped (0.00s)\n"
                        "--- PASS: TestSkipped (1.00s)\n")
        self.assertEqual(scenarios.go_results([path]), {"TestDefault": True, "TestIntegration": True, "TestSkipped": False})

    def test_both_backends_must_have_real_evidence(self):
        policy = [{"id": "contract", "java": ["C#m"], "go": ["TestContract"]}]
        self.assertFalse(scenarios.evaluate(policy, {"C#m": True}, {})["passed"])
        self.assertTrue(scenarios.evaluate(policy, {"C#m": True}, {"TestContract": True})["passed"])
        with self.assertRaises(ValueError):
            scenarios.evaluate([], {}, {})
        with self.assertRaises(ValueError):
            scenarios.evaluate(policy + policy, {}, {})
        with self.assertRaises(ValueError):
            scenarios.evaluate([{"id": "one-sided", "java": ["C#m"]}], {}, {})
