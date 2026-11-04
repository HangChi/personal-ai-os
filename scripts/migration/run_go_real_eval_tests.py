#!/usr/bin/env python3
"""获准真实模型与公开正文的Java/Go隔离验收，不访问现有业务库。"""
import argparse
import contextlib
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import time

from fetch_go_juejin_corpus import load_corpus
from go_eval_adapter import FixtureAdapter, arguments, original_modules
from go_real_model import ModelFailure, RealModel, load_config, pooled_transport, serve
from go_runtime import private_json
from go_test_environment import cache_environment
from java_embedding_content import embedding_hashes
from run_go_cutover_tests import Client
from run_go_demo_seed_tests import OwnedDatabase
from run_go_eval_tests import query_subset, verify_ingestion
import run_go_e2e_tests as processes
from run_go_postgres_tests import IMAGE, ROOT, run_package

SPEC = ROOT / "specs/033-go-rewrite"
QA_QUERIES = ("BRIN 索引适合什么场景", "goroutine 为什么比操作系统线程轻量")


def metrics(rows):
    if not rows:
        raise ValueError("Real evaluation requires nonempty original query results")
    return dict(queries=len(rows), recall_at_1=sum(row["rank"] == 1 for row in rows) / len(rows),
                recall_at_3=sum(row["rank"] is not None and row["rank"] <= 3 for row in rows) / len(rows),
                recall_at_5=sum(row["rank"] is not None and row["rank"] <= 5 for row in rows) / len(rows),
                mrr=sum(1 / row["rank"] if row["rank"] else 0 for row in rows) / len(rows),
                misses=sum(row["rank"] is None for row in rows))


class MeasuredAdapter(FixtureAdapter):
    def __init__(self, client, database, documents, backend, progress):
        super().__init__(client, database, documents)
        self.backend, self.latencies = backend, []
        self.progress = progress

    def search(self, query, top_k=5):
        start = time.monotonic()
        result = super().search(query, top_k)
        self.latencies.append((time.monotonic() - start) * 1000)
        self.progress["completed_search_calls"] = self.search_calls
        if self.search_calls % 10 == 0:
            print(self.backend + ": real search calls " + str(self.search_calls), flush=True)
        return result


def check_answer(response, expected, uploaded, stored):
    answer, citations = response.get("answer"), response.get("citations")
    if not response.get("answered") or not isinstance(answer, str) or not answer.strip():
        raise AssertionError("Real knowledge answer was missing or guarded")
    if not isinstance(citations, list) or not citations:
        raise AssertionError("Real knowledge answer had no citations")
    marks = {int(value) for value in re.findall(r"\[([0-9]+)\]", answer)}
    indices = {row["index"] for row in citations}
    if not marks or marks != indices:
        raise AssertionError("Real answer citation marks differ from actual citation records")
    for row in citations:
        key = (row["documentId"], row["chunkIndex"])
        if (row["documentId"] not in uploaded or uploaded[row["documentId"]] != row["documentTitle"]
                or stored.get(key) != hashlib.sha256(row["content"].encode()).hexdigest()):
            raise AssertionError("Real answer citation escaped its persisted corpus chunk")
    titles = sorted({row["documentTitle"] for row in citations})
    if not set(titles) & set(expected):
        raise AssertionError("Real answer citations missed every original acceptable document")
    return dict(answer_sha256=hashlib.sha256(answer.encode()).hexdigest(), answer_chars=len(answer),
                citation_count=len(citations), citation_titles=titles,
                explicit_marks_match=True, citation_chunks_match_database=True,
                acceptable_document_cited=True, factual_quality_manually_approved=False)


