#!/usr/bin/env python3
"""Gate Java lines/branches and Go statements, keeping the metrics separate."""

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from decimal import Decimal


def metric(covered, total):
    if total <= 0 or covered < 0 or covered > total:
        raise ValueError("Coverage denominator must be positive")
    return {"covered": covered, "total": total, "percent": 100 * covered / total}


def merge_go(paths):
    blocks = {}
    file_layouts = {}
    inputs = sorted({Path(path).resolve() for path in paths})
    if not inputs:
        raise ValueError("No Go profiles found")
    for path in inputs:
        layouts = {}
        with path.open(encoding="utf-8") as stream:
            if stream.readline().strip() != "mode: atomic":
                raise ValueError(f"Expected atomic Go coverage: {path}")
            rows = 0
            for line in stream:
                location, statements, hits = line.split()
                statements, hits = int(statements), int(hits)
                if statements < 0 or hits < 0 or ":" not in location:
                    raise ValueError(f"Invalid coverage block: {path}: {line}")
                previous = blocks.get(location)
                if previous is not None and previous[0] != statements:
                    raise ValueError(f"Statement count changed at {location}")
                blocks[location] = (statements, hits + (previous[1] if previous else 0))
                filename = location.rsplit(":", 1)[0]
                layouts.setdefault(filename, {})[location] = statements
                rows += 1
            if not rows:
                raise ValueError(f"Empty Go profile: {path}")
        for filename, layout in layouts.items():
            if filename in file_layouts and file_layouts[filename] != layout:
                raise ValueError(f"Coverage source layout changed: {filename}")
            file_layouts[filename] = layout
    # Different build tags may add a file, but must not change an existing file's blocks.
    # Full-project -coverpkg profiles can omit packages unused by an individual test binary.
    return blocks


def go_summary(blocks):
    covered = sum(n for n, hits in blocks.values() if hits > 0)
    return metric(covered, sum(n for n, _ in blocks.values()))


def java_summary(path):
    root = ET.parse(path).getroot()
    result = {"sessions": len(root.findall("sessioninfo"))}
    for name in ("BRANCH", "LINE"):
        counter = next(counter for counter in root.findall("counter") if counter.get("type") == name)
        covered, missed = int(counter.get("covered")), int(counter.get("missed"))
        result[name.lower()] = metric(covered, covered + missed)
    return result


def meets_minimum(measured, minimum):
    """Compare integer counts before rounding; reject invalid policy values."""
    minimum = Decimal(str(minimum))
    if not minimum.is_finite() or not 0 <= minimum <= 100:
        raise ValueError("Minimum must be a finite percentage between 0 and 100")
    return Decimal(measured["covered"]) * 100 >= minimum * measured["total"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go-profile", action="append", type=Path, default=[], help="atomic profile or directory; repeatable")
    parser.add_argument("--write-go-profile", type=Path, help="write a deduplicated profile outside input directories")
    parser.add_argument("--java-report", type=Path, help="JaCoCo XML from a completed full run")
    parser.add_argument("--minimum", type=Decimal, default=Decimal(95), help="fallback threshold for each named metric")
    parser.add_argument("--java-line-minimum", type=Decimal)
    parser.add_argument("--java-branch-minimum", type=Decimal)
    parser.add_argument("--go-statement-minimum", type=Decimal)
    args = parser.parse_args()
    thresholds = [args.minimum, args.java_line_minimum, args.java_branch_minimum, args.go_statement_minimum]
    if any(value is not None and (not value.is_finite() or not 0 <= value <= 100) for value in thresholds) or not (args.java_report or args.go_profile):
        parser.error("Provide coverage inputs and a minimum between 0 and 100")
    result = {}
    if args.go_profile:
        paths = []
        for path in args.go_profile:
            paths.extend(sorted(path.rglob("*.out")) if path.is_dir() else [path])
        if args.write_go_profile and args.write_go_profile.resolve() in {path.resolve() for path in paths}:
            parser.error("Merged output must not be an input")
        blocks = merge_go(paths)
        result["go_statements"] = go_summary(blocks)
        result["go_input_profiles"] = len({path.resolve() for path in paths})
        if args.write_go_profile:
            args.write_go_profile.parent.mkdir(parents=True, exist_ok=True)
            with args.write_go_profile.open("w", encoding="utf-8") as stream:
                stream.write("mode: atomic\n")
                for location, (statements, hits) in sorted(blocks.items()):
                    stream.write(f"{location} {statements} {hits}\n")
    if args.java_report:
        result["java"] = java_summary(args.java_report)
    checks = {}
    for name, item, minimum in [
        ("go_statements", result.get("go_statements"), args.go_statement_minimum),
        ("java_lines", result.get("java", {}).get("line"), args.java_line_minimum),
        ("java_branches", result.get("java", {}).get("branch"), args.java_branch_minimum),
    ]:
        if item is not None:
            threshold = args.minimum if minimum is None else minimum
            checks[name] = {"minimum": float(threshold), "passed": meets_minimum(item, threshold)}
    result["minimum"] = float(args.minimum)
    result["checks"] = checks
    result["go_branches"] = {"status": "not_measured", "reason": "native Go profiles measure statements"}
    result["passed"] = all(check["passed"] for check in checks.values())
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
