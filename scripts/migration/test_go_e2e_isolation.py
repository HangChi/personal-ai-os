import types
import unittest
import tempfile
from pathlib import Path
from unittest.mock import Mock

from run_go_e2e_tests import create_report_root, isolate_budget_case


class EvidenceIsolationTest(unittest.TestCase):
    def test_new_report_directory_refuses_to_overwrite_previous_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = create_report_root(Path(temporary) / "new" / "run")
            evidence = output / "result.json"
            evidence.write_text('{"status":"failed"}')
            with self.assertRaises(FileExistsError):
                create_report_root(output)
            self.assertEqual(evidence.read_text(), '{"status":"failed"}')


class BudgetIsolationTest(unittest.TestCase):
    def suite(self, original):
        other = Mock()
        return types.SimpleNamespace(
            CASES=[{"fn": original, "mode": "fail"}, {"fn": other, "mode": "observe"}],
            g4_trim_under_small_budget=original, put=Mock(return_value="response"), ok=Mock())

    def test_pass_preserves_result_context_other_case_and_mode(self):
        original = Mock(return_value=17)
        suite = self.suite(original)
        other = suite.CASES[1].copy()
        failures = isolate_budget_case(suite)
        context = {}
        self.assertEqual(suite.CASES[0]["fn"](context), 17)
        original.assert_called_once_with(context)
        suite.put.assert_called_once_with("/chat/context-budget", {"tokens": 32768})
        suite.ok.assert_called_once_with("response", "P8 restore budget after case")
        self.assertEqual(suite.CASES[1], other)
        self.assertEqual(suite.CASES[0]["mode"], "fail")
        self.assertEqual(failures, [])

    def test_original_failure_is_preserved_after_successful_reset(self):
        error = AssertionError("original budget failure")
        suite = self.suite(Mock(side_effect=error))
        failures = isolate_budget_case(suite)
        with self.assertRaises(AssertionError) as caught:
            suite.CASES[0]["fn"]({})
        self.assertIs(caught.exception, error)
        suite.put.assert_called_once()
        self.assertEqual(failures, [])

    def test_reset_failure_is_observable_without_masking_original_failure(self):
        error = AssertionError("original budget failure")
        suite = self.suite(Mock(side_effect=error))
        suite.ok.side_effect = RuntimeError("reset failed")
        failures = isolate_budget_case(suite)
        with self.assertRaises(AssertionError) as caught:
            suite.CASES[0]["fn"]({})
        self.assertIs(caught.exception, error)
        self.assertEqual(len(failures), 1)

    def test_reset_failure_after_success_is_not_swallowed(self):
        suite = self.suite(Mock())
        suite.put.side_effect = RuntimeError("reset failed")
        failures = isolate_budget_case(suite)
        with self.assertRaisesRegex(RuntimeError, "reset failed"):
            suite.CASES[0]["fn"]({})
        self.assertEqual(len(failures), 1)

    def test_missing_or_duplicated_original_case_is_rejected(self):
        suite = self.suite(Mock())
        suite.CASES.append(suite.CASES[0].copy())
        with self.assertRaises(ValueError):
            isolate_budget_case(suite)
        suite.CASES = []
        with self.assertRaises(ValueError):
            isolate_budget_case(suite)
