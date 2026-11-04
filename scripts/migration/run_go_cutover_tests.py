#!/usr/bin/env python3
"""Java-created synthetic backup -> isolated Go writes -> Java rollback; never a live target."""
import base64
from datetime import datetime, timedelta
import hashlib
import http.cookiejar
from http.server import ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

import go_backup
import run_go_e2e_tests as processes
from p8_model_fixture import Handler
from run_go_postgres_tests import IMAGE, ROOT, run_package


class Model(Handler):
    credential_calls = 0

    def do_POST(self):
        if self.path.endswith("/chat/completions") and self.headers.get("Authorization") == "Bearer p8-cutover-synthetic-key":
            type(self).credential_calls += 1
        super().do_POST()


class Client:
    def __init__(self, state, cookies=None):
        self.base = "http://127.0.0.1:" + str(state["port"]) + "/api/v1"
        self.jar = cookies if cookies is not None else http.cookiejar.CookieJar()
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(self.jar))
        self.csrf = ""

    def request(self, path, method="GET", value=None, status=200, headers=None):
        headers = dict(headers or {})
        if value is not None and not isinstance(value, bytes):
            value = json.dumps(value).encode()
            headers["Content-Type"] = "application/json"
        if method not in ("GET", "HEAD"):
            headers["X-CSRF-TOKEN"] = self.csrf
        request = urllib.request.Request(self.base + path, value, headers, method=method)
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            body = response.read()
            if response.status != status:
                raise AssertionError("Isolated cutover HTTP " + method + " " + path + " returned " + str(response.status))
            if "application/json" in response.headers.get("Content-Type", ""):
                parsed = json.loads(body)
                return parsed.get("data", parsed)
            return body

    def login(self, values):
        self.csrf = self.request("/auth/csrf")["token"]
        self.request("/auth/login", "POST", {"username": values["AUTH_USERNAME"], "password": values["AUTH_PASSWORD"]})
        self.csrf = self.request("/auth/csrf")["token"]

    def upload(self, path, name, content, status=200):
        boundary = "p8" + uuid.uuid4().hex
        body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + name + '"\r\nContent-Type: text/plain\r\n\r\n').encode() + content + ("\r\n--" + boundary + "--\r\n").encode()
        return self.request(path, "POST", body, status=status, headers={"Content-Type": "multipart/form-data; boundary=" + boundary})


def normalize(value):
    if isinstance(value, dict):
        return {key: normalize(item) for key, item in value.items() if key != "exportedAt"}
    if isinstance(value, list):
        return [normalize(item) for item in value]
    if isinstance(value, str) and len(value) > 19 and value[10] == "T":
        try:
            fraction = re.search(r"\.(\d+)(?:Z|[+-]\d{2}:\d{2})$", value)
            parsed_value = value
            if fraction:
                start, end = fraction.span(1)
                parsed_value = value[:start] + fraction[1][:6].ljust(6, "0") + value[end:]
            instant = datetime.fromisoformat(parsed_value.replace("Z", "+00:00"))
            # Match the fixed Java Instants helper and PostgreSQL storage precision.
            if fraction and len(fraction[1]) > 6 and fraction[1][6] >= "5":
                instant += timedelta(microseconds=1)
            return instant.isoformat(timespec="microseconds")
        except ValueError:
            pass
    return value


def differences(left, right, path="$"):
    if type(left) is not type(right):
        return [path + ":type"]
    if isinstance(left, dict):
        paths = [path + "." + key + ":missing" for key in left.keys() ^ right.keys()]
        for key in left.keys() & right.keys():
            paths.extend(differences(left[key], right[key], path + "." + key))
        return paths
    if isinstance(left, list):
        paths = [path + ":length"] if len(left) != len(right) else []
        for index, (old, new) in enumerate(zip(left, right)):
            paths.extend(differences(old, new, path + "[" + str(index) + "]"))
        return paths
    if left != right:
        if path.endswith((".createdAt", ".updatedAt")) and isinstance(left, str) and isinstance(right, str):
            return [path + ": " + left + " -> " + right]
        return [path + ":value"]
    return []


def measure(client, state):
    measurements = {}
    for path in ("/tasks", "/chat/sessions", "/knowledge/documents"):
        for _ in range(5):
            client.request(path)
        samples = []
        for _ in range(30):
            start = time.perf_counter()
            client.request(path)
            samples.append((time.perf_counter() - start) * 1000)
        samples.sort()
        measurements[path] = {"samples": len(samples), "p50_ms": round(samples[14], 3), "p95_ms": round(samples[28], 3)}
    rss = subprocess.run(["ps", "-o", "rss=", "-p", str(state["pid"])], capture_output=True, text=True, check=True, timeout=5).stdout.strip()
    return {"http": measurements, "rss_kib_after_warmup": int(rss)}


