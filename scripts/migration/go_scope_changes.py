"""Explicit, content-bound additions to the immutable Java migration oracle.

Recording scope is not parity verification. Original inventory hashes never
change; pending amendments invalidate inherited completion claims.
"""

import hashlib
import json
import re
import subprocess
from pathlib import Path


def safe_file(root, name):
    if not isinstance(name, str) or not name:
        raise ValueError("Scope path must be a repository file")
    path = Path(name)
    if (path.is_absolute() or ".." in path.parts or path.as_posix() != name
            or name.startswith((".git/", ".cache/")) or path.name == ".env"):
        raise ValueError("Unsafe scope path: " + name)
    target = root / path
    if not target.is_file() or target.is_symlink() or not target.resolve().is_relative_to(root.resolve()):
        raise ValueError("Missing/unsafe scope file: " + name)
    return target


def validate(inventory, scope, root, read_revision):
    """Validate a reviewed scope ledger against both committed and live bytes.

    read_revision(commit, path) reads an immutable Git blob. Injected for isolated
    tests, so tests never depend on or change the developer's dirty worktree.
    """
    if scope.get("schema_version") != 1 or scope.get("baseline_commit") != inventory["baseline_commit"]:
        raise ValueError("Scope baseline/schema mismatch")
    baseline = {entry["path"]: entry["sha256"] for entry in inventory["files"]}
    migration_prefix = "backend/src/main/resources/db/migration/"
    migration_pattern = re.compile(re.escape(migration_prefix) + r"V([1-9][0-9]*)__[^/]+\.sql")
    frozen_versions = [int(match[1]) for path in baseline if (match := migration_pattern.fullmatch(path))]
    seen, ids, appended_versions = {}, set(), set()
    for change in scope.get("changes", []):
        identity = change.get("id", "")
        if not re.fullmatch(r"scope:[a-z0-9-]+", identity) or identity in ids:
            raise ValueError("Invalid/duplicate scope id: " + identity)
        ids.add(identity)
        if change.get("status") not in {"in_progress", "implemented", "verified"}:
            raise ValueError("Invalid scope status: " + identity)
        if not change.get("summary") or not change.get("limitations") or not change.get("files"):
            raise ValueError("Scope needs an explanation, files and limits: " + identity)
        revision = change.get("source_commit", "")
        if not re.fullmatch(r"[0-9a-f]{40}", revision):
            raise ValueError("Scope requires an immutable source commit: " + identity)
        evidence = change.get("evidence", [])
        parity = change.get("parity", {})
        if not evidence or not all(parity.get(side) for side in ("java", "go")):
            raise ValueError("Scope requires evidence and both backend responsibilities: " + identity)
        for name in evidence + parity["java"] + parity["go"]:
            safe_file(root, name)
        hashes = {}
        for entry in change["files"]:
            name = entry["path"]
            if name in seen:
                raise ValueError("Duplicate scope file: " + name)
            if name.startswith(migration_prefix):
                # 原迁移永远不可改；增量只允许追加高于冻结版本的标准Flyway脚本。
                candidate = migration_pattern.fullmatch(name)
                if name in baseline or not frozen_versions or not candidate or int(candidate[1]) <= max(frozen_versions):
                    raise ValueError("Original SQL migrations cannot be amended; append-only versions required: " + name)
                version = int(candidate[1])
                if version in appended_versions:
                    raise ValueError("Duplicate appended SQL migration version: " + str(version))
                appended_versions.add(version)
            if entry.get("baseline_sha256") != baseline.get(name):
                raise ValueError("Baseline file drift: " + name)
            expected = entry.get("candidate_sha256", "")
            if not re.fullmatch(r"[0-9a-f]{64}", expected) or expected == baseline.get(name):
                raise ValueError("Invalid/unchanged candidate hash: " + name)
            live = safe_file(root, name).read_bytes()
            if hashlib.sha256(live).hexdigest() != expected:
                raise ValueError("Unreviewed candidate file drift: " + name)
            try:
                committed = read_revision(revision, name)
            except (OSError, ValueError, subprocess.CalledProcessError) as error:
                raise ValueError("Candidate source commit missing file: " + name) from error
            if hashlib.sha256(committed).hexdigest() != expected:
                raise ValueError("Candidate hash differs from source commit: " + name)
            hashes[name] = expected
            seen[name] = dict(entry, change_id=identity, status=change["status"],
                              evidence=evidence, summary=change["summary"])
        # Bind parity responsibilities to candidate bytes, not just existing filenames.
        for name in set(parity["java"] + parity["go"]) - hashes.keys():
            expected = change.get("parity_sha256", {}).get(name)
            if not expected:
                raise ValueError("Backend responsibility missing candidate hash: " + identity)
            if (hashlib.sha256(safe_file(root, name).read_bytes()).hexdigest() != expected
                    or hashlib.sha256(read_revision(revision, name)).hexdigest() != expected):
                raise ValueError("Backend responsibility drift: " + name)
            hashes[name] = expected
        if change["status"] == "verified":
            report = json.loads(safe_file(root, change.get("verification_report")).read_text())
            expected_report = {"scope_id": identity, "baseline_commit": inventory["baseline_commit"],
                               "source_commit": revision, "status": "passed", "file_sha256": hashes}
            if any(report.get(key) != value for key, value in expected_report.items()) or not report.get("checks"):
                raise ValueError("Scope verification must cover the exact candidate: " + identity)
    return seen


def load(inventory, root, read_revision):
    path = root / "specs/033-go-rewrite/scope-changes.json"
    scope = json.loads(path.read_text()) if path.is_file() else {
        "schema_version": 1, "baseline_commit": inventory["baseline_commit"], "changes": []}
    return scope, validate(inventory, scope, root, read_revision)
