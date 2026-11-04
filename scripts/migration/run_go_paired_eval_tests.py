#!/usr/bin/env python3
"""Isolate retrieval parity on one owned index and recorded real query vectors."""
import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import secrets
import struct
import subprocess
import tempfile
import threading

from fetch_go_juejin_corpus import load_corpus
from go_eval_adapter import original_modules
from go_real_model import ModelFailure, RealModel, load_config, pooled_transport, serve
from go_test_environment import cache_environment
from java_embedding_content import embedding_hashes
from run_go_cutover_tests import Client
from run_go_demo_seed_tests import OwnedDatabase
from run_go_eval_tests import verify_ingestion
from run_go_real_eval_tests import MeasuredAdapter, comparison, corpus_coverage, metrics
import run_go_e2e_tests as processes
from run_go_postgres_tests import IMAGE, ROOT, run_package

SPEC = ROOT / "specs/033-go-rewrite"


class QueryReplay:
    """Record Java's real float32 query vectors; replay only the same ordered inputs."""
    def __init__(self, upstream, questions):
        self.upstream, self.config = upstream, upstream.config
        self.questions = tuple(questions)
        if not self.questions:
            raise ModelFailure("PAIRED_QUERIES_EMPTY")
        self.phase, self.position = "ingestion", 0
        self.tape, self.receipts = [], []
        self.lock = threading.Lock()

    def begin_recording(self):
        with self.lock:
            if self.phase != "ingestion":
                raise ModelFailure("PAIRED_RECORDING_PHASE")
            self.phase = "recording"

    def begin_replay(self):
        with self.lock:
            if self.phase != "recording" or len(self.tape) != len(self.questions):
                raise ModelFailure("PAIRED_RECORDING_INCOMPLETE")
            self.phase, self.position = "replay", 0

    def finish(self):
        with self.lock:
            if self.phase != "replay" or self.position != len(self.questions):
                raise ModelFailure("PAIRED_REPLAY_INCOMPLETE")
            return dict(recorded_real_query_vectors=len(self.tape), replayed_query_vectors=self.position,
                        ordered_input_vector_receipts_sha256=hashlib.sha256(
                            json.dumps(self.receipts, sort_keys=True).encode()).hexdigest(),
                        replay_upstream_calls=0, vector_origin="real-upstream-Java-base64-float32",
                        unexpected_input_fallback=False, synthetic_vectors=False)

    def forward(self, path, payload):
        with self.lock:
            if path not in ("/embeddings", "/v1/embeddings"):
                raise ModelFailure("PAIRED_ROUTE_REJECTED")
            if self.phase == "ingestion":
                return self.upstream.forward(path, payload)
            inputs = payload.get("input")
            inputs = [inputs] if isinstance(inputs, str) else inputs
            encoding = payload.get("encoding_format", "float")
            if (not set(payload) <= {"model", "input", "encoding_format", "dimensions"}
                    or payload.get("model") != self.config.embedding_model
                    or type(payload.get("dimensions", 1024)) is not int
                    or payload.get("dimensions", 1024) != 1024
                    or self.position >= len(self.questions)
                    or inputs != [self.questions[self.position]]
                    or encoding != ("base64" if self.phase == "recording" else "float")):
                raise ModelFailure("PAIRED_QUERY_INPUT_MISMATCH")
            if self.phase == "recording":
                result = self.upstream.forward(path, payload)
                try:
                    rows = result["data"]
                    if len(rows) != 1 or rows[0]["index"] != 0:
                        raise ValueError()
                    raw = base64.b64decode(rows[0]["embedding"], validate=True)
                    vector = list(struct.unpack("<1024f", raw))
                    if not any(vector) or not all(math.isfinite(value) for value in vector):
                        raise ValueError()
                except (KeyError, TypeError, ValueError, struct.error):
                    raise ModelFailure("PAIRED_QUERY_VECTOR_INVALID") from None
                recorded = copy.deepcopy(result)
                recorded["data"][0]["embedding"] = vector
                self.tape.append(recorded)
                self.receipts.append(dict(input_sha256=hashlib.sha256(inputs[0].encode()).hexdigest(),
                                          vector_float32_sha256=hashlib.sha256(raw).hexdigest()))
            else:
                result = copy.deepcopy(self.tape[self.position])
            self.position += 1
            return result


