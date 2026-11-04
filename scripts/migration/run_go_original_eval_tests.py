#!/usr/bin/env python3
"""Frozen project RAG/Agent cases on disposable authenticated TLS runtimes."""
import argparse
from contextlib import ExitStack
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
from types import SimpleNamespace
from urllib.parse import quote
import urllib.request

from go_live_conflicts import owned_search
from go_real_agent_model import AgentModel
from go_real_model import ModelFailure, RealModel, load_config, pooled_transport, serve
from go_runtime import private_json
from go_test_environment import cache_environment
from run_go_cutover_tests import Client
from run_go_demo_seed_tests import OwnedDatabase
from run_go_paired_eval_tests import require_quiescent
import run_go_e2e_tests as processes
from run_go_postgres_tests import IMAGE, ROOT, run_package

SPEC = ROOT / "specs/033-go-rewrite"


def frozen_module(name):
    if name not in ("rag-live-eval.py", "agent-eval.py"):
        raise ValueError("Unknown frozen evaluation")
    inventory = json.loads((SPEC / "inventory.json").read_text())
    expected = {row["path"]: row["sha256"] for row in inventory["files"]}
    sources = ["scripts/eval/" + name]
    if name == "rag-live-eval.py":
        sources.append("scripts/eval/rag-live-cases.json")
    hashes = {}
    for source in sources:
        hashes[source] = hashlib.sha256((ROOT / source).read_bytes()).hexdigest()
        if hashes[source] != expected[source]:
            raise ValueError("Original evaluation source differs from fixed baseline")
    spec = importlib.util.spec_from_file_location("p8_frozen_" + name.replace("-", "_"), ROOT / sources[0])
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, hashes


class AgentClient:
    def __init__(self, client, model, directory):
        self.client, self.model, self.directory = client, model, directory
        self.turn = 0

    def api(self, method, path, body=None):
        if self.model.snapshot()["failures"] and method == "POST" and path == "/chat/sessions":
            raise ModelFailure("AGENT_UPSTREAM_FAILURE_NO_RETRY")
        status = 201 if method == "POST" and path in ("/tasks", "/chat/sessions") else 200
        return self.client.request(path, method, body, status=status)

    def chat(self, session, prompt):
        if self.model.snapshot()["failures"]:
            raise ModelFailure("AGENT_UPSTREAM_FAILURE_NO_RETRY")
        request = urllib.request.Request(self.client.base + "/chat/sessions/" + session + "/stream",
            json.dumps({"content": prompt}).encode(),
            {"Content-Type": "application/json", "Accept": "text/event-stream",
             "X-CSRF-TOKEN": self.client.csrf}, method="POST")
        events, event, data = [], "", []
        with self.client.opener.open(request, timeout=260) as response:
            for raw in response:
                line = raw.decode().rstrip("\r\n")
                if not line:
                    if data:
                        events.append((event, json.loads("\n".join(data))))
                        if event in ("done", "error"):
                            self.turn += 1
                            private_json(self.directory / ("agent-turn-" + str(self.turn) + ".json"), events)
                            print("Original Agent turn " + str(self.turn) + ": " + event, flush=True)
                            return events
                    event, data = "", []
                elif line.startswith("event:"):
                    event = line[6:].strip()
                elif line.startswith("data:"):
                    data.append(line[5:].lstrip())
        raise AssertionError("Original Agent stream ended without a terminal event")


