import hashlib
import unittest

from java_embedding_content import embedding_hashes


class JavaEmbeddingContentTest(unittest.TestCase):
    def test_exact_four_field_spring_formatter_with_unicode_and_template_literals(self):
        rows, expected = [], []
        for content, title, filename in (("synthetic content", "title", "real.md"),
                                          ("two\nlines", "中文{content}", "file{value}.md")):
            metadata = dict(document_id="owned-id", document_title=title, filename=filename, chunk_index=7)
            rows.append(dict(content=content, metadata=metadata))
            prefix = ("document_title: " + title + "\nchunk_index: 7\nfilename: " + filename
                      + "\ndocument_id: owned-id")
            formatted = (prefix + "\n\n{content}").replace("{content}", content)
            expected.append(hashlib.sha256(formatted.encode()).hexdigest())
        actual, evidence = embedding_hashes(rows)
        self.assertEqual(actual, expected)
        self.assertEqual(len(evidence["oracle_sha256"]), 64)
        self.assertIn("spring-ai-commons-2.0.1.jar", evidence["jars_sha256"])
        self.assertNotIn("two\nlines", str(evidence))


if __name__ == "__main__":
    unittest.main()
