import http.cookiejar
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import run_go_capacity_tests as subject


class CapacityTest(unittest.TestCase):
    def test_explicit_workload_is_bounded_before_allocating_resources(self):
        subject.validate_load(8, 240, 500)
        for values in ((0, 240, 500), (33, 240, 500), (8, 7, 500),
                       (8, 10001, 500), (8, 240, 0), (8, 240, 5001), (True, 240, 500)):
            with self.assertRaises(ValueError):
                subject.validate_load(*values)

    def test_percentiles_use_nearest_rank_and_empty_samples_are_not_zero(self):
        self.assertEqual(subject.percentile(list(range(20)), .95), 18)
        self.assertEqual(subject.percentile([3], .99), 3)
        self.assertIsNone(subject.percentile([], .95))
        self.assertEqual(subject.digest(dict(a=1, b=2)), subject.digest(dict(b=2, a=1)))
        java, go = dict(createdAt="2026-11-05T00:00:00Z"), dict(createdAt="2026-11-05T00:00:00.000000Z")
        self.assertNotEqual(subject.digest(java), subject.digest(go))
        self.assertEqual(subject.digest(subject.normalize(java)), subject.digest(subject.normalize(go)))

    def test_worker_has_independent_cookie_storage_without_relogin(self):
        original = subject.Client(dict(port=1234))
        cookie = http.cookiejar.Cookie(0, "SESSION", "test-only-cookie", None, False, "127.0.0.1",
            False, False, "/", True, False, None, True, None, None, {}, False)
        original.jar.set_cookie(cookie)
        original.csrf = "test-only-csrf"
        worker = subject.clone_client(original, dict(port=1234))
        self.assertIsNot(worker.jar, original.jar)
        self.assertEqual(next(iter(worker.jar)).value, cookie.value)
        self.assertIsNot(next(iter(worker.jar)), cookie)
        worker.jar.clear()
        self.assertEqual(len(original.jar), 1)
        self.assertEqual(worker.csrf, original.csrf)

    def test_concurrent_requests_retain_all_failures_without_private_error_text(self):
        client = Mock()
        client.request.side_effect = lambda path: dict(path=path)
        worker = Mock()
        def request(path):
            if path == subject.PATHS[2]:
                raise TimeoutError("private endpoint and credential must not be reported")
            if path == subject.PATHS[0]:
                return dict(path="corrupted")
            return dict(path=path)
        worker.request.side_effect = request
        with (patch.object(subject, "clone_client", return_value=worker) as clone,
              patch.object(subject.subprocess, "run", return_value=SimpleNamespace(stdout="123"))):
            result = subject.measure(client, dict(pid=1234), 3, 12)
        self.assertEqual(clone.call_count, 3)
        self.assertEqual(worker.request.call_count, 12)
        self.assertEqual(result["requests"], 12)
        self.assertEqual(result["failures"], 8)
        self.assertEqual(result["failure_types"], ["AssertionError", "TimeoutError"])
        self.assertEqual([row["requests"] for row in result["endpoints"].values()], [4, 4, 4])
        self.assertNotIn("private endpoint", str(result))


if __name__ == "__main__":
    unittest.main()
