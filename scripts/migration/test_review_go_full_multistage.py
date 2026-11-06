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

    def test_java_only_passes_keep_original_indices_and_observed_groups(self):
        backend = self.report["backends"]["go"]
        backend.update(result="FAIL", multistage_passed=124, multistage_failed=5)
        self.report["result"] = "FAIL"
        rows = backend["multistage_cases"]
        for row in rows[:5]:
            row.update(result="FAIL", failure="retained original failure")
        rows[0]["stages"][2]["status"] = "FALLBACK"
        rows[1].update(retrieval_status="CLARIFY", answered=False)
        rows[2].update(retrieval_status="CONFLICT", answered=False)
        rows[3]["answer"]["explicit_marks_match"] = False
        rows[4]["answer"]["acceptable_document_cited"] = False
        rows[3]["answer"]["text"] = "private answer must not be copied"
        result = self.review()["java_pass_go_fail"]
        self.assertEqual([row["index"] for row in result["cases"]], [1, 2, 3, 4, 5])
        self.assertEqual([row["source_index"] for row in result["cases"]], [0, 1, 2, 3, 4])
        self.assertEqual(result["group_counts"], dict(rerank_fallback_ready=1, clarify_without_warning=1,
            conflict_without_warning=1, explicit_citation_mismatch=1, other_observed_failure=1))
        self.assertEqual(result["cases"][0]["observations"]["go"]["failure"], "retained original failure")
        self.assertTrue(all(not row["root_cause_verified"] for row in result["cases"]))
        self.assertFalse(result["raw_stage_responses_available"])
        self.assertNotIn("private answer", str(result))

    def test_failed_citation_check_without_answer_metadata_is_still_explicit(self):
        self.report["result"] = "FAIL"
        backend = self.report["backends"]["go"]
        backend.update(result="FAIL", multistage_passed=128, multistage_failed=1)
        row = backend["multistage_cases"][27]
        row.update(result="FAIL", failure="Real answer citation marks differ from actual citation records")
        row.pop("answer")
        result = self.review()["java_pass_go_fail"]
        self.assertEqual(result["group_counts"], dict(explicit_citation_mismatch=1))
        self.assertEqual(result["cases"][0]["index"], 28)
        self.assertEqual(result["cases"][0]["observations"]["go"]["citation_checks"], {})


if __name__ == "__main__":
    unittest.main()
