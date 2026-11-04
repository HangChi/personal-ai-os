#!/usr/bin/env python3
"""Bounded local read capacity comparison; never a production SLO approval."""
import argparse
from concurrent.futures import ThreadPoolExecutor
import copy
from datetime import datetime, timezone
import hashlib
import http.cookiejar
from http.server import ThreadingHTTPServer
import json
import math
import os
from pathlib import Path
import subprocess
import tempfile
import threading
import time

from go_test_environment import cache_environment
from p8_model_fixture import Handler
from run_go_cutover_tests import Client, normalize
from run_go_demo_seed_tests import OwnedDatabase
from run_go_original_eval_tests import environment
from run_go_paired_eval_tests import require_quiescent
import run_go_e2e_tests as processes
from run_go_postgres_tests import IMAGE, ROOT, run_package

SPEC = ROOT / "specs/033-go-rewrite"
PATHS = ("/tasks?size=20", "/chat/sessions", "/knowledge/documents")


def validate_load(concurrency, requests, tasks):
    if (type(concurrency) is not int or not 1 <= concurrency <= 32
            or type(requests) is not int or not concurrency <= requests <= 10000
            or type(tasks) is not int or not 1 <= tasks <= 5000):
        raise ValueError("Explicit bounded load required: workers 1..32, requests workers..10000, tasks 1..5000")


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=True, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def percentile(values, fraction):
    return round(sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)], 3) if values else None


def clone_client(client, state):
    jar = http.cookiejar.CookieJar()
    for cookie in client.jar:
        jar.set_cookie(copy.copy(cookie))
    result = Client(state, jar, timeout=10)
    result.csrf = client.csrf
    return result


def measure(client, state, concurrency, requests):
    validate_load(concurrency, requests, 1)
    responses = {path: client.request(path) for path in PATHS}
    raw_hashes = {path: digest(value) for path, value in responses.items()}
    expected = {path: digest(normalize(value)) for path, value in responses.items()}
    for _ in range(5):
        for path in PATHS:
            if digest(normalize(client.request(path))) != expected[path]:
                raise AssertionError("Local warmup response drift")
    barrier = threading.Barrier(concurrency)

    def worker(index):
        owned = clone_client(client, state)
        rows = []
        barrier.wait(timeout=15)
        for position in range(index, requests, concurrency):
            path, start = PATHS[position % len(PATHS)], time.perf_counter()
            try:
                if digest(normalize(owned.request(path))) != expected[path]:
                    raise AssertionError("Response digest mismatch")
                failure = None
            except Exception as error:
                failure = type(error).__name__
            rows.append(dict(path=path, milliseconds=(time.perf_counter() - start) * 1000, error_type=failure))
        return rows

    started = time.perf_counter()
    with ThreadPoolExecutor(max_workers=concurrency) as executor:
        groups = list(executor.map(worker, range(concurrency)))
    elapsed = time.perf_counter() - started
    rows = [row for group in groups for row in group]
    if len(rows) != requests:
        raise AssertionError("Load runner did not execute its exact request budget")
    endpoints = {}
    for path in PATHS:
        selected = [row for row in rows if row["path"] == path]
        timings = [row["milliseconds"] for row in selected]
        endpoints[path] = dict(requests=len(selected), failures=sum(row["error_type"] is not None for row in selected),
            p50_ms=percentile(timings, .5), p95_ms=percentile(timings, .95), p99_ms=percentile(timings, .99),
            max_ms=round(max(timings), 3) if timings else None)
    rss = subprocess.run(["ps", "-o", "rss=", "-p", str(state["pid"])],
                         check=True, text=True, capture_output=True, timeout=5).stdout.strip()
    return dict(requests=requests, concurrency=concurrency, elapsed_seconds=round(elapsed, 3),
        requests_per_second=round(requests / elapsed, 3),
        failures=sum(row["error_type"] is not None for row in rows),
        failure_types=sorted({row["error_type"] for row in rows if row["error_type"]}),
        response_hashes=expected, raw_response_hashes=raw_hashes, endpoints=endpoints, rss_kib_after_load=int(rss))


