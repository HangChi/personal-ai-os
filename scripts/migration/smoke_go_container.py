#!/usr/bin/env python3
"""Smoke the actual P8 image in the network namespace of this runner's disposable TLS DB.

No Compose operation, real .env, published application port or existing volume is used.
The caller must provide its freshly created DB container ID and ephemeral certificate.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid


def capture(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, timeout=45, **kwargs).stdout.strip()


def run(image, postgres_container, fixture):
    if fixture.get("GO_TEST_POSTGRES") != "disposable" or fixture.get("GO_TEST_POSTGRES_SSL_MODE") != "verify-full":
        raise ValueError("P8 image test requires this runner's disposable TLS database")
    if len(postgres_container) != 64 or any(c not in "0123456789abcdef" for c in postgres_container):
        raise ValueError("Invalid disposable container identity")
    certificate = Path(fixture["GO_TEST_POSTGRES_ROOT_CERT"]).resolve()
    if not certificate.is_file():
        raise ValueError("Disposable certificate unavailable")
    password = secrets.token_urlsafe(24)
    containers = []
    try:
        for role in ("combined", "api", "index", "review", "delivery"):
            suffix = uuid.uuid4().hex[:12]
            values = dict(
                GO_DOTENV_ENABLED="false", SPRING_PROFILES_ACTIVE="monitoring" + (",worker-" + role if role in {"index","review","delivery"} else ""),
                APP_RUNTIME_ROLE=role, SERVER_PORT="18081", MANAGEMENT_SERVER_PORT="18091",
                POSTGRES_HOST="127.0.0.1", POSTGRES_PORT="5432", POSTGRES_DB=fixture["GO_TEST_POSTGRES_DB"],
                POSTGRES_USER=fixture["GO_TEST_POSTGRES_USER"], POSTGRES_PASSWORD=fixture["GO_TEST_POSTGRES_PASSWORD"],
                GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT="/run/postgres-ca.crt",
                AUTH_ENABLED="true", AUTH_USERNAME="p8-fixture", AUTH_PASSWORD=password,
                APP_SECURITY_CSRF_ENABLED="true", APP_RATE_LIMIT_ENABLED="false",
                AI_CHAT_UPLOAD_DIR="/app/data/uploads", APP_CHAT_ATTACHMENTJANITORENABLED="false",
                APP_CONTEXT_SUMMARY_ENABLED="false", APP_CONTEXT_AUTOMEMORY_ENABLED="false",
                APP_MEMORY_BACKFILL_ENABLED="false", APP_CALENDAR_SCANENABLED="false", MCP_ENABLED="false",
                SPRING_AI_MCP_SERVER_ENABLED="false")
            with tempfile.TemporaryDirectory(prefix="personalos-p8-container-") as directory:
                env_path = Path(directory, "fixture.env")
                descriptor = os.open(env_path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(descriptor, "w") as output:
                    output.write("".join(key + "=" + value + "\n" for key, value in values.items()))
                container = capture(["docker", "run", "--detach", "--name", "personalos-p8-" + role + "-" + suffix,
                    "--label", "personalos.go-p8-test=true", "--network", "container:" + postgres_container,
                    "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--user", "10001:10001",
                    "--tmpfs", "/tmp:rw,noexec,nosuid,size=32m,mode=1777",
                    "--tmpfs", "/app/data/uploads:rw,nosuid,size=32m,uid=10001,gid=10001,mode=0700",
                    "--mount", "type=bind,source=" + str(certificate) + ",target=/run/postgres-ca.crt,readonly",
                    "--env-file", str(env_path), image])
            if len(container) != 64 or any(c not in "0123456789abcdef" for c in container):
                raise RuntimeError("Invalid created container identity")
            containers.append(container)

            def http(path, management=False, extra=None, expected=200):
                result = capture(["docker", "exec", container, "curl", "--silent", "--show-error", "--max-time", "5",
                                  "--write-out", "\nHTTP_STATUS:%{http_code}", *(extra or []),
                                  "http://127.0.0.1:" + ("18091" if management else "18081") + path])
                body, status = result.rsplit("HTTP_STATUS:", 1)
                body = body.rstrip("\n")
                if status != str(expected):
                    raise AssertionError("P8 " + role + " HTTP contract failed: " + path + " status=" + status)
                return body

            deadline = time.monotonic() + 60
            while True:
                state = json.loads(capture(["docker", "inspect", "--format", "{{json .State}}", container]))
                if not state["Running"]:
                    raise AssertionError("P8 " + role + " exited during startup")
                try:
                    http("/actuator/health", management=True)
                    break
                except (subprocess.CalledProcessError, AssertionError):
                    if time.monotonic() >= deadline:
                        raise TimeoutError("P8 " + role + " management did not become healthy")
                    time.sleep(0.25)
            if capture(["docker", "exec", container, "id", "-u"]) != "10001":
                raise AssertionError("P8 image is not non-root")
            metrics = http("/actuator/prometheus", management=True)
            if "personalos_" not in metrics:
                raise AssertionError("P8 management metrics missing")
            http("/api/v1/tasks", management=True, expected=403)
            http("/actuator/env", management=True, expected=403)
            if role in {"combined", "api"}:
                http("/api/v1/tasks", expected=401)
                http("/api/v1/tasks", extra=["-X", "POST", "-H", "Content-Type: application/json", "--data", '{"title":"not-authorized"}'], expected=403)
                csrf = json.loads(http("/api/v1/auth/csrf", extra=["-c", "/tmp/p8-cookies"]))["data"]
                http("/api/v1/auth/login", extra=["-b", "/tmp/p8-cookies", "-c", "/tmp/p8-cookies", "-H", "Content-Type: application/json",
                     "-H", "X-CSRF-TOKEN: " + csrf["token"], "--data", json.dumps({"username":"p8-fixture", "password":password})])
                http("/api/v1/tasks", extra=["-b", "/tmp/p8-cookies"])
                http("/readyz", extra=["-b", "/tmp/p8-cookies"], expected=503)
                http("/actuator/prometheus", extra=["-b", "/tmp/p8-cookies"], expected=404)
            else:
                for path in ("/api/v1/tasks", "/api/v1/auth/me", "/livez", "/readyz"):
                    http(path, expected=404)
            capture(["docker", "stop", "--time", "35", container])
            exit_code = capture(["docker", "inspect", "--format", "{{.State.ExitCode}}", container])
            if exit_code != "0":
                raise AssertionError("P8 " + role + " did not exit gracefully")
            capture(["docker", "rm", container])
            containers.remove(container)
            print("P8 actual image: " + role + " non-root/TLS/auth/CSRF/management/isolation/SIGTERM passed", flush=True)
    finally:
        for container in reversed(containers):
            subprocess.run(["docker", "rm", "--force", container], check=True, capture_output=True, timeout=45)
