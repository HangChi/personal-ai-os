#!/usr/bin/env python3
"""固定 Java 基线 Flyway 的离线 CRC32 对照；不运行应用或读取真实凭据。"""

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
TARGET = ROOT / "backend-go/internal/platform/migration/testdata/java-flyway-checksums.json"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def dependencies(m2):
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    pom = ET.fromstring(subprocess.run(["git", "show", baseline + ":backend/pom.xml"], cwd=ROOT,
                                      text=True, check=True, capture_output=True).stdout)
    boot = pom.find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    version = bom.find("m:properties/m:flyway.version", NS).text
    jdbc = bom.find("m:properties/m:postgresql.version", NS).text
    jackson = bom.find("m:properties/m:jackson-bom.version", NS).text
    # Jackson3 的注解仍使用 Jackson2 版本，由其实际 BOM 指定。
    jackson_bom = ET.parse(m2 / f"tools/jackson/jackson-bom/{jackson}/jackson-bom-{jackson}.pom")
    annotations = jackson_bom.find("m:properties/m:jackson.version.annotations", NS).text
    jars = [("org/flywaydb", "flyway-core", version), ("org/flywaydb", "flyway-database-postgresql", version),
            ("org/postgresql", "postgresql", jdbc), ("tools/jackson/core", "jackson-databind", jackson),
            ("tools/jackson/core", "jackson-core", jackson), ("com/fasterxml/jackson/core", "jackson-annotations", annotations)]
    paths = [m2 / f"{group}/{name}/{value}/{name}-{value}.jar" for group, name, value in jars]
    for path in paths:
        if not path.is_file():
            raise FileNotFoundError("基线 Flyway 依赖不在离线缓存: " + str(path))
    return baseline, version, paths


def compile_oracle(folder, m2):
    baseline, version, jars = dependencies(m2)
    classpath = os.pathsep.join(str(path) for path in jars)
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", str(folder),
                    str(ROOT / "scripts/migration/FlywayOracle.java")], check=True, capture_output=True, timeout=60)
    return baseline, version, jars, str(folder) + os.pathsep + classpath


def generate(m2):
    with tempfile.TemporaryDirectory(prefix="personalos-java-flyway-") as folder:
        baseline, version, jars, classpath = compile_oracle(folder, m2)
        scripts = sorted((ROOT / "backend/src/main/resources/db/migration").glob("V*.sql"),
                         key=lambda path: int(path.name.split("__")[0][1:]))
        cases = [{"name": name, "sql": sql} for name, sql in [
            ("empty", ""), ("lf", "SELECT 1;\nSELECT 2;\n"), ("crlf", "SELECT 1;\r\nSELECT 2;\r\n"),
            ("cr", "SELECT 1;\rSELECT 2;\r"), ("no-final-newline", "SELECT 1;\nSELECT 2;"),
            ("bom", "\ufeffSELECT 1;\nSELECT 2;\n"), ("interior-bom", "SELECT 1;\n\ufeffSELECT 2;"),
            ("unicode", "-- 中文🔐\nSELECT 'é';\n"), ("unicode-lines", "a\u2028b\u0085c\u2029d"),
            ("blank-lines", "\r\n\n\r"), ("one-character-change", "SELECT 1;\nSELECT 3;\n"),
            ("long-line", "--" + "界" * 6000 + "\nSELECT 1;"),
        ]]
        values = [path.read_text() for path in scripts] + [case["sql"] for case in cases]
        encoded = "\n".join(base64.b64encode(sql.encode()).decode() for sql in values) + "\n"
        result = subprocess.run(["java", "-cp", classpath, "FlywayOracle"], input=encoded,
                                text=True, check=True, capture_output=True, timeout=60)
        outputs = [int(value) for value in result.stdout.splitlines()]
        if len(outputs) != len(values) or len(scripts) != 42:
            raise ValueError("Flyway 对照数量不一致")
        for case, checksum in zip(cases, outputs[len(scripts):]):
            case["checksum"] = checksum
        return {"baseline_commit": baseline, "flyway_version": version,
                "jars": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in jars},
                "scripts": [{"name": path.name, "checksum": checksum} for path, checksum in zip(scripts, outputs)],
                "cases": cases}


def migrate_disposable(environment):
    """只由新建容器的脚本调用；不读取 .env，不下载或启动 Spring 应用。"""
    with tempfile.TemporaryDirectory(prefix="personalos-java-flyway-migrate-") as folder:
        _, _, _, classpath = compile_oracle(folder, Path.home() / ".m2/repository")
        result = subprocess.run(["java", "-cp", classpath, "FlywayOracle", "migrate",
                                 str(ROOT / "backend/src/main/resources/db/migration")],
                                env=environment, text=True, capture_output=True, timeout=120)
        if result.returncode != 0:
            secret = environment["GO_TEST_POSTGRES_PASSWORD"]
            raise RuntimeError("临时 Flyway 执行失败: " + (result.stdout + result.stderr).replace(secret, "[redacted]"))
        print("真实 Flyway 双执行器锁竞争/合计42迁移/validate/再次零执行通过", flush=True)


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
        raise SystemExit("Flyway fixture 改变，需审查后显式 --write")
    print("真实 Java Flyway CRC32 对照通过：42原脚本与12边界")


if __name__ == "__main__":
    main()