def exercise(container, fixture, backend, binary, relay, relay_values, downloaded, artifact_dir, progress):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    modules, hashes = original_modules()
    documents, manifest = downloaded
    queries = query_subset(modules["queries.py"].QUERIES, documents)
    result = progress
    result.update(backend=backend, result="IN_PROGRESS", documents=len(documents), queries=len(queries),
                  excluded_queries=len(modules["queries.py"].QUERIES) - len(queries), sources_sha256=hashes,
                  stage="starting")
    with tempfile.TemporaryDirectory(prefix="personalos-p8-real-" + backend + "-") as temporary:
        directory = Path(temporary)
        values = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
        values.update({key: fixture["GO_TEST_" + key] for key in
                       ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
        values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
                      AUTH_ENABLED="true", AUTH_USERNAME="p8-real-eval", AUTH_PASSWORD=secrets.token_urlsafe(24),
                      APP_SECURITY_CSRF_ENABLED="true", APP_RATELIMIT_ENABLED="false",
                      AI_CHAT_UPLOAD_DIR=str(directory / "uploads"), APP_CONTEXT_SUMMARY_ENABLED="false",
                      APP_CONTEXT_AUTOMEMORY_ENABLED="false", APP_MEMORY_BACKFILL_ENABLED="false",
                      AI_CALENDAR_SCAN_ENABLED="false", MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false",
                      AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai", RAG_ENABLED="false",
                      EMBEDDING_CACHE_ENABLED="false", RAG_CANDIDATE_CACHE_ENABLED="false",
                      AI_KNOWLEDGE_HYBRID_ENABLED="true")
        values.update(relay_values)
        before = relay.snapshot()
        receipts_before = relay.embedding_receipts()
        processes.BACKEND = backend
        with processes.candidate(directory, binary, values) as state:
            client = Client(state, timeout=180)
            client.login(values)
            adapter = MeasuredAdapter(client, database, documents, backend, result)
            adapter.bind(modules)
            result["stage"] = "upload"
            for position, (post, (title, _)) in enumerate(documents.items(), 1):
                modules["rebuild-corpus.py"].upload(post, title)
                result["uploaded_documents"] = position
                print(backend + ": uploaded real article " + str(position) + "/" + str(len(documents)), flush=True)
            expected_hashes = None
            rows = json.loads(database.sql("SELECT json_agg(x) FROM (SELECT content,metadata FROM vector_store) x;"))
            expected_hashes, result["embedding_format_oracle"] = embedding_hashes(rows)
            result["embedding_input_format"] = "Spring-AI-2.0.1-MetadataMode.EMBED"
            result["ingestion"] = verify_ingestion(client.request("/knowledge/documents"), documents,
                                                   adapter.uploaded, database,
                                                   relay.embedding_receipts() - receipts_before, expected_hashes)
            result["stage"] = "hybrid"
            retrieval = modules["retrieval-eval.py"]
            retrieval.QUERIES = queries
            rows = retrieval.evaluate()
            if len(rows) != len(queries) or adapter.search_calls != len(queries):
                raise AssertionError("Real model evaluation query coverage changed")
            summary = io.StringIO()
            with contextlib.redirect_stdout(summary):
                retrieval.summarize(rows, "REAL_" + backend.upper())
            result.update(metrics=metrics(rows), query_results=rows, summary=summary.getvalue().strip(),
                          by_level={level: metrics([row for row in rows if row["level"] == level])
                                    for level in sorted({row["level"] for row in rows})},
                          hybrid_search_calls=adapter.search_calls)
            stored_rows = json.loads(database.sql(
                "SELECT json_agg(x) FROM (SELECT metadata->>'document_id' AS id,"
                "(metadata->>'chunk_index')::int AS index,"
                "encode(sha256(convert_to(content,'UTF8')),'hex') AS sha256 FROM vector_store) x;"))
            stored = {(row["id"], row["index"]): row["sha256"] for row in stored_rows}
            answers = []
            result["stage"] = "knowledge_answers"
            for question in QA_QUERIES:
                acceptable = next(row["docs"] for row in queries if row["q"] == question)
                calls = relay.snapshot()["calls"].get("chat", 0)
                response = client.request("/knowledge/ask", "POST", {"question": question, "topK": 5})
                private_json(artifact_dir / (backend + "-answer-" + str(len(answers) + 1) + ".json"), response)
                check = check_answer(response, acceptable, adapter.uploaded, stored)
                check.update(question=question, actual_chat_calls=relay.snapshot()["calls"].get("chat", 0) - calls)
                if check["actual_chat_calls"] != 1:
                    raise AssertionError("Legacy knowledge answer did not use exactly one real chat request")
                answers.append(check)
                print(backend + ": real answer and persisted citations verified " + str(len(answers)), flush=True)
            result["knowledge_answers"] = answers
        values["AI_KNOWLEDGE_HYBRID_ENABLED"] = "false"
        result["stage"] = "vector_noise"
        with processes.candidate(directory, binary, values) as state:
            client = Client(state, timeout=180)
            client.login(values)
            adapter.client = client
            noise = io.StringIO()
            with arguments(["noise-floor.py", "10", "5"]), contextlib.redirect_stdout(noise):
                noise_result = modules["noise-floor.py"].main()
            if noise_result != 0 or adapter.search_calls - len(queries) != 50:
                raise AssertionError("Real model pure-vector noise sampling did not finish")
            result.update(vector_noise_search_calls=50, observed_noise=noise.getvalue().strip())
        if database.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();") != "0":
            raise AssertionError("Real evaluation retained application database connections")
        for path in directory.rglob("*.log"):
            if relay.config.api_key.encode() in path.read_bytes():
                raise AssertionError("Real model credential leaked into a runtime log")
        latencies = sorted(adapter.latencies)
        result.update(result="PASS", processes_stopped=True, database_connections_after_stop=0,
                      stage="complete",
                      real_model_before=before, real_model_after=relay.snapshot(),
                      search_latency_ms=dict(samples=len(latencies), p50=latencies[len(latencies) // 2],
                                             p95=latencies[min(len(latencies) - 1, int(len(latencies) * .95))]))
    return result


def comparison(backends):
    java, go = backends["java"], backends["go"]
    if [(row["query"], row["docs"]) for row in java["query_results"]] != [
            (row["query"], row["docs"]) for row in go["query_results"]]:
        raise AssertionError("Java and Go did not evaluate identical original annotations")
    delta = {key: go["metrics"][key] - java["metrics"][key]
             for key in ("recall_at_1", "recall_at_3", "recall_at_5", "mrr")}
    return dict(go_minus_java=delta,
                go_recall_at_5_not_lower=delta["recall_at_5"] >= 0,
                go_mrr_not_lower=delta["mrr"] >= 0,
                strict_rank_changes=sum(left["rank"] != right["rank"] for left, right in
                                        zip(java["query_results"], go["query_results"])),
                identical_source_files=True, live_upstream_calls_independent=True,
                embedding_input_formats={key: value.get("embedding_input_format", "UNRECORDED")
                                         for key, value in backends.items()},
                noise_tolerance_unchanged=0.007, default_similarity_threshold_unchanged=0.3,
                release_gate_approved=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-env-file", required=True, type=Path, help="Only explicitly authorized model keys are loaded")
    parser.add_argument("--downloaded-corpus-dir", required=True, type=Path)
    parser.add_argument("--backend", choices=("both", "go", "java"), default="both")
    args = parser.parse_args()
    config = load_config(args.model_env_file)
    with pooled_transport(config) as opener:
        return run(args, config, opener)


def run(args, config, opener):
    model = RealModel(config, opener)
    modules, _ = original_modules()
    downloaded = load_corpus(args.downloaded_corpus_dir, modules)
    query_subset(modules["queries.py"].QUERIES, downloaded[0])
    artifact_dir = Path(tempfile.mkdtemp(prefix="personalos-p8-real-evidence-")).resolve()
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = SPEC / ("p8-eval-real-" + stamp + ".json")
    report = dict(result="IN_PROGRESS", started_at=datetime.now(timezone.utc).isoformat(),
                  configured_real_model_used=True, synthetic_model=False, model=model.config.metadata(),
                  acquisition=downloaded[1], backends={}, selected_backend=args.backend,
                  private_answer_evidence=str(artifact_dir),
                  semantic_quality_verified=False, release_gates_changed=False,
                  limits="16-document subset of fresh public pages, not authenticated historical 26-article corpus. "
                         "Independent live model calls can have noise. Legacy knowledge QA only; multistage RAG, "
                         "factual answer review and full production acceptance remain separate. No existing DB, "
                         "production mutation, automatic release approval or synthetic fallback.")
    code = 1
    try:
        embedding = model.forward("/v1/embeddings", {"model": model.config.embedding_model,
                                  "input": ["P8 real model dimensionality and connectivity preflight"]})
        chat = model.forward("/v1/chat/completions", {"model": model.config.chat_model,
                             "messages": [{"role": "user", "content": "请仅回复：连接成功"}], "max_tokens": 32})
        report["preflight"] = dict(embedding_dimensions=len(embedding["data"][0]["embedding"]),
                                   chat_nonempty=bool(chat["choices"][0]["message"]["content"].strip()))
        print("Real embedding/chat preflight passed; configured models only", flush=True)
        with tempfile.TemporaryDirectory(prefix="personalos-p8-real-build-") as temporary, serve(model) as relay_values:
            binary = Path(temporary) / "personalos"
            build = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
            build.update(cache_environment(), GOPROXY="off", GOTOOLCHAIN="local", GOMAXPROCS="4")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"],
                           cwd=ROOT / "backend-go", env=build, check=True, timeout=120)
            for backend in (("go", "java") if args.backend == "both" else (args.backend,)):
                def run_backend(container, fixture):
                    progress = report["backends"].setdefault(backend, {})
                    report["backends"][backend] = exercise(container, fixture, backend, binary, model,
                                                          relay_values, downloaded, artifact_dir, progress)
                run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                            exercise=run_backend)
                print("Real model " + backend + " subset completed", flush=True)
            if args.backend == "both":
                report["comparison"] = comparison(report["backends"])
        if model.snapshot()["failures"]:
            raise AssertionError("Real model upstream failures were observed")
        report["result"], code = "PASS", 0
    except Exception as error:
        report["result"] = "FAIL"
        for progress in report["backends"].values():
            if progress.get("result") == "IN_PROGRESS":
                progress["result"] = "FAIL"
        report["failure"] = str(error) if isinstance(error, (ModelFailure, AssertionError)) else type(error).__name__
        print("Real evaluation failed: " + report["failure"] + "; no synthetic fallback or raw error output", flush=True)
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        report["real_model_requests"] = model.snapshot()
        with report_path.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print("Real model report: " + str(report_path), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
