"""Run the frozen conflict scenario body with IO bound to owned test resources."""
import ast
import hashlib
import json
from pathlib import Path
import tempfile
import time
from types import SimpleNamespace
from urllib.parse import quote

from go_runtime import private_json

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "scripts/eval/rag-live-conflicts.py"


def original_program():
    inventory = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())
    expected = next(row["sha256"] for row in inventory["files"] if row["path"] == SOURCE)
    raw = (ROOT / SOURCE).read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError("Original conflict evaluation differs from fixed baseline")
    nodes = ast.parse(raw, filename=SOURCE).body
    # Keep the exact scenario, finally cleanup, and original final assertion; exclude shared-runtime setup.
    selected = [node for node in nodes if isinstance(node, ast.Try)]
    if len(selected) != 1 or not isinstance(nodes[-1], ast.If):
        raise ValueError("Original conflict scenario structure changed")
    return compile(ast.Module(body=selected + [nodes[-1]], type_ignores=[]), SOURCE, "exec"), expected


class OwnedConflictClient:
    def __init__(self, client, space, model, private_directory, verify_answer=None):
        self.client, self.space, self.model = client, space, model
        self.directory = private_directory
        self.documents, self.calls = [], []
        self.verify_answer = verify_answer

    def upload(self, path, title):
        if title not in ("合成差旅制度 A", "合成差旅制度 B") or len(self.documents) >= 2:
            raise ValueError("Conflict upload escaped its frozen fixture")
        if path.is_symlink() or not path.is_file() or not path.name.startswith("policy-") or path.stat().st_size > 12000:
            raise ValueError("Conflict upload is not its bounded generated policy")
        data = path.read_bytes()
        if not 350 < len(data.decode()) < 3000:
            raise ValueError("Conflict upload is not its bounded generated policy")
        document = self.client.upload("/knowledge/documents?title=" + quote(title, safe=""), path.name, data, status=201)
        if document.get("title") != title or document.get("id") in self.documents:
            raise AssertionError("Conflict policy title or identity changed")
        self.documents.append(document["id"])
        return document

    def request(self, path, method="GET", body=None):
        if path == "/knowledge/search-index" and method == "GET":
            return self.client.request(path)
        if (method == "PUT" and path in {"/knowledge/governance/sources/" + identity for identity in self.documents}
                and isinstance(body, dict) and body.get("spaceId") == self.space["id"]):
            return self.client.request(path, method, body)
        if method == "DELETE" and path in {"/knowledge/documents/" + identity for identity in self.documents}:
            return self.client.request(path, method)
        if path == "/knowledge/ask" and method == "POST" and isinstance(body, dict) and body.get("topK") == 1:
            before = self.model.snapshot()["calls"].get("chat", 0)
            result = self.client.request(path, method, body)
            private_json(self.directory / ("answer-" + str(len(self.calls) + 1) + ".json"), result)
            self.calls.append(self.model.snapshot()["calls"].get("chat", 0) - before)
            if self.verify_answer:
                self.verify_answer(result, self.calls[-1])
            return result
        raise ValueError("Conflict scenario attempted an unowned HTTP operation")


def public_cases(rows, calls):
    return [dict(case=row["case"], expected=row["expected"], status=row["status"], answered=row["answered"],
                 citations=row["citations"], hasBothSources=row["hasBothSources"], durationMs=row["durationMs"],
                 stages=[{key: stage[key] for key in ("name", "status", "durationMs") if key in stage}
                         for stage in row["stages"]],
                 actual_chat_calls=calls[index] if index < len(calls) else None)
            for index, row in enumerate(rows)]


def execute(client, space, model, private_directory, report, verify_answer=None):
    program, digest = original_program()
    adapter = OwnedConflictClient(client, space, model, private_directory, verify_answer)
    rows = []

    def save(path, data):
        if path.parent != private_directory:
            raise ValueError("Original conflict report escaped its private directory")
        private_json(path, data)

    namespace = dict(client=adapter, space=space, created=[], rows=rows, json=json, time=time,
                     tempfile=tempfile, pathlib=SimpleNamespace(Path=Path),
                     state=dict(createdAt="owned"), m=SimpleNamespace(WORK=private_directory, private_json=save))
    try:
        exec(program, namespace)
        if len(rows) != 3 or len(adapter.calls) != 3:
            raise AssertionError("Original conflict scenario coverage changed")
    finally:
        report["sources_sha256"] = {SOURCE: digest}
        report["cases"] = public_cases(rows, adapter.calls)
