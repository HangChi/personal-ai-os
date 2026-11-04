import copy
import hashlib
import unittest

from go_eval_adapter import original_modules
from run_go_real_eval_tests import check_answer, check_multistage, check_no_evidence, comparison, corpus_coverage, metrics


class RealEvaluationTest(unittest.TestCase):
    def test_corpus_coverage_is_measured_without_claiming_historical_identity(self):
        modules, _ = original_modules()
        corpus = modules["rebuild-corpus.py"].CORPUS
        full = corpus_coverage({post: (title, "fixture") for post, title in corpus}, modules)
        self.assertEqual(full["documents"], 26)
        self.assertEqual(full["eligible_original_queries"], 129)
        self.assertEqual(full["excluded_original_queries"], 0)
        self.assertFalse(full["historical_corpus_identity_verified"])
        partial = corpus_coverage({post: (title, "fixture") for post, title in corpus[:22]}, modules)
        self.assertEqual(partial["eligible_original_queries"], 111)
        self.assertEqual(partial["excluded_original_queries"], 18)

    def answer(self):
        return dict(answered=True, answer="概述[1]", citations=[
            dict(index=1, documentId="owned", documentTitle="acceptable", chunkIndex=0, content="stored chunk")])

    def check(self, response):
        return check_answer(response, ["acceptable"], {"owned": "acceptable"},
                            {("owned", 0): hashlib.sha256(b"stored chunk").hexdigest()})

    def test_answer_must_cite_an_acceptable_persisted_chunk_without_approving_factual_quality(self):
        result = self.check(self.answer())
        self.assertTrue(result["explicit_marks_match"])
        self.assertTrue(result["citation_chunks_match_database"])
        self.assertFalse(result["factual_quality_manually_approved"])
        self.assertNotIn("stored chunk", str(result))
        self.assertNotIn("概述", str(result))

    def test_answer_rejects_missing_marks_foreign_document_wrong_chunk_and_unsupported_citation(self):
        for key, value in (("answered", False), ("answer", "没有编号"), ("answer", "错误编号[2]"),
                           ("citations", [])):
            response = self.answer()
            response[key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.check(response)
        for key, value in (("documentId", "foreign"), ("documentTitle", "other"),
                           ("chunkIndex", 1), ("content", "invented source")):
            response = self.answer()
            response["citations"][0][key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                self.check(response)
        with self.assertRaises(AssertionError):
            check_answer(self.answer(), ["different acceptable"], {"owned": "acceptable"},
                         {("owned", 0): hashlib.sha256(b"stored chunk").hexdigest()})

    def test_metrics_preserve_actual_misses_and_ranks(self):
        result = metrics([{"rank": rank} for rank in (1, 3, 5, None)])
        self.assertEqual(result["recall_at_1"], .25)
        self.assertEqual(result["recall_at_3"], .5)
        self.assertEqual(result["recall_at_5"], .75)
        self.assertAlmostEqual(result["mrr"], (1 + 1 / 3 + 1 / 5) / 4)
        self.assertEqual(result["misses"], 1)
        with self.assertRaises(ValueError):
            metrics([])

    def test_differential_requires_identical_annotations_and_does_not_hide_degradation(self):
        rows = [dict(query="q", docs=["acceptable"], rank=1, tied_rank=1)]
        base = dict(query_results=rows, metrics=metrics(rows))
        result = comparison({"java": base, "go": copy.deepcopy(base)})
        self.assertTrue(result["go_mrr_not_lower"])
        self.assertTrue(result["original_tied_quality_not_lower"])
        self.assertFalse(result["release_gate_approved"])
        candidate = dict(query_results=[dict(rows[0], rank=None, tied_rank=None)], metrics=metrics([{"rank": None}]))
        result = comparison({"java": base, "go": candidate})
        self.assertFalse(result["go_recall_at_5_not_lower"])
        self.assertFalse(result["go_mrr_not_lower"])
        self.assertFalse(result["original_tied_quality_not_lower"])
        self.assertEqual(result["strict_quality_status"], "DEGRADATION_OBSERVED")
        self.assertEqual(result["strict_rank_changes"], 1)
        candidate["query_results"][0]["docs"] = ["weakened annotation"]
        with self.assertRaises(AssertionError):
            comparison({"java": base, "go": candidate})

    def test_tied_parity_never_overwrites_strict_degradation(self):
        java = [dict(query="q", docs=["acceptable"], rank=1, tied_rank=1)]
        go = [dict(query="q", docs=["acceptable"], rank=2, tied_rank=1)]
        result = comparison({"java": dict(query_results=java, metrics=metrics(java)),
                             "go": dict(query_results=go, metrics=metrics(go))})
        self.assertFalse(result["strict_quality_not_lower"])
        self.assertTrue(result["original_tied_quality_not_lower"])
        self.assertEqual(result["go_minus_java"]["mrr"], -.5)
        self.assertFalse(result["release_gate_approved"])

    def test_multistage_requires_all_real_stages_no_warnings_and_four_calls(self):
        response = dict(retrieval=dict(status="READY", warnings=[], stages=[
            dict(name=name, status="OK", durationMs=1) for name in ("recall", "rewrite", "rerank", "evidence")]))
        self.assertEqual(check_multistage(response, 4)["status"], "READY")
        self.assertFalse(check_multistage(response, 4)["factual_quality_manually_approved"])
        for field in ("status", "warning", "stage", "missing", "calls"):
            altered = copy.deepcopy(response)
            if field == "status": altered["retrieval"]["status"] = "UNVERIFIED"
            elif field == "warning": altered["retrieval"]["warnings"].append("fallback")
            elif field == "stage": altered["retrieval"]["stages"][1]["status"] = "FALLBACK"
            elif field == "missing": altered["retrieval"]["stages"].pop()
            with self.subTest(field=field), self.assertRaises(AssertionError):
                check_multistage(altered, 3 if field == "calls" else 4)

    def test_strict_parity_never_overwrites_tied_degradation(self):
        java = [dict(query="a", docs=["acceptable"], rank=2, tied_rank=1),
                dict(query="b", docs=["acceptable"], rank=1, tied_rank=1)]
        go = [dict(query="a", docs=["acceptable"], rank=1, tied_rank=1),
              dict(query="b", docs=["acceptable"], rank=2, tied_rank=2)]
        result = comparison({"java": dict(query_results=java, metrics=metrics(java)),
                             "go": dict(query_results=go, metrics=metrics(go))})
        self.assertTrue(result["strict_quality_not_lower"])
        self.assertFalse(result["original_tied_quality_not_lower"])
        self.assertLess(result["original_tied_rank_go_minus_java"]["mrr"], 0)
        self.assertFalse(result["release_gate_approved"])

    def test_no_evidence_requires_clean_stages_empty_sources_and_no_final_answer_call(self):
        response = dict(answered=False, answer="知识库中没有找到相关内容。", citations=[],
                        retrieval=dict(status="INSUFFICIENT", hits=[], warnings=[], stages=[
                            dict(name=name, status="OK", durationMs=1)
                            for name in ("recall", "rewrite", "rerank", "evidence")]))
        result = check_no_evidence(response, 3)
        self.assertFalse(result["final_answer_model_called"])
        self.assertFalse(result["factual_quality_manually_approved"])
        for field in ("answered", "answer", "citations", "status", "clarify", "hits", "warning", "stage", "missing", "calls"):
            altered = copy.deepcopy(response)
            if field == "answered": altered["answered"] = True
            elif field == "answer": altered["answer"] = "unsupported answer"
            elif field == "citations": altered["citations"] = [dict(index=1)]
            elif field == "status": altered["retrieval"]["status"] = "UNVERIFIED"
            elif field == "clarify": altered["retrieval"]["status"] = "CLARIFY"
            elif field == "hits": altered["retrieval"]["hits"] = [dict(documentId="foreign")]
            elif field == "warning": altered["retrieval"]["warnings"].append("timeout")
            elif field == "stage": altered["retrieval"]["stages"][3]["status"] = "FALLBACK"
            elif field == "missing": altered["retrieval"]["stages"].pop()
            with self.subTest(field=field), self.assertRaises(AssertionError):
                check_no_evidence(altered, 4 if field == "calls" else 3)


if __name__ == "__main__":
    unittest.main()
