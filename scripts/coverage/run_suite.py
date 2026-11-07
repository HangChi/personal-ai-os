#!/usr/bin/env python3
"""Run fresh, isolated coverage stages and verify their complete, matching evidence."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
from datetime import datetime, timezone

import changed
import scenarios
import summarize

ROOT = Path(__file__).resolve().parents[2]
STAGES = ("java", "default", "postgres", "redis", "p6")
EXPECTED_PROFILES = {
    "default": {"default.out"},
    "postgres": {"postgres-" + name + ".out" for name in (
        "database", "ratelimit", "migration", "coordination", "modelgate", "health",
        "calendar", "chat", "confirmation", "idempotency", "knowledge", "maintenance",
        "model", "search", "task", "workflow", "app", "command")} | {"command-process.out"},
    "redis": {"redis-" + name + ".out" for name in ("redis", "auth", "ratelimit", "model")}
             | {"postgres-" + name + ".out" for name in ("app", "command", "knowledge")}
             | {"command-process.out"},
    "p6": {"postgres-" + name + ".out" for name in ("knowledge", "app", "calendar")},
}


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def source_identity():
    paths = subprocess.check_output(["git", "ls-files", "--cached", "--others", "--exclude-standard", "--",
        "backend/src", "backend/pom.xml", "backend/.mvn", "backend/mvnw",
        "backend-go", "testdata", "scripts/migration"], cwd=ROOT, text=True).splitlines()
    paths += [str(path.relative_to(ROOT)) for path in (ROOT / "scripts/coverage").glob("*")
              if path.suffix in {".py", ".json"}]
    digest = hashlib.sha256()
    for name in sorted(set(paths)):
        path = ROOT / name
        if path.is_file():
            digest.update(name.encode() + b"\0" + path.read_bytes() + b"\0")
    return {"revision": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "source_sha256": digest.hexdigest()}


def commands(stage, output):
    profile_args = ["--coverage-dir", str(output / "profiles")]
    return {
        "java": [(["./mvnw", "-B", "--no-transfer-progress", "-Dapp.dotenv.enabled=false", "verify"], ROOT / "backend")],
        "default": [(["go", "test", "-race", "-json", "-coverpkg=./...", "-covermode=atomic",
                      "-coverprofile=" + str(output / "profiles/default.out"), "-count=1", "-p", "1", "-timeout=20m", "./..."], ROOT / "backend-go"),
                    (["go", "vet", "-tags=integration", "./..."], ROOT / "backend-go"),
                    (["go", "build", "./..."], ROOT / "backend-go")],
        "postgres": [([sys.executable, "scripts/migration/run_go_postgres_tests.py", "--tls", "--backup", "--baseline", *profile_args], ROOT)],
        "redis": [([sys.executable, "scripts/migration/run_go_redis_tests.py", "--tls", "--backup", *profile_args], ROOT)],
        "p6": [([sys.executable, "scripts/migration/run_go_p6_tests.py", *profile_args], ROOT)],
    }[stage]


def run_stage(stage, root):
    output = root / stage
    output.mkdir(parents=True, exist_ok=False)
    identity = source_identity()
    if stage == "java":
        # Keep previous evidence but never let it contribute to this run.
        for old, target in [(ROOT / "backend/target/jacoco.exec", output / "previous.exec"),
                            (ROOT / "backend/target/surefire-reports", output / "previous-junit")]:
            if old.exists():
                shutil.move(str(old), str(target))
    else:
        (output / "profiles").mkdir()
    env = dict(os.environ, APP_DOTENV_ENABLED="false", GO_DOTENV_ENABLED="false")
    started = datetime.now(timezone.utc).isoformat()
    for index, (command, cwd) in enumerate(commands(stage, output)):
        print(f"{stage}: running command {index + 1}", flush=True)
        with (output / f"run-{index}.log").open("w", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=cwd, env=env, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(f"{stage} failed ({result.returncode}); inspect {output / f'run-{index}.log'}")
    if identity != source_identity():
        raise ValueError("Tested sources changed during stage " + stage)
    if stage == "java":
        shutil.copy2(ROOT / "backend/target/site/jacoco/jacoco.xml", output / "jacoco.xml")
        shutil.copytree(ROOT / "backend/target/surefire-reports", output / "junit")
        if summarize.java_summary(output / "jacoco.xml")["sessions"] != 1:
            raise ValueError("Java requires exactly one fresh JaCoCo session")
        files = [output / "jacoco.xml", *sorted((output / "junit").glob("TEST-*.xml"))]
        if len(files) < 2:
            raise ValueError("Missing Java test reports")
    else:
        files = sorted((output / "profiles").glob("*.out"))
        if {file.name for file in files} != EXPECTED_PROFILES[stage]:
            raise ValueError("Incomplete or unexpected profiles for stage " + stage)
        summarize.merge_go(files)
    files += sorted(output.glob("run-*.log"))
    manifest = {**identity, "stage": stage, "status": "passed", "started_at": started,
                "completed_at": datetime.now(timezone.utc).isoformat(),
                "files": {str(path.relative_to(output)): sha(path) for path in files}}
    (output / "stage.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(stage + ": complete", flush=True)


def verify_manifests(root):
    identity, profiles = None, []
    for stage in STAGES:
        folder = root / stage
        manifest = json.loads((folder / "stage.json").read_text())
        current = {key: manifest[key] for key in ("revision", "source_sha256")}
        if manifest.get("status") != "passed" or manifest.get("stage") != stage:
            raise ValueError("Incomplete stage " + stage)
        if identity is not None and identity != current:
            raise ValueError("Cannot merge stages from different sources or revisions")
        identity = current
        for name, expected in manifest["files"].items():
            path = folder / name
            if Path(name).is_absolute() or ".." in Path(name).parts or path.is_symlink() or sha(path) != expected:
                raise ValueError("Evidence changed: " + str(path))
        if stage == "java":
            if "jacoco.xml" not in manifest["files"] or not any(name.startswith("junit/TEST-") for name in manifest["files"]):
                raise ValueError("Missing Java report evidence")
            if summarize.java_summary(folder / "jacoco.xml")["sessions"] != 1:
                raise ValueError("Java coverage must have a single session")
        else:
            expected = {"profiles/" + name for name in EXPECTED_PROFILES[stage]}
            actual = {name for name in manifest["files"] if name.startswith("profiles/")}
            if actual != expected:
                raise ValueError("Missing required profile evidence: " + stage)
            profiles += [folder / name for name in sorted(actual)]
        if "run-0.log" not in manifest["files"]:
            raise ValueError("Missing executed test log: " + stage)
    if identity != source_identity():
        raise ValueError("Coverage evidence does not match current sources")
    return identity, profiles


def check(root, base=None):
    identity, profiles = verify_manifests(root)
    java = summarize.java_summary(root / "java/jacoco.xml")
    blocks = summarize.merge_go(profiles)
    go = summarize.go_summary(blocks)
    default = summarize.go_summary(summarize.merge_go([root / "default/profiles/default.out"]))
    checks = {name: {"minimum": 95, "passed": summarize.meets_minimum(value, 95)}
              for name, value in (("java_lines", java["line"]), ("java_branches", java["branch"]), ("go_full_statements", go))}
    required = scenarios.check(root)
    result = {**identity, "java": java, "go_default_statements": default, "go_full_statements": go,
              "go_branches": {"status": "not_measured"}, "profiles": len(profiles),
              "critical_scenarios": required, "checks": checks}
    if base:
        resolved = subprocess.check_output(["git", "rev-parse", "--verify", base + "^{commit}"], cwd=ROOT, text=True).strip()
        diff = subprocess.check_output(["git", "-c", "core.quotePath=false", "diff", "--no-ext-diff", "--no-renames",
            "--unified=0", resolved, "--", "backend/src/main/java", "backend-go"], cwd=ROOT, text=True)
        metrics = changed.measure(changed.changed_lines(diff), changed.java_lines(root / "java/jacoco.xml"), blocks)
        result["changed"] = {"base": resolved, **metrics}
        for name, measured in metrics.items():
            checks[name] = {"minimum": 95, "status": measured.get("status", "measured"),
                            "passed": not measured["total"] or summarize.meets_minimum(measured, 95)}
    result["passed"] = all(item["passed"] for item in checks.values()) and required["passed"]
    (root / "summary.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--stage", choices=(*STAGES, "all", "check"), required=True)
    parser.add_argument("--output", type=Path, required=True, help="new output directory; each stage refuses reuse")
    parser.add_argument("--base", help="PR merge-base for changed executable code; omitted only for local/scheduled full runs")
    args = parser.parse_args()
    root = args.output.resolve()
    if args.stage == "check":
        return check(root, args.base)
    for stage in STAGES if args.stage == "all" else (args.stage,):
        run_stage(stage, root)
    return check(root, args.base) if args.stage == "all" else 0


if __name__ == "__main__":
    raise SystemExit(main())
