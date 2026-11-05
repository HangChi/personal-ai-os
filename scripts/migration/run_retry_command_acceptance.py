#!/usr/bin/env python3
"""Real Java/Go, authenticated browser and installed external MCP; disposable TLS DB only."""
import argparse
from contextlib import contextmanager
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

from run_go_postgres_tests import IMAGE, ROOT, run_package
from run_go_local_tests import free_port


class API:
    def __init__(self, base, username, password):
        self.base, self.csrf = base, ""
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.request("GET", "/auth/csrf")["data"]["token"]
        self.request("POST", "/auth/login", {"username": username, "password": password})
        self.csrf = self.request("GET", "/auth/csrf")["data"]["token"]

    def request(self, method, path, body=None, key=None, expected=200):
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if method != "GET":
            headers["X-CSRF-TOKEN"] = self.csrf
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(self.base + "/api/v1" + path,
            data=None if body is None else json.dumps(body).encode(), headers=headers, method=method)
        try:
            response = self.opener.open(request, timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            if response.status != expected:
                raise AssertionError((method, path, response.status, expected, raw.decode()[:1200]))
            return json.loads(raw)

    def command(self, conversation, key, tool, arguments, expected=200):
        return self.request("POST", "/chat/sessions/" + conversation + "/tools",
            {"tool": tool, "arguments": arguments}, key, expected)


@contextmanager
def application(backend, directory, values, java_jar, binary, label):
    port = free_port()
    env = dict(values, SERVER_ADDRESS="127.0.0.1", SERVER_PORT=str(port), APP_DOTENV_ENABLED="false",
               MANAGEMENT_SERVER_PORT=str(free_port()),
               GO_DOTENV_ENABLED="false", APP_RUNTIME_ROLE="combined")
    if backend == "java":
        env["MANAGEMENT_SERVER_ADDRESS"] = "127.0.0.1"
        env["SPRING_DATASOURCE_URL"] = ("jdbc:postgresql://" + values["POSTGRES_HOST"] + ":" +
            values["POSTGRES_PORT"] + "/" + values["POSTGRES_DB"] + "?sslmode=verify-full&sslrootcert=" +
            values["GO_POSTGRES_ROOT_CERT"])
        command = ["java", "-Xmx512m", "-Dapp.dotenv.enabled=false", "-jar", str(java_jar)]
    else:
        command = [str(binary), "--foundation", "--p8", "--migrations",
                   str(ROOT / "backend/src/main/resources/db/migration")]
    with (directory / (label + ".log")).open("w") as log:
        process = subprocess.Popen(command, cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            deadline = time.monotonic() + 90
            while True:
                if process.poll() is not None:
                    raise RuntimeError("Isolated " + backend + " exited; inspect " + label + ".log")
                try:
                    with opener.open("http://127.0.0.1:" + str(port) + "/api/v1/health", timeout=2) as response:
                        if response.status == 200:
                            break
                except OSError:
                    pass
                if time.monotonic() >= deadline:
                    raise TimeoutError("Isolated backend failed to become healthy")
                time.sleep(.2)
            yield "http://127.0.0.1:" + str(port)
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=45)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=10)
                    raise AssertionError("Isolated application required a forced kill")


