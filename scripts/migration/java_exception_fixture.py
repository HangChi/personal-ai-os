#!/usr/bin/env python3
"""离线直接运行固定Java全局异常处理器；不启动Spring或读取真实配置。"""

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
TARGET = ROOT / "backend-go/internal/platform/http/testdata/java-exceptions.json"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
SOURCES = ["common/api/ApiResponse.java", "common/api/ErrorCode.java",
           "common/exception/BusinessException.java", "common/exception/ResourceNotFoundException.java",
           "common/exception/GlobalExceptionHandler.java"]


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

    def version(name):
        return bom.find("m:properties/m:" + name + ".version", NS).text

    spring = version("spring-framework")
    jackson = version("jackson-bom")
    annotations = ET.parse(m2 / f"tools/jackson/jackson-bom/{jackson}/jackson-bom-{jackson}.pom").find(
        "m:properties/m:jackson.version.annotations", NS).text
    artifacts = [("org/springframework", "spring-" + name, spring)
                 for name in ["core", "beans", "context", "expression", "web", "webmvc", "tx", "test"]]
    artifacts += [("org/slf4j", "slf4j-api", version("slf4j")),
                  ("ch/qos/logback", "logback-core", version("logback")),
                  ("ch/qos/logback", "logback-classic", version("logback")),
                  ("org/apache/tomcat/embed", "tomcat-embed-core", version("tomcat")),
                  ("jakarta/validation", "jakarta.validation-api", version("jakarta-validation")),
                  ("com/fasterxml/jackson/core", "jackson-annotations", annotations),
                  ("commons-logging", "commons-logging", "1.4.0")]
    jars = [m2 / f"{group}/{name}/{value}/{name}-{value}.jar" for group, name, value in artifacts]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError("基线依赖未缓存，不会自动下载：" + str(jar))
    hashes, cases = {}, []
    with tempfile.TemporaryDirectory(prefix="personalos-java-errors-") as directory:
        sources = []
        for name in SOURCES:
            relative = "backend/src/main/java/com/personalos/" + name
            source = run(["git", "show", baseline + ":" + relative], cwd=ROOT)
            path = Path(directory) / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(str(path))
            hashes[relative] = hashlib.sha256(source.encode()).hexdigest()
        classpath = os.pathsep.join(str(path) for path in jars)
        run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", directory,
             *sources, str(ROOT / "scripts/migration/ExceptionHandlerOracle.java")])
        output = run(["java", "-cp", directory + os.pathsep + classpath, "ExceptionHandlerOracle"])
        disconnected = False
        for line in output.splitlines():
            if line == "DISCONNECT\tDEBUG\tno-body":
                disconnected = True
                continue
            if not line.startswith("CASE\t"):
                raise ValueError("Java对照返回非预期输出")
            _, name, status, code, message, content_type = line.split("\t")
            cases.append({"name": name, "status": int(status), "code": int(code),
                          "message": base64.b64decode(message).decode(),
                          "content_type": base64.b64decode(content_type).decode()})
        if len(cases) != 36 or not disconnected or len({case["name"] for case in cases}) != len(cases):
            raise ValueError("Java对照数量/断连契约不一致：" + str(len(cases)))
    return {"baseline_commit": baseline, "sources_sha256": hashes,
            "spring_framework": spring, "jars": {jar.name: hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars},
            "scope": "direct baseline exception handlers only; not servlet dispatch/JSON binding/validation providers",
            "disconnect": "DEBUG/no-body", "cases": cases}


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
        raise SystemExit("异常处理快照改变，需审查后显式--write")
    print("真实Java异常处理器对照通过：36响应与断连/405日志契约")


if __name__ == "__main__":
    main()
