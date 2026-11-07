#!/usr/bin/env python3
"""Gate changed Java executable lines and Go statements in touched coverage blocks."""

import argparse
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

from summarize import merge_go, metric, meets_minimum

ROOT = Path(__file__).resolve().parents[2]


def changed_lines(diff):
    result, path = {}, None
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            path = line[6:]
            result.setdefault(path, set())
        elif line == "+++ /dev/null":
            path = None
        elif line.startswith("@@ ") and path:
            match = re.match(r"@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
            if not match:
                raise ValueError("Unrecognized diff hunk: " + line)
            first, count = int(match[1]), int(match[2] or 1)
            result[path].update(range(first, first + count))
    return result


def java_lines(path):
    result = {}
    for package in ET.parse(path).getroot().iter("package"):
        for source in package.findall("sourcefile"):
            filename = "backend/src/main/java/" + package.get("name") + "/" + source.get("name")
            result[filename] = {
                int(line.get("nr")): int(line.get("ci")) > 0
                for line in source.findall("line") if int(line.get("ci")) + int(line.get("mi")) > 0
            }
    return result


def measure(changes, java, go, module="personal-ai-os/backend-go"):
    totals = {"java_changed_lines": [0, 0], "go_changed_statements": [0, 0]}
    for path, lines in changes.items():
        if not lines:
            continue
        if path.startswith("backend/src/main/java/") and path.endswith(".java"):
            if path not in java:
                raise ValueError("Changed Java source missing from report: " + path)
            selected = [hit for number, hit in java[path].items() if number in lines]
            totals["java_changed_lines"][0] += sum(selected)
            totals["java_changed_lines"][1] += len(selected)
        elif path.startswith("backend-go/") and path.endswith(".go") and not path.endswith("_test.go"):
            filename = module + "/" + path.removeprefix("backend-go/")
            selected = [(location, counts) for location, counts in go.items()
                        if location.rsplit(":", 1)[0] == filename]
            if not selected:
                raise ValueError("Changed Go source missing from report: " + path)
            for location, (statements, hits) in selected:
                match = re.fullmatch(r"(\d+)\.\d+,(\d+)\.(\d+)", location.rsplit(":", 1)[1])
                if not match:
                    raise ValueError("Invalid block location: " + location)
                start, end, end_column = map(int, match.groups())
                # Profile ends are exclusive; column 1 belongs to the previous line.
                last = end - 1 if end_column == 1 else end
                if any(start <= line <= last for line in lines):
                    totals["go_changed_statements"][0] += statements if hits > 0 else 0
                    totals["go_changed_statements"][1] += statements
    return {name: metric(covered, total) if total else {"status": "not_applicable", "covered": 0, "total": 0}
            for name, (covered, total) in totals.items()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", required=True, help="explicit commit or merge-base; never inferred from a mutable branch")
    parser.add_argument("--java-report", type=Path, required=True)
    parser.add_argument("--go-profile", type=Path, required=True)
    parser.add_argument("--minimum", default="95")
    args = parser.parse_args()
    # Validate even when no executable code changed.
    meets_minimum({"covered": 1, "total": 1}, args.minimum)
    base = subprocess.check_output(["git", "rev-parse", "--verify", args.base + "^{commit}"], cwd=ROOT, text=True).strip()
    diff = subprocess.check_output(["git", "-c", "core.quotePath=false", "diff", "--no-ext-diff", "--no-renames",
                                    "--unified=0", base, "--", "backend/src/main/java", "backend-go"], cwd=ROOT, text=True)
    result = measure(changed_lines(diff), java_lines(args.java_report), merge_go([args.go_profile]))
    result["base"] = base
    result["passed"] = all(not item.get("total") or meets_minimum(item, args.minimum)
                           for item in result.values() if isinstance(item, dict))
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