def verify_mcp(api, sandbox, journal, report):
    conversation = api.request("POST", "/chat/sessions", {"title": "External MCP retry acceptance"}, expected=201)["data"]["conversationId"]
    tools = api.request("GET", "/chat/tools")["data"]
    assert any(t["name"] == "mcp_fs_edit_file" for t in tools), "External filesystem catalog missing"
    path = sandbox / "success.txt"
    path.write_text("ANCHOR\n")
    args = {"path": str(path), "edits": [{"oldText": "ANCHOR", "newText": "ANCHOR\nAPPLIED"}]}
    key = str(uuid.uuid4())
    first = api.command(conversation, key, "mcp_fs_edit_file", args)["data"]
    assert first["result"]["status"] == "OK", first
    assert path.read_text().count("APPLIED") == 1
    replay = api.command(conversation, key, "mcp_fs_edit_file", args)["data"]
    assert replay["replayed"] and replay["result"] == first["result"]
    assert path.read_text().count("APPLIED") == 1
    api.command(conversation, str(uuid.uuid4()), "mcp_fs_edit_file", args)
    assert path.read_text().count("APPLIED") == 2, "Fixture must detect a genuine second execution"
    report["checks"].append("external AUTO edit executes once per identity; new identity executes again")

    unknown = sandbox / "lose-response-edit.txt"
    unknown.write_text("ANCHOR\n")
    lost_args = dict(args, path=str(unknown))
    lost_key = str(uuid.uuid4())
    lost = api.command(conversation, lost_key, "mcp_fs_edit_file", lost_args)["data"]
    assert lost["result"]["status"] == "ERROR", lost
    assert unknown.read_text().count("APPLIED") == 1, "The server must commit before losing its reply"
    before = len(journal.read_text().splitlines())
    api.command(conversation, lost_key, "mcp_fs_edit_file", lost_args, expected=409)
    api.request("POST", "/mcp/servers/fs/reconnect", {})
    api.command(conversation, lost_key, "mcp_fs_edit_file", lost_args, expected=409)
    assert unknown.read_text().count("APPLIED") == 1
    assert len(journal.read_text().splitlines()) == before
    report["checks"].append("committed external edit with lost reply stays unknown; retries and reconnect never resend")

    source, destination = sandbox / "move-source.txt", sandbox / "move-destination.txt"
    source.write_text("fixture")
    move_args = {"source": str(source), "destination": str(destination)}
    move_key = str(uuid.uuid4())
    first = api.command(conversation, move_key, "mcp_fs_move_file", move_args)["data"]
    assert first["result"]["status"] == "REQUIRES_CONFIRMATION", first
    confirmation = first["result"]["confirmationId"]
    replay = api.command(conversation, move_key, "mcp_fs_move_file", move_args)["data"]
    assert replay["result"]["confirmationId"] == confirmation
    assert source.is_file() and not destination.exists()
    pending = api.request("GET", "/agent/confirmations?status=PENDING&conversationId=" + conversation)["data"]
    assert len(pending) == 1 and pending[0]["id"] == confirmation
    decision = {"token": pending[0]["token"]}
    approved = api.request("POST", "/agent/confirmations/" + confirmation + "/confirm", decision)["data"]
    assert approved["executed"], approved
    assert not source.exists() and destination.read_text() == "fixture"
    api.request("POST", "/agent/confirmations/" + confirmation + "/confirm", decision, expected=409)
    api.command(conversation, move_key, "mcp_fs_move_file", move_args)
    report["checks"].append("external CONFIRM move waits for approval; duplicate approval cannot execute again")

    lost_source, lost_destination = sandbox / "lose-response-move.txt", sandbox / "lost-destination.txt"
    lost_source.write_text("committed move")
    pending_move = api.command(conversation, str(uuid.uuid4()), "mcp_fs_move_file",
        {"source": str(lost_source), "destination": str(lost_destination)})["data"]
    lost_confirmation = pending_move["result"]["confirmationId"]
    pending = api.request("GET", "/agent/confirmations?status=PENDING&conversationId=" + conversation)["data"]
    token = next(item["token"] for item in pending if item["id"] == lost_confirmation)
    api.request("POST", "/agent/confirmations/" + lost_confirmation + "/confirm", {"token": token}, expected=409)
    assert not lost_source.exists() and lost_destination.read_text() == "committed move"
    failed = api.request("GET", "/agent/confirmations?status=FAILED&conversationId=" + conversation)["data"]
    assert any(item["id"] == lost_confirmation for item in failed)
    before = len(journal.read_text().splitlines())
    api.request("POST", "/agent/confirmations/" + lost_confirmation + "/confirm", {"token": token}, expected=409)
    api.request("POST", "/mcp/servers/fs/reconnect", {})
    api.request("POST", "/agent/confirmations/" + lost_confirmation + "/confirm", {"token": token}, expected=409)
    assert len(journal.read_text().splitlines()) == before
    report["checks"].append("confirmed external move commits before reply loss; FAILED receipt cannot be approved again, including after reconnect")
    rows = [json.loads(line) for line in journal.read_text().splitlines()]
    assert len(rows) == 5 and all(row["operationId"] for row in rows), rows
    assert rows[-2]["operationId"] == "confirmation:" + confirmation, rows[-2]
    assert rows[-1]["operationId"] == "confirmation:" + lost_confirmation, rows[-1]
    report["external_calls"] = len(rows)
    return conversation, lost_key, lost_args, key, args, lost_confirmation, token