def environment(fixture, directory, relay):
    values = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
    values.update({key: fixture["GO_TEST_" + key] for key in
                   ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
    values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
        AUTH_ENABLED="true", AUTH_USERNAME="p8-original-eval", AUTH_PASSWORD=secrets.token_urlsafe(24),
        APP_SECURITY_CSRF_ENABLED="true", APP_RATELIMIT_ENABLED="false",
        AI_CHAT_UPLOAD_DIR=str(directory / "uploads"), APP_CONTEXT_SUMMARY_ENABLED="false",
        APP_CONTEXT_AUTOMEMORY_ENABLED="false", APP_MEMORY_BACKFILL_ENABLED="false",
        AI_CALENDAR_SCAN_ENABLED="false", MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false",
        AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai",
        APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED="false", EMBEDDING_CACHE_ENABLED="false",
        RAG_CANDIDATE_CACHE_ENABLED="false", AI_KNOWLEDGE_HYBRID_ENABLED="true")
    values.update(relay)
    return values


def project_cases(module, database, binary, values, directory, model, report):
    records = module.cases()
    if len(records) != 18:
        raise AssertionError("Original project case coverage changed")
    sources = sorted({source for record in records for source in record["sources"]})
    snapshots = {source: (ROOT / source).read_bytes() for source in sources}
    documents, clients, entries = {}, {}, []
    with owned_search() as (search_values, image), ExitStack() as stack:
        report["owned_opensearch_image"] = image
        for mode in ("baseline", "enhanced"):
            runtime = directory / mode
            runtime.mkdir()
            settings = dict(values, RAG_ENABLED=str(mode == "enhanced").lower())
            settings.update(search_values if mode == "enhanced" else {"OPENSEARCH_ENABLED": "false"})
            state = stack.enter_context(processes.candidate(runtime, binary, settings))
            client = Client(state, timeout=180)
            client.login(settings)
            clients[client.base] = client
            entries.append(dict(name=mode, port=state["port"], management=state["port"]))
        baseline = clients["http://127.0.0.1:" + str(entries[0]["port"]) + "/api/v1"]
        for source, content in snapshots.items():
            doc = baseline.upload("/knowledge/documents?title=" + quote(source, safe=""),
                                  Path(source).name, content, status=201)
            if doc.get("title") != source or doc.get("chunkCount", 0) <= 0:
                raise AssertionError("Original project document ingestion failed")
            documents[source] = doc["id"]
            print("Project corpus uploaded " + str(len(documents)) + "/" + str(len(sources)), flush=True)
        enhanced = clients["http://127.0.0.1:" + str(entries[1]["port"]) + "/api/v1"]
        deadline = time.monotonic() + 180
        while enhanced.request("/knowledge/search-index")["pending"]:
            if time.monotonic() >= deadline:
                raise TimeoutError("Owned project search projection deadline")
            time.sleep(1)
        stored_rows = json.loads(database.sql("SELECT json_agg(x) FROM (SELECT metadata->>'document_id' AS id,"
            "(metadata->>'chunk_index')::int AS index,encode(sha256(convert_to(content,'UTF8')),'hex') AS sha256 FROM vector_store) x;"))
        stored = {(row["id"], row["index"]): row["sha256"] for row in stored_rows}

        class BoundClient:
            def __init__(self, base):
                if base not in clients:
                    raise ValueError("Original project evaluator escaped owned clients")
                self.client = clients[base]

            def login(self):
                pass  # Already authenticated with the owned runtime's generated credential.

            def request(self, path, method="GET", body=None):
                if path != "/knowledge/search" or method != "POST" or body.get("topK") != 5:
                    raise ValueError("Original project evaluator escaped its read-only search contract")
                if model.snapshot()["failures"]:
                    raise ModelFailure("PROJECT_UPSTREAM_FAILURE_NO_RETRY")
                result = self.client.request(path, method, body)
                for hit in result["hits"]:
                    if (hit["documentId"] not in documents.values()
                            or stored.get((hit["documentId"], hit["chunkIndex"])) != hashlib.sha256(hit["content"].encode()).hexdigest()):
                        raise AssertionError("Original project retrieval escaped persisted corpus chunks")
                return result

        state = dict(processes=entries, documents=documents,
                     commit=subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                     corpusHashes={key: hashlib.sha256(content).hexdigest() for key, content in snapshots.items()})
        module.STATE = SimpleNamespace(read_text=lambda: json.dumps(state))
        module.WORK = directory
        module.Client = BoundClient
        module.counters = lambda port: model.snapshot()["usage"]
        captured = []
        module.private_json = lambda path, value: captured.append(value)
        module.evaluate(0)
        if len(captured) != 1 or len(captured[0]["rows"]) != 36:
            raise AssertionError("Original project evaluator did not execute all 18 cases in both modes")
        result = captured[0]
        report.update(evaluation=result, token_counter_origin="owned real-model gateway; no concurrent calls in this runner",
                      retrieved_chunks_match_database=True)
        report["passed"] = sum(row["rank"] is not None and row["status"] in ("READY", "LEGACY")
                                and not row.get("warnings") for row in result["rows"])
        report["total"] = 36
    report["owned_opensearch_removed"] = True


def exercise(container, fixture, backend, binary, model, relay, directory, mode, report):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    directory.mkdir()
    values = environment(fixture, directory, relay)
    processes.BACKEND = backend
    module, hashes = frozen_module("agent-eval.py" if mode == "agent" else "rag-live-eval.py")
    report.update(backend=backend, sources_sha256=hashes, result="IN_PROGRESS")
    try:
        if mode == "project":
            project_cases(module, database, binary, values, directory, model, report)
        else:
            values.update(RAG_ENABLED="false", OPENSEARCH_ENABLED="false", APP_CONTEXT_SUMMARY_ENABLED="true")
            with processes.candidate(directory, binary, values) as state:
                client = Client(state, timeout=180)
                client.login(values)
                rows = module.evaluate(AgentClient(client, model, directory), True)
                report.update(cases=rows, passed=sum(row["passed"] for row in rows), total=len(rows),
                              long_history_enabled=True, rolling_summary_enabled=True, upstream_streaming_verified=False,
                              local_sse_origin="buffered real upstream completions including original tool calls and usage")
                if len(rows) != 7:
                    raise AssertionError("Original Agent evaluation coverage or cleanup failed")
        report["result"] = "PASS" if report["passed"] == report["total"] else "FAIL"
    except Exception as error:
        report.update(result="FAIL", error_type=type(error).__name__)
        raise
    finally:
        require_quiescent(database)
        report.update(processes_stopped=True, database_connections_after_stop=0)
        for path in directory.rglob("*.log"):
            if model.config.api_key.encode() in path.read_bytes():
                raise AssertionError("Real model credential leaked into an owned runtime log")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-env-file", type=Path, required=True)
    parser.add_argument("--mode", choices=("project", "agent"), required=True)
    parser.add_argument("--backend", choices=("go", "java", "both"), default="both")
    args = parser.parse_args()
    config = load_config(args.model_env_file)
    directory = Path(tempfile.mkdtemp(prefix="personalos-p8-original-evidence-")).resolve()
    path = SPEC / ("p8-original-" + args.mode + "-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    report = dict(result="IN_PROGRESS", mode=args.mode, started_at=datetime.now(timezone.utc).isoformat(),
                  model=config.metadata(), backends={}, private_evidence=str(directory),
                  release_gates_changed=False, factual_quality_manually_approved=False)
    model, code = None, 1
    try:
        with pooled_transport(config) as opener, tempfile.TemporaryDirectory(prefix="personalos-p8-original-build-") as temporary:
            if args.mode == "agent":
                tools = set()
                for name in ("tool-definitions.json", "tool-definitions-p5.json", "tool-definitions-p6.json"):
                    tools.update(row["name"] for row in json.loads((ROOT / "backend-go/internal/business/agent" / name).read_text()))
                model = AgentModel(config, tools, opener)
            else:
                model = RealModel(config, opener)
            model.max_calls, model.max_chat_calls, model.max_characters = 1600, 400, 30_000_000
            binary = Path(temporary) / "personalos"
            build = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
            build.update(cache_environment(), GOPROXY="off", GOTOOLCHAIN="local", GOMAXPROCS="4")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"],
                           cwd=ROOT / "backend-go", env=build, check=True, timeout=120)
            with serve(model) as relay:
                for backend in (("go", "java") if args.backend == "both" else (args.backend,)):
                    progress = report["backends"].setdefault(backend, {})
                    run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                        exercise=lambda container, fixture: exercise(container, fixture, backend, binary, model, relay,
                            directory / backend, args.mode, progress))
                    if model.snapshot()["failures"]:
                        raise ModelFailure("ORIGINAL_EVAL_UPSTREAM_FAILURE_NO_RETRY")
            report["result"] = "PASS" if all(row["result"] == "PASS" for row in report["backends"].values()) else "FAIL"
            code = 0 if report["result"] == "PASS" else 1
    except Exception as error:
        report.update(result="FAIL", error_type=type(error).__name__)
        if isinstance(error, (AssertionError, ModelFailure)):
            report["failure"] = str(error)
        print("Original evaluation failed; see redacted report", flush=True)
    finally:
        report.update(finished_at=datetime.now(timezone.utc).isoformat(),
                      real_model_requests=model.snapshot() if model else {})
        with path.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print("Original evaluation report: " + str(path), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
