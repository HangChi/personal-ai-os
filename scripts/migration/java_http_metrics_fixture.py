#!/usr/bin/env python3
"""离线执行已固定Spring/Micrometer依赖的HTTP标签与histogram约定，默认仅比较。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "backend-go/internal/app/testdata/java-http-metrics.json"


def generate(m2):
    fixed = json.loads((ROOT / "backend-go/internal/app/testdata/java-foundation-http.json").read_text())
    wanted = {name: digest for name, digest in fixed["jars_sha256"].items()
              if name.startswith(("spring-web-", "spring-core-", "micrometer-", "tomcat-embed-core-", "commons-logging-"))}
    jars = []
    for path in m2.rglob("*.jar"):
        if path.name in wanted and hashlib.sha256(path.read_bytes()).hexdigest() == wanted[path.name]:
            jars.append(path)
    if len(jars) != len(wanted):
        raise RuntimeError("固定依赖未缓存或哈希不同，禁止自动下载")
    source = ROOT / "scripts/migration/HTTPMetricsOracle.java"
    classpath = os.pathsep.join(str(path) for path in sorted(jars))
    env = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR"}}
    with tempfile.TemporaryDirectory(prefix="personalos-http-metrics-") as directory:
        subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", directory, str(source)], env=env, check=True, capture_output=True, timeout=60)
        output = subprocess.run(["java", "-cp", directory + os.pathsep + classpath, "HTTPMetricsOracle"], env=env, check=True, capture_output=True, text=True, timeout=60).stdout
    result = {"baseline_commit": fixed["baseline_commit"], "jars_sha256": wanted,
              "oracle_sha256": hashlib.sha256(source.read_bytes()).hexdigest(), "bounds_seconds": [], "cases": []}
    for line in output.splitlines():
        fields = line.split("\t")
        if fields[0] == "BOUND":
            result["bounds_seconds"].append(float(fields[1]))
        elif fields[0] == "CASE":
            result["cases"].append(dict(method=fields[1], status=int(fields[2]), route=fields[3], tags=dict(item.split("=", 1) for item in fields[4:])))
        else:
            raise RuntimeError("未知Java输出")
    if len(result["cases"]) != 160 or not result["bounds_seconds"]:
        raise RuntimeError("Java标签/桶输出遗漏")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    result = generate(args.m2)
    content = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.write:
        TARGET.write_text(content)
    elif not TARGET.is_file() or TARGET.read_text() != content:
        raise SystemExit("HTTP指标夹具变化，审查后显式--write")
    print("Java HTTP观测约定通过：160标签场景，%d桶边界" % len(result["bounds_seconds"]))


if __name__ == "__main__":
    main()