def run(args, container, fixture):
    assert fixture.get("GO_TEST_POSTGRES") == "disposable"
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="personalos-retry-acceptance-") as temporary:
        # macOS /var is a symlink; the external server compares allowed paths lexically.
        directory = Path(temporary).resolve()
        sandbox = directory / "sandbox"
        sandbox.mkdir()
        journal = directory / "mcp-calls.jsonl"
        report = {"backend": args.backend, "result": "FAIL", "checks": [], "real_external_server":
            {"package": "@modelcontextprotocol/server-filesystem", "version": args.server_version},
            "remote_dedup_claimed": False, "scope": "isolated TLS DB, authenticated application, external stdio server in a fresh sandbox"}
        values = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
        values.update({key: fixture["GO_TEST_" + key] for key in
            ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
        proxy_args = ["-u", str(ROOT / "scripts/migration/mcp_stdio_fault_proxy.py"),
            "--node", shutil.which("node"), "--server", str(args.filesystem_server),
            "--sandbox", str(sandbox), "--journal", str(journal), "--fault-marker", str(directory / "lost.marker")]
        if any("," in item for item in proxy_args):
            raise ValueError("MCP list binding requires comma-free fixture paths")
        values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
            AUTH_ENABLED="true", AUTH_USERNAME="retry-fixture", AUTH_PASSWORD=secrets.token_urlsafe(24),
            APP_SECURITY_CSRF_ENABLED="true", AI_CHAT_UPLOAD_DIR=str(directory / "uploads"),
            APP_CONTEXT_SUMMARY_ENABLED="false", APP_CONTEXT_AUTO_MEMORY_ENABLED="false",
            APP_MEMORY_BACKFILL_ENABLED="false", APP_CALENDAR_SCAN_ENABLED="false",
            APP_RATE_LIMIT_ENABLED="false", RAG_ENABLED="false", MCP_ENABLED="true",
            MCP_SERVERS_FS_COMMAND=sys.executable, MCP_SERVERS_FS_ARGS=",".join(proxy_args),
            MCP_SERVERS_FS_AUTO_TOOLS="edit_file", MCP_SERVERS_FS_CONNECT_TIMEOUT="5s",
            SPRING_AI_MCP_SERVER_ENABLED="false", AI_CHAT_PROVIDER="openai", AI_EMBEDDING_PROVIDER="openai",
            OPENAI_API_KEY="synthetic-unused", OPENAI_BASE_URL="http://127.0.0.1:1",
            OPENAI_CHAT_MODEL="unused-fixture", OPENAI_EMBEDDING_MODEL="unused-fixture")
        binary = directory / "personalos"
        if args.backend == "go":
            env = dict(os.environ, GOPROXY="off", GOTOOLCHAIN="local")
            subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"], cwd=ROOT / "backend-go", env=env, check=True, timeout=180)
        try:
            with application(args.backend, directory, values, args.java_jar, binary, "first") as base:
                api = API(base, values["AUTH_USERNAME"], values["AUTH_PASSWORD"])
                retained = verify_mcp(api, sandbox, journal, report)
            with application(args.backend, directory, values, args.java_jar, binary, "restart") as base:
                api = API(base, values["AUTH_USERNAME"], values["AUTH_PASSWORD"])
                conversation, lost_key, lost_args, success_key, success_args, lost_confirmation, token = retained
                before = journal.read_bytes()
                api.command(conversation, lost_key, "mcp_fs_edit_file", lost_args, expected=409)
                api.request("POST", "/agent/confirmations/" + lost_confirmation + "/confirm", {"token": token}, expected=409)
                assert api.command(conversation, success_key, "mcp_fs_edit_file", success_args)["data"]["replayed"]
                assert journal.read_bytes() == before, "Restart must not erase persistent ownership"
                report["checks"].append("application restart preserves successful replay and unknown-operation ownership")
                if args.browser:
                    env = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR", "NODE_PATH"}}
                    env.update(RETRY_UI_BASE=base, RETRY_UI_USERNAME=values["AUTH_USERNAME"],
                        RETRY_UI_PASSWORD=values["AUTH_PASSWORD"], RETRY_UI_TEMP=str(directory),
                        RETRY_UI_BACKEND=args.backend, RETRY_UI_OUTPUT=str(output))
                    subprocess.run(["node", str(ROOT / "scripts/migration/run_retry_commands_browser.mjs")],
                                   cwd=ROOT, env=env, check=True, timeout=240)
                    report["checks"].append("real authenticated browser command workflow passed")
            report["result"] = "PASS"
        finally:
            for name in ("first.log", "restart.log"):
                if (directory / name).exists():
                    shutil.copyfile(directory / name, output / (args.backend + "-" + name))
            if journal.exists():
                shutil.copyfile(journal, output / (args.backend + "-mcp-calls.jsonl"))
            (output / (args.backend + "-acceptance.json")).write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", choices=("java", "go"), required=True)
    parser.add_argument("--java-jar", type=Path)
    parser.add_argument("--filesystem-server", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--browser", action="store_true")
    args = parser.parse_args()
    args.filesystem_server = args.filesystem_server.resolve(strict=True)
    package = json.loads((args.filesystem_server.parent.parent / "package.json").read_text())
    assert package["name"] == "@modelcontextprotocol/server-filesystem"
    args.server_version = package["version"]
    if args.backend == "java":
        if args.java_jar is None:
            parser.error("--java-jar is required for Java")
        args.java_jar = args.java_jar.resolve(strict=True)
    run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8ContainerListenerBoundary$",
                exercise=lambda container, fixture: run(args, container, fixture))


if __name__ == "__main__":
    main()
