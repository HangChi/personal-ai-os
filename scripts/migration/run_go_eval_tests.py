#!/usr/bin/env python3
"""Original evaluation tooling on synthetic documents/new TLS resources, never real quality."""
import argparse
from collections import Counter
import contextlib
import hashlib
from http.server import ThreadingHTTPServer
import io
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
from urllib.parse import urlencode
import uuid

from go_eval_adapter import FixtureAdapter, arguments, corpus_preflight, original_modules
from go_test_environment import cache_environment
from p8_model_fixture import Handler
from run_go_cutover_tests import Client
from run_go_demo_seed_tests import OwnedDatabase
import run_go_e2e_tests as processes
from run_go_postgres_tests import IMAGE, ROOT, run_package

SPEC = ROOT / "specs/033-go-rewrite"


def upload_binding(client):
    cases = [
        ("missing", [], []), ("query", [("title", "query title")], []),
        ("query-repeat", [("title", "first"), ("title", "second")], []),
        ("form", [], ["form title"]), ("form-repeat", [], ["first", "second"]),
        ("query-and-form", [("title", "query")], ["form"]),
        ("empty-query-and-form", [("title", "")], ["form"]),
        ("unicode", [("title", "中文 标题+%")], []),
        ("empty-form", [], [""]), ("query-comma", [("title", "first,second")], ["third"]),
    ]
    rows = []
    for name, query, fields in cases:
        boundary = "p8-binding-" + uuid.uuid4().hex
        body = "".join("--" + boundary + '\r\nContent-Disposition: form-data; name="title"\r\n\r\n'
                       + title + "\r\n" for title in fields)
        body += ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="binding.md"\r\n'
                 'Content-Type: text/markdown; charset=utf-8\r\n\r\n' + "上传参数绑定测试材料。" * 50
                 + "\r\n--" + boundary + "--\r\n")
        path = "/knowledge/documents" + ("?" + urlencode(query) if query else "")
        document = client.request(path, "POST", body.encode(), status=201,
                                  headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
        try:
            rows.append(dict(name=name, query=query, form=fields, title=document["title"]))
        finally:
            client.request("/knowledge/documents/" + document["id"], "DELETE")
    # Unknown UUIDs fail in requireDocument before any job can be enqueued.
    target = "00000000-0000-4000-8000-000000000001"
    for name, query, fields, status in [
        ("target-query", [("documentId", target)], [], 404),
        ("target-query-invalid", [("documentId", "invalid")], [], 400),
        ("target-query-spaces", [("documentId", " " + target + " ")], [], 404),
        ("target-form-spaces", [], [" " + target + " "], 404),
        ("target-unicode-surrounding-space", [("documentId", "\u2003" + target + "\u2003")], [], 400),
        ("target-nbsp", [("documentId", "\u00a0")], [], 400),
        ("target-nul", [("documentId", "\x00")], [], 400),
        ("target-query-repeat", [("documentId", target), ("documentId", "invalid")], [], 404),
        ("target-form-first", [("documentId", "invalid")], [target], 404),
        ("target-invalid-form-first", [("documentId", target)], ["invalid"], 400),
    ]:
        boundary = "p8-target-" + uuid.uuid4().hex
        body = "".join("--" + boundary + '\r\nContent-Disposition: form-data; name="documentId"\r\n\r\n'
                       + value + "\r\n" for value in fields)
        body += ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="binding.md"\r\n'
                 'Content-Type: text/markdown\r\n\r\n' + "upload parameter fixture " * 30
                 + "\r\n--" + boundary + "--\r\n")
        client.request("/knowledge/jobs?" + urlencode(query), "POST", body.encode(), status=status,
                       headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
        rows.append(dict(name=name, query=query, form=fields, status=status))
    return rows


def exercise(container, fixture, binding_only=False):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    modules, hashes = original_modules()
    model = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=model.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="personalos-p8-eval-") as temporary:
            directory = Path(temporary)
            binary = directory / "personalos"
            build = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
            build.update(cache_environment(), GOPROXY="off", GOTOOLCHAIN="local", GOMAXPROCS="4")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"], cwd=ROOT / "backend-go",
                           env=build, check=True, timeout=120)
            values = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
            values.update({key: fixture["GO_TEST_" + key] for key in ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
            values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
                AUTH_ENABLED="true", AUTH_USERNAME="p8-eval", AUTH_PASSWORD=secrets.token_urlsafe(24), APP_SECURITY_CSRF_ENABLED="true",
                APP_RATELIMIT_ENABLED="false", AI_CHAT_UPLOAD_DIR=str(directory / "uploads"), APP_CONTEXT_SUMMARY_ENABLED="false",
                APP_CONTEXT_AUTOMEMORY_ENABLED="false", APP_MEMORY_BACKFILL_ENABLED="false", APP_CALENDAR_SCANENABLED="false",
                MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false", AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai",
                OPENAI_BASE_URL="http://127.0.0.1:" + str(model.server_port), OPENAI_API_KEY="synthetic-fixture",
                OPENAI_CHAT_MODEL="mock-model", OPENAI_EMBEDDING_MODEL="mock-embedding", RAG_ENABLED="false")
            processes.BACKEND = "java"
            with processes.candidate(directory, binary, values) as state:
                client = Client(state)
                client.login(values)
                expected_binding = upload_binding(client)
                if database.sql("SELECT count(*) FROM knowledge_index_job;") != "0":
                    raise AssertionError("Java parameter rejection unexpectedly enqueued an index job")
            print("Original Java upload binding: " + json.dumps(expected_binding, ensure_ascii=False), flush=True)
            corpus = modules["rebuild-corpus.py"].CORPUS
            # Synthetic title repetition tests IO, not semantic recall against the missing articles.
            documents = {post: (title, "# P8 synthetic protocol fixture\n\n" + (title + "。\n") * 32) for post, title in corpus}
            processes.BACKEND = "go"
            with processes.candidate(directory, binary, values) as state:
                client = Client(state)
                client.login(values)
                actual_binding = upload_binding(client)
                if database.sql("SELECT count(*) FROM knowledge_index_job;") != "0":
                    raise AssertionError("Go parameter rejection unexpectedly enqueued an index job")
                if actual_binding != expected_binding:
                    print("Go upload binding: " + json.dumps(actual_binding, ensure_ascii=False), flush=True)
                    raise AssertionError("Go upload parameter binding differs from original Java")
                (SPEC / "p8-upload-binding.json").write_text(json.dumps(dict(result="PASS",
                    baseline_commit=json.loads((SPEC / "inventory.json").read_text())["baseline_commit"],
                    source_sha256=hashlib.sha256((ROOT / "backend/src/main/java/com/personalos/knowledge/controller/KnowledgeController.java").read_bytes()).hexdigest(),
                    job_controller_sha256=hashlib.sha256((ROOT / "backend/src/main/java/com/personalos/knowledge/jobs/KnowledgeJobController.java").read_bytes()).hexdigest(),
                    cases=actual_binding, synthetic_only=True, real_servlet_and_database=True,
                    rejected_targets_enqueued_zero_jobs=True), ensure_ascii=False, indent=2) + "\n")
                if binding_only:
                    return
                adapter = FixtureAdapter(client, database, documents)
                adapter.bind(modules)
                for post, title in corpus:
                    modules["rebuild-corpus.py"].upload(post, title)
                actual = client.request("/knowledge/documents")
                if {row["id"]: row["title"] for row in actual} != adapter.uploaded or len(actual) != len(corpus):
                    raise AssertionError("Evaluation corpus differs from its owned synthetic manifest")
                retrieval = modules["retrieval-eval.py"]
                rows = retrieval.evaluate()
                if len(rows) != len(modules["queries.py"].QUERIES) or adapter.search_calls != len(rows):
                    raise AssertionError("Original evaluator did not run the complete query set")
                summary = io.StringIO()
                with contextlib.redirect_stdout(summary):
                    retrieval.summarize(rows, "SYNTHETIC")
                hybrid_calls = adapter.search_calls
            # The original noise tool requires numeric cosine scores, not nullable hybrid scores.
            # Use a second explicit pure-vector fixture process; do not discard null samples.
            values["AI_KNOWLEDGE_HYBRID_ENABLED"] = "false"
            with processes.candidate(directory, binary, values) as state:
                client = Client(state)
                client.login(values)
                adapter.client = client
                noise_output = io.StringIO()
                with arguments(["noise-floor.py", "10", "5"]), contextlib.redirect_stdout(noise_output):
                    result = modules["noise-floor.py"].main()
                if result != 0 or adapter.search_calls - hybrid_calls != 50:
                    raise AssertionError("Original noise tool did not complete ten queries/five repetitions")
            if database.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();") != "0":
                raise AssertionError("Evaluation fixture retained application connections")
            report = dict(tooling_result="PASS", synthetic_only=True, original_articles_used=False,
                semantic_quality_verified=False, sources_sha256=hashes, documents=len(corpus), queries=len(rows),
                query_levels=dict(Counter(row["level"] for row in rows)), hybrid_search_calls=hybrid_calls,
                vector_noise_search_calls=adapter.search_calls - hybrid_calls,
                synthetic_summary=summary.getvalue().strip(),
                synthetic_noise="\n".join(line for line in noise_output.getvalue().splitlines() if not line.startswith("[mock] ")).strip(),
                checks=["owned random TLS PostgreSQL/pgvector and original Flyway42; initially empty",
                        "unchanged original CORPUS titles/query labels/evaluate/summarize/noise algorithms",
                        "only in-memory upload manifest and authenticated CSRF API; no original rebuild main/delete_all/fixed-target IO",
                        "all hits belong to fixture, titles/IDs agree and documents are unique",
                        "default hybrid evaluation; separate explicit pure-vector noise process",
                        "Java and both Go processes stopped and application database connections released"],
                limits="Synthetic repeated-title documents and deterministic word-bag embeddings only. Scores are tooling observations, not original 26-article/model quality, regression targets or release approval. The 0.007 tie tolerance and default 0.3 similarity threshold were not changed.")
            (SPEC / "p8-eval-tooling.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            print("P8 original evaluation tooling passed on synthetic owned fixture; real quality remains unverified", flush=True)
    finally:
        model.shutdown()
        model.server_close()
        thread.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixture", action="store_true", help="Start only newly owned synthetic resources")
    parser.add_argument("--corpus-dir", type=Path, help="Read-only preflight of explicitly provided original Markdown files")
    parser.add_argument("--binding-only", action="store_true", help="Actual Java/Go isolated upload parameter differential only")
    args = parser.parse_args()
    if args.fixture or args.binding_only:
        if args.corpus_dir:
            parser.error("Synthetic fixture never substitutes for a provided original corpus")
        run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                    exercise=lambda container, fixture: exercise(container, fixture, binding_only=args.binding_only))
        return 0
    modules, hashes = original_modules()
    report = corpus_preflight(modules, args.corpus_dir)
    report["sources_sha256"] = hashes
    print(json.dumps(report, ensure_ascii=False, indent=2))
    return 2 if report["result"] == "BLOCKED" else 0


if __name__ == "__main__":
    raise SystemExit(main())
