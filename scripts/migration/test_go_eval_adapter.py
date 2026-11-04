from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import go_eval_adapter as subject


class EvaluationAdapterTest(unittest.TestCase):
    def test_no_implicit_corpus_scan_and_complete_fixed_annotations(self):
        modules, hashes = subject.original_modules()
        report = subject.corpus_preflight(modules)
        self.assertEqual(report["result"], "BLOCKED")
        self.assertEqual(report["required_documents"], 26)
        self.assertEqual(report["queries"], 129)
        self.assertEqual(len(report["missing"]), 26)
        self.assertEqual(len(hashes), 4)
        self.assertFalse(report["real_model_quality_verified"])

    def test_preflight_rejects_missing_symlink_short_and_invalid_utf8(self):
        modules, _ = subject.original_modules()
        corpus = modules["rebuild-corpus.py"].CORPUS
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = lambda index: root / ("juejin-" + corpus[index][0] + ".md")
            path(0).write_text("Synthetic only. " * 40)
            path(1).write_text("short")
            path(2).write_bytes(b"\xff" * 400)
            path(3).symlink_to(path(0))
            report = subject.corpus_preflight(modules, root)
            self.assertEqual(report["result"], "BLOCKED")
            self.assertEqual(len(report["available"]), 1)
            self.assertEqual(set(report["invalid"]), {corpus[index][0] for index in (1, 2, 3)})
            self.assertEqual(len(report["missing"]), 22)
            for index in range(1, len(corpus)):
                if path(index).is_symlink():
                    path(index).unlink()
                path(index).write_text("Synthetic only. " * 40)
            report = subject.corpus_preflight(modules, root)
            self.assertEqual(report["result"], "FILES_PRESENT_NOT_AUTHENTICATED")
            self.assertFalse(report["source_provenance_verified"])
        with self.assertRaises(ValueError):
            subject.corpus_preflight(modules, "relative")

    def test_fixed_source_drift_fails_before_original_code_executes(self):
        with patch.object(subject.hashlib, "sha256") as digest, patch.object(subject.importlib.util, "spec_from_file_location") as execute:
            digest.return_value.hexdigest.return_value = "0" * 64
            with self.assertRaisesRegex(ValueError, "fixed baseline"):
                subject.original_modules()
            execute.assert_not_called()

    def test_adapter_guards_empty_database_upload_manifest_and_destructive_original_entries(self):
        client, database = Mock(), Mock()
        database.require_empty.side_effect = ValueError("nonempty")
        with self.assertRaises(ValueError):
            subject.FixtureAdapter(client, database, {})
        client.assert_not_called()
        database.require_empty.side_effect = None
        adapter = subject.FixtureAdapter(client, database, {"owned": ("fixture", "body")})
        modules = {name: SimpleNamespace() for name in ("rebuild-corpus.py", "retrieval-eval.py", "noise-floor.py")}
        adapter.bind(modules)
        for name in ("main", "delete_all", "request"):
            with self.assertRaises(ValueError):
                getattr(modules["rebuild-corpus.py"], name)()
        for path, title in (("/tmp/existing.md", "fixture"), ("https://external.invalid", "fixture"), ("owned", "different")):
            with self.assertRaises(ValueError):
                adapter.upload(path, title)
        client.upload.assert_not_called()
        client.upload.return_value = dict(id="owned-id", title="fixture", chunkCount=1)
        adapter.upload("owned", "fixture")
        with self.assertRaises(ValueError):
            adapter.upload("owned", "fixture")

    def test_original_metrics_preserve_misses_ties_and_hybrid_score_order(self):
        modules, _ = subject.original_modules()
        evaluator = modules["retrieval-eval.py"]
        hit = lambda title, score: dict(documentTitle=title, score=score)
        self.assertIsNone(evaluator.effective_rank([hit("other", 1)], ["wanted"]))
        self.assertEqual(evaluator.first_hit_rank(["other", "wanted"], ["wanted"]), 2)
        self.assertEqual(evaluator.effective_rank([hit("other", .9), hit("wanted", .895)], ["wanted"]), 1)
        self.assertEqual(evaluator.effective_rank([hit("other", .9), hit("wanted", .88)], ["wanted"]), 2)
        self.assertEqual(evaluator.effective_rank([hit("other", None), hit("wanted", .895)], ["wanted"]), 2)
        self.assertEqual(evaluator.effective_rank([hit("other", .8), hit("wanted", .9)], ["wanted"]), 2)

    def test_search_rejects_foreign_duplicate_and_invalid_hits_without_discarding_them(self):
        client = Mock()
        adapter = subject.FixtureAdapter(client, Mock(), {})
        adapter.uploaded = {"owned-id": "fixture"}
        valid = dict(documentId="owned-id", documentTitle="fixture", score=None)
        for hits in ([dict(valid, documentId="foreign")], [valid, valid], [dict(valid, documentTitle="wrong")],
                     [dict(valid, score=float("nan"))], [dict(valid, score=True)]):
            client.request.return_value = {"hits": hits}
            with self.subTest(hits=hits), self.assertRaises(AssertionError):
                adapter.search("fixture")
        client.request.return_value = {"hits": [valid]}
        self.assertEqual(adapter.search("fixture"), [valid])
