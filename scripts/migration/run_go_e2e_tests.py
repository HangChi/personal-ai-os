#!/usr/bin/env python3
"""Run the unchanged original E2E cases against an isolated authenticated P8 process."""
import argparse
from contextlib import contextmanager
import http.client
import http.cookiejar
from http.server import ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import threading
import time
import urllib.request

import go_runtime
from p8_model_fixture import Handler
from run_go_local_tests import free_port, reap
from run_go_postgres_tests import IMAGE, ROOT, run_package

BACKEND = "go"
GROUPS = None
MODE = "e2e"


@contextmanager
def candidate(directory, binary, values):
    if BACKEND == "go":
        with go_runtime.locked(directory / "runtime") as runtime:
            state = go_runtime.start(runtime, "combined", binary, ROOT / "backend/src/main/resources/db/migration",
                                     values, free_port(), free_port())
            try:
                yield state
            finally:
                go_runtime.stop(runtime, "combined")
                reap(state["pid"])
        return
    # The Java artifact is built by the unchanged verify gate; startup has no dotenv or shared DB.
    jar = ROOT / "backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar"
    if not jar.is_file():
        raise FileNotFoundError("Build the unchanged Java baseline with verify first")
    port = free_port()
    environment = dict(values, SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(port), APP_DOTENV_ENABLED="false",
        SPRING_DATASOURCE_URL="jdbc:postgresql://" + values["POSTGRES_HOST"] + ":" + values["POSTGRES_PORT"] + "/" + values["POSTGRES_DB"]
            + "?sslmode=verify-full&sslrootcert=" + values["GO_POSTGRES_ROOT_CERT"])
    with (directory / "java.log").open("w") as output:
        process = subprocess.Popen(["java", "-Xmx512m", "-Dapp.dotenv.enabled=false", "-jar", str(jar)],
                                   cwd=directory, env=environment, stdout=output, stderr=subprocess.STDOUT)
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Isolated Java baseline exited; no live service was selected")
                try:
                    with opener.open("http://127.0.0.1:" + str(port) + "/api/v1/health", timeout=2) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                if time.monotonic() >= deadline:
                    raise TimeoutError("Isolated Java baseline startup exceeded 90 seconds")
                time.sleep(.2)
            yield {"port": port}
        finally:
            process.terminate()
            process.wait(timeout=45)


