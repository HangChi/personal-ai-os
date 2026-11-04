#!/usr/bin/env python3
"""Execute baseline Micrometer naming, units and timer buckets without starting an application."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "backend-go/internal/app/testdata/java-knowledge-metrics.json"
SOURCES = [
    "backend/src/main/java/com/personalos/knowledge/" + name
    for name in ("jobs/KnowledgeIndexJobs.java", "rag/RagRetrievalPipeline.java",
                 "rag/RagModelGateway.java", "rag/CrossEncoderReranker.java",
                 "service/KnowledgeRetrievalService.java")
]


def generate():
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    report = ROOT / "backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml"
    classpath = next(item.attrib["value"] for item in ET.parse(report).find("properties")
                     if item.attrib.get("name") == "java.class.path")
    jars = [Path(path) for path in classpath.split(os.pathsep) if path.endswith(".jar")]
    hashes = {}
    for path in SOURCES:
        raw = subprocess.check_output(["git", "show", baseline + ":" + path], cwd=ROOT)
        if (ROOT / path).read_bytes() != raw:
            raise ValueError("Java metric source drifted from the fixed baseline")
        hashes[path] = hashlib.sha256(raw).hexdigest()
    environment = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
    source = ROOT / "scripts/migration/KnowledgeMetricsOracle.java"
    with tempfile.TemporaryDirectory(prefix="personalos-knowledge-metrics-oracle-") as directory:
        cp = os.pathsep.join(str(path) for path in jars)
        compiled = subprocess.run(["javac", "-encoding", "UTF-8", "-proc:none", "-cp", cp, "-d", directory, str(source)],
                                  env=environment, capture_output=True, text=True, timeout=60)
        if compiled.returncode:
            raise RuntimeError("Isolated metric oracle compile failed: " + compiled.stderr[-4000:])
        output = subprocess.run(["java", "-cp", directory + os.pathsep + cp, "KnowledgeMetricsOracle"],
                                env=environment, check=True, capture_output=True, text=True, timeout=60).stdout
    samples = {}
    for line in output.splitlines():
        if line.startswith("personalos_knowledge_"):
            key, value = line.rsplit(" ", 1)
            # Prometheus Java clients may append a syntactically optional trailing label comma.
            samples[key.replace(",}", "}")] = float(value)
    if len(samples) < 140:
        raise ValueError("Original Micrometer samples are incomplete")
    return {"baseline_commit": baseline, "sources_sha256": hashes,
            "jars_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest()
                           for path in jars if path.name.startswith(("micrometer-", "prometheus-"))},
            "oracle_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "samples": samples}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    arguments = parser.parse_args()
    content = json.dumps(generate(), ensure_ascii=False, indent=2) + chr(10)
    if arguments.write:
        TARGET.write_text(content)
    elif not TARGET.is_file() or TARGET.read_text() != content:
        raise SystemExit("Baseline knowledge metric fixture changed; review before --write")
    print("Original Java knowledge metric names/labels/units/histogram oracle passed")


if __name__ == "__main__":
    main()
