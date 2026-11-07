#!/usr/bin/env python3
"""Summarize JaCoCo branches and Go statements without counting duplicate blocks."""

import argparse
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def metric(covered, total):
    if total <= 0:
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--go-profile", action="append", type=Path, default=[], help="atomic profile or directory; repeatable")
    parser.add_argument("--write-go-profile", type=Path, help="write a deduplicated profile outside input directories")
    parser.add_argument("--java-report", type=Path, help="JaCoCo XML from a completed full run")
    parser.add_argument("--minimum", type=float, default=95, help="minimum Java branches / Go statements, before rounding")
    args = parser.parse_args()
    if not 0 <= args.minimum <= 100 or not (args.java_report or args.go_profile):
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
    measured = ([result["go_statements"]] if "go_statements" in result else [])
    measured += [result["java"]["branch"]] if "java" in result else []
    result["minimum"] = args.minimum
    result["passed"] = all(item["covered"] * 100 >= args.minimum * item["total"] for item in measured)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0 if result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
