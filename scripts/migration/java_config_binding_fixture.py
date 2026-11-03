#!/usr/bin/env python3
"""真实Spring Binder三类安全/限流/预算record对照；不做YAML或环境来源解析。"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

from java_config_scalar_fixture import NS, ROOT, run

TARGET = ROOT / "backend-go/internal/platform/config/testdata/java-binding.json"
RECORDS = {
    "auth": ("app.auth", "com.personalos.common.auth.AuthProperties"),
    "rate-limit": ("app.rate-limit", "com.personalos.common.config.RateLimitProperties"),
    "agent": ("app.agent", "com.personalos.common.ai.AgentExecutionProperties"),
}


def cases():
    result = []
    for kind, (prefix, _) in RECORDS.items():
        result.append({"kind": kind, "input": {}})
        result.append({"kind": kind, "input": {prefix + ".unknown": "ignored-synthetic"}})
    for kind, key in [("auth", "enabled"), ("rate-limit", "enabled")]:
        prefix = RECORDS[kind][0]
        for value in ["", " ", "true", "TRUE", "TrUe", "on", "yes", "1", "false", "off", "NO", "0",
                      " true\t", "\u3000true", "truth", "2", "y", "n", "tr ue", "truſe"]:
            result.append({"kind": kind, "input": {prefix + "." + key: value}})
    for kind, key in [("auth", "max-failures"), ("rate-limit", "capacity"),
                      ("agent", "max-model-calls"), ("agent", "max-consecutive-failures")]:
        prefix = RECORDS[kind][0]
        for value in ["", " ", "0", "-1", "1", "+12", "0012", "2147483647", "2147483648", "-2147483648",
                      "-2147483649", "0x10", "0X7fffffff", "#10", "+0x10", "-0x10", "0x80000000",
                      "1_000", "1,000", "1.0", " 1\t2\n", "1\u30002", "１２", "١٢", "123abc",
                      "𝟙𝟚", "0xＦＦ", "#ａ", "0x+10", "0x-10", "++1", "--1", "0b10", "010", "1\u00a02", "1\u00852"]:
            result.append({"kind": kind, "input": {prefix + "." + key: value}})
    for kind, key in [("auth", "lockout"), ("rate-limit", "period")]:
        prefix = RECORDS[kind][0]
        for value in ["", " ", "0", "-1", "1", "1ns", "60s", "2m", "PT1.000000001S", "PT-0.5S",
                      "1.5s", "P1D", "9223372036854775807ns", "9223372036854775808ns",
                      "9223372036855ms", "PT9223372036854775807S", "bad-synthetic-value"]:
            result.append({"kind": kind, "input": {prefix + "." + key: value}})
    for key in ["username", "password"]:
        for value in ["", " ", "\t\u001c\u3000", "  合成用户😀  ", "\u00a0fixture\u00a0", "a\u0000b", "${literal}"]:
            result.append({"kind": "auth", "input": {"app.auth." + key: value}})
    result += [
        {"kind": "auth", "input": {"app.auth.enabled": "true", "app.auth.username": " test ",
                                    "app.auth.password": "fixture-only-password", "app.auth.max-failures": "7", "app.auth.lockout": "3s"}},
        {"kind": "rate-limit", "input": {"app.rate-limit.enabled": "false", "app.rate-limit.capacity": "10", "app.rate-limit.period": "250ms"}},
        {"kind": "agent", "input": {"app.agent.max-model-calls": "4", "app.agent.max-consecutive-failures": "3"}},
    ]
    return result


def generate(m2):
    inventory = json.loads((ROOT / "specs/033-go-rewrite/config-inventory.json").read_text())
    baseline = inventory["baseline_commit"]
    boot = ET.fromstring(run(["git", "show", baseline + ":backend/pom.xml"], cwd=ROOT)).find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    spring = bom.find("m:properties/m:spring-framework.version", NS).text
    artifacts = [("org/springframework/boot", "spring-boot", boot)]
    artifacts += [("org/springframework", "spring-" + name, spring) for name in ["core", "context", "beans", "expression"]]
    artifacts.append(("commons-logging", "commons-logging", "1.4.0"))
    jars = [m2 / f"{group}/{name}/{version}/{name}-{version}.jar" for group, name, version in artifacts]
    for path in jars:
        if not path.is_file():
            raise FileNotFoundError("基线依赖未缓存，不自动下载：" + str(path))
    rows = cases()
    sources = {row["class"]: row["path"] for row in inventory["configuration_records"]}
    hashes = {}
    with tempfile.TemporaryDirectory(prefix="personalos-binding-") as directory:
        files = []
        for _, name in RECORDS.values():
            path = sources[name]
            source = run(["git", "show", baseline + ":" + path], cwd=ROOT)
            hashes[path] = hashlib.sha256(source.encode()).hexdigest()
            target = Path(directory) / Path(path).name
            target.write_text(source)
            files.append(str(target))
        cp = os.pathsep.join(str(path) for path in jars)
        run(["javac", "-encoding", "UTF-8", "-cp", cp, "-d", directory, *files,
             str(ROOT / "scripts/migration/ConfigBindingOracle.java")])
        encoded = []
        for row in rows:
            fields = list(RECORDS[row["kind"]])
            for key, value in sorted(row["input"].items()):
                fields += [base64.b64encode(key.encode()).decode(), base64.b64encode(value.encode()).decode()]
            encoded.append("\t".join(fields))
        output = run(["java", "-cp", directory + os.pathsep + cp, "ConfigBindingOracle"],
                     input="\n".join(encoded) + "\n").splitlines()
    if len(output) != len(rows):
        raise ValueError("Binder结果数量不一致")
    for row, encoded in zip(rows, output):
        value = base64.b64decode(encoded).decode()
        row["java_error"] = value == "error"
        row["value"] = None if row["java_error"] else json.loads(value)
        row["go_overflow"] = bool(row["value"] and any(
            key in row["value"] and not -(2**63) <= int(row["value"][key]) <= 2**63 - 1
            for key in ["lockout", "period"]))
    return {"baseline_commit": baseline, "spring_boot": boot, "spring_framework": spring,
            "jars_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in jars},
            "sources_sha256": hashes,
            "scope": "Binder.bindOrCreate; canonical resolved String map only; no placeholders/YAML/environment/application assembly",
            "cases": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    data = generate(args.m2)
    output = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    if args.write:
        TARGET.write_text(output)
    elif TARGET.read_text() != output:
        raise SystemExit("绑定快照不同，需审查后显式--write")
    print("真实Java Binder对照通过：" + str(len(data["cases"])) + "项")


if __name__ == "__main__":
    main()