def exercise(container, fixture, binary, directory, args, report):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    model = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=model.serve_forever, daemon=True)
    thread.start()
    values = environment(fixture, directory, dict(AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai",
        OPENAI_BASE_URL="http://127.0.0.1:" + str(model.server_port), OPENAI_API_KEY="synthetic-fixture",
        OPENAI_CHAT_MODEL="mock-model", OPENAI_EMBEDDING_MODEL="mock-embedding"))
    values.update(OPENSEARCH_ENABLED="false", RAG_ENABLED="false")
    try:
        report["stage"] = "java-fixture"
        report["seed_progress"] = dict(tasks=0, sessions=0, documents=0)
        processes.BACKEND = "java"
        with processes.candidate(directory, binary, values) as state:
            client = Client(state)
            client.login(values)
            for position in range(args.tasks):
                client.request("/tasks", "POST", dict(title="P8 capacity task " + str(position),
                    tags=["capacity", "group-" + str(position % 10)]), status=201)
                report["seed_progress"]["tasks"] += 1
            for position in range(30):
                client.request("/chat/sessions", "POST", dict(title="P8 capacity session " + str(position)), status=201)
                report["seed_progress"]["sessions"] += 1
            for position in range(10):
                client.upload("/knowledge/documents", "capacity-" + str(position) + ".txt",
                    (("Synthetic local capacity fixture " + str(position) + ". ") * 20).encode(), status=201)
                report["seed_progress"]["documents"] += 1
            if client.request("/tasks?size=20")["total"] != args.tasks:
                raise AssertionError("Capacity fixture task count differs")
        require_quiescent(database)
        report["fixture_counts"] = database.counts()
        snapshot = None
        for backend in ("java", "go"):
            report["stage"] = backend + "-load"
            processes.BACKEND = backend
            progress = report["backends"].setdefault(backend, dict(result="IN_PROGRESS"))
            try:
                with processes.candidate(directory, binary, values) as state:
                    client = Client(state)
                    client.login(values)
                    before = digest(normalize(client.request("/export")))
                    if snapshot is not None and snapshot != before:
                        raise AssertionError("Paired backends did not read the same seeded data")
                    snapshot = before
                    progress.update(measure(client, state, args.concurrency, args.requests))
                    if digest(normalize(client.request("/export"))) != before:
                        raise AssertionError("Read load changed exported business data")
                    progress["business_data_unchanged"] = True
                require_quiescent(database)
                progress.update(processes_stopped=True, database_connections_after_stop=0,
                    result="PASS" if progress["failures"] == 0 else "FAIL")
                print("Capacity " + backend + ": " + progress["result"], flush=True)
            except Exception as error:
                progress.update(result="FAIL", error_type=type(error).__name__)
                raise
        report["matching_response_hashes"] = report["backends"]["java"]["response_hashes"] == report["backends"]["go"]["response_hashes"]
        report["raw_response_hashes_match"] = report["backends"]["java"]["raw_response_hashes"] == report["backends"]["go"]["raw_response_hashes"]
        if not report["matching_response_hashes"]:
            raise AssertionError("Paired capacity response hashes differ")
        report["stage"] = "complete"
    finally:
        model.shutdown()
        model.server_close()
        thread.join(timeout=5)
        require_quiescent(database)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--concurrency", type=int, required=True)
    parser.add_argument("--requests", type=int, required=True)
    parser.add_argument("--tasks", type=int, required=True)
    args = parser.parse_args()
    validate_load(args.concurrency, args.requests, args.tasks)
    path = SPEC / ("p8-capacity-" + datetime.now().strftime("%Y%m%d-%H%M%S") + ".json")
    report = dict(result="IN_PROGRESS", started_at=datetime.now(timezone.utc).isoformat(),
        scope="Local closed-loop authenticated HTTP reads; one worker owns each client, no connection pooling; response and data integrity only.",
        timestamp_normalization="Original baseline instant comparator, UTC microsecond precision; raw response hashes retained separately.",
        production_capacity_accepted=False, target_slo_provided=False, release_gates_changed=False,
        real_model_used=False, synthetic_only=True, backends={})
    code = 1
    try:
        with tempfile.TemporaryDirectory(prefix="personalos-p8-capacity-") as temporary:
            directory = Path(temporary)
            binary = directory / "personalos"
            build = {key: os.environ[key] for key in ("PATH", "HOME", "TMPDIR") if key in os.environ}
            build.update(cache_environment(), GOPROXY="off", GOTOOLCHAIN="local", GOMAXPROCS="4")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"],
                           cwd=ROOT / "backend-go", env=build, check=True, timeout=120)
            run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                exercise=lambda container, fixture: exercise(container, fixture, binary, directory, args, report))
        report["result"] = "PASS" if all(row["result"] == "PASS" for row in report["backends"].values()) else "FAIL"
        code = 0 if report["result"] == "PASS" else 1
    except Exception as error:
        report.update(result="FAIL", error_type=type(error).__name__)
        if isinstance(error, AssertionError):
            report["failure"] = str(error)
        print("Capacity check failed; see redacted report", flush=True)
    finally:
        report["finished_at"] = datetime.now(timezone.utc).isoformat()
        with path.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, ensure_ascii=False, indent=2) + chr(10))
        print("Capacity report: " + str(path), flush=True)
    return code


if __name__ == "__main__":
    raise SystemExit(main())
