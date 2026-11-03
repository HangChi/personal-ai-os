#!/usr/bin/env python3
"""Offline baseline BCrypt oracle; synthetic inputs, no app, secrets or network."""

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
SPEC = ROOT / "specs/033-go-rewrite"
TARGET = ROOT / "backend-go/internal/platform/auth/testdata/java-bcrypt.json"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True,
                          timeout=90, **kwargs).stdout


def generate(m2):
    baseline = json.loads((SPEC / "inventory.json").read_text())["baseline_commit"]
    pom = ET.fromstring(run(["git", "show", f"{baseline}:backend/pom.xml"], cwd=ROOT))
    boot = pom.find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    security = bom.find("m:properties/m:spring-security.version", NS).text
    spring = bom.find("m:properties/m:spring-framework.version", NS).text
    artifacts = [("org/springframework/security", "spring-security-crypto", security),
                 ("org/springframework", "spring-core", spring),
                 ("commons-logging", "commons-logging", "1.4.0")]
    jars = [m2 / f"{group}/{name}/{version}/{name}-{version}.jar"
            for group, name, version in artifacts]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError(f"Baseline dependency not cached: {jar}")
    passwords = ["", "test-secret", "秘密🔐", "é", "e\u0301", "nul\0inside", "\0start",
                 " space ", "a" * 71, "a" * 72, "界" * 24, "🔐" * 18,
                 "a" * 73, "a" * 71 + "é", "🔐" * 19]
    cases = []
    for index, configured in enumerate(passwords):
        for candidate in dict.fromkeys([configured, "", configured + "x", configured + "界", "wrong"]):
            cases.append({"name": f"password-{index}-{len(cases)}", "configured": configured,
                          "candidate": candidate})
    classpath = os.pathsep.join(str(jar) for jar in jars)
    with tempfile.TemporaryDirectory(prefix="personalos-java-bcrypt-") as directory:
        run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", directory,
             str(ROOT / "scripts/migration/BCryptOracle.java")])
        encoded = "\n".join("\t".join(base64.b64encode(row[key].encode()).decode()
                                     for key in ("configured", "candidate")) for row in cases) + "\n"
        outputs = run(["java", "-cp", directory + os.pathsep + classpath, "BCryptOracle"],
                      input=encoded).splitlines()
    if len(cases) != len(outputs):
        raise ValueError("Oracle result count mismatch")
    for row, output in zip(cases, outputs):
        hashed, matched, prefix = output.split("\t")
        if matched not in ("true", "false") or prefix not in ("-", "$2a$10$"):
            raise ValueError("Unexpected BCrypt oracle result")
        row.update(hash=None if hashed.startswith("error:") else hashed,
                   encode_error=hashed.startswith("error:"), matched=matched == "true",
                   default_prefix=None if prefix == "-" else prefix)
    return {"baseline_commit": baseline, "spring_security": security,
            "spring_framework": spring,
            "jars": {jar.name: hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars},
            "scope": "Spring BCrypt encoder only; synthetic passwords and fixed salt, not sessions/HTTP",
            "cases": cases}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    result = generate(args.m2)
    output = json.dumps(result, ensure_ascii=False, indent=2) + "\n"
    if args.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(output)
    elif TARGET.read_text() != output:
        raise SystemExit("BCrypt fixture differs; review before explicit --write")
    print(f"Java BCrypt oracle passed: {len(result['cases'])} cases")


if __name__ == "__main__":
    main()
