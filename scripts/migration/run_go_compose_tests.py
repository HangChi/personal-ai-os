#!/usr/bin/env python3
"""Actual candidate Compose in a new random project; no existing stack, .env or volume."""
import argparse
import base64
import http.cookiejar
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import uuid

from disposable_tls import create_tls_fixture

ROOT = Path(__file__).resolve().parents[2]


def capture(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, timeout=180, **kwargs).stdout.strip()


def run(monitoring=False):
    project = "personalos-go-p8-drill-" + uuid.uuid4().hex[:12]
    with tempfile.TemporaryDirectory(prefix="personalos-p8-compose-") as temporary:
        server, _ = create_tls_fixture(temporary, dns_names=("postgres",))
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", 0))
            port = probe.getsockname()[1]
        environment = {key: value for key, value in os.environ.items() if key in {
            "PATH", "HOME", "DOCKER_HOST", "DOCKER_CONTEXT", "DOCKER_CONFIG"}}
        environment.update(GO_COMPOSE_PROJECT=project, GO_WEB_PORT=str(port),
            GO_AUTH_USERNAME="p8-fixture", GO_AUTH_PASSWORD=secrets.token_urlsafe(24),
            GO_POSTGRES_DB="p8_fixture", GO_POSTGRES_USER="p8_fixture", GO_POSTGRES_PASSWORD=secrets.token_urlsafe(24),
            GO_MODEL_KEY_ENCRYPTION_KEY=base64.b64encode(secrets.token_bytes(32)).decode(),
            GO_POSTGRES_TLS_DIR=str(server), GO_POSTGRES_CA=str(Path(temporary) / "ca.crt"))
        command = ["docker", "compose", "--env-file", "/dev/null", "--project-name", project,
                   "-f", str(ROOT / "backend-go/deploy/compose.yml")]
        grafana_port = None
        if monitoring:
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                grafana_port = probe.getsockname()[1]
            environment.update(GO_GRAFANA_ADMIN_USER="p8-monitor", GO_GRAFANA_ADMIN_PASSWORD=secrets.token_urlsafe(24),
                               GO_GRAFANA_PORT=str(grafana_port))
            command += ["-f", str(ROOT / "backend-go/deploy/compose.monitoring.yml")]
        started = False
        containers = {}
        try:
            # This project name is randomly created here; no prior project is selected.
            started = True
            capture(command + ["up", "--detach", "--no-build"], cwd=ROOT, env=environment)
            for service in ("postgres", "migrate", "backend", "web", *(("prometheus", "grafana") if monitoring else ())):
                identity = capture(command + ["ps", "--all", "--quiet", service], cwd=ROOT, env=environment)
                if len(identity) != 64 or any(c not in "0123456789abcdef" for c in identity):
                    raise AssertionError("Missing disposable Compose service: " + service)
                label = capture(["docker", "inspect", "--format", '{{index .Config.Labels "com.docker.compose.project"}}', identity])
                if label != project:
                    raise AssertionError("Compose resource ownership mismatch")
                containers[service] = identity
            for service in ("backend", "web"):
                if capture(["docker", "exec", containers[service], "id", "-u"]) != "10001":
                    raise AssertionError("Disposable Compose service is not running as UID 10001: " + service)
            deadline = time.monotonic() + 90
            while True:
                result = subprocess.run(["docker", "exec", containers["backend"], "curl", "--fail", "--silent", "--max-time", "2",
                                         "http://127.0.0.1:9091/actuator/health"], capture_output=True, timeout=8)
                if result.returncode == 0 and json.loads(result.stdout).get("status") == "UP":
                    break
                if time.monotonic() >= deadline:
                    raise TimeoutError("Disposable Compose backend did not become healthy")
                time.sleep(.3)
            jar = http.cookiejar.CookieJar()
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(jar))

            def request(path, method="GET", body=None, token=None, expected=200, headers=None, target_port=port):
                values = dict(headers or {})
                if body is not None and not isinstance(body, bytes):
                    body = json.dumps(body).encode()
                    values["Content-Type"] = "application/json"
                if token:
                    values["X-CSRF-TOKEN"] = token
                message = urllib.request.Request("http://127.0.0.1:" + str(target_port) + path, data=body, headers=values, method=method)
                try:
                    response = opener.open(message, timeout=10)
                except urllib.error.HTTPError as error:
                    response = error
                with response:
                    payload = response.read()
                    if response.status != expected:
                        raise AssertionError("Compose HTTP " + path + " unexpected status " + str(response.status))
                    return payload, response.headers

            deadline = time.monotonic() + 60
            while True:
                try:
                    html, headers = request("/")
                    break
                except (urllib.error.URLError, OSError):
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Disposable Caddy did not become reachable") from None
                    time.sleep(.2)
            if b'<div id="app">' not in html or headers.get("X-Content-Type-Options") != "nosniff":
                raise AssertionError("Original frontend or security headers missing")
            for path in ("/actuator", "/actuator/health", "/actuator/prometheus", "/livez", "/readyz"):
                request(path, expected=404)
            request("/api/v1/tasks", expected=401)
            request("/api/v1/auth/login", "POST", {"username": "p8-fixture", "password": "invalid"}, expected=403)
            csrf = json.loads(request("/api/v1/auth/csrf")[0])["data"]["token"]
            request("/api/v1/auth/login", "POST", {"username": "p8-fixture", "password": environment["GO_AUTH_PASSWORD"]}, token=csrf)
            csrf = json.loads(request("/api/v1/auth/csrf")[0])["data"]["token"]
            task = json.loads(request("/api/v1/tasks", "POST", {"title": "P8 Compose persisted"}, token=csrf, expected=201)[0])["data"]
            request("/api/v1/tasks/" + task["id"])
            boundary = "p8" + uuid.uuid4().hex
            content = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="p8.txt"\r\n'
                       'Content-Type: text/plain\r\n\r\nP8 compose attachment\r\n--' + boundary + "--\r\n").encode()
            attachment = json.loads(request("/api/v1/chat/uploads", "POST", content, token=csrf,
                    headers={"Content-Type": "multipart/form-data; boundary=" + boundary})[0])["data"]
            if request("/api/v1/chat/uploads/" + attachment["id"] + "/content")[0] != b"P8 compose attachment":
                raise AssertionError("Caddy multipart/download content changed")
            for path in ("/chat", "/tasks", "/knowledge", "/calendar", "/memories", "/settings"):
                if request(path)[0] != html:
                    raise AssertionError("SPA route fallback failed: " + path)
            rows = capture(["docker", "exec", containers["postgres"], "psql", "-U", "p8_fixture", "-d", "p8_fixture", "-Atc",
                            "SELECT count(*) FROM flyway_schema_history WHERE success"])
            if rows != "42":
                raise AssertionError("Candidate Flyway did not apply exactly 42 original migrations")
            # Re-run the same external executor; Go never performs the migration itself.
            capture(command + ["run", "--rm", "--no-deps", "migrate"], cwd=ROOT, env=environment)
            if monitoring:
                capture(["docker", "exec", containers["prometheus"], "/bin/promtool", "check", "config", "/etc/prometheus/prometheus.yml"])
                capture(["docker", "exec", containers["prometheus"], "/bin/promtool", "check", "rules", "/etc/prometheus/alerts.yml"])
                capture(["docker", "exec", "--workdir", "/etc/prometheus", containers["prometheus"], "/bin/promtool", "test", "rules", "/etc/prometheus/alerts.test.yml"])
                deadline = time.monotonic() + 90
                while True:
                    try:
                        up = json.loads(capture(["docker", "exec", containers["backend"], "curl", "--fail", "--silent", "--max-time", "3",
                                                "http://prometheus:9090/api/v1/query?query=up"]))
                        health = json.loads(request("/api/health", target_port=grafana_port)[0])
                        if up["status"] == "success" and len(up["data"]["result"]) == 1 and up["data"]["result"][0]["value"][1] == "1" and health["database"] == "ok":
                            break
                    except (OSError, ValueError, KeyError, subprocess.SubprocessError):
                        pass
                    if time.monotonic() >= deadline:
                        raise TimeoutError("Disposable Prometheus/Grafana did not become ready")
                    time.sleep(.5)
                credentials = base64.b64encode((environment["GO_GRAFANA_ADMIN_USER"] + ":" + environment["GO_GRAFANA_ADMIN_PASSWORD"]).encode()).decode()
                dashboard = json.loads(request("/api/dashboards/uid/personalos-go-runtime", headers={"Authorization": "Basic " + credentials}, target_port=grafana_port)[0])
                if len(dashboard["dashboard"]["panels"]) != 20:
                    raise AssertionError("Go Grafana dashboard was not provisioned with all 20 panels")
                for metric in ("go_memory_used_bytes", "personalos_go_database_connections", "personalos_knowledge_jobs_pending", "personalos_knowledge_review_failed", "personalos_knowledge_search_pending"):
                    result = json.loads(capture(["docker", "exec", containers["backend"], "curl", "--fail", "--silent", "--max-time", "3",
                                                 "http://prometheus:9090/api/v1/query?query=" + metric]))
                    if result["status"] != "success" or not result["data"]["result"]:
                        raise AssertionError("Prometheus did not ingest actual Go metric: " + metric)
                print("P8 actual monitoring: promtool config/rules, live Go scrape/runtime/knowledge gauges, provisioned Grafana 20 panels passed", flush=True)
            capture(command + ["stop", "--timeout", "40", "backend", "web"], cwd=ROOT, env=environment)
            if capture(["docker", "inspect", "--format", "{{.State.ExitCode}}", containers["backend"]]) != "0":
                raise AssertionError("Compose backend did not stop gracefully")
            print("P8 actual Compose: original Flyway42/revalidate, TLS DB, non-root Go/Web, auth/CSRF, SPA/Caddy, upload/download, graceful stop passed", flush=True)
        except Exception as error:
            detail = (error.stderr or "") if isinstance(error, subprocess.CalledProcessError) else ""
            for service in ("backend", "web", "prometheus", "grafana"):
                if service not in containers:
                    continue
                try:
                    logs = subprocess.run(["docker", "logs", "--tail", "12", containers[service]],
                                          text=True, capture_output=True, timeout=10)
                    detail += "\n" + service + ": " + logs.stdout + logs.stderr
                except subprocess.SubprocessError:
                    pass
            for key in ("GO_AUTH_PASSWORD", "GO_POSTGRES_PASSWORD", "GO_MODEL_KEY_ENCRYPTION_KEY", "GO_GRAFANA_ADMIN_PASSWORD"):
                if key in environment:
                    detail = detail.replace(environment[key], "[redacted]")
            print("Disposable Compose error: " + detail[-4000:], flush=True)
            raise
        finally:
            if started:
                capture(command + ["down", "--volumes", "--remove-orphans", "--timeout", "40"], cwd=ROOT, env=environment)
                print("Removed only this runner's random Compose project/volumes", flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--monitoring", action="store_true")
    arguments = parser.parse_args()
    run(arguments.monitoring)
