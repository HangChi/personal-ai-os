import unittest
import go_monitoring_assets as subject


class GoMonitoringTest(unittest.TestCase):
    def test_all_twenty_panels_preserved_with_real_go_runtime_metrics(self):
        model = subject.generate()
        self.assertEqual(len(model["panels"]), 20)
        self.assertEqual({panel["id"] for panel in model["panels"]}, set(range(1, 21)))
        self.assertEqual({panel["datasource"]["uid"] for panel in model["panels"]}, {"personalos-go-prometheus"})
        expressions = [target["expr"] for panel in model["panels"] for target in panel["targets"]]
        self.assertFalse(any("jvm_" in expression or "hikaricp_" in expression for expression in expressions))
        self.assertIn("go_memory_used_bytes", expressions)
        self.assertTrue(any("personalos_go_database_connections" in expression for expression in expressions))
        self.assertTrue(any("stage" in expression and "personalos_knowledge_recall_seconds_bucket" in expression for expression in expressions))


if __name__ == "__main__":
    unittest.main()
