"""Run the frozen conflict scenario body with IO bound to owned test resources."""
import ast
from contextlib import contextmanager
import hashlib
import json
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time
from types import SimpleNamespace
import urllib.request
from urllib.parse import quote

from go_runtime import private_json

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "scripts/eval/rag-live-conflicts.py"
INGESTION_SOURCE = "scripts/eval/rag-live-ingestion.py"


@contextmanager
def owned_search():
    image = subprocess.check_output(["docker", "image", "inspect", "opensearchproject/opensearch:3.9.0",
        "--format", "{{index .RepoDigests 0}}"], text=True, timeout=10).strip()
    if not re.fullmatch(r"opensearchproject/opensearch@sha256:[0-9a-f]{64}", image):
        raise ValueError("Live conflict search fixture requires the locally pinned image")
    container = subprocess.check_output(["docker", "run", "--detach", "--rm", "--name",
        "personalos-p8-live-search-" + secrets.token_hex(6), "--label", "personalos.go-p8-live-search=true",
        "--memory", "1536m", "--tmpfs", "/usr/share/opensearch/data:rw,nosuid,uid=1000,gid=1000,size=512m",
        "--publish", "127.0.0.1::9200", "--env", "discovery.type=single-node", "--env",
        "DISABLE_SECURITY_PLUGIN=true", "--env", "DISABLE_INSTALL_DEMO_CONFIG=true", "--env",
        "OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m", image], text=True, timeout=120).strip()
    if not re.fullmatch(r"[0-9a-f]{64}", container):
        raise ValueError("Invalid owned search container identity")
    try:
        template = ('{"owned":{{json (index .Config.Labels "personalos.go-p8-live-search")}},'
                    '"bindings":{{json (index .NetworkSettings.Ports "9200/tcp")}},'
                    '"mounts":{{json .Mounts}}}')
        info = json.loads(subprocess.check_output(["docker", "inspect", "--format", template, container], text=True, timeout=10))
        bindings = info.get("bindings", [])
        if (info.get("owned") != "true" or len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1"
                or any(mount["Type"] != "tmpfs" for mount in info.get("mounts", []))):
            raise ValueError("Live conflict search fixture isolation violated")
        port = bindings[0].get("HostPort", "")
        if not port.isascii() or not port.isdecimal() or not 0 < int(port) < 65536:
            raise ValueError("Live conflict search fixture port invalid")
        endpoint = "http://127.0.0.1:" + bindings[0]["HostPort"]
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        deadline = time.monotonic() + 120
        while True:
            try:
                with opener.open(endpoint + "/_cluster/health", timeout=3) as response:
                    if json.load(response).get("status") in ("yellow", "green"):
                        break
            except (OSError, ValueError):
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError("Owned conflict search startup exceeded 120 seconds")
            time.sleep(1)
        yield dict(OPENSEARCH_ENABLED="true", OPENSEARCH_ENDPOINT=endpoint,
                   OPENSEARCH_INDEX="p8-live-conflicts-" + secrets.token_hex(8)), image
    finally:
        subprocess.run(["docker", "rm", "--force", container], capture_output=True, check=True, timeout=30)


def original_program(source=SOURCE):
    if source not in (SOURCE, INGESTION_SOURCE):
        raise ValueError("Unknown original policy evaluation source")
    inventory = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())
    expected = next(row["sha256"] for row in inventory["files"] if row["path"] == source)
    raw = (ROOT / source).read_bytes()
    if hashlib.sha256(raw).hexdigest() != expected:
        raise ValueError("Original conflict evaluation differs from fixed baseline")
    nodes = ast.parse(raw, filename=source).body
    # Keep the exact scenario, finally cleanup, and original final assertion; exclude shared-runtime setup.
    selected = [node for node in nodes if isinstance(node, ast.Try)]
    if len(selected) != 1 or not isinstance(nodes[-1], ast.If):
        raise ValueError("Original conflict scenario structure changed")
    return compile(ast.Module(body=selected + [nodes[-1]], type_ignores=[]), source, "exec"), expected


class OwnedConflictClient:
    def __init__(self, client, space, model, private_directory, verify_answer=None, ingestion=False):
        self.client, self.space, self.model = client, space, model
        self.directory = private_directory
        self.documents, self.calls = [], []
        self.verify_answer = verify_answer
        self.ingestion, self.retry_refused = ingestion, False

    def upload(self, path, title):
        prefix = "录入审查合成政策 " if self.ingestion else "合成差旅制度 "
        filename_prefix = "ingestion-" if self.ingestion else "policy-"
        if title not in (prefix + "A", prefix + "B") or len(self.documents) >= 2:
            raise ValueError("Conflict upload escaped its frozen fixture")
        if path.is_symlink() or not path.is_file() or not path.name.startswith(filename_prefix) or path.stat().st_size > 12000:
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
        if self.ingestion and method == "GET" and path in ("/knowledge/governance/sources", "/knowledge/governance/conflicts"):
            result = self.client.request(path)
            private_json(self.directory / (path.rsplit("/", 1)[-1] + ".json"), result)
            return result
        if self.ingestion and method == "POST" and path.endswith("/review"):
            self.retry_refused = True
            raise ValueError("Unavailable live ingestion review is retained; manual retries are disabled")
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


def execute_ingestion(client, space, model, private_directory, report, restart, candidate_path):
    program, digest = original_program(INGESTION_SOURCE)
    adapter = OwnedConflictClient(client, space, model, private_directory, ingestion=True)

    def save(path, data):
        if path.parent != private_directory:
            raise ValueError("Original ingestion report escaped its private directory")
        private_json(path, data)

    def restart_enhanced(path, enabled):
        if path != candidate_path or enabled is not True:
            raise ValueError("Ingestion restart escaped its owned candidate")
        adapter.client = restart()

    def authenticated_client(base):
        if base != "http://127.0.0.1:18082/api/v1":
            raise ValueError("Unexpected original ingestion client alias")
        # The restart callback has already authenticated this owned process with CSRF.
        return SimpleNamespace(request=adapter.request, login=lambda: None)

    namespace = dict(client=adapter, space=space, created=[], result={}, json=json, time=time,
                     tempfile=tempfile, Path=Path,
                     state=dict(jar=str(candidate_path), processes=[dict(name="enhanced", jar=str(candidate_path))]),
                     m=SimpleNamespace(WORK=private_directory, private_json=save,
                                       restart_enhanced=restart_enhanced, Client=authenticated_client))
    try:
        exec(program, namespace)
        if namespace["result"].get("retriedSources") != 0 or adapter.retry_refused:
            raise AssertionError("Live ingestion acceptance must not rely on review retries")
    finally:
        result = namespace["result"]
        report["sources_sha256"] = {INGESTION_SOURCE: digest}
        report["ingestion_review"] = {key: result[key] for key in
            ("states", "openConflicts", "passed", "retriedSources") if key in result}
        report["manual_review_retry_refused"] = adapter.retry_refused
