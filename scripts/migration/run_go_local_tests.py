#!/usr/bin/env python3
"""Verify Go local lifecycle/discovery/backup using only this runner's fresh TLS DB."""
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile

import go_backup
import go_runtime
from run_go_postgres_tests import IMAGE, ROOT, run_package


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
            finally:
                for state in reversed(states):
                    go_runtime.stop(runtime, state["role"])
                    reap(state["pid"])
                go_runtime.publish_targets(runtime)
            if json.loads((runtime / "worker-targets.json").read_text()) != []:
                raise AssertionError("Stopped Workers still discovered")
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
    run_package(IMAGE, "app", tls_mode=True, test_pattern="^TestP8", exercise=exercise)
