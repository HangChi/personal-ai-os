import unittest

from go_test_environment import cache_environment


class GoTestEnvironmentTest(unittest.TestCase):
    def test_only_explicit_cache_paths_are_read(self):
        result = cache_environment({"GO_TEST_GOCACHE": "/tmp/build", "GO_TEST_GOPATH": "/tmp/modules", "POSTGRES_PASSWORD": "unrelated"})
        self.assertEqual(result, {"GOCACHE": "/tmp/build", "GOPATH": "/tmp/modules"})
        self.assertEqual(cache_environment({"GOPATH": "/unrelated"})["GOPATH"], "/private/tmp/personal-ai-os-go")

    def test_invalid_paths_are_rejected(self):
        for name in ("GO_TEST_GOCACHE", "GO_TEST_GOPATH"):
            for value in ("", "relative", "/tmp/invalid\x00path"):
                with self.subTest(name=name, value=value), self.assertRaises(ValueError):
                    cache_environment({name: value})
