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
NO_EVIDENCE_QUERY = "量子引力理论能否解释黑洞信息悖论的最终答案是什么？"


def configure_budget(model, args, query_count):
    fields = (("max_requests", "max_calls", 5000),
              ("max_chat_requests", "max_chat_calls", 2500),
              ("max_input_characters", "max_characters", 100_000_000))
    values = [getattr(args, field, None) for field, _, _ in fields]
    if args.mode == "multistage-full" and any(value is None for value in values):
        raise ValueError("Full multistage evaluation requires three explicit model budgets")
    for (field, attribute, maximum), value in zip(fields, values):
        if value is not None:
            if type(value) is not int or not 1 <= value <= maximum:
                raise ValueError("Invalid explicit model evaluation budget: " + field)
            setattr(model, attribute, value)
    backends = 2 if args.backend == "both" else 1
    if args.mode == "multistage-full" and model.max_chat_calls < 1 + 4 * query_count * backends:
        raise ValueError("Full multistage chat budget cannot cover all original questions")


def multistage_batch(client, model, queries, uploaded, stored, save_answer, progress):
    if not queries or len({row["q"] for row in queries}) != len(queries):
        raise ValueError("Full multistage questions must be nonempty and unique")
    rows = []
    progress.update(expected_original_queries=len(queries), executed_original_queries=0,
                    unexecuted_eligible_queries=len(queries), multistage_cases=rows)
    for position, query in enumerate(queries, 1):
        before = model.snapshot()["calls"].get("chat", 0)
        started = time.monotonic()
        row = dict(question=query["q"], acceptable_documents=query["docs"], result="FAIL")
        try:
            response = client.request("/knowledge/ask", "POST", {"question": query["q"], "topK": 5})
            save_answer(position, response)
            row.update(retrieval_status=response.get("retrieval", {}).get("status"),
                       answered=response.get("answered"),
                       stages=[dict(name=stage.get("name"), status=stage.get("status"))
                               for stage in response.get("retrieval", {}).get("stages", [])],
                       warning_count=len(response.get("retrieval", {}).get("warnings", [])))
            calls = model.snapshot()["calls"].get("chat", 0) - before
            row["pipeline"] = check_multistage(response, calls)
            row["answer"] = check_answer(response, query["docs"], uploaded, stored)
            row["result"] = "PASS"
        except AssertionError as error:
            row["failure"] = str(error)
        except Exception as error:
            row["error_type"] = type(error).__name__
        row.update(actual_chat_calls=model.snapshot()["calls"].get("chat", 0) - before,
                   duration_ms=round((time.monotonic() - started) * 1000))
        rows.append(row)
        progress.update(executed_original_queries=position, unexecuted_eligible_queries=len(queries) - position)
        print("Full multistage " + str(position) + "/" + str(len(queries)) + ": " + row["result"], flush=True)
        if model.snapshot()["failures"]:
            raise ModelFailure("FULL_MULTISTAGE_UPSTREAM_FAILURE_NO_RETRY")
    progress.update(multistage_passed=sum(row["result"] == "PASS" for row in rows),
                    multistage_failed=sum(row["result"] != "PASS" for row in rows),
                    all_original_questions_executed=True)
    return rows


def corpus_coverage(documents, modules):
    eligible = query_subset(modules["queries.py"].QUERIES, documents)
    return dict(documents=len(documents), expected_documents=len(modules["rebuild-corpus.py"].CORPUS),
                eligible_original_queries=len(eligible), expected_original_queries=len(modules["queries.py"].QUERIES),
                excluded_original_queries=len(modules["queries.py"].QUERIES) - len(eligible),
                historical_corpus_identity_verified=False)


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


def check_multistage(response, actual_chat_calls):
    retrieval = response.get("retrieval", {})
    stages = retrieval.get("stages", [])
    if (retrieval.get("status") != "READY" or retrieval.get("warnings")
            or [(row.get("name"), row.get("status")) for row in stages] !=
               [(name, "OK") for name in ("recall", "rewrite", "rerank", "evidence")]
            or actual_chat_calls != 4):
        raise AssertionError("Real multistage RAG was guarded, degraded or lacked four actual model calls")
    return dict(status="READY", stages=[dict(name=row["name"], status=row["status"],
                                              duration_ms=row["durationMs"]) for row in stages],
                warnings=0, actual_chat_calls=actual_chat_calls,
                factual_quality_manually_approved=False)


