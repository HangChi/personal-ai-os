import copy
import hashlib
import unittest
from types import SimpleNamespace

from go_real_model import ModelFailure
from run_go_real_eval_tests import configure_budget, multistage_batch


class Model:
    max_calls, max_chat_calls, max_characters = 600, 12, 1_000_000

    def __init__(self):
        self.calls = 0
        self.failures = {}

    def snapshot(self):
        return dict(calls=dict(chat=self.calls), failures=self.failures)


class FullMultistageTest(unittest.TestCase):
    def test_complete_suite_requires_explicit_sufficient_bounded_budgets(self):
        args = SimpleNamespace(mode="multistage-full", backend="both")
        with self.assertRaises(ValueError):
            configure_budget(Model(), args, 129)
        args.max_requests, args.max_chat_requests, args.max_input_characters = 2400, 1100, 40_000_000
        model = Model()
        configure_budget(model, args, 129)
        self.assertEqual((model.max_calls, model.max_chat_calls, model.max_characters), (2400, 1100, 40_000_000))
        for field, value in (("max_chat_requests", 1032), ("max_requests", 0),
                             ("max_requests", True), ("max_input_characters", 100_000_001)):
            invalid = copy.copy(args)
            setattr(invalid, field, value)
            with self.subTest(field=field), self.assertRaises(ValueError):
                configure_budget(Model(), invalid, 129)

    def test_single_probe_defaults_are_not_increased(self):
        model = Model()
        configure_budget(model, SimpleNamespace(mode="multistage", backend="both"), 129)
        self.assertEqual((model.max_calls, model.max_chat_calls, model.max_characters), (600, 12, 1_000_000))

    def run_batch(self, responses, model=None):
        model = model or Model()
        queries = [dict(q="question-" + str(i), docs=["source"]) for i in range(len(responses))]
        saved, progress = [], {}

        class Client:
            def request(self, path, method, body):
                position = int(body["question"].split("-")[-1])
                model.calls += 4
                if isinstance(responses[position], Exception):
                    raise responses[position]
                return responses[position]

        rows = multistage_batch(Client(), model, queries, {"owned": "source"},
            {("owned", 0): hashlib.sha256(b"stored-sensitive-body").hexdigest()},
            lambda number, response: saved.append(number), progress)
        return rows, saved, progress

    @staticmethod
    def answer():
        return dict(answered=True, answer="Answer[1]", citations=[dict(index=1, documentId="owned",
            documentTitle="source", chunkIndex=0, content="stored-sensitive-body")],
            retrieval=dict(status="READY", warnings=[], stages=[dict(name=name, status="OK", durationMs=1)
                for name in ("recall", "rewrite", "rerank", "evidence")]))

    def test_guarded_and_wrong_citation_cases_are_retained_without_skipping_later_questions(self):
        guarded = self.answer()
        guarded["retrieval"]["status"] = "CLARIFY"
        foreign = self.answer()
        foreign["citations"][0]["documentId"] = "foreign"
        rows, saved, progress = self.run_batch([guarded, foreign, self.answer()])
        self.assertEqual([row["result"] for row in rows], ["FAIL", "FAIL", "PASS"])
        self.assertEqual(saved, [1, 2, 3])
        self.assertEqual(progress["executed_original_queries"], 3)
        self.assertEqual(progress["unexecuted_eligible_queries"], 0)
        self.assertEqual(progress["multistage_failed"], 2)
        self.assertTrue(progress["all_original_questions_executed"])
        self.assertNotIn("stored-sensitive-body", str(rows))
        self.assertFalse(rows[-1]["answer"]["factual_quality_manually_approved"])

    def test_unexpected_errors_do_not_publish_raw_messages_and_do_not_abort_other_cases(self):
        rows, _, progress = self.run_batch([ValueError("secret upstream content"), self.answer()])
        self.assertEqual(rows[0]["error_type"], "ValueError")
        self.assertNotIn("secret", str(rows))
        self.assertEqual(progress["multistage_passed"], 1)

    def test_real_upstream_failure_stops_spending_without_retry(self):
        model = Model()
        model.failures = {"UPSTREAM_HTTP_429": 1}
        with self.assertRaises(ModelFailure):
            self.run_batch([self.answer(), self.answer()], model)
        self.assertEqual(model.calls, 4)

    def test_empty_and_duplicate_questions_are_rejected_before_requests(self):
        for queries in ([], [dict(q="same", docs=[]), dict(q="same", docs=[])]):
            with self.assertRaises(ValueError):
                multistage_batch(None, Model(), queries, {}, {}, None, {})


if __name__ == "__main__":
    unittest.main()