def paired_comparison(backends):
    result = comparison(backends)
    result.update(live_upstream_calls_independent=False, same_persisted_index=True,
                  same_recorded_real_query_vectors=True, semantic_quality_verified=False,
                  all_original_rankings_identical=all(
                      (left["rank"], left["tied_rank"], left["titles"]) ==
                      (right["rank"], right["tied_rank"], right["titles"])
                      for left, right in zip(backends["java"]["query_results"], backends["go"]["query_results"])))
    return result


def index_fingerprint(database):
    return {table: database.sql("SELECT encode(sha256(convert_to(coalesce("
            "jsonb_agg(to_jsonb(t) ORDER BY " + key + ")::text,'[]'),'UTF8')),'hex') FROM " + table + " t;")
            for table, key in (("knowledge_document", "id"), ("vector_store", "id"),
                               ("flyway_schema_history", "installed_rank"))}


def require_quiescent(database):
    if database.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();") != "0":
        raise AssertionError("Paired evaluation retained application database connections")


def exercise(container, fixture, binary, model, relay, relay_values, downloaded, report):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    modules, report["sources_sha256"] = original_modules()
    documents = downloaded[0]
    queries = modules["queries.py"].QUERIES
    adapter = None
    receipts_before = model.embedding_receipts()
    with tempfile.TemporaryDirectory(prefix="personalos-p8-paired-runtime-") as temporary:
        directory = Path(temporary)
        values = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
        values.update({key: fixture["GO_TEST_" + key] for key in
                       ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
        values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
                      AUTH_ENABLED="true", AUTH_USERNAME="p8-paired-eval", AUTH_PASSWORD=secrets.token_urlsafe(24),
                      APP_SECURITY_CSRF_ENABLED="true", APP_RATELIMIT_ENABLED="false",
                      AI_CHAT_UPLOAD_DIR=str(directory / "uploads"), APP_CONTEXT_SUMMARY_ENABLED="false",
                      APP_CONTEXT_AUTOMEMORY_ENABLED="false", APP_MEMORY_BACKFILL_ENABLED="false",
                      AI_CALENDAR_SCAN_ENABLED="false", MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false",
                      AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai", RAG_ENABLED="false",
                      APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED="false",
                      EMBEDDING_CACHE_ENABLED="false", RAG_CANDIDATE_CACHE_ENABLED="false",
                      AI_KNOWLEDGE_HYBRID_ENABLED="true")
        values.update(relay_values)
        for backend in ("java", "go"):
            processes.BACKEND = backend
            progress = report["backends"].setdefault(backend, dict(result="IN_PROGRESS"))
            with processes.candidate(directory, binary, values) as state:
                client = Client(state, timeout=180)
                client.login(values)
                if backend == "java":
                    adapter = MeasuredAdapter(client, database, documents, backend, progress)
                    adapter.bind(modules)
                    for position, (post, (title, _)) in enumerate(documents.items(), 1):
                        modules["rebuild-corpus.py"].upload(post, title)
                        print("Paired Java corpus upload " + str(position) + "/" + str(len(documents)), flush=True)
                    raw = json.loads(database.sql("SELECT json_agg(x) FROM (SELECT content,metadata FROM vector_store) x;"))
                    expected, oracle = embedding_hashes(raw)
                    report["ingestion"] = verify_ingestion(client.request("/knowledge/documents"), documents,
                        adapter.uploaded, database, model.embedding_receipts() - receipts_before, expected)
                    report["embedding_format_oracle"] = oracle
                    relay.begin_recording()
                    report["index_before_queries"] = index_fingerprint(database)
                else:
                    if index_fingerprint(database) != report["index_before_queries"]:
                        raise AssertionError("Paired index changed before Go query execution")
                    relay.begin_replay()
                    adapter.client, adapter.backend, adapter.progress = client, backend, progress
                    adapter.search_calls, adapter.latencies = 0, []
                modules["retrieval-eval.py"].QUERIES = queries
                rows = modules["retrieval-eval.py"].evaluate()
                if len(rows) != len(queries) or adapter.search_calls != len(queries):
                    raise AssertionError("Paired original query coverage changed")
                progress.update(query_results=rows, metrics=metrics(rows), queries=len(rows),
                                embedding_input_format="Spring-AI-2.0.1-MetadataMode.EMBED",
                                index_uploaded_by_this_backend=backend == "java")
            require_quiescent(database)
            if index_fingerprint(database) != report["index_before_queries"]:
                raise AssertionError("Paired index changed during read-only query execution")
            progress.update(result="PASS", processes_stopped=True, database_connections_after_stop=0)
        report["query_vector_replay"] = relay.finish()
        report["comparison"] = paired_comparison(report["backends"])
        report["index_after_queries"] = index_fingerprint(database)
        for path in directory.rglob("*.log"):
            if model.config.api_key.encode() in path.read_bytes():
                raise AssertionError("Real model credential leaked into a paired runtime log")
        if not report["comparison"]["all_original_rankings_identical"]:
            raise AssertionError("Same-index same-query-vector original rankings differ")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-env-file", required=True, type=Path)
    parser.add_argument("--downloaded-corpus-dir", required=True, type=Path)
    args = parser.parse_args()
    modules, _ = original_modules()
    downloaded = load_corpus(args.downloaded_corpus_dir, modules)
    coverage = corpus_coverage(downloaded[0], modules)
    if coverage["excluded_original_queries"] or coverage["documents"] != coverage["expected_documents"]:
        raise ValueError("Paired full evaluation requires the complete original corpus")
    config = load_config(args.model_env_file)
    model = None
    report = dict(result="IN_PROGRESS", started_at=datetime.now(timezone.utc).isoformat(),
                  mode="same-index-recorded-real-query-vectors", model=config.metadata(),
                  input_corpus_coverage=coverage, acquisition=downloaded[1], backends={},
                  configured_real_model_used=True, query_vectors_replayed_to_go=True, synthetic_model=False,
                  semantic_quality_verified=False, release_gates_changed=False,
                  limits="Supplemental retrieval isolation only: Java uploads once; Go reads the same owned index "
                         "and receives the exact recorded real float32 query vectors. No fresh Go embedding calls, "
                         "answer generation, 50-query noise suite, historical identity or release approval. "
                         "Earlier independent-call results and failures remain authoritative for their own run.")
    report_path = SPEC / ("p8-eval-paired-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    code = 1
    try:
        with pooled_transport(config) as opener, tempfile.TemporaryDirectory(prefix="personalos-p8-paired-build-") as temporary:
            model = RealModel(config, opener)
            relay = QueryReplay(model, [row["q"] for row in modules["queries.py"].QUERIES])
            binary = Path(temporary) / "personalos"
            build = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
            build.update(cache_environment(), GOPROXY="off", GOTOOLCHAIN="local", GOMAXPROCS="4")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"],
                           cwd=ROOT / "backend-go", env=build, check=True, timeout=120)
            with serve(relay) as relay_values:
                run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                    exercise=lambda container, fixture: exercise(container, fixture, binary, model, relay,
                                                               relay_values, downloaded, report))
            if model.snapshot()["failures"]:
                raise AssertionError("Paired upstream failures were observed")
        report["result"], code = "PASS", 0
    except Exception as error:
        report["result"] = "FAIL"
        for progress in report["backends"].values():
            if progress.get("result") == "IN_PROGRESS": progress["result"] = "FAIL"
        report["failure"] = str(error) if isinstance(error, (ModelFailure, AssertionError)) else type(error).__name__
        print("Paired evaluation failed: " + report["failure"] + "; no retries or raw errors", flush=True)
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        report["real_model_requests"] = model.snapshot() if model is not None else {}
        with report_path.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print("Paired real-vector report: " + str(report_path), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
