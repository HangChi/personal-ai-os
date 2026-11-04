import copy
from pathlib import Path
import unittest

from go_eval_adapter import original_modules
from review_go_real_eval import review_multistage


class MultistageReviewTest(unittest.TestCase):
    def setUp(self):
        modules, hashes = original_modules()
        rows = [dict(question=row["q"], acceptable_documents=row["docs"], result="PASS",
            retrieval_status="READY", answered=True, warning_count=0, actual_chat_calls=4,
            stages=[dict(name=name, status="OK") for name in ("recall", "rewrite", "rerank", "evidence")],
            answer=dict(explicit_marks_match=True, citation_chunks_match_database=True, acceptable_document_cited=True))
            for row in modules["queries.py"].QUERIES]
        backend = dict(mode="multistage-full", stage="complete",
            documents=len(modules["rebuild-corpus.py"].CORPUS), queries=len(rows), sources_sha256=hashes,
            executed_original_queries=len(rows), unexecuted_eligible_queries=0, excluded_queries=0,
            all_original_questions_executed=True, processes_stopped=True, database_connections_after_stop=0,
            multistage_cases=rows, result="PASS", multistage_passed=len(rows), multistage_failed=0)
        self.report = dict(result="PASS", mode="multistage-full", synthetic_model=False,
            configured_real_model_used=True, model={"chat_model": "test-model"},
            backends={name: copy.deepcopy(backend) for name in ("go", "java")},
            real_model_requests=dict(failures={}, calls=dict(chat=1 + 8 * len(rows))))

    def review(self, report=None):
        return review_multistage(report or self.report, b"test-evidence", Path("test-report.json"))

    def test_complete_run_never_implicitly_approves_facts_or_release(self):
        result = self.review()
        self.assertEqual(result["backends"]["go"]["strict_passed"], 129)
        self.assertFalse(result["factual_quality_manually_approved"])
        self.assertFalse(result["release_gate_approved"])
        self.assertEqual(result["paired_strict_outcomes"], [dict(go="PASS", java="PASS", cases=129)])

    def test_missing_acceptable_source_or_overall_mislabel_is_rejected(self):
        for kind in ("source", "overall", "unfinished"):
            report = copy.deepcopy(self.report)
            if kind == "source":
                report["backends"]["go"]["multistage_cases"][0]["answer"].pop("acceptable_document_cited")
            elif kind == "overall":
                report["result"] = "FAIL"
            else:
                backend = report["backends"]["go"]
                backend.update(result="FAIL", multistage_passed=128, multistage_failed=1)
                backend["multistage_cases"][0]["result"] = "IN_PROGRESS"
                report["result"] = "FAIL"
            with self.assertRaises(ValueError):
                self.review(report)

    def test_guarded_original_case_is_retained_as_failure_after_full_execution(self):
        self.report["result"] = "FAIL"
        backend = self.report["backends"]["go"]
        backend.update(result="FAIL", multistage_passed=128, multistage_failed=1)
        backend["multistage_cases"][0].update(result="FAIL", retrieval_status="CLARIFY", answered=False,
            actual_chat_calls=3, failure="guarded response")
        self.report["real_model_requests"]["calls"]["chat"] -= 1
        result = self.review()
        self.assertEqual(result["backends"]["go"]["strict_failed"], 1)
        self.assertEqual(result["backends"]["go"]["failed_cases"][0]["index"], 1)
        self.assertFalse(result["semantic_quality_verified"])

    def test_omitted_reordered_or_relabelled_cases_are_rejected(self):
        for kind in ("omitted", "reordered", "relabelled"):
            report = copy.deepcopy(self.report)
            rows = report["backends"]["go"]["multistage_cases"]
            if kind == "omitted":
                rows.pop()
            elif kind == "reordered":
                rows.reverse()
            else:
                rows[0]["retrieval_status"] = "CLARIFY"
            with self.assertRaises(ValueError):
                self.review(report)

    def test_source_drift_failures_or_inconsistent_receipts_are_rejected(self):
        for kind in ("source", "upstream", "calls", "counters"):
            report = copy.deepcopy(self.report)
            if kind == "source":
                report["backends"]["go"]["sources_sha256"] = {}
            elif kind == "upstream":
                report["real_model_requests"]["failures"] = dict(UPSTREAM_HTTP_500=1)
            elif kind == "calls":
                report["real_model_requests"]["calls"]["chat"] += 1
            else:
                report["backends"]["go"]["multistage_failed"] += 1
            with self.assertRaises(ValueError):
                self.review(report)


if __name__ == "__main__":
    unittest.main()