def verify_cancellation(suite, jar, csrf):
    """Close an actual partial stream; check upstream cancellation and Java persistence policy."""
    session = suite.new_session("P8 explicit cancellation")
    cookie_request = urllib.request.Request(suite.BASE + "/chat/sessions/" + session + "/stream")
    jar.add_cookie_header(cookie_request)
    connection = http.client.HTTPConnection(suite.HOST, suite.PORT, timeout=15)
    response = None
    names = []
    Handler.stream_canceled.clear()
    try:
        connection.request("POST", suite.BASE_PATH + "/chat/sessions/" + session + "/stream",
            body=json.dumps({"content": "请从 1 数到 200，每个数字单独一行，不要省略。"}).encode(),
            headers={"Content-Type": "application/json", "Cookie": cookie_request.get_header("Cookie", ""), "X-CSRF-TOKEN": csrf})
        response = connection.getresponse()
        if response.status != 200 or "text/event-stream" not in response.getheader("Content-Type", ""):
            raise AssertionError("Cancellation did not open an authenticated SSE stream")
        while True:
            line = response.readline()
            if not line:
                raise AssertionError("Stream completed before cancellation")
            if line.startswith(b"event:"):
                names.append(line[6:].strip().decode())
            if line in (b"\n", b"\r\n") and names and names[-1] == "delta":
                break
    finally:
        if response is not None:
            response.close()
        connection.close()
    if not Handler.stream_canceled.wait(8):
        raise AssertionError("Canceled HTTP stream did not stop its upstream model connection")
    time.sleep(.3)
    messages = suite.messages(session)
    assistants = [item for item in messages if item["role"] == "ASSISTANT"]
    if assistants:
        raise AssertionError("Legacy canceled stream persisted a partial assistant message")
    if len([item for item in messages if item["role"] == "USER"]) != 1 or "done" in names:
        raise AssertionError("Canceled stream has invalid user/done state")
    report = {"backend": BACKEND, "result": "PASS", "events_before_close": names,
              "user_messages": 1, "assistant_messages": 0, "upstream_canceled": True}
    suite.REPORT_DIR.mkdir(parents=True, exist_ok=True)
    (suite.REPORT_DIR / "cancellation.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + chr(10))
    print("P8 explicit HTTP cancellation, upstream stop and no partial assistant passed", flush=True)


def exercise(container, fixture):
    if fixture.get("GO_TEST_POSTGRES") != "disposable":
        raise ValueError("E2E refuses a non-disposable database")
    model = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    thread = threading.Thread(target=model.serve_forever, daemon=True)
    thread.start()
    try:
        with tempfile.TemporaryDirectory(prefix="personalos-p8-e2e-") as temporary:
            directory = Path(temporary)
            binary = directory / "personalos"
            if BACKEND == "go":
                subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"], cwd=ROOT / "backend-go", check=True, timeout=120)
            values = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
            values.update({key: fixture["GO_TEST_" + key] for key in ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
            values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
                AUTH_ENABLED="true", AUTH_USERNAME="p8-fixture", AUTH_PASSWORD=secrets.token_urlsafe(24), APP_SECURITY_CSRF_ENABLED="true",
                AI_CHAT_UPLOAD_DIR=str(directory / "uploads"), APP_CONTEXT_SUMMARY_ENABLED="false", APP_CONTEXT_AUTOMEMORY_ENABLED="false",
                APP_MEMORY_BACKFILL_ENABLED="false", APP_CALENDAR_SCANENABLED="false", MCP_ENABLED="false", SPRING_AI_MCP_SERVER_ENABLED="false",
                AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai", OPENAI_BASE_URL="http://127.0.0.1:" + str(model.server_port),
                OPENAI_API_KEY="synthetic-fixture", OPENAI_CHAT_MODEL="mock-model", OPENAI_EMBEDDING_MODEL="mock-embedding",
                RAG_ENABLED="false", AI_KNOWLEDGE_SIMILARITY_THRESHOLD="0.05")
            with candidate(directory, binary, values) as state:
                    if MODE == "browser":
                        browser_environment = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR", "NODE_PATH"}}
                        browser_environment.update(GO_P8_UI_BASE="http://127.0.0.1:" + str(state["port"]),
                            GO_P8_UI_USERNAME=values["AUTH_USERNAME"], GO_P8_UI_PASSWORD=values["AUTH_PASSWORD"],
                            GO_P8_UI_TEMP=str(directory), GO_P8_UI_BACKEND=BACKEND)
                        subprocess.run(["node", str(ROOT / "scripts/migration/run_go_browser_tests.mjs")],
                                       env=browser_environment, cwd=ROOT, check=True, timeout=240)
                        return
                    spec = importlib.util.spec_from_file_location("original_e2e", ROOT / "scripts/e2e/e2e_suite.py")
                    suite = importlib.util.module_from_spec(spec)
                    spec.loader.exec_module(suite)
                    suite.HOST, suite.PORT = "127.0.0.1", state["port"]
                    suite.BASE = "http://127.0.0.1:" + str(state["port"]) + suite.BASE_PATH
                    suite.PG_CONTAINER, suite.PG_USER, suite.PG_DB = container, fixture["GO_TEST_POSTGRES_USER"], fixture["GO_TEST_POSTGRES_DB"]
                    suite.REPORT_DIR = ROOT / "specs/033-go-rewrite/p8-e2e" / BACKEND
                    jar = http.cookiejar.CookieJar()
                    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))
                    csrf = ""

                    def urlopen(request, timeout=60):
                        if not request.full_url.startswith(suite.BASE + "/"):
                            raise ValueError("Original E2E attempted to leave its disposable origin")
                        if request.get_method() not in ("GET", "HEAD", "OPTIONS"):
                            request.add_header("X-CSRF-TOKEN", csrf)
                        return opener.open(request, timeout=timeout)
                    suite.urlopen = urlopen
                    csrf = suite.get("/auth/csrf").data["token"]
                    suite.ok(suite.post("/auth/login", {"username": "p8-fixture", "password": values["AUTH_PASSWORD"]}))
                    csrf = suite.get("/auth/csrf").data["token"]
                    original_sse = suite.sse

                    def sse(path, body, headers=None, **kwargs):
                        cookie_request = urllib.request.Request(suite.BASE + path)
                        jar.add_cookie_header(cookie_request)
                        merged = {"Cookie": cookie_request.get_header("Cookie", ""), "X-CSRF-TOKEN": csrf}
                        merged.update(headers or {})
                        return original_sse(path, body, headers=merged, **kwargs)
                    suite.sse = sse
                    if MODE == "cancel":
                        verify_cancellation(suite, jar, csrf)
                        return
                    original_stream = suite.stream_chat

                    def stream_chat(*args, **kwargs):
                        result = original_stream(*args, **kwargs)
                        if result.first("error") is not None:
                            print("Synthetic E2E stream error: " + json.dumps(result.first("error"), ensure_ascii=False), flush=True)
                        return result
                    suite.stream_chat = stream_chat
                    if suite.run(GROUPS) != 0:
                        raise AssertionError("Original E2E failures recorded in specs/033-go-rewrite/p8-e2e")
    finally:
        model.shutdown()
        model.server_close()
        thread.join(timeout=5)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=("go", "java"), default="go")
    parser.add_argument("--groups", nargs="+")
    parser.add_argument("--mode", choices=("e2e", "cancel", "browser"), default="e2e")
    arguments = parser.parse_args()
    BACKEND, GROUPS = arguments.backend, set(arguments.groups) if arguments.groups else None
    MODE = arguments.mode
    # No P8 mutation tests before the original suite: G0 requires the new migration DB to be empty.
    run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$", exercise=exercise)
