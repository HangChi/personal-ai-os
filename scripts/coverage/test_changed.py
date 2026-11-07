import unittest

import changed
import summarize


class ChangedCoverageTest(unittest.TestCase):
    def test_new_lines_and_deletion_only_hunks(self):
        diff = "+++ b/backend-go/a.go\n@@ -2,3 +2,0 @@\n-x\n@@ -6 +4,2 @@\n+a\n+b\n"
        self.assertEqual(changed.changed_lines(diff), {"backend-go/a.go": {4, 5}})

    def test_java_ignores_non_executable_changed_lines(self):
        result = changed.measure({"backend/src/main/java/p/A.java": {1, 2, 3}},
                                 {"backend/src/main/java/p/A.java": {2: True, 4: False}}, {})
        self.assertEqual(result["java_changed_lines"], summarize.metric(1, 1))
        self.assertEqual(result["go_changed_statements"]["status"], "not_applicable")

    def test_go_counts_touched_block_statements_once_and_exclusive_end(self):
        blocks = {"personal-ai-os/backend-go/a.go:2.1,5.1": (3, 1),
                  "personal-ai-os/backend-go/a.go:5.1,6.3": (2, 0)}
        result = changed.measure({"backend-go/a.go": {2, 3, 5}}, {}, blocks)
        self.assertEqual(result["go_changed_statements"], summarize.metric(3, 5))
        result = changed.measure({"backend-go/a.go": {5}}, {}, blocks)
        self.assertEqual(result["go_changed_statements"], summarize.metric(0, 2))

    def test_missing_changed_source_cannot_silently_pass(self):
        for path in ("backend-go/new.go", "backend/src/main/java/p/New.java"):
            with self.subTest(path=path), self.assertRaisesRegex(ValueError, "missing"):
                changed.measure({path: {1}}, {}, {})

    def test_test_files_and_deleted_files_are_not_production_denominators(self):
        result = changed.measure({"backend-go/a_test.go": {1}, "backend-go/deleted.go": set()}, {}, {})
        self.assertTrue(all(value["status"] == "not_applicable" for value in result.values()))


class ThresholdTest(unittest.TestCase):
    def test_unrounded_949999_does_not_pass_95(self):
        self.assertFalse(summarize.meets_minimum(summarize.metric(949999, 1000000), 95))
        self.assertTrue(summarize.meets_minimum(summarize.metric(950000, 1000000), 95))

    def test_invalid_threshold_and_coverage_counts_fail_closed(self):
        for value in ("NaN", "Infinity", "-1", "101"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                summarize.meets_minimum({"covered": 1, "total": 1}, value)
        for covered, total in ((1, 0), (-1, 2), (3, 2)):
            with self.subTest(counts=(covered, total)), self.assertRaises(ValueError):
                summarize.metric(covered, total)
