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
        self.assertTrue(all(row["phase"] == "P1" or row["status"] == "deferred" for row in data["items"]))

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
