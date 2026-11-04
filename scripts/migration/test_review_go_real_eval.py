import json
from pathlib import Path
import tempfile
import unittest

from review_go_real_eval import SPEC, review


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
            for field in ("result", "synthetic", "failure", "metrics", "annotations"):
                report = json.loads(json.dumps(source))
                if field == "result": report["result"] = "FAIL"
                elif field == "synthetic": report["synthetic_model"] = True
                elif field == "failure": report["real_model_requests"]["failures"] = {"UPSTREAM_HTTP_400": 1}
                elif field == "metrics": report["backends"]["go"]["metrics"]["mrr"] = 1
                else: report["backends"]["go"]["query_results"][0]["docs"] = ["weakened"]
                path.write_text(json.dumps(report))
                with self.subTest(field=field), self.assertRaises(ValueError):
                    review(path)


if __name__ == "__main__":
    unittest.main()
