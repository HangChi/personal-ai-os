import contextlib
import hashlib
import io
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import fetch_go_juejin_corpus as subject
from go_eval_adapter import original_modules


class JuejinCorpusTest(unittest.TestCase):
    def setUp(self):
        self.modules, _ = original_modules()
        self.extract, self.source_sha = subject.extractor()
        self.post, self.label = self.modules["rebuild-corpus.py"].CORPUS[0]
        self.url = "https://juejin.cn/post/" + self.post
        self.body = "真实页面结构的合成单测文本，不是已抓取文章。" * 30
        self.html = ('<html><title>Fixture title</title><link rel="canonical" href="' + self.url
                     + '"><div class="article-viewer"><p>' + self.body + "</p></div></html>").encode()

    def fetch(self, root, stdout=None):
        def download(*args, **kwargs):
            (root / "response.html").write_bytes(self.html)
            return SimpleNamespace(returncode=0, stdout=stdout or "200\n" + self.url + "\ntext/html; charset=utf-8\n")
        with patch.object(subject.subprocess, "run", side_effect=download) as run:
            row = subject.fetch_one(self.post, self.label, root, self.extract)
        args = run.call_args.args[0]
        self.assertIn("--proto", args)
        self.assertIn("--max-time", args)
        self.assertNotIn("--insecure", args)
        self.assertNotIn("--location", args)
        self.assertFalse((root / "response.html").exists())
        return row

    def test_body_must_be_article_content_with_matching_canonical_and_title(self):
        title, body = subject.extract_page(self.html, self.post, self.extract)
        self.assertEqual(title, "Fixture title")
        self.assertEqual(body.strip(), self.body)
        for html in (self.html.replace(self.url.encode(), b"https://foreign.invalid/post/1"),
                     b"<html><title>Challenge</title><script>" + b"x" * 600 + b"</script></html>",
                     b"<title>Title</title><div class='article-viewer'>short</div>", b"\xff"):
            with self.subTest(html=html[:25]), self.assertRaises(subject.FetchFailure):
                subject.extract_page(html, self.post, self.extract)

    def test_fetch_preserves_published_title_label_source_hashes_and_private_file(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            row = self.fetch(root)
            data = (root / row["filename"]).read_bytes()
            self.assertEqual(row["url"], self.url)
            self.assertEqual(row["label"], self.label)
            self.assertEqual(row["published_title"], "Fixture title")
            self.assertEqual(row["sha256"], hashlib.sha256(data).hexdigest())
            self.assertEqual((root / row["filename"]).stat().st_mode & 0o777, 0o600)
            self.assertIn(self.body, data.decode())

    def test_non_200_or_non_html_is_rejected_without_document(self):
        for metadata in ("302\n" + self.url + "\ntext/html\n", "200\n" + self.url + "\napplication/json\n"):
            with tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary)
                with self.assertRaises(subject.FetchFailure):
                    self.fetch(root, metadata)
                self.assertEqual(list(root.iterdir()), [])

    def test_acquisition_stops_on_first_failure_and_records_partial_result(self):
        with tempfile.TemporaryDirectory() as temporary, contextlib.redirect_stdout(io.StringIO()):
            rows = self.modules["rebuild-corpus.py"].CORPUS[:3]
            fetch = Mock(side_effect=[{"post_id": rows[0][0], "body_chars": 500}, subject.FetchFailure("ARTICLE_BODY_MISSING_OR_BLOCKED")])
            sleep = Mock()
            with patch.object(subject, "fetch_one", fetch):
                report = subject.collect(Path(temporary), rows, self.extract, self.source_sha, pause=sleep)
            self.assertEqual(fetch.call_count, 2)
            sleep.assert_called_once_with(3)
            self.assertEqual(report["result"], "PARTIAL")
            self.assertFalse(report["historical_corpus_identity_verified"])
            self.assertEqual(report["failure"]["post_id"], rows[1][0])

    def test_loader_checks_actual_bytes_identity_symlinks_and_duplicate_articles(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            row = self.fetch(root)
            manifest = dict(version=1, result="COMPLETE", articles=[row], extractor_sha256=self.source_sha)
            path = root / "manifest.json"
            path.write_text(json.dumps(manifest))
            documents, _ = subject.load_corpus(root, self.modules)
            self.assertEqual(documents[self.post][0], self.label)
            self.assertIn(self.body, documents[self.post][1])
            manifest["articles"] = [row, row]
            path.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                subject.load_corpus(root, self.modules)
            manifest["articles"] = [dict(row, url="https://foreign.invalid")]
            path.write_text(json.dumps(manifest))
            with self.assertRaises(ValueError):
                subject.load_corpus(root, self.modules)
            manifest["articles"] = [row]
            path.write_text(json.dumps(manifest))
            document = root / row["filename"]
            document.write_text("changed")
            with self.assertRaises(ValueError):
                subject.load_corpus(root, self.modules)
            document.unlink()
            document.symlink_to(path)
            with self.assertRaises(ValueError):
                subject.load_corpus(root, self.modules)

    def test_drifted_original_extractor_is_not_executed(self):
        with patch.object(subject.hashlib, "sha256") as digest, patch.object(subject.importlib.util, "spec_from_file_location") as execute:
            digest.return_value.hexdigest.return_value = "0" * 64
            with self.assertRaises(ValueError):
                subject.extractor()
            execute.assert_not_called()
