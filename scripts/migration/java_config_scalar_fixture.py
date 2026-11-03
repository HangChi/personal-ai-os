#!/usr/bin/env python3
"""基线Spring Boot时长/大小转换器离线对照；合成配置，不读取.env。"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "backend-go/internal/platform/config/testdata/java-scalars.json"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def cases():
    durations = ["", " ", "0", "1", "-1", "+1", "00", "1.5s", "1h30m", "1µs", "1μs", "1uS",
                 "1w", "1y", "1 s", " 1s", "1s ", "1s\n", "1\u3000s", "一秒", "١s", "１２s",
                 "9223372036854775807ns", "-9223372036854775808ns", "9223372036854775808ns",
                 "9223372036854775us", "9223372036854776us", "-9223372036854776us",
                 "9223372036854ms", "9223372036855ms", "9223372036854775807d",
                 "P", "PT", "P1DT", "P1D", "P-1D", "-P1D", "+PT1S", "pt1.5s", "PT1,5S",
                 "PT.5S", "PT1.S", "PT1,S", "PT0.000000001S", "PT0.0000000001S",
                 "PT-0.5S", "-PT-0.5S", "P1DT2H3M4.5S", "P-1DT+2H-3M+4.5S",
                 "PT-1.5S", "-PT-1.5S", "P1W", "P1M", "PT1D", "PT1H1H", "PT1S1M",
                 "PT9223372036.854775807S", "PT9223372036.854775808S",
                 "PT-9223372036.854775808S", "PT-9223372036.854775809S",
                 "P106752D", "PT9223372036854775807S", "PT9223372036854775808S",
                 "PT9223372036854775807H-9223372036854775807H",
                 "P106751DT23H47M16.854775807S", "-P106751DT23H47M16.854775808S",
                 "PT+1H-60M", "PT9223372036854775807S", "PT-9223372036854775808S",
                 "PT1ſ", "PT1Ｓ", "PT١S", "ＰＴ１Ｓ",
                 "P106751991167300DT16H-9223372036854775807S",
                 "P-106751991167300DT153722867280912930M8S"]
    for amount in ["0", "1", "-1", "+12", "0012"]:
        for suffix in ["ns", "NS", "us", "Us", "ms", "MS", "s", "S", "m", "M", "h", "H", "d", "D"]:
            durations.append(amount + suffix)
    sizes = ["", " ", "0", "1", "-1", "+1", "00", "8MB", "10MB", "1.5MB", "1MiB", "1PB",
             "1KB2B", "١KB", "１２KB", "1\u00a0KB", "1\u0085KB", "1\u2007KB", "1\u202fKB",
             "9223372036854775807", "-9223372036854775808", "9223372036854775808",
             "9007199254740991KB", "9007199254740992KB", "-9007199254740992KB",
             "8388607TB", "8388608TB", "-8388608TB", "-8388609TB"]
    for amount in ["0", "1", "-1", "+12", "0012"]:
        for suffix in ["B", "KB", "MB", "GB", "TB", "b", "kb", "Mb", "K", "M"]:
            sizes.append(amount + suffix)
    for value in [" 1 KB ", "1\tK\nB", "1\u3000KB", "1\u001cKB", "\u20021\u2002MB\u2002"]:
        sizes.append(value)
    return [{"kind": kind, "input": value} for kind, values in [("duration", durations), ("size", sizes)]
            for value in dict.fromkeys(values)]


def run(command, **kwargs):
    result = subprocess.run(command, text=True, capture_output=True, timeout=60, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def generate(m2):
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    pom = ET.fromstring(run(["git", "show", baseline + ":backend/pom.xml"], cwd=ROOT))
    boot = pom.find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    spring = bom.find("m:properties/m:spring-framework.version", NS).text
    artifacts = [("org/springframework/boot", "spring-boot", boot)]
    artifacts += [("org/springframework", "spring-" + name, spring) for name in ["core", "context", "beans", "expression"]]
    artifacts += [("commons-logging", "commons-logging", "1.4.0")]
    jars = [m2 / f"{group}/{name}/{value}/{name}-{value}.jar" for group, name, value in artifacts]
    for path in jars:
        if not path.is_file():
            raise FileNotFoundError("基线依赖未缓存，不自动下载：" + str(path))
    inputs = cases()
    with tempfile.TemporaryDirectory(prefix="personalos-java-scalars-") as directory:
        cp = os.pathsep.join(str(path) for path in jars)
        run(["javac", "-encoding", "UTF-8", "-cp", cp, "-d", directory,
             str(ROOT / "scripts/migration/ConfigScalarOracle.java")])
        encoded = "\n".join(row["kind"] + "\t" + base64.b64encode(row["input"].encode()).decode() for row in inputs) + "\n"
        output = run(["java", "-cp", directory + os.pathsep + cp, "ConfigScalarOracle"], input=encoded).splitlines()
    if len(inputs) != len(output):
        raise ValueError("转换器结果数量不一致")
    for row, value in zip(inputs, output):
        row["java_error"] = value == "error"
        row["null"] = value == "null"
        row["value"] = None
        row["go_overflow"] = False
        if value not in ("error", "null"):
            tag, integer = value.split("\t")
            if tag != "value":
                raise ValueError("未知转换器结果")
            row["value"] = integer
            row["go_overflow"] = not -(2**63) <= int(integer) <= 2**63 - 1
    return {"baseline_commit": baseline, "spring_boot": boot, "spring_framework": spring,
            "jars": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in jars},
            "scope": "unannotated ApplicationConversionService Duration/DataSize only; no binding/property precedence",
            "cases": inputs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    output = json.dumps(generate(args.m2), ensure_ascii=False, indent=2) + "\n"
    if args.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(output)
    elif TARGET.read_text() != output:
        raise SystemExit("配置转换快照变化，需审查后显式--write")
    print("真实Java配置转换器对照通过：" + str(len(json.loads(output)["cases"])) + "项")


if __name__ == "__main__":
    main()
