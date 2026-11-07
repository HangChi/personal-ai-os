#!/usr/bin/env python3
"""Require actual passing test evidence for the shared Agent/RAG acceptance matrix."""

import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET

POLICY = Path(__file__).with_name("critical-scenarios.json")


def java_results(paths):
    results = {}
    for path in paths:
        for case in ET.parse(path).getroot().iter("testcase"):
            method = re.split(r"[\[(]", case.get("name", ""))[0]
            key = case.get("classname", "") + "#" + method
            passed = not any(case.find(tag) is not None for tag in ("skipped", "failure", "error"))
            results[key] = results.get(key, True) and passed
    return results


def go_results(paths):
    results = {}
    for path in paths:
        with path.open(encoding="utf-8", errors="replace") as log:
            for line in log:
                if line.startswith("{"):
                    try:
                        event = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if event.get("Test") and event.get("Action") in {"pass", "skip", "fail"}:
                        results[event["Test"]] = results.get(event["Test"], True) and event["Action"] == "pass"
                else:
                    match = re.match(r"\s*--- (PASS|SKIP|FAIL): (\S+) ", line)
                    if match:
                        results[match[2]] = results.get(match[2], True) and match[1] == "PASS"
    return results


def evaluate(policy, java, go):
    if not policy or len({item["id"] for item in policy}) != len(policy):
        raise ValueError("Critical scenarios must be nonempty and have unique IDs")
    result = []
    for item in policy:
        if not item.get("java") or not item.get("go"):
            raise ValueError("Each critical scenario requires both backends")
        missing = {"java": [name for name in item["java"] if not java.get(name, False)],
                   "go": [name for name in item["go"] if not go.get(name, False)]}
        result.append({"id": item["id"], "passed": not any(missing.values()), "missing_or_failed": missing})
    return {"passed": all(item["passed"] for item in result), "scenarios": result}


def check(root):
    java = java_results(sorted((root / "java/junit").glob("TEST-*.xml")))
    logs = []
    for stage in ("default", "postgres", "redis", "p6"):
        # Only logs attested by successful stages may contribute.
        folder = root / stage
        manifest = json.loads((folder / "stage.json").read_text())
        logs += [folder / name for name in manifest["files"] if re.fullmatch(r"run-\d+\.log", name)]
    return evaluate(json.loads(POLICY.read_text()), java, go_results(logs))
