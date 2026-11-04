import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock, patch

from go_live_conflicts import OwnedConflictClient, execute, execute_ingestion, owned_search, public_cases


class LiveConflictTest(unittest.TestCase):
    def test_original_ingestion_restarts_owned_candidate_checks_both_sources_and_keeps_deadline(self):
        for unavailable in (False, True):
            client, restarted, restart = Mock(), Mock(), Mock()
            restart.return_value = restarted
            ids = iter(("owned-a", "owned-b"))
            client.upload.side_effect = lambda path, name, data, status: dict(
                id=next(ids), title="录入审查合成政策 " + ("A" if name == "ingestion-A.md" else "B"))
            restarted.request.side_effect = lambda path, method="GET": (
                [dict(documentId=identity, source=dict(reviewState="UNAVAILABLE" if unavailable else "CHECKED_CANDIDATES"))
                 for identity in ("owned-a", "owned-b")] if path.endswith("/sources") else
                [dict(leftId="owned-a", rightId="owned-b", status="OPEN")])
            report = {}
            with tempfile.TemporaryDirectory() as temporary:
                arguments = (client, {"id": "space"}, Mock(), Path(temporary), report, restart, Path("/owned/candidate"))
                if unavailable:
                    with self.assertRaisesRegex(ValueError, "manual retries are disabled"): execute_ingestion(*arguments)
                else:
                    execute_ingestion(*arguments)
                    self.assertTrue(report["ingestion_review"]["passed"])
                    self.assertEqual(report["ingestion_review"]["retriedSources"], 0)
                self.assertEqual(report["manual_review_retry_refused"], unavailable)
                restart.assert_called_once_with()
            deleted = [call.args for call in restarted.request.call_args_list if len(call.args) > 1]
            self.assertEqual(deleted, [("/knowledge/documents/owned-a", "DELETE"), ("/knowledge/documents/owned-b", "DELETE")])

    def test_owned_search_uses_pinned_image_bounded_ephemeral_loopback_and_always_cleans_up(self):
        info = dict(owned="true", bindings=[dict(HostIp="127.0.0.1", HostPort="12345")], mounts=[dict(Type="tmpfs")])
        with patch("go_live_conflicts.subprocess.check_output") as capture, patch("go_live_conflicts.subprocess.run") as run, \
                patch("go_live_conflicts.urllib.request.build_opener") as opener:
            capture.side_effect = ["opensearchproject/opensearch@sha256:" + "a" * 64, "b" * 64, json.dumps(info)]
            opener.return_value.open.return_value = io.BytesIO(b'{"status":"green"}')
            with owned_search() as (values, image):
                self.assertEqual(values["OPENSEARCH_ENDPOINT"], "http://127.0.0.1:12345")
                self.assertTrue(image.endswith("a" * 64))
            args = capture.call_args_list[1].args[0]
            self.assertIn("127.0.0.1::9200", args)
            self.assertIn("1536m", args)
            self.assertIn("--tmpfs", args)
            self.assertNotIn("--volume", args)
            self.assertNotIn(".Config.Env", str(capture.call_args_list))
            self.assertEqual(run.call_args.args[0], ["docker", "rm", "--force", "b" * 64])

    def test_search_refuses_unowned_public_port_volume_or_invalid_port_before_http(self):
        for change in (dict(owned="false"), dict(bindings=[dict(HostIp="0.0.0.0", HostPort="12345")]),
                       dict(mounts=[dict(Type="volume")]), dict(bindings=[dict(HostIp="127.0.0.1", HostPort="invalid")])):
            info = dict(owned="true", bindings=[dict(HostIp="127.0.0.1", HostPort="12345")], mounts=[])
            info.update(change)
            with self.subTest(change=change), patch("go_live_conflicts.subprocess.check_output") as capture, \
                    patch("go_live_conflicts.subprocess.run") as run, patch("go_live_conflicts.urllib.request.build_opener") as opener:
                capture.side_effect = ["opensearchproject/opensearch@sha256:" + "a" * 64, "b" * 64, json.dumps(info)]
                with self.assertRaises(ValueError):
                    with owned_search(): self.fail("unsafe search fixture accepted")
                opener.assert_not_called()
                run.assert_called_once()

    def test_original_three_case_expectations_and_cleanup_execute_without_runtime_setup(self):
        for mismatch in (False, True):
            client, model = Mock(), Mock()
            model.snapshot.return_value = {"calls": {"chat": 3}}
            ids = iter(("owned-a", "owned-b"))
            client.upload.side_effect = lambda path, name, data, status: dict(
                id=next(ids), title="合成差旅制度 " + ("A" if name == "policy-A.md" else "B"))
            expected = iter(("CONFLICT", "CONFLICT", "CLARIFY"))

            def request(path, method="GET", value=None):
                if path == "/knowledge/search-index": return {"pending": 0}
                if path != "/knowledge/ask": return {}
                return dict(retrieval=dict(status=next(expected), stages=[]), answered=False,
                            citations=[dict(documentId=identity) for identity in
                                       (("owned-a",) if mismatch else ("owned-a", "owned-b"))])

            client.request.side_effect = request
            report = {}
            with tempfile.TemporaryDirectory() as temporary:
                if mismatch:
                    with self.assertRaisesRegex(SystemExit, "Conflict evaluation failed"):
                        execute(client, {"id": "space"}, model, Path(temporary), report)
                else:
                    execute(client, {"id": "space"}, model, Path(temporary), report)
                self.assertEqual(len(report["cases"]), 3)
            deleted = [call.args[0] for call in client.request.call_args_list if len(call.args) > 1 and call.args[1] == "DELETE"]
            self.assertEqual(deleted, ["/knowledge/documents/owned-a", "/knowledge/documents/owned-b"])

    def test_adapter_rejects_unowned_deletes_sources_and_fixed_runtime_paths(self):
        adapter = OwnedConflictClient(Mock(), {"id": "space"}, Mock(), Path("/private/owned"))
        for path, method, body in (("/knowledge/documents/existing", "DELETE", None),
                                   ("/knowledge/governance/sources/existing", "PUT", {"spaceId": "space"}),
                                   ("/tasks", "POST", {"title": "unexpected"}),
                                   ("/knowledge/ask", "POST", {"topK": 2})):
            with self.subTest(path=path), self.assertRaises(ValueError): adapter.request(path, method, body)
        adapter.client.request.assert_not_called()

    def test_public_report_drops_raw_model_stage_details_and_never_copies_answer_text(self):
        rows = [dict(case="sameScope", expected="CONFLICT", status="CONFLICT", answered=False,
                     citations=2, hasBothSources=True, durationMs=4, answer="private answer",
                     stages=[dict(name="evidence", status="OK", durationMs=1, detail="private details")])]
        public = public_cases(rows, [3])
        self.assertNotIn("private", json.dumps(public))
        self.assertEqual(public[0]["actual_chat_calls"], 3)


if __name__ == "__main__":
    unittest.main()
