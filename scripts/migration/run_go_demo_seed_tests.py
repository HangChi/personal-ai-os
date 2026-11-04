#!/usr/bin/env python3
"""Exercise the unchanged demo recipe only on a new disposable TLS Go fixture."""
import hashlib
from http.server import ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import threading
from urllib.parse import quote
import uuid

from go_test_environment import cache_environment
from p8_model_fixture import Handler
from run_go_cutover_tests import Client
import run_go_e2e_tests as processes
from run_go_postgres_tests import IMAGE, ROOT, run_package

SPEC = ROOT / "specs/033-go-rewrite"
SOURCE = "scripts/dev/seed-demo-data.py"
RECIPE = ("seed_tasks", "seed_events", "seed_knowledge", "seed_memory", "seed_conversations", "seed_usage", "seed_audit")
TABLES = ("task", "task_tag", "calendar_event", "reminder", "knowledge_document", "vector_store", "memory",
          "chat_session", "chat_message", "chat_message_citation", "agent_plan", "agent_plan_step",
          "confirmation", "operation_record", "token_usage")


def original_seed():
    inventory = json.loads((SPEC / "inventory.json").read_text())
    entry = next(row for row in inventory["files"] if row["path"] == SOURCE)
    if hashlib.sha256((ROOT / SOURCE).read_bytes()).hexdigest() != entry["sha256"]:
        raise ValueError("Original demo recipe differs from the fixed baseline")
    spec = importlib.util.spec_from_file_location("original_demo_seed", ROOT / SOURCE)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, entry["sha256"]


class OwnedDatabase:
    def __init__(self, container, fixture):
        port = fixture.get("GO_TEST_POSTGRES_PORT", "")
        if (not re.fullmatch(r"[0-9a-f]{64}", container)
                or fixture.get("GO_TEST_POSTGRES") != "disposable"
                or fixture.get("GO_TEST_POSTGRES_SSL_MODE") != "verify-full"
                or fixture.get("GO_TEST_POSTGRES_HOST") != "127.0.0.1"
                or fixture.get("GO_TEST_POSTGRES_USER") != "go_fixture"
                or fixture.get("GO_TEST_POSTGRES_DB") != "personalos_go_test"
                or not port.isascii() or not port.isdecimal() or not 0 < int(port) < 65536):
            raise ValueError("Demo seed refuses a non-disposable TLS fixture")
        # Inspect only ownership/network fields, never container environment or process arguments.
        template = ('{"owned":{{json (index .Config.Labels "personalos.go-test")}},'
                    '"name":{{json .Name}},"running":{{json .State.Running}},'
                    '"bindings":{{json (index .NetworkSettings.Ports "5432/tcp")}}}')
        result = subprocess.run(["docker", "inspect", "--format", template, container],
                                text=True, capture_output=True, check=True, timeout=10)
        info = json.loads(result.stdout)
        if (info.get("owned") != "true" or info.get("running") is not True
                or not re.fullmatch(r"/personalos-go-test-[0-9a-f]{12}", info.get("name", ""))
                or info.get("bindings") != [{"HostIp": "127.0.0.1", "HostPort": port}]):
            raise ValueError("Demo seed refuses a database outside its owned runner")
        self.container = container

    def sql(self, statement):
        result = subprocess.run(["docker", "exec", "--interactive", self.container, "psql", "--no-psqlrc",
                                 "--username=go_fixture", "--dbname=personalos_go_test", "--set=ON_ERROR_STOP=1",
                                 "--quiet", "--tuples-only", "--no-align"],
                                input=statement, text=True, capture_output=True, timeout=60)
        if result.returncode:
            raise RuntimeError("Owned demo SQL failed; no statement or credentials printed")
        return result.stdout.strip()

    def counts(self):
        fields = ",".join("'" + table + "',(SELECT count(*) FROM " + table + ")" for table in TABLES)
        return json.loads(self.sql("SELECT json_build_object(" + fields + ");"))

    def require_empty(self):
        if any(self.counts().values()):
            raise ValueError("Demo seed requires empty business tables; it never resets them")


def bind_recipe(seed, client, database):
    def api(method, path, body=None):
        allowed = ((method == "POST" and path in {"/tasks", "/calendar/events", "/memories"})
                   or (method == "POST" and re.fullmatch(r"/tasks/[0-9a-f-]{36}/complete", path))
                   or (method == "PATCH" and re.fullmatch(r"/memories/[0-9a-f-]{36}/pin", path)))
        if not allowed:
            raise ValueError("Original demo recipe attempted an unexpected HTTP operation")
        status = 201 if method == "POST" and path in {"/tasks", "/calendar/events"} else 200
        return client.request(path, method, body, status=status)

    def upload(filename, title, content):
        if not filename.endswith(".md") or any(char in filename for char in '/\\"\r\n'):
            raise ValueError("Unexpected demo upload name")
        boundary = "p8-demo-" + uuid.uuid4().hex
        body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + filename
                + '"\r\nContent-Type: text/markdown; charset=utf-8\r\n\r\n' + content
                + "\r\n--" + boundary + "--\r\n").encode()
        return client.request("/knowledge/documents?title=" + quote(title, safe=""), "POST", body, status=201,
                              headers={"Content-Type": "multipart/form-data; boundary=" + boundary})

    def refuse_reset():
        raise ValueError("The demo adapter never invokes the original reset or main entry point")

    seed.api, seed.upload_document, seed.sql, seed.reset = api, upload, database.sql, refuse_reset


def run_recipe(seed):
    for name in RECIPE:
        getattr(seed, name)()


