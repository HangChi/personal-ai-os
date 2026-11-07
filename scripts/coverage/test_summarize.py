import tempfile
import unittest
import contextlib
import io
import json
from unittest.mock import patch
from pathlib import Path

import summarize


class CoverageMergeTest(unittest.TestCase):
    def setUp(self):
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name)

    def profile(self, name, body):
        path = self.root / name
        path.write_text("mode: atomic\n" + body)
        return path

    def test_duplicate_blocks_and_profiles_do_not_inflate_denominator(self):
        first = self.profile("first.out", "module/a.go:1.1,2.1 2 1\nmodule/a.go:1.1,2.1 2 0\nmodule/b.go:3.1,4.1 3 0\n")
        second = self.profile("second.out", "module/a.go:1.1,2.1 2 0\nmodule/b.go:3.1,4.1 3 1\nmodule/testdb.go:1.1,2.1 1 0\n")
        result = summarize.go_summary(summarize.merge_go([first, second, first]))
        self.assertEqual((result["covered"], result["total"]), (5, 6))

    def test_conflicting_statement_counts_fail_closed(self):
        first = self.profile("first.out", "module/a.go:1.1,2.1 2 1\n")
        second = self.profile("second.out", "module/a.go:1.1,2.1 3 1\n")
        with self.assertRaisesRegex(ValueError, "Statement count changed"):
            summarize.merge_go([first, second])

    def test_empty_or_non_atomic_profiles_are_rejected(self):
        empty = self.profile("empty.out", "")
        with self.assertRaisesRegex(ValueError, "Empty"):
            summarize.merge_go([empty])
        empty.write_text("mode: count\nmodule/a.go:1.1,2.1 2 1\n")
        with self.assertRaisesRegex(ValueError, "atomic"):
            summarize.merge_go([empty])

    def test_source_layout_changes_cannot_add_a_second_denominator(self):
        first = self.profile("first.out", "module/a.go:1.1,2.1 2 1\n")
        second = self.profile("second.out", "module/a.go:1.1,3.1 2 1\n")
        with self.assertRaisesRegex(ValueError, "source layout changed"):
            summarize.merge_go([first, second])

    def test_java_uses_report_total_instead_of_summing_classes(self):
        path = self.root / "jacoco.xml"
        path.write_text('<report><sessioninfo/><package><counter type="BRANCH" missed="0" covered="999"/></package><counter type="BRANCH" missed="5" covered="95"/><counter type="LINE" missed="1" covered="99"/></report>')
        result = summarize.java_summary(path)
        self.assertEqual(result["branch"]["percent"], 95)
        self.assertEqual(result["line"]["percent"], 99)
        self.assertEqual(result["sessions"], 1)

    def test_java_branch_success_cannot_hide_line_failure(self):
        path = self.root / "jacoco.xml"
        path.write_text('<report><sessioninfo/><counter type="BRANCH" missed="0" covered="100"/>'
                        '<counter type="LINE" missed="6" covered="94"/></report>')
        output = io.StringIO()
        with patch("sys.argv", ["summarize.py", "--java-report", str(path)]), contextlib.redirect_stdout(output):
            self.assertEqual(summarize.main(), 1)
        result = json.loads(output.getvalue())
        self.assertTrue(result["checks"]["java_branches"]["passed"])
        self.assertFalse(result["checks"]["java_lines"]["passed"])

    def test_explicit_zero_threshold_is_not_replaced_with_default_95(self):
        profile = self.profile("zero.out", "module/a.go:1.1,2.1 1 0\n")
        output = io.StringIO()
        with patch("sys.argv", ["summarize.py", "--go-profile", str(profile), "--go-statement-minimum", "0"]), contextlib.redirect_stdout(output):
            self.assertEqual(summarize.main(), 0)
        self.assertEqual(json.loads(output.getvalue())["go_branches"]["status"], "not_measured")


if __name__ == "__main__":
    unittest.main()