def check_no_evidence(response, actual_chat_calls):
    retrieval = response.get("retrieval", {})
    stages = retrieval.get("stages", [])
    if (response.get("answered") is not False or response.get("citations") != []
            or response.get("answer") != "知识库中没有找到相关内容。"
            or retrieval.get("status") != "INSUFFICIENT" or retrieval.get("hits") != []
            or retrieval.get("warnings")
            or [(row.get("name"), row.get("status")) for row in stages] !=
               [(name, "OK") for name in ("recall", "rewrite", "rerank", "evidence")]
            or actual_chat_calls != 3):
        raise AssertionError("Real no-evidence control did not meet the strict INSUFFICIENT response contract")
    return dict(status="INSUFFICIENT", answered=False, citation_count=0,
                stages=[dict(name=row["name"], status=row["status"], duration_ms=row["durationMs"])
                        for row in stages], warnings=0, actual_chat_calls=actual_chat_calls,
                answer_sha256=hashlib.sha256(response["answer"].encode()).hexdigest(),
                final_answer_model_called=False, factual_quality_manually_approved=False)


def exercise(container, fixture, backend, binary, relay, relay_values, downloaded, artifact_dir, progress, mode="retrieval"):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    modules, hashes = original_modules()
    documents, manifest = downloaded
    queries = query_subset(modules["queries.py"].QUERIES, documents)
    result = progress
    multistage = mode != "retrieval"
    full_multistage = mode == "multistage-full"
    no_evidence = mode == "no-evidence"
    executed_original = 0 if no_evidence or full_multistage else 1 if multistage else len(queries)
    result.update(backend=backend, result="IN_PROGRESS", documents=len(documents),
                  queries=1 if multistage and not full_multistage else len(queries), eligible_original_queries=len(queries), mode=mode,
                  executed_original_queries=executed_original,
                  unexecuted_eligible_queries=len(queries) - executed_original,
                  background_ingestion_review_enabled=False,
                  excluded_queries=len(modules["queries.py"].QUERIES) - len(queries), sources_sha256=hashes,
                  stage="starting")
    if no_evidence:
        result["probe_question_origin"] = "supplemental-negative-control; not an original annotated query"
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
                      AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai", RAG_ENABLED=str(multistage).lower(),
                      APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED="false",
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
            result["stage"] = "ingestion_verification"
            rows = json.loads(database.sql("SELECT json_agg(x) FROM (SELECT content,metadata FROM vector_store) x;"))
            expected_hashes, result["embedding_format_oracle"] = embedding_hashes(rows)
            result["embedding_input_format"] = "Spring-AI-2.0.1-MetadataMode.EMBED"
            result["ingestion"] = verify_ingestion(client.request("/knowledge/documents"), documents,
                                                   adapter.uploaded, database,
                                                   relay.embedding_receipts() - receipts_before, expected_hashes)
            if not multistage:
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
            result["stage"] = "multistage_answer" if multistage else "knowledge_answers"
            questions = (NO_EVIDENCE_QUERY,) if no_evidence else QA_QUERIES[:1] if multistage else QA_QUERIES
            if full_multistage:
                multistage_batch(client, relay, queries, adapter.uploaded, stored,
                    lambda number, response: private_json(artifact_dir / (backend + "-full-answer-" + str(number) + ".json"), response),
                    result)
                questions = ()
            for question in questions:
                calls = relay.snapshot()["calls"].get("chat", 0)
                response = client.request("/knowledge/ask", "POST", {"question": question, "topK": 5})
                private_json(artifact_dir / (backend + "-answer-" + str(len(answers) + 1) + ".json"), response)
                if multistage:
                    result["multistage_observation"] = dict(
                        status=response.get("retrieval", {}).get("status"),
                        stages=[dict(name=row.get("name"), status=row.get("status"))
                                for row in response.get("retrieval", {}).get("stages", [])],
                        warning_count=len(response.get("retrieval", {}).get("warnings", [])),
                        answered=response.get("answered"),
                        actual_chat_calls=relay.snapshot()["calls"].get("chat", 0) - calls)
                    if no_evidence:
                        result["no_evidence_observation"] = dict(
                            citation_count=len(response.get("citations", [])),
                            hit_count=len(response.get("retrieval", {}).get("hits", [])),
                            answer_chars=len(response.get("answer", "")),
                            answer_sha256=hashlib.sha256(response.get("answer", "").encode()).hexdigest(),
                            clarification_chars=len(response.get("retrieval", {}).get("clarification", "")))
                    result["multistage"] = (check_no_evidence if no_evidence else check_multistage)(
                        response, result["multistage_observation"]["actual_chat_calls"])
                if no_evidence:
                    check = dict(result["multistage"])
                else:
                    acceptable = next(row["docs"] for row in queries if row["q"] == question)
                    check = check_answer(response, acceptable, adapter.uploaded, stored)
                check.update(question=question, actual_chat_calls=relay.snapshot()["calls"].get("chat", 0) - calls)
                if not multistage and check["actual_chat_calls"] != 1:
                    raise AssertionError("Legacy knowledge answer did not use exactly one real chat request")
                answers.append(check)
                print(backend + ": real no-evidence rejection verified" if no_evidence else
                      backend + ": real answer and persisted citations verified " + str(len(answers)), flush=True)
            result["knowledge_answers"] = answers
        if not multistage:
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
        result.update(result="FAIL" if result.get("multistage_failed", 0) else "PASS", processes_stopped=True, database_connections_after_stop=0,
                      stage="complete",
                      real_model_before=before, real_model_after=relay.snapshot())
        if latencies:
            result["search_latency_ms"] = dict(samples=len(latencies), p50=latencies[len(latencies) // 2],
                                                p95=latencies[min(len(latencies) - 1, int(len(latencies) * .95))])
    return result


def comparison(backends):
    java, go = backends["java"], backends["go"]
    if [(row["query"], row["docs"]) for row in java["query_results"]] != [
            (row["query"], row["docs"]) for row in go["query_results"]]:
        raise AssertionError("Java and Go did not evaluate identical original annotations")
    delta = {key: go["metrics"][key] - java["metrics"][key]
             for key in ("recall_at_1", "recall_at_3", "recall_at_5", "mrr")}
    tied = {name: metrics([dict(row, rank=row["tied_rank"]) for row in value["query_results"]])
            for name, value in backends.items()}
    tied_delta = {key: tied["go"][key] - tied["java"][key] for key in delta}
    return dict(go_minus_java=delta,
                go_recall_at_5_not_lower=delta["recall_at_5"] >= 0,
                go_mrr_not_lower=delta["mrr"] >= 0,
                strict_quality_not_lower=all(value >= 0 for value in delta.values()),
                original_tied_rank_metrics=tied, original_tied_rank_go_minus_java=tied_delta,
                original_tied_quality_not_lower=all(value >= 0 for value in tied_delta.values()),
                strict_quality_status="NO_OBSERVED_DEGRADATION" if all(value >= 0 for value in delta.values())
                                      else "DEGRADATION_OBSERVED",
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
    parser.add_argument("--mode", choices=("retrieval", "multistage", "multistage-full", "no-evidence"), default="retrieval")
    parser.add_argument("--max-requests", type=int)
    parser.add_argument("--max-chat-requests", type=int)
    parser.add_argument("--max-input-characters", type=int)
    args = parser.parse_args()
    config = load_config(args.model_env_file)
    with pooled_transport(config) as opener:
        return run(args, config, opener)


def run(args, config, opener):
    model = RealModel(config, opener)
    modules, _ = original_modules()
    downloaded = load_corpus(args.downloaded_corpus_dir, modules)
    coverage = corpus_coverage(downloaded[0], modules)
    if args.mode == "multistage-full" and coverage["excluded_original_queries"]:
        raise ValueError("Full multistage evaluation refuses incomplete original corpus coverage")
    configure_budget(model, args, coverage["eligible_original_queries"])
    artifact_dir = Path(tempfile.mkdtemp(prefix="personalos-p8-real-evidence-")).resolve()
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = SPEC / ("p8-eval-real-" + stamp + ".json")
    report = dict(result="IN_PROGRESS", started_at=datetime.now(timezone.utc).isoformat(),
                  configured_real_model_used=True, synthetic_model=False, model=model.config.metadata(),
                  acquisition=downloaded[1], backends={}, selected_backend=args.backend, mode=args.mode,
                  input_corpus_coverage=coverage,
                  private_answer_evidence=str(artifact_dir),
                  semantic_quality_verified=False, release_gates_changed=False,
                  limits=str(coverage["documents"]) + " of " + str(coverage["expected_documents"]) +
                         " fresh public articles; current-page coverage is not authenticated historical corpus identity. "
                         "Independent live model calls can have noise. Retrieval mode uses legacy QA; "
                         "multistage mode probes one original question; multistage-full executes every original question "
                         "with explicit budgets and retains each failed case without approving factual quality. "
                         "No-evidence mode is one supplemental negative control, not an original annotated query. "
                         "Factual answer review and full production acceptance remain separate. No existing DB, "
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
                                                          relay_values, downloaded, artifact_dir, progress, args.mode)
                run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                            exercise=run_backend)
                print("Real model " + backend + " subset completed", flush=True)
            if args.backend == "both" and args.mode == "retrieval":
                report["comparison"] = comparison(report["backends"])
        if model.snapshot()["failures"]:
            raise AssertionError("Real model upstream failures were observed")
        if any(value.get("result") != "PASS" for value in report["backends"].values()):
            raise AssertionError("Complete multistage evaluation retained failed original cases")
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
