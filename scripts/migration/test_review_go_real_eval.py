import json
from pathlib import Path
import tempfile
import unittest

from review_go_real_eval import SPEC, review
from go_eval_adapter import original_modules
from run_go_real_eval_tests import metrics


class RealEvaluationReviewTest(unittest.TestCase):
    def test_completed_subset_retains_strict_regression_and_original_tied_parity(self):
        result = review(SPEC / "p8-eval-real-20261104-222729.json")
        self.assertEqual(result["queries"], 84)
        self.assertEqual(result["excluded_queries"], 45)
        self.assertFalse(result["assessment"]["strict_quality_not_lower"])
        self.assertTrue(result["assessment"]["original_tied_quality_not_lower"])
        self.assertFalse(result["release_gate_approved"])
        self.assertFalse(result["semantic_quality_verified"])

    def test_failed_synthetic_tampered_metric_and_annotation_cannot_be_reviewed_as_pass(self):
        source = json.loads((SPEC / "p8-eval-real-20261104-222729.json").read_text())
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            for field in ("result", "synthetic", "mode", "negative", "failure", "metrics", "annotations", "documents", "queries", "excluded"):
                report = json.loads(json.dumps(source))
                if field == "result": report["result"] = "FAIL"
                elif field == "synthetic": report["synthetic_model"] = True
                elif field == "mode": report["mode"] = "multistage"
                elif field == "negative": report["mode"] = "no-evidence"
                elif field == "failure": report["real_model_requests"]["failures"] = {"UPSTREAM_HTTP_400": 1}
                elif field == "metrics": report["backends"]["go"]["metrics"]["mrr"] = 1
                elif field == "documents": report["backends"]["go"]["documents"] = 26
                elif field == "queries": report["backends"]["go"]["queries"] = 129
                elif field == "excluded": report["backends"]["go"]["excluded_queries"] = 0
                else: report["backends"]["go"]["query_results"][0]["docs"] = ["weakened"]
                path.write_text(json.dumps(report))
                with self.subTest(field=field), self.assertRaises(ValueError):
                    review(path)

    def test_full_current_corpus_never_approves_historical_identity_or_quality(self):
        source = json.loads((SPEC / "p8-eval-real-20261104-222729.json").read_text())
        modules, _ = original_modules()
        rows = [dict(query=row["q"], docs=row["docs"], level=row["level"], rank=1, tied_rank=1)
                for row in modules["queries.py"].QUERIES]
        for backend in source["backends"].values():
            backend.update(documents=26, queries=129, excluded_queries=0, metrics=metrics(rows), query_results=rows)
            backend["ingestion"]["per_document"] = [dict(title=title) for _, title in modules["rebuild-corpus.py"].CORPUS]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "fixture-report.json"
            path.write_text(json.dumps(source))
            result = review(path)
        self.assertEqual(result["result"], "FULL_CURRENT_CORPUS_EXECUTED_QUALITY_NOT_APPROVED")
        self.assertTrue(result["full_current_corpus_executed"])
        self.assertFalse(result["historical_corpus_identity_verified"])
        self.assertFalse(result["semantic_quality_verified"])
        self.assertFalse(result["release_gate_approved"])


if __name__ == "__main__":
    unittest.main()
