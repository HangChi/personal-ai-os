#!/usr/bin/env python3
"""离线运行固定基线 ModelRequestPaths，生成/核对 Go 使用的独立期望值。

仅使用本机 Maven 缓存中的基线 BOM 版本，不调用 Maven，不启动 Spring，
不读 .env、不联网、不跳过项目 JaCoCo 门禁。此工具不是全量 Java 回归。
"""

import argparse
import base64
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "specs/033-go-rewrite"
SOURCE = "backend/src/main/java/com/personalos/common/web/ModelRequestPaths.java"
TARGET = ROOT / "backend-go/internal/platform/http/testdata/java-model-request-paths.json"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def run(command, **kwargs):
    return subprocess.run(command, check=True, text=True, capture_output=True, timeout=60, **kwargs).stdout


def cases(source):
    paths = re.findall(r'"(/api/v1/[^"\n]+)"', source)
    result = []
    for pattern in paths:
        method = "PUT" if pattern == "/api/v1/knowledge/documents/*" else "POST"
        path = pattern.replace("*", "session-123")
        for other in (method, method.lower(), "GET", "DELETE", "PATCH", "OPTIONS", "HEAD"):
            result.append({"method": other, "uri": path, "context_path": ""})
        variants = [path + "/", path + "//", path.replace("/api/", "//api//"),
                    path.replace("/api/", "/API/"), path.replace("api", "%61pi", 1),
                    path.replace("/api/", "%2Fapi%2F"), path + ";v=1",
                    path + "/extra", path[1:]]
        for uri in variants:
            result.append({"method": method, "uri": uri, "context_path": ""})
        for context in ("/personal-os", "/应用"):
            result.append({"method": method, "uri": context + path, "context_path": context})
    for method, uri in [
        ("POST", "/api/v1/tasks"), ("POST", "/api/v1/chat/%75ploads"),
        ("POST", "/api/v1/chat/%2575ploads"), ("POST", "/api/v1/chat/+uploads"),
        ("POST", "/api/v1/chat/sessions//stream"),
        ("POST", "/api/v1/chat/sessions/a%2Fb/stream"),
        ("POST", "/api/v1/chat/sessions/%E7%94%A8%E6%88%B7/stream"),
        ("POST", "/api/v1/chat/sessions/%FF/stream"),
        ("POST", "/api/v1/chat/sessions/%0A/stream"),
        ("POST", "/api/v1/chat/sessions/../stream"),
        ("PUT", "/api/v1/knowledge/documents/"),
        ("PUT", "/api/v1/knowledge/documents//"),
        ("PUT", "/api/v1/knowledge/documents"),
        ("PUT", "/api/v1/knowledge/documents/%2F"),
        ("POST", "/api/v1/chat/uploads%"), ("GET", "/invalid%2"),
        ("PUT", "/invalid%GG"), ("POST", ""),
    ]:
        result.append({"method": method, "uri": uri, "context_path": ""})
    return result


def generate(m2):
    baseline = json.loads((SPEC / "inventory.json").read_text())["baseline_commit"]
    source = run(["git", "show", f"{baseline}:{SOURCE}"], cwd=ROOT)
    pom = ET.fromstring(run(["git", "show", f"{baseline}:backend/pom.xml"], cwd=ROOT))
    boot = pom.find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    spring = bom.find("m:properties/m:spring-framework.version", NS).text
    tomcat = bom.find("m:properties/m:tomcat.version", NS).text
    artifacts = [("org/springframework", "spring-core", spring),
                 ("org/springframework", "spring-web", spring),
                 ("org/apache/tomcat/embed", "tomcat-embed-core", tomcat)]
    jars = [m2 / f"{group}/{name}/{version}/{name}-{version}.jar" for group, name, version in artifacts]
    for jar in jars:
        if not jar.is_file():
            raise FileNotFoundError(f"基线依赖未缓存，工具不会自动下载：{jar}")
    import os
    classpath = os.pathsep.join(str(jar) for jar in jars)
    inputs = cases(source)
    with tempfile.TemporaryDirectory(prefix="personalos-java-paths-") as directory:
        folder = Path(directory)
        source_file = folder / "ModelRequestPaths.java"
        source_file.write_text(source)
        run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", directory,
             str(source_file), str(ROOT / "scripts/migration/ModelRequestPathsOracle.java")])
        encoded = "\n".join("\t".join(base64.b64encode(row[key].encode()).decode()
                                      for key in ("method", "uri", "context_path")) for row in inputs) + "\n"
        outputs = run(["java", "-cp", directory + os.pathsep + classpath,
                       "ModelRequestPathsOracle"], input=encoded).splitlines()
    if len(inputs) != len(outputs):
        raise ValueError("Java 对照结果数量不一致")
    for row, outcome in zip(inputs, outputs):
        if outcome not in ("true", "false", "error:IllegalArgumentException"):
            raise ValueError(f"未知 Java 对照结果：{outcome}")
        row["matched"] = outcome == "true"
        row["error"] = outcome.startswith("error:")
    return {"baseline_commit": baseline, "source": SOURCE,
            "source_sha256": hashlib.sha256(source.encode()).hexdigest(),
            "spring_boot": boot, "spring_framework": spring, "tomcat": tomcat,
            "scope": "direct Java matcher only; not servlet-container/security/router acceptance",
            "cases": inputs}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--write", action="store_true", help="显式重生成快照；默认只核对")
    args = parser.parse_args()
    output = json.dumps(generate(args.m2), ensure_ascii=False, indent=2) + "\n"
    if args.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(output)
    elif TARGET.read_text() != output:
        raise SystemExit("Java 模型路径快照不一致，请审查后显式 --write")
    print(f"Java 模型路径对照通过：{len(json.loads(output)['cases'])} 个用例")


if __name__ == "__main__":
    main()
