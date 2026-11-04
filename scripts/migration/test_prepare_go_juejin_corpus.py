import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from go_eval_adapter import original_modules
from fetch_go_juejin_corpus import load_corpus
from prepare_go_juejin_corpus import import_rendered, merge


class PreparedCorpusTest(unittest.TestCase):
    def setUp(self):
        self.modules, _ = original_modules()

    def exported(self, root, index=0):
        post, _ = self.modules["rebuild-corpus.py"].CORPUS[index]
        url = "https://juejin.cn/post/" + post
        raw = ('<title>Fixture</title><link rel="canonical" href="' + url
               + '"><div class="article-viewer"><p>' + "synthetic fixture body " * 40 + '</p></div>').encode()
        path = root / ("rendered-" + post + ".html")
        path.write_bytes(raw)
        receipt = dict(post_id=post, url=url, canonical=url, title="Fixture", path=str(path),
                       acquisition_method="normal-browser-rendered-article-viewer",
                       captured_at="2026-11-04T15:19:03.838Z", html_sha256=hashlib.sha256(raw).hexdigest())
        (root / ("receipt-" + post + ".json")).write_text(json.dumps(receipt))
        return post, receipt

    def test_import_uses_original_extractor_and_preserves_rendered_provenance(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source, destination = root / "source", root / "out"
            source.mkdir(); destination.mkdir()
            post, _ = self.exported(source)
            result = import_rendered(source, [post], destination, self.modules)
            documents, _ = load_corpus(destination, self.modules)
            self.assertEqual(set(documents), {post})
            self.assertEqual(result["articles"][0]["acquisition_method"], "normal-browser-rendered-article-viewer")
            self.assertIn("not raw HTTP", result["articles"][0]["page_hash_scope"])
            self.assertFalse(result["historical_corpus_identity_verified"])
            self.assertEqual((destination / result["articles"][0]["filename"]).stat().st_mode & 0o777, 0o600)

    def test_tampered_identity_hash_title_or_timestamp_is_rejected_before_writes(self):
        for field in ("url", "canonical", "title", "html_sha256", "path", "captured_at", "acquisition_method"):
            with self.subTest(field=field), tempfile.TemporaryDirectory() as temporary:
                root = Path(temporary).resolve()
                source, destination = root / "source", root / "out"
                source.mkdir(); destination.mkdir()
                post, receipt = self.exported(source)
                receipt[field] = "invalid"
                (source / ("receipt-" + post + ".json")).write_text(json.dumps(receipt))
                with self.assertRaises(ValueError):
                    import_rendered(source, [post], destination, self.modules)
                self.assertEqual(list(destination.iterdir()), [])

    def test_symlink_duplicate_and_unknown_article_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            source, destination = root / "source", root / "out"
            source.mkdir(); destination.mkdir()
            post, _ = self.exported(source)
            for posts in ([post, post], ["unknown"]):
                with self.assertRaises(ValueError):
                    import_rendered(source, posts, destination, self.modules)
            alias = root / "alias"
            alias.symlink_to(source, target_is_directory=True)
            with self.assertRaises(ValueError):
                import_rendered(alias, [post], destination, self.modules)

    def test_merge_preserves_original_bytes_order_and_partial_status(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary).resolve()
            acquisitions = []
            for index in (1, 0):
                source, destination = root / ("source" + str(index)), root / ("out" + str(index))
                source.mkdir(); destination.mkdir()
                post, _ = self.exported(source, index)
                import_rendered(source, [post], destination, self.modules)
                acquisitions.append(destination)
            merged = root / "merged"; merged.mkdir()
            result = merge(acquisitions, merged, self.modules)
            documents, _ = load_corpus(merged, self.modules)
            self.assertEqual(result["result"], "PARTIAL")
            self.assertEqual(result["requested"], 26)
            self.assertEqual(len(result["missing_articles"]), 24)
            self.assertEqual(list(documents), [post for post, _ in self.modules["rebuild-corpus.py"].CORPUS[:2]])
            self.assertEqual(len(result["merged_from"]), 2)
            self.assertFalse(result["real_model_quality_verified"])
            duplicate = root / "duplicate"; duplicate.mkdir()
            with self.assertRaises(ValueError):
                merge([acquisitions[0], acquisitions[0]], duplicate, self.modules)
            self.assertEqual(list(duplicate.iterdir()), [])


if __name__ == "__main__":
    unittest.main()
