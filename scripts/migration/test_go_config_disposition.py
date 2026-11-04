"""逐项配置处置门禁的反例：不能静默增加/遗漏调用方或动态键。"""
import unittest
from unittest.mock import patch

import go_config_disposition as subject


class ConfigDispositionTest(unittest.TestCase):
    def test_all_baseline_call_sites_have_a_decision(self):
        data = subject.generate()
        self.assertEqual(len(data["items"]), 78)
        self.assertEqual(sum(row["kind"] == "value_injections" for row in data["items"]), 70)
        self.assertEqual(sum(row["kind"] == "property_lookups" for row in data["items"]), 8)
        self.assertTrue(all(row["phase"] in {"P1", "P3", "P4", "P5", "P6", "P7", "P8"} or row["status"] == "deferred" for row in data["items"]))

    def test_unknown_or_unused_source_rule_fails(self):
        for rules in [dict(subject.RULES, **{"Unreviewed.java": ("P1", "T104", "invalid")}),
                      {key: value for key, value in subject.RULES.items() if key != "SecurityConfig.java"}]:
            with patch.object(subject, "RULES", rules), self.assertRaises(ValueError):
                subject.generate()

    def test_unreviewed_dynamic_keys_fail(self):
        for keys in [{},
                     dict(subject.DYNAMIC_KEYS, **{"ModelConfigService.java": ["MISSING_SYNTHETIC_KEY"]}),
                     dict(subject.DYNAMIC_KEYS, **{"EmbeddingCacheConfig.java": subject.DYNAMIC_KEYS["EmbeddingCacheConfig.java"][:-1]}),
                     dict(subject.DYNAMIC_KEYS, **{"RedisCandidateCache.java": subject.DYNAMIC_KEYS["RedisCandidateCache.java"][1:]}),
                     dict(subject.DYNAMIC_KEYS, **{"ModelConfigService.java": subject.DYNAMIC_KEYS["ModelConfigService.java"][:-1]})]:
            with patch.object(subject, "DYNAMIC_KEYS", keys), self.assertRaises(ValueError):
                subject.generate()

    def test_missing_p1_evidence_fails(self):
        with patch.object(subject, "P1_EVIDENCE", dict(subject.P1_EVIDENCE, T104=["backend-go/nonexistent-evidence"])), self.assertRaises(ValueError):
            subject.generate()

    def test_p3_consumers_have_wiring_evidence(self):
        rows = [row for row in subject.generate()["items"] if row["phase"] == "P3"]
        self.assertEqual(len(rows), 14)
        self.assertTrue(all(row["status"] == "wired" for row in rows))
        with patch.object(subject, "P3_EVIDENCE", dict(subject.P3_EVIDENCE, T304=["backend-go/missing-p3-evidence"])), self.assertRaises(ValueError):
            subject.generate()

    def test_p4_consumers_have_wiring_evidence(self):
        rows = [row for row in subject.generate()["items"] if row["phase"] == "P4"]
        self.assertEqual(len(rows), 13)
        self.assertTrue(all(row["status"] == "wired" for row in rows))
        with patch.object(subject, "P4_EVIDENCE", dict(subject.P4_EVIDENCE, T408=["backend-go/missing-p4-evidence"])), self.assertRaises(ValueError):
            subject.generate()

    def test_p5_settings_are_wired(self):
        rows = [row for row in subject.generate()["items"] if row["phase"] == "P5"]
        self.assertEqual(len(rows), 9)
        self.assertEqual({row["task"] for row in rows}, {"T501", "T505", "T506"})
        self.assertTrue(all(row["status"] == "wired" for row in rows))

    def test_p6_settings_are_wired_and_missing_evidence_fails(self):
        rows = [row for row in subject.generate()["items"] if row["phase"] == "P6"]
        self.assertEqual(len(rows), 20)
        self.assertEqual({row["task"] for row in rows}, {"T601", "T602", "T603", "T604", "T605", "T606", "T607"})
        self.assertTrue(all(row["status"] == "wired" for row in rows))
        with patch.object(subject, "P6_EVIDENCE", dict(subject.P6_EVIDENCE, T607=["backend-go/missing-p6-evidence"])), self.assertRaises(ValueError):
            subject.generate()

    def test_p7_independent_server_settings_and_missing_evidence(self):
        rows = [row for row in subject.generate()["items"] if row["phase"] == "P7"]
        self.assertEqual(len(rows), 4)
        self.assertEqual({row["task"] for row in rows}, {"T702"})
        self.assertTrue(all(row["status"] == "wired" for row in rows))
        with patch.object(subject, "P7_EVIDENCE", {"T702": ["backend-go/missing-p7-evidence"]}), self.assertRaises(ValueError):
            subject.generate()

    def test_p8_retention_wired_and_missing_evidence_fails(self):
        rows = [row for row in subject.generate()["items"] if row["phase"] == "P8"]
        self.assertEqual(len(rows), 2)
        self.assertTrue(all(row["status"] == "wired" and row["task"] == "T801" for row in rows))
        with patch.object(subject, "P8_EVIDENCE", {"T801": ["backend-go/missing-p8-evidence"]}), self.assertRaises(ValueError):
            subject.generate()
