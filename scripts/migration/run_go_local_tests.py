#!/usr/bin/env python3
"""Verify Go local lifecycle/discovery/backup using only this runner's fresh TLS DB."""
import importlib.util
import json
import os
import platform
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import time
import uuid

import go_backup
import go_runtime
from run_go_postgres_tests import IMAGE, ROOT, run_package

MONITORING = False


def monitor_workers(discovery):
    """Docker Desktop can reach host loopback; Linux host networking is not assumed."""
    name = "personalos-p8-local-monitor-" + uuid.uuid4().hex[:12]
    command = ["docker", "exec", name, "/bin/wget", "-q", "-O", "-",
               "http://127.0.0.1:9090/api/v1/query?query=up"]
    try:
        subprocess.run(["docker", "run", "--detach", "--name", name, "--user", "65534:65534", "--read-only",
            "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
            "--tmpfs", "/prometheus:rw,noexec,nosuid,uid=65534,gid=65534,size=32m",
            "--mount", "type=bind,src=" + str(discovery) + ",dst=/etc/prometheus/discovery,readonly",
            "--mount", "type=bind,src=" + str(ROOT / "backend-go/deploy/monitoring/prometheus.local.yml") + ",dst=/etc/prometheus/prometheus.yml,readonly",
            "prom/prometheus:v3.14.0"], check=True, capture_output=True, timeout=60)
        deadline = time.monotonic() + 45
        while True:
            result = subprocess.run(command, capture_output=True, timeout=5)
            if result.returncode == 0:
                rows = json.loads(result.stdout)["data"]["result"]
                if len(rows) == 3 and all(row["value"][1] == "1" for row in rows) and {row["metric"]["role"] for row in rows} == set(go_runtime.WORKERS):
                    print("Non-root container read public file_sd and scraped three real local Workers", flush=True)
                    return
            if time.monotonic() >= deadline:
                raise TimeoutError("Local Docker Desktop Worker scrape failed")
            time.sleep(.3)
    finally:
        subprocess.run(["docker", "rm", "--force", name], check=True, capture_output=True, timeout=30)


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def reap(pid):
    # Popen's cleanup may already reap an exited child while running the ps probe.
    try:
        os.waitpid(pid, 0)
    except ChildProcessError:
        pass


def exercise(container, fixture):
    if fixture.get("GO_TEST_POSTGRES") != "disposable" or fixture.get("GO_TEST_POSTGRES_SSL_MODE") != "verify-full":
        raise ValueError("Local runtime exercise needs this runner's fresh TLS DB")
    with tempfile.TemporaryDirectory(prefix="personalos-p8-local-") as temporary:
        directory = Path(temporary)
        binary = directory / "personalos"
        subprocess.run(["go", "build", "-o", str(binary), "./cmd/personalos"], cwd=ROOT / "backend-go", check=True, timeout=120)
        uploads = directory / "uploads"
        uploads.mkdir()
        values = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
        values.update({key: fixture["GO_TEST_" + key] for key in ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")})
        values.update(GO_POSTGRES_SSL_MODE="verify-full", GO_POSTGRES_ROOT_CERT=fixture["GO_TEST_POSTGRES_ROOT_CERT"],
            AUTH_ENABLED="true", AUTH_USERNAME="p8-fixture", AUTH_PASSWORD=secrets.token_urlsafe(24), APP_SECURITY_CSRF_ENABLED="true",
            AI_CHAT_UPLOAD_DIR=str(uploads), APP_CONTEXT_SUMMARY_ENABLED="false", APP_CONTEXT_AUTOMEMORY_ENABLED="false",
            APP_MEMORY_BACKFILL_ENABLED="false", APP_CALENDAR_SCANENABLED="false", MCP_ENABLED="false",
            SPRING_AI_MCP_SERVER_ENABLED="false")
        states = []
        with go_runtime.locked(directory / "runtime") as runtime:
            try:
                for role in ("combined", "api", *go_runtime.WORKERS):
                    state = go_runtime.start(runtime, role, binary, ROOT / "backend/src/main/resources/db/migration",
                                             values, free_port(), free_port())
                    states.append(state)
                    try:
                        go_runtime.start(runtime, role, binary, ROOT / "backend/src/main/resources/db/migration", values)
                        raise AssertionError("Duplicate Go role accepted")
                    except ValueError:
                        pass
                    if role not in go_runtime.WORKERS:
                        go_runtime.stop(runtime, role)
                        reap(state["pid"])
                        states.remove(state)
                go_runtime.publish_targets(runtime)
                targets = json.loads((runtime / "worker-targets.json").read_text())
                if {entry["labels"]["role"] for entry in targets} != set(go_runtime.WORKERS):
                    raise AssertionError("Running Worker discovery incomplete")
                if MONITORING:
                    go_runtime.publish_targets(runtime, directory / "discovery", "host.docker.internal")
                    monitor_workers(directory / "discovery")
            finally:
                for state in reversed(states):
                    go_runtime.stop(runtime, state["role"])
                    reap(state["pid"])
                go_runtime.publish_targets(runtime, directory / "discovery" if MONITORING else None)
            if json.loads((runtime / "worker-targets.json").read_text()) != []:
                raise AssertionError("Stopped Workers still discovered")
            if MONITORING and json.loads((directory / "discovery/worker-targets.json").read_text()) != []:
                raise AssertionError("Stopped Workers still publicly discovered")
        # No writer/Worker remains; keep the same baseline tar/custom-dump format.
        (uploads / "synthetic-attachment").write_bytes(b"P8 backup attachment")
        configuration = go_backup.configuration(dict(GO_BACKUP_DB_CONTAINER=container, GO_BACKUP_DB_USER=fixture["GO_TEST_POSTGRES_USER"],
            GO_BACKUP_DB_NAME=fixture["GO_TEST_POSTGRES_DB"], GO_BACKUP_DIR=str(directory / "backup"),
            GO_BACKUP_UPLOAD_DIR=str(uploads)), "backup", allow_unencrypted=True)
        artifact = go_backup.baseline.backup(configuration)
        go_backup.baseline.verify(artifact, configuration, drill=True)
        with tempfile.TemporaryDirectory(prefix="personalos-p8-push-") as keys:
            spec = importlib.util.spec_from_file_location("baseline_push_keys", ROOT / "scripts/dev/generate-push-keys.py")
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            target = Path(keys) / "new-keys"
            module.generate(target)
            if target.stat().st_mode & 0o777 != 0o600:
                raise AssertionError("Push private key permissions changed")
            try:
                module.generate(target)
                raise AssertionError("Push key overwrite accepted")
            except FileExistsError:
                pass
        print("P8 local: five-role lifecycle/duplicate refusal/Worker discovery/stale cleanup, original backup+restore and Push keys passed", flush=True)


if __name__ == "__main__":
    import argparse
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--monitoring", action="store_true")
    args = parser.parse_args()
    MONITORING = args.monitoring
    if MONITORING and platform.system() != "Darwin":
        parser.error("This Docker Desktop host-loopback drill is verified on macOS only; use native loopback monitoring elsewhere")
    run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8", exercise=exercise)
