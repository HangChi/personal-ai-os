#!/usr/bin/env python3
"""Frozen three-case real-model conflict/clarification evaluation on a fresh TLS database."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile

from go_live_conflicts import execute, original_program
from go_real_model import ModelFailure, RealModel, load_config, pooled_transport, serve
from go_test_environment import cache_environment
from run_go_cutover_tests import Client
from run_go_demo_seed_tests import OwnedDatabase
from run_go_paired_eval_tests import require_quiescent
import run_go_e2e_tests as processes
from run_go_postgres_tests import IMAGE, ROOT, run_package

SPEC = ROOT / "specs/033-go-rewrite"


def exercise(container, fixture, backend, binary, model, relay_values, private_directory, report):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    with tempfile.TemporaryDirectory(prefix="personalos-p8-live-conflict-runtime-") as temporary:
        directory = Path(temporary)
        values = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
        values.update({key: fixture["GO_TEST_" + key] for key in
                       ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
        values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
                      AUTH_ENABLED="true", AUTH_USERNAME="p8-conflicts", AUTH_PASSWORD=secrets.token_urlsafe(24),
                      APP_SECURITY_CSRF_ENABLED="true", APP_RATELIMIT_ENABLED="false",
                      AI_CHAT_UPLOAD_DIR=str(directory / "uploads"), APP_CONTEXT_SUMMARY_ENABLED="false",
                      APP_CONTEXT_AUTOMEMORY_ENABLED="false", APP_MEMORY_BACKFILL_ENABLED="false",
                      AI_CALENDAR_SCAN_ENABLED="false", MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false",
                      AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai", RAG_ENABLED="true",
                      APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED="false",
                      EMBEDDING_CACHE_ENABLED="false", RAG_CANDIDATE_CACHE_ENABLED="false",
                      AI_KNOWLEDGE_HYBRID_ENABLED="true")
        values.update(relay_values)
        processes.BACKEND = backend
        try:
            with processes.candidate(directory, binary, values) as state:
                client = Client(state, timeout=180)
                client.login(values)
                space = client.request("/knowledge/governance/spaces", "POST", {"name": "P8 owned conflicts " + secrets.token_hex(8)})

                def verify_answer(response, calls):
                    stored = json.loads(database.sql("SELECT json_agg(x) FROM (SELECT metadata->>'document_id' AS id,"
                        "(metadata->>'chunk_index')::int AS index,metadata->>'document_title' AS title,"
                        "encode(sha256(convert_to(content,'UTF8')),'hex') AS sha256 FROM vector_store) x;"))
                    chunks = {(row["id"], row["index"], row["title"]): row["sha256"] for row in stored}
                    for citation in response.get("citations", []):
                        key = (citation["documentId"], citation["chunkIndex"], citation["documentTitle"])
                        if chunks.get(key) != hashlib.sha256(citation["content"].encode()).hexdigest():
                            raise AssertionError("Real conflict citation differs from its persisted policy chunk")
                    retrieval = response.get("retrieval", {})
                    if (retrieval.get("warnings") or calls != 3
                            or [(row.get("name"), row.get("status")) for row in retrieval.get("stages", [])] !=
                            [(name, "OK") for name in ("recall", "rewrite", "rerank", "evidence")]):
                        raise AssertionError("Real conflict stages degraded or did not use three foreground model calls")

                try:
                    execute(client, space, model, private_directory, report, verify_answer)
                except SystemExit:
                    raise AssertionError("Frozen conflict/clarification expectations were not met") from None
                remaining = json.loads(database.sql("SELECT json_build_object(" + ",".join(
                    "'" + table + "',(SELECT count(*) FROM " + table + ")" for table in
                    ("knowledge_document", "vector_store", "knowledge_source", "knowledge_review_job")) + ");"))
                if any(remaining.values()):
                    raise AssertionError("Original conflict cleanup left owned documents or review jobs")
                report.update(cleanup_counts=remaining, citation_chunks_match_database=True,
                              background_ingestion_review_enabled=False)
        finally:
            require_quiescent(database)
            report.update(processes_stopped=True, database_connections_after_stop=0)
            for path in directory.rglob("*.log"):
                if model.config.api_key.encode() in path.read_bytes():
                    raise AssertionError("Real model credential leaked into a conflict runtime log")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model-env-file", required=True, type=Path)
    parser.add_argument("--backend", required=True, choices=("go", "java"))
    args = parser.parse_args()
    original_program()
    config = load_config(args.model_env_file)
    private_directory = Path(tempfile.mkdtemp(prefix="personalos-p8-live-conflict-evidence-")).resolve()
    report_path = SPEC / ("p8-live-conflicts-" + args.backend + "-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    report = dict(result="IN_PROGRESS", backend=args.backend, started_at=datetime.now(timezone.utc).isoformat(),
                  model=config.metadata(), configured_real_model_used=True, synthetic_documents=True,
                  private_answer_evidence=str(private_directory), expected_cases=3,
                  semantic_quality_manually_approved=False, release_gates_changed=False,
                  limits="Original frozen synthetic-policy conflict/clarification cases; owned TLS database only. "
                         "No production configuration, original runtime entry point, existing data or model fallback. "
                         "Background ingestion review disabled only for the isolated foreground test; "
                         "this does not cover the 129 original retrieval queries, full Agent or production acceptance.")
    code, model = 1, None
    try:
        with pooled_transport(config) as opener, tempfile.TemporaryDirectory(prefix="personalos-p8-live-conflict-build-") as temporary:
            model = RealModel(config, opener)
            binary = Path(temporary) / "personalos"
            build = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
            build.update(cache_environment(), GOPROXY="off", GOTOOLCHAIN="local", GOMAXPROCS="4")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"],
                           cwd=ROOT / "backend-go", env=build, check=True, timeout=120)
            with serve(model) as relay_values:
                run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                    exercise=lambda container, fixture: exercise(container, fixture, args.backend, binary, model,
                                                               relay_values, private_directory, report))
            if model.snapshot()["failures"]:
                raise AssertionError("Real conflict upstream failures were observed")
        report["result"], code = "PASS", 0
    except Exception as error:
        report["result"] = "FAIL"
        report["failure"] = str(error) if isinstance(error, (ModelFailure, AssertionError)) else type(error).__name__
        print("Live conflict evaluation failed: " + report["failure"] + "; no retries or raw error output", flush=True)
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        report["real_model_requests"] = model.snapshot() if model is not None else {}
        with report_path.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print("Live conflict report: " + str(report_path), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
