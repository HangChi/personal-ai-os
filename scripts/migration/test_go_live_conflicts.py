import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import Mock

from go_live_conflicts import OwnedConflictClient, execute, public_cases


class LiveConflictTest(unittest.TestCase):
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
