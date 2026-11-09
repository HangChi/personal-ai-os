import copy
import types
import unittest
from unittest.mock import Mock

from current_budget_cases import current_input_rejection, history_trimming, install_current_budget_cases
from p8_model_fixture import Handler


class CurrentBudgetCasesTest(unittest.TestCase):
    def suite(self, *, model_called=False, altered_input=False):
        content = "这是一个用于填充上下文的句子。" * 120 + " 第0轮，只回复 OK"
        rows = [dict(role="USER", content=content[:-1] if altered_input else content),
                dict(role="ASSISTANT", content="（本条回答因连接中断未说完）")]

        def stream(session, text):
            if model_called:
                Handler.request_observer(dict(messages=[dict(role="user", content=text)], stream=True))
            result = {"error": {"code": 41301}}
            return types.SimpleNamespace(first=lambda name: result.get(name))

        return types.SimpleNamespace(
            ok=lambda response, *args: response,
            put=Mock(return_value=dict(budgetTokens=6729, contextWindow=131072)),
            new_session=Mock(return_value="owned-session"), stream_chat=Mock(side_effect=stream),
            messages=Mock(return_value=rows))

    def test_false_local_rejection_with_an_upstream_call_fails_and_resets_observer_and_budget(self):
        suite = self.suite(model_called=True)
        previous = Handler.request_observer
        with self.assertRaisesRegex(AssertionError, "zero model calls"):
            current_input_rejection(suite)
        self.assertIs(Handler.request_observer, previous)
        suite.put.assert_called_with("/chat/context-budget", {"tokens": 32768})

    def test_rejected_input_cannot_be_silently_truncated_in_storage(self):
        suite = self.suite(altered_input=True)
        with self.assertRaisesRegex(AssertionError, "preserve exact input"):
            current_input_rejection(suite)
        suite.put.assert_called_with("/chat/context-budget", {"tokens": 32768})

    def test_rejection_retains_receipt_and_counts_zero_calls(self):
        result = current_input_rejection(self.suite())
        self.assertEqual(result["error_code"], 41301)
        self.assertEqual(result["upstream_calls"], 0)
        self.assertEqual(result["interruption_markers"], 1)
        self.assertFalse(result["done"])
        self.assertFalse(result["delta"])

    def test_failed_history_setup_does_not_look_like_successful_trimming(self):
        suite = self.suite()
        with self.assertRaisesRegex(AssertionError, "History setup must complete"):
            history_trimming(suite)
        suite.put.assert_called_with("/chat/context-budget", {"tokens": 32768})

    def test_current_suite_replaces_only_one_obsolete_case_with_two_failing_contracts(self):
        legacy = Mock()
        original = dict(group="G4", name="frozen case", mode="fail", fn=legacy)
        before = [dict(group="G3", name="before", mode="observe", fn=Mock()), original,
                  dict(group="G5", name="after", mode="fail", fn=Mock())]
        frozen = copy.copy(before)
        suite = types.SimpleNamespace(CASES=before, g4_trim_under_small_budget=legacy)
        current = install_current_budget_cases(suite, "java")
        self.assertEqual(before, frozen)
        self.assertEqual(len(suite.CASES), 4)
        self.assertIs(suite.CASES[0], before[0])
        self.assertIs(suite.CASES[-1], before[-1])
        self.assertEqual(suite.CASES[1:3], current)
        self.assertEqual([case["mode"] for case in current], ["fail", "fail"])
        legacy.assert_not_called()

    def test_missing_or_duplicate_legacy_case_is_rejected(self):
        legacy = Mock()
        case = dict(group="G4", name="frozen", mode="fail", fn=legacy)
        for entries in ([], [case, case.copy()]):
            with self.subTest(entries=len(entries)), self.assertRaises(ValueError):
                install_current_budget_cases(types.SimpleNamespace(
                    CASES=entries, g4_trim_under_small_budget=legacy), "java")


if __name__ == "__main__":
    unittest.main()