def exercise(container, fixture):
    if fixture.get("GO_TEST_POSTGRES") != "disposable" or fixture.get("GO_TEST_POSTGRES_SSL_MODE") != "verify-full":
        raise ValueError("Cutover refuses a non-disposable or non-TLS database")
    target = "p8_cutover_" + uuid.uuid4().hex[:16]
    user = fixture["GO_TEST_POSTGRES_USER"]
    source = fixture["GO_TEST_POSTGRES_DB"]

    def pg(command, *args, data=None):
        return subprocess.run(["docker", "exec", "--interactive", container, command, "--username=" + user, *args],
                              input=data, capture_output=True, check=True, timeout=60).stdout

    def sql(database, query):
        return pg("psql", "--no-psqlrc", "--dbname=" + database, "-At", "--set=ON_ERROR_STOP=1", "-c", query).decode().strip()

    def quiescent(database):
        if sql(database, "SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND pid<>pg_backend_pid()") != "0":
            raise AssertionError("Application/Worker connections remain before snapshot/rollback")

    report = {"baseline_commit": json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"],
              "date": "2026-11-04", "result": "IN_PROGRESS", "synthetic_only": True, "checks": []}
    model = ThreadingHTTPServer(("127.0.0.1", 0), Model)
    thread = threading.Thread(target=model.serve_forever, daemon=True)
    thread.start()
    created = False
    try:
        with tempfile.TemporaryDirectory(prefix="personalos-p8-cutover-") as temporary:
            directory = Path(temporary)
            binary = directory / "personalos"
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"], cwd=ROOT / "backend-go", check=True, timeout=120)
            values = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
            values.update({key: fixture["GO_TEST_" + key] for key in ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
            values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
                AUTH_ENABLED="true", AUTH_USERNAME="p8-cutover", AUTH_PASSWORD=secrets.token_urlsafe(24), APP_SECURITY_CSRF_ENABLED="true",
                APP_RATELIMIT_ENABLED="false", AI_CHAT_UPLOAD_DIR=str(directory / "uploads"),
                APP_CONTEXT_SUMMARY_ENABLED="false", APP_CONTEXT_AUTOMEMORY_ENABLED="false", APP_MEMORY_BACKFILL_ENABLED="false",
                APP_CALENDAR_SCANENABLED="false", MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false",
                MODEL_KEY_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
                AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai", OPENAI_BASE_URL="http://127.0.0.1:" + str(model.server_port),
                OPENAI_API_KEY="synthetic-fixture", OPENAI_CHAT_MODEL="mock-model", OPENAI_EMBEDDING_MODEL="mock-embedding", RAG_ENABLED="false")
            processes.BACKEND = "java"
            with processes.candidate(directory, binary, values) as state:
                java = Client(state)
                java.login(values)
                task = java.request("/tasks", "POST", {"title": "P8 Java existing", "tags": ["old", "ordered"], "dueAt": "2026-11-08T03:00:00Z"}, status=201)
                java.request("/memories", "POST", {"content": "P8 Java-created existing memory"})
                java.request("/calendar/events", "POST", {"title": "P8 Java existing calendar", "startsAt": "2026-11-08T03:00:00Z", "endsAt": "2026-11-08T04:00:00Z"}, status=201)
                attachment_bytes = b"P8 original Java attachment bytes"
                attachment = java.upload("/chat/uploads", "p8-existing.txt", attachment_bytes)
                document = java.upload("/knowledge/documents", "p8-existing.txt", ("P8 stored knowledge facts and migration evidence. " * 20).encode(), status=201)
                profile = java.request("/settings/model/profiles", "POST", {"name": "P8 Java encrypted key", "baseUrl": values["OPENAI_BASE_URL"], "apiKey": "p8-cutover-synthetic-key", "chatModels": ["p8-profile-chat"]})
                java.request("/settings/model/profiles/" + profile["id"] + "/activate", "POST")
                session = java.request("/chat/sessions", "POST", {"title": "P8 existing conversation"}, status=201)
                conversation = session["conversationId"]
                java.request("/chat/sessions/" + conversation + "/model", "PUT", {"model": "p8-profile-chat"})
                stream = java.request("/chat/sessions/" + conversation + "/stream", "POST", {"content": "P8 migration check", "attachments": [attachment]})
                if b"event:done" not in stream.replace(b" ", b""):
                    raise AssertionError("Java seed chat did not finish")
                snapshot = normalize(java.request("/export"))
                report["local_comparison"] = {"java": measure(java, state),
                    "scope": "One synthetic task/session/document, sequential authenticated loopback requests; five warmups then 30 samples; process RSS only, no production load or model quality claim."}
                old_cookies = java.jar
            quiescent(source)
            if Model.credential_calls < 1:
                raise AssertionError("Java did not use the configured synthetic profile key")
            if int(sql(source, "SELECT count(*) FROM vector_store")) < 1:
                raise AssertionError("Java seed did not produce persisted pgvector data")
            sql(source, "INSERT INTO operation_record(idempotency_key,source,operation,request_digest,outcome,created_at) VALUES('p8-cutover-uncertain','TOOL','p8 fixture','fixture','IN_PROGRESS',now())")
            encrypted = sql(source, "SELECT api_key FROM ai_model_profile")
            if encrypted == "p8-cutover-synthetic-key":
                raise AssertionError("Java seed key was not encrypted")
            configuration = go_backup.configuration(dict(GO_BACKUP_DB_CONTAINER=container, GO_BACKUP_DB_USER=user,
                GO_BACKUP_DB_NAME=source, GO_BACKUP_DIR=str(directory / "backups"), GO_BACKUP_UPLOAD_DIR=values["AI_CHAT_UPLOAD_DIR"]), "backup", allow_unencrypted=True)
            artifact = go_backup.baseline.backup(configuration)
            go_backup.baseline.verify(artifact, configuration, drill=True)
            dump = go_backup.baseline.unpack(artifact, directory / "restored")
            pg("createdb", target)
            created = True
            pg("pg_restore", "--exit-on-error", "--no-owner", "--no-acl", "--dbname=" + target, data=dump.read_bytes())
            values["POSTGRES_DB"] = target
            values["AI_CHAT_UPLOAD_DIR"] = str(directory / "restored/uploads")
            report["checks"].append("Java stopped including combined Workers; connections zero; original tar/SHA/custom dump restored to new DB with attachments")
            processes.BACKEND = "go"
            calls_before = Model.credential_calls
            with processes.candidate(directory, binary, values) as state:
                go = Client(state, old_cookies)
                go.request("/tasks", status=401)
                go.jar.clear()
                go.login(values)
                restored = normalize(go.request("/export"))
                mismatch = differences(snapshot, restored)
                if mismatch:
                    raise AssertionError("Restored Go export differs from Java snapshot: " + ", ".join(sorted(mismatch)[:20]))
                report["local_comparison"]["go"] = measure(go, state)
                if go.request("/chat/uploads/" + attachment["id"] + "/content") != attachment_bytes:
                    raise AssertionError("Restored Java attachment bytes differ")
                documents = go.request("/knowledge/documents")
                if not any(item["id"] == document["id"] for item in documents):
                    raise AssertionError("Restored knowledge document is not listed")
                key = {"Idempotency-Key": "p8-cutover-update-001"}
                path = "/tasks/" + task["id"]
                first = go.request(path, "PATCH", {"title": "P8 written by Go"}, headers=key)
                if go.request(path, "PATCH", {"title": "P8 written by Go"}, headers=key) != first:
                    raise AssertionError("Go did not replay the same operation result")
                go.request("/chat/sessions/" + conversation + "/stream", "POST", {"content": "P8 Go continuation"})
                if Model.credential_calls <= calls_before:
                    raise AssertionError("Go did not decrypt/use Java profile key")
                go_snapshot = normalize(go.request("/export"))
                go_cookies = go.jar
            quiescent(target)
            if sql(target, "SELECT api_key FROM ai_model_profile") != encrypted:
                raise AssertionError("Go rewrote Java profile ciphertext")
            if sql(target, "SELECT count(*) FROM operation_record WHERE idempotency_key='p8-cutover-update-001' AND outcome='SUCCESS'") != "1":
                raise AssertionError("Unexpected repeated Go write")
            if sql(target, "SELECT count(*) FROM operation_record WHERE idempotency_key='p8-cutover-uncertain' AND outcome='IN_PROGRESS'") != "1":
                raise AssertionError("Unknown external outcome was replayed or cleared")
            report["checks"].append("Go requires new login; exact normalized five-module export; attachment bytes; vectors; Java encrypted model key; one idempotent write; unknown TOOL preserved")
            processes.BACKEND = "java"
            with processes.candidate(directory, binary, values) as state:
                rollback = Client(state, go_cookies)
                rollback.request("/tasks", status=401)
                rollback.jar.clear()
                rollback.login(values)
                if normalize(rollback.request("/export")) != go_snapshot:
                    raise AssertionError("Java rollback cannot read Go-written data")
                if rollback.request("/chat/uploads/" + attachment["id"] + "/content") != attachment_bytes:
                    raise AssertionError("Rollback attachment bytes differ")
                replay = rollback.request("/tasks/" + task["id"], "PATCH", {"title": "P8 written by Go"}, headers=key)
                if replay != first:
                    raise AssertionError("Java cannot replay Go idempotency record")
            quiescent(target)
            if sql(target, "SELECT count(*) FROM flyway_schema_history WHERE success") != "42":
                raise AssertionError("Cutover changed original Flyway history")
            report["checks"].append("Go stopped including Workers/connections zero; Java new login, Go data/read/replay and attachment bytes unchanged; 42 migrations")
            report.update(result="PASS", backup_sha256=hashlib.sha256(artifact.read_bytes()).hexdigest(),
                limitation="Only newly created synthetic Java data and local attachments; not a real user backup, real external side effects, production TLS or release approval.")
            print("P8 isolated Java backup -> Go writes -> Java rollback passed", flush=True)
    finally:
        model.shutdown()
        model.server_close()
        thread.join(timeout=5)
        if created:
            pg("dropdb", target)
        if report["result"] != "PASS":
            report["result"] = "FAIL"
        report_path = ROOT / "specs/033-go-rewrite/p8-cutover.json"
        report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$", exercise=exercise)