def verify_seed(seed, client, database):
    documents = client.request("/knowledge/documents")
    if len(documents) != 4:
        raise AssertionError("Original demo knowledge document count differs")
    tasks = client.request("/tasks?size=100")["items"]
    actual_tasks = {row["title"]: row for row in tasks}
    if len(tasks) != len(seed.TASKS):
        raise AssertionError("Original demo task count differs")
    for title, description, priority, due, status, tags in seed.TASKS:
        task = actual_tasks[title]
        if (task["description"], task["priority"], task["status"], task["tags"]) != (description, priority, status, tags):
            raise AssertionError("Original demo task fields differ")
        if (task["dueAt"] is None) != (due is None) or (task["completedAt"] is not None) != (status == "DONE"):
            raise AssertionError("Original demo task deadline/completion state differs")
    snapshot = client.request("/export")
    events = {row["title"]: row for row in snapshot["calendarEvents"]}
    if len(events) != len(seed.EVENTS):
        raise AssertionError("Original demo event count differs")
    for title, description, location, all_day, _, _, _, _, _, _, recurrence, lead in seed.EVENTS:
        event = events[title]
        if (event["description"], event["location"], event["allDay"], event["recurrenceType"], event["reminderLeadMinutes"]) != (
                description, location, all_day, recurrence or "NONE", lead):
            raise AssertionError("Original demo event fields differ")
    memories = client.request("/memories")
    if len(memories) != 5 or sum(row["pinned"] for row in memories) != 2:
        raise AssertionError("Original demo memory/pin count differs")
    sessions = {row["session"]["title"]: row for row in snapshot["sessions"]}
    if len(sessions) != len(seed.CONVERSATIONS):
        raise AssertionError("Original demo conversation count differs")
    for expected in seed.CONVERSATIONS:
        actual = sessions[expected["title"]]["messages"]
        if [(row["role"], row["content"]) for row in actual] != expected["messages"]:
            raise AssertionError("Original demo persisted conversation content differs")
        if [row["sequenceNo"] for row in actual] != list(range(1, len(actual) + 1)):
            raise AssertionError("Original demo message order differs")
    usage = client.request("/usage?days=31")
    counts = database.counts()
    outcomes = json.loads(database.sql("SELECT json_object_agg(outcome,n) FROM (SELECT outcome,count(*) n FROM operation_record GROUP BY outcome) s;"))
    if outcomes != {"SUCCESS": 5, "REQUIRES_CONFIRMATION": 2, "REJECTED": 1, "FAILED": 1}:
        raise AssertionError("Original demo audit outcomes differ")
    if (counts["token_usage"] != usage["turns"] or len(usage["daily"]) != 31 or not usage["hasData"]
            or usage["actualTokens"] <= 0 or usage["estimatedTokens"] != 0):
        raise AssertionError("Original demo 31-day usage is not visible through Go")
    if counts["vector_store"] < 4 or any(row["chunkCount"] <= 0 or row["charCount"] <= 0 for row in documents):
        raise AssertionError("Original demo knowledge chunks/vectors are missing")
    return counts


def exercise(container, fixture):
    database = OwnedDatabase(container, fixture)
    database.require_empty()
    seed, source_hash = original_seed()
    model = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=model.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="personalos-p8-demo-") as temporary:
            directory = Path(temporary)
            binary = directory / "personalos"
            build_environment = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
            build_environment.update(cache_environment(), GOPROXY="off", GOTOOLCHAIN="local", GOMAXPROCS="4")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"], cwd=ROOT / "backend-go",
                           env=build_environment, check=True, timeout=120)
            values = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
            values.update({key: fixture["GO_TEST_" + key] for key in ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
            values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
                AUTH_ENABLED="true", AUTH_USERNAME="p8-demo", AUTH_PASSWORD=secrets.token_urlsafe(24), APP_SECURITY_CSRF_ENABLED="true",
                APP_RATELIMIT_ENABLED="false", AI_CHAT_UPLOAD_DIR=str(directory / "uploads"), APP_CONTEXT_SUMMARY_ENABLED="false",
                APP_CONTEXT_AUTOMEMORY_ENABLED="false", APP_MEMORY_BACKFILL_ENABLED="false", APP_CALENDAR_SCANENABLED="false",
                MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false", AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai",
                OPENAI_BASE_URL="http://127.0.0.1:" + str(model.server_port), OPENAI_API_KEY="synthetic-fixture",
                OPENAI_CHAT_MODEL="mock-model", OPENAI_EMBEDDING_MODEL="mock-embedding", RAG_ENABLED="false")
            processes.BACKEND = "go"
            with processes.candidate(directory, binary, values) as state:
                client = Client(state)
                client.login(values)
                bind_recipe(seed, client, database)
                run_recipe(seed)
                counts = verify_seed(seed, client, database)
            if database.sql("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid();") != "0":
                raise AssertionError("Demo fixture retained application connections after shutdown")
            report = dict(result="PASS", synthetic_only=True, source=SOURCE, source_sha256=source_hash, counts=counts,
                          checks=["owned disposable TLS container; empty tables; no original main/reset",
                                  "unchanged seven-function demo recipe; authenticated CSRF API and owned SQL",
                                  "eight task states/tags; six recurrence/all-day events; five memories/two pins",
                                  "four synchronously ingested Markdown documents with persisted pgvector chunks",
                                  "five exact ordered conversations; 31-day actual usage; nine audit outcomes",
                                  "combined stopped and all application connections released"],
                          limits="Disposable synthetic demo only; no existing user database, real model quality, browser rendering or release approval.")
            (SPEC / "p8-demo-seed.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
            print("P8 unchanged demo recipe passed on its disposable Go fixture", flush=True)
    finally:
        model.shutdown()
        model.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$", exercise=exercise)
