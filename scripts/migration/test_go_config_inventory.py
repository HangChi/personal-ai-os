"""配置注解索引器边界；真实Java/YAML覆盖由默认重生成核对。"""

import json
from pathlib import Path
import unittest

from go_config_inventory import configuration_class, property_lookups, value_annotations


class ConfigurationIndexTest(unittest.TestCase):
    def test_comments_literals_and_real_parameter_injections(self):
        source = '''package fixture;
        // @Value("${fake:secret}")
        class Example {
          String comment = "@Value(\\\"fake\\\")";
          Example(@Value("${first:}") String first,
                  @org.springframework.beans.factory.annotation.Value("${second:10s}") String second) {}
        }'''
        rows = value_annotations(source)
        self.assertEqual([row["expression"] for row in rows], ["${first:}", "${second:10s}"])
        self.assertEqual([row["line"] for row in rows], [5, 6])

    def test_unsupported_expressions_fail_instead_of_disappearing(self):
        with self.assertRaises(ValueError):
            value_annotations('class X { @Value("${first}" + "${second}") String value; }')

    def test_configuration_record_and_documentation_lookalikes(self):
        source = '''package fixture;
        /** @ConfigurationProperties(prefix="fake") */
        @ConfigurationProperties(prefix = "real")
        public record Settings(boolean enabled, java.util.Map<String, Server> servers) {
          record Server(String command) {}
        }'''
        self.assertEqual(configuration_class(source), "fixture.Settings")
        self.assertIsNone(configuration_class('package fixture; class X { String s="@ConfigurationProperties"; }'))

    def test_unsupported_mutable_config_class_fails_closed(self):
        with self.assertRaises(ValueError):
            configuration_class('package fixture; @ConfigurationProperties("x") class X {}')
        with self.assertRaises(ValueError):
            configuration_class('package fixture; @ConfigurationProperties("x") class X { record Nested(int value){} }')

    def test_property_lookup_dynamic_keys_are_not_lost(self):
        source = '''class X {
          // env.getProperty("fake")
          String example = "env.getProperty(fake)";
          Object a = env.getProperty("app.cache.ttl", Duration.class, Duration.ofMinutes(10));
          Object b = environment.getProperty(key, "");
        }'''
        rows = property_lookups(source)
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[0]["literal_key"], "app.cache.ttl")
        self.assertIn("Duration.ofMinutes(10)", rows[0]["arguments"])
        self.assertTrue(rows[1]["dynamic_key"])
        self.assertIsNone(rows[1]["literal_key"])

    def test_real_cookie_resolution_gap_stays_explicit(self):
        data = json.loads((Path(__file__).resolve().parents[2] / "specs/033-go-rewrite/config-inventory.json").read_text())
        cases = {row["scenario"]: row for row in data["cookie_resolution_cases"]}
        self.assertTrue(cases["prod-cookie-alias"]["yaml_cookie_secure"])
        self.assertFalse(cases["prod-cookie-alias"]["guard_cookie_secure"])
        self.assertEqual(cases["prod-cookie-alias"]["production_guard"], "生产配置必须启用 SESSION_COOKIE_SECURE=true")
        self.assertTrue(cases["prod-canonical-env"]["guard_cookie_secure"])
        self.assertEqual(cases["prod-canonical-env"]["production_guard"], "accepted")


if __name__ == "__main__":
    unittest.main()
