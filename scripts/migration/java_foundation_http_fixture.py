#!/usr/bin/env python3
"""从固定基线启动真实 Spring Servlet，合成配置/临时端口，不读现有 .env 或服务。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from java_exception_fixture import NS, ROOT, SOURCES

TARGET = ROOT / "backend-go/internal/app/testdata/java-foundation-http.json"
AUTH_SOURCES = ["common/auth/" + name + ".java" for name in ["SecurityConfig", "MonitoringSecurityConfig", "AuthController", "CsrfController", "AuthService", "AuthProperties", "CurrentUserDto", "LoginRequest"]] + ["common/json/JsonCodec.java"]


def run(command, **kwargs):
    # 不继承 JAVA_TOOL_OPTIONS/JDK_JAVA_OPTIONS 等宿主注入；应用来源另行全部隔离。
    environment = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR", "JAVA_HOME"}}
    result = subprocess.run(command, text=True, capture_output=True, timeout=90, env=environment, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def cases():
    rows = []
    def add(name, method="GET", path="/api/v1/auth/me", **extra):
        rows.append(dict(name=name, method=method, path=path, **extra))
    for name, method, path in [
        ("me", "GET", "/api/v1/auth/me"), ("me-head", "HEAD", "/api/v1/auth/me"), ("me-options", "OPTIONS", "/api/v1/auth/me"),
        ("me-post", "POST", "/api/v1/auth/me"), ("login-get", "GET", "/api/v1/auth/login"),
        ("missing", "GET", "/api/v1/missing"), ("csrf", "GET", "/api/v1/auth/csrf"),
        ("health", "GET", "/actuator/health"), ("health-head", "HEAD", "/actuator/health"),
        ("health-post", "POST", "/actuator/health"), ("info", "GET", "/actuator/info"),
    ]: add(name, method, path)
    for accept in ["text/html", "text/event-stream", "application/json", "application/*+json", "application/json;q=0", "*/*", "application/json;q=0,text/html;q=1"]:
        add("me-accept-"+str(len(rows)), accept=accept)
    bodies = [
        ("valid", '{"username":"admin","password":"fixture-password"}'),
        ("unknown", '{"username":"admin","password":"fixture-password","extra":42}'),
        ("case", '{"USERNAME":"admin","password":"fixture-password"}'),
        ("number", '{"username":123,"password":"fixture-password"}'),
        ("boolean", '{"username":true,"password":"fixture-password"}'),
        ("array", '["admin","fixture-password"]'), ("null", "null"), ("broken", "{"),
        ("trailing", '{"username":"admin","password":"fixture-password"} {}'),
        ("missing-user", '{"password":"fixture-password"}'), ("missing-password", '{"username":"admin"}'),
        ("blank-user", '{"username":"　","password":"fixture-password"}'),
        ("null-password", '{"username":"admin","password":null}'),
        ("wrong", '{"username":"admin","password":"wrong"}'),
        ("duplicate", '{"username":"wrong","username":"admin","password":"fixture-password"}'),
    ]
    for name, body in bodies:
        add("login-"+name, "POST", "/api/v1/auth/login", body=body, contentType="application/json", csrf=True)
    for content_type in ["text/plain", "application/problem+json", "application/json;charset=UTF-8", "application/json;charset=unsupported", "application/xml"]:
        add("login-media-"+str(len(rows)), "POST", "/api/v1/auth/login", body=bodies[0][1], contentType=content_type, csrf=True)
    add("login-empty", "POST", "/api/v1/auth/login", body="", contentType="application/json", csrf=True)
    add("login-no-type", "POST", "/api/v1/auth/login", body=bodies[0][1], csrf=True)
    add("login-no-csrf", "POST", "/api/v1/auth/login", body=bodies[0][1], contentType="application/json")
    add("login-bad-csrf", "POST", "/api/v1/auth/login", body=bodies[0][1], contentType="application/json", csrf=True, badCsrf=True)
    add("login-accept", "POST", "/api/v1/auth/login", body=bodies[0][1], contentType="application/json", accept="text/html", csrf=True)
    add("health-down", path="/actuator/health", databaseDown=True)
    add("health-accept", path="/actuator/health", accept="application/json")
    add("health-html", path="/actuator/health", accept="text/html")
    add("health-authenticated", path="/actuator/health", login=True, csrf=True)
    add("health-readiness", path="/actuator/health/readiness")
    add("health-liveness", path="/actuator/health/liveness")
    add("health-component-anonymous", path="/actuator/health/db")
    add("health-component-authenticated", path="/actuator/health/db", login=True, csrf=True)
    for name, method, path in [("management-health", "GET", "/actuator/health"), ("management-head", "HEAD", "/actuator/health"),
                               ("management-subpath", "GET", "/actuator/health/db"), ("management-business", "GET", "/api/v1/auth/me")]:
        add(name, method, path, management=True)
    return rows


def generate(m2):
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    pom = ET.fromstring(run(["git", "show", baseline + ":backend/pom.xml"], cwd=ROOT))
    boot = pom.find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    def version(name): return bom.find("m:properties/m:" + name + ".version", NS).text
    artifacts = [("org/springframework/boot", "spring-boot" + suffix, boot) for suffix in ["", "-autoconfigure", "-servlet", "-web-server", "-tomcat", "-webmvc", "-http-converter", "-http-codec", "-jackson", "-validation", "-security", "-actuator", "-actuator-autoconfigure", "-health", "-micrometer-metrics", "-micrometer-observation"]]
    artifacts += [("org/springframework", "spring-"+name, version("spring-framework")) for name in ["core", "beans", "context", "expression", "aop", "web", "webmvc", "tx"]]
    artifacts += [("org/springframework/security", "spring-security-"+name, version("spring-security")) for name in ["core", "web", "config", "crypto"]]
    artifacts += [("io/micrometer", "micrometer-"+name, version("micrometer")) for name in ["core", "commons", "observation"]]
    artifacts += [("org/apache/tomcat/embed", "tomcat-embed-"+name, version("tomcat")) for name in ["core", "el", "websocket"]]
    artifacts += [("tools/jackson/core", "jackson-"+name, version("jackson-bom")) for name in ["core", "databind"]]
    jackson_bom = ET.parse(m2 / f"tools/jackson/jackson-bom/{version('jackson-bom')}/jackson-bom-{version('jackson-bom')}.pom")
    annotations = jackson_bom.find("m:properties/m:jackson.version.annotations", NS).text
    artifacts += [("com/fasterxml/jackson/core", "jackson-annotations", annotations), ("org/yaml", "snakeyaml", version("snakeyaml")),
                  ("jakarta/annotation", "jakarta.annotation-api", version("jakarta-annotation")), ("jakarta/validation", "jakarta.validation-api", version("jakarta-validation")),
                  ("org/hibernate/validator", "hibernate-validator", version("hibernate-validator")), ("org/jboss/logging", "jboss-logging", version("jboss-logging")),
                  ("com/fasterxml", "classmate", version("classmate")), ("org/slf4j", "slf4j-api", version("slf4j")),
                  ("ch/qos/logback", "logback-core", version("logback")), ("ch/qos/logback", "logback-classic", version("logback")),
                  ("commons-logging", "commons-logging", "1.4.0"), ("org/jspecify", "jspecify", version("jspecify"))]
    jars = [m2 / f"{group}/{name}/{value}/{name}-{value}.jar" for group, name, value in artifacts]
    for jar in jars:
        if not jar.is_file(): raise FileNotFoundError("固定基线依赖未缓存，不下载：" + str(jar))
    hashes, scenarios = {}, []
    with tempfile.TemporaryDirectory(prefix="personalos-java-foundation-http-") as directory:
        sources = []
        for name in SOURCES + AUTH_SOURCES:
            relative = "backend/src/main/java/com/personalos/" + name
            source = run(["git", "show", baseline + ":" + relative], cwd=ROOT)
            path = Path(directory, name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(str(path))
            hashes[relative] = hashlib.sha256(source.encode()).hexdigest()
        cp = os.pathsep.join(str(path) for path in jars)
        run(["javac", "-parameters", "-encoding", "UTF-8", "-cp", cp, "-d", directory, *sources, str(ROOT / "scripts/migration/FoundationHttpOracle.java")])
        for auth, csrf, management in [(False, False, False), (True, False, False), (True, True, False), (True, True, True)]:
            inputs = cases()
            output = run(["java", "-cp", directory + os.pathsep + cp, "FoundationHttpOracle", str(auth).lower(), str(csrf).lower(), str(management).lower()],
                         cwd=directory, input="\n".join(json.dumps(row, ensure_ascii=False) for row in inputs)+"\n")
            results = [json.loads(line[5:]) for line in output.splitlines() if line.startswith("CASE\t")]
            if len(results) != len(inputs): raise ValueError("真实 HTTP 场景结果数量不符")
            for request, response in zip(inputs, results):
                if request["name"] != response.pop("name"): raise ValueError("HTTP 场景顺序不符")
                if response["body"]:
                    try: response["body"] = json.loads(response["body"])
                    except json.JSONDecodeError: pass
                if isinstance(response["body"], dict):
                    if "timestamp" in response["body"]:
                        response["body"]["timestamp"] = "<generated>"
                    data = response["body"].get("data")
                    if isinstance(data, dict) and data.get("token") is not None: data["token"] = "<generated>"
                scenarios.append(dict(auth=auth, csrf=csrf, monitoring=management, request=request, response=response))
    return dict(baseline_commit=baseline, spring_boot=boot, sources_sha256=hashes,
                jars_sha256={jar.name: hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars},
                scope="real embedded Servlet/Security/Jackson/Validator/Actuator with original foundation sources; synthetic auth and database HealthIndicator only", cases=scenarios)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    result = generate(args.m2)
    output = json.dumps(result, ensure_ascii=False, indent=2)+"\n"
    if args.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(output)
    elif TARGET.read_text() != output: raise SystemExit("真实基础 HTTP 快照改变，需审查后显式 --write")
    print(f"真实基础 HTTP 对照通过：{len(result['cases'])} 场景")


if __name__ == "__main__": main()
