#!/usr/bin/env python3
"""Compare P5 recurrence, memory fact parsing and tool definitions with the fixed Java baseline."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "backend"
BUSINESS = ROOT / "backend-go/internal/business"
SOURCES = (
    "calendar/entity/RecurrenceType.java",
    "calendar/recurrence/RecurrenceCalculator.java",
    "calendar/tool/CalendarTools.java",
    "common/json/JsonCodec.java",
    "memory/service/MemoryFactParser.java",
    "memory/tool/MemoryTools.java",
)


def inputs():
    recurrence = []
    windows = [
        ("1960-01-31T14:00:00Z", "2024-01-30T00:00:00Z", "2024-04-02T00:00:00Z"),
        ("2024-01-31T14:00:00Z", "2024-01-31T14:00:00Z", "2024-04-01T00:00:00Z"),
        ("2024-03-09T07:30:00Z", "2024-03-09T07:30:00Z", "2024-03-15T00:00:00Z"),
        ("2024-11-02T05:30:00Z", "2024-11-02T05:30:00Z", "2024-11-10T00:00:00Z"),
        ("2024-03-08T14:00:00Z", "2024-03-08T14:00:00Z", "2024-03-08T14:00:00Z"),
        ("2024-03-08T14:00:00Z", "2024-03-11T14:00:00Z", "2024-03-08T14:00:00Z"),
        ("2024-03-11T14:00:00Z", "2024-03-08T14:00:00Z", "2024-03-09T14:00:00Z"),
    ]
    for zone in ("Asia/Shanghai", "America/New_York", "Europe/Berlin"):
        for rule in (None, "NONE", "DAILY", "WEEKLY", "MONTHLY", "WEEKDAYS"):
            for start, lower, upper in windows:
                for until in (None, lower, "1950-01-01T00:00:00Z"):
                    recurrence.append(dict(zone=zone, rule=rule, start=start, until=until, **{"from": lower, "to": upper}))
    facts = [
        '["每周五提交报告"," 使用中文回复  "]',
        '前言\n```json\n["每周五提交报告"]\n```',
        '[null,"短", "😀😀😀", "每周五提交报告"]',
        '[123456, false, true, "每周五提交报告"]',
        '["每周五提交报告",{}]',
        '["每周五提交报告",',
        '没有值得记住的事实',
        '[]',
    ]
    return {"recurrence": recurrence, "facts": [dict(raw=raw, limit=limit) for raw in facts for limit in (0, 1, 3)]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    report = JAVA / "target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml"
    classpath = next(p.attrib["value"] for p in ET.parse(report).find("properties") if p.attrib.get("name") == "java.class.path")
    classpath = os.pathsep.join([str(JAVA / "target/classes"), *(part for part in classpath.split(os.pathsep) if part.endswith(".jar"))])
    cases = inputs()
    hashes = {}
    with tempfile.TemporaryDirectory(prefix="personalos-p5-oracle-") as temporary:
        paths = []
        for source in SOURCES:
            path = "backend/src/main/java/com/personalos/" + source
            data = subprocess.check_output(["git", "show", baseline + ":" + path], cwd=ROOT)
            target = Path(temporary, source)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            paths.append(str(target))
            hashes[path] = hashlib.sha256(data).hexdigest()
        subprocess.run(["javac", "-parameters", "-encoding", "UTF-8", "-proc:none", "-sourcepath", "",
                        "-cp", classpath, "-d", temporary, *paths, str(ROOT / "scripts/migration/P5Oracle.java")], check=True, timeout=90)
        result = subprocess.run(["java", "-cp", temporary + os.pathsep + classpath, "com.personalos.migration.P5Oracle"],
                                input=json.dumps(cases), text=True, capture_output=True, check=True, timeout=90)
    expected = json.loads(next(line[7:] for line in result.stdout.splitlines() if line.startswith("ORACLE ")))
    artifacts = {
        BUSINESS / "agent/tool-definitions-p5.json": expected["tools"],
        BUSINESS / "calendar/testdata/java-recurrence.json": dict(baseline_commit=baseline, sources_sha256=hashes,
                                                                  cases=[dict(case, expected=out) for case, out in zip(cases["recurrence"], expected["dates"])]),
        BUSINESS / "memory/testdata/java-facts.json": [dict(case, expected=out) for case, out in zip(cases["facts"], expected["facts"])],
    }
    for path, artifact in artifacts.items():
        contents = json.dumps(artifact, ensure_ascii=False, separators=(",", ":")) + "\n"
        if args.write:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(contents, encoding="utf-8")
        elif path.read_text(encoding="utf-8") != contents:
            raise SystemExit("Java P5 fixture drift: " + str(path))
    print("Java P5: %d recurrence cases, %d fact parsing cases, %d tool definitions" %
          (len(expected["dates"]), len(expected["facts"]), len(expected["tools"])))


if __name__ == "__main__":
    main()
