import copy
import json
import unittest

import go_p8_inventory_progress as subject


class P8InventoryDispositionTest(unittest.TestCase):
    def setUp(self):
        self.inventory = json.loads((subject.SPEC / "inventory.json").read_text())
        self.progress = json.loads((subject.SPEC / "progress.json").read_text())

    def test_every_file_is_disposed_but_blocked_work_and_gates_stay_open(self):
        after, audit = subject.apply(self.inventory, self.progress)
        self.assertEqual(after["release_gates"], self.progress["release_gates"])
        self.assertEqual(len(audit["files"]), 1093)
        self.assertEqual({row["id"] for row in audit["files"]}, {entry["id"] for entry in self.inventory["files"]})
        self.assertTrue(all(row["sha256"] and row["disposition"] for row in audit["files"]))
        for path in subject.PRODUCTION | subject.PUSH | {"scripts/e2e/e2e_suite.py", "scripts/eval/retrieval-eval.py", "scripts/eval/fetch-corpus-juejin.py"}:
            self.assertEqual(after["items"]["file:" + path]["status"], "in_progress", path)
        for path in ("scripts/eval/retrieval-eval.py", "scripts/eval/fetch-corpus-juejin.py"):
            entry = after["items"]["file:" + path]
            self.assertIn("scripts/migration/fetch_go_juejin_corpus.py", entry["evidence"])
            self.assertIn("specs/033-go-rewrite/p8-eval-juejin-20261104-212152.json", entry["evidence"])
            self.assertIn("完整当前26篇/129原查询", entry["note"])
            self.assertIn("零缺材料", entry["note"])
            self.assertIn("质量未批准", entry["note"])
            self.assertIn("另128题未执行", entry["note"])
            self.assertIn("specs/033-go-rewrite/p8-corpus-complete-20261104-231937.json", entry["evidence"])
            self.assertIn("specs/033-go-rewrite/p8-eval-real-20261104-232203.json", entry["evidence"])
            self.assertIn("specs/033-go-rewrite/p8-real-model-review-20261104-232203.json", entry["evidence"])
            self.assertIn("specs/033-go-rewrite/p8-eval-paired-20261104-233557.json", entry["evidence"])
            self.assertIn("该轮共同五miss", entry["note"])
            self.assertIn("specs/033-go-rewrite/p8-live-conflicts-java-20261104-234606.json", entry["evidence"])
            self.assertIn("specs/033-go-rewrite/p8-live-ingestion-review-go-20261104-234857.json", entry["evidence"])
            self.assertIn("开放冲突Go1/Java2差异保留", entry["note"])
            self.assertIn("specs/033-go-rewrite/p8-real-model-review.json", entry["evidence"])
        for group in ("java_test_cases", "tools"):
            for entry in self.inventory[group]:
                self.assertEqual(after["items"].get(entry["id"]), self.progress["items"].get(entry["id"]), entry["id"])
        for entry in self.inventory["files"]:
            before = self.progress["items"].get(entry["id"], {})
            if before.get("status") == "verified":
                self.assertEqual(after["items"][entry["id"]]["status"], "verified")
        again, audit2 = subject.apply(self.inventory, after)
        self.assertEqual(after, again)
        self.assertEqual(audit, audit2)

    def test_original_file_drift_is_rejected(self):
        inventory = copy.deepcopy(self.inventory)
        inventory["files"][0]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "Baseline file drift"):
            subject.apply(inventory, self.progress)

    def test_unreviewed_java_code_does_not_inherit_a_category_wide_pass(self):
        path = "backend/src/main/java/com/personalos/task/service/TaskService.java"
        progress = copy.deepcopy(self.progress)
        progress["items"].pop("file:" + path, None)
        with self.assertRaisesRegex(ValueError, "Unreviewed Java responsibility"):
            subject.apply(self.inventory, progress)
