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
HEALTH_SOURCES = ["common/health/"+name+".java" for name in ["HealthController", "HealthService", "HealthStatusDto"]] + ["common/config/AppProperties.java"]


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
    for accept in ["text/html", "text/event-stream", "application/json", "application/*+json", "application/problem+json", "application/json;q=0", "*/*", "application/json;q=0,text/html;q=1"]:
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
    add("metrics-list", path="/actuator/metrics")
    add("metrics-head", "HEAD", "/actuator/metrics")
    add("metrics-options", "OPTIONS", "/actuator/metrics")
    add("metrics-post", "POST", "/actuator/metrics", csrf=True)
    for name in ["personalos.rate.limit.acquire", "personalos.rate.limit.errors", "fixture.missing"]:
        add("metric-"+name, path="/actuator/metrics/"+name)
    for query in ["tag=backend:postgres", "tag=backend:redis", "tag=backend:missing", "tag=other:value", "tag=backend", "tag=backend:postgres&tag=backend:redis", "tag=backend:redis&tag=other:value"]:
        add("metric-filter-"+str(len(rows)), path="/actuator/metrics/personalos.rate.limit.acquire?"+query)
    add("metric-json", path="/actuator/metrics/personalos.rate.limit.acquire", accept="application/json")
    add("metric-html", path="/actuator/metrics/personalos.rate.limit.acquire", accept="text/html")
    add("metric-expired", path="/actuator/metrics/personalos.rate.limit.acquire", metricExpired=True)
    add("metric-counter-filter", path="/actuator/metrics/personalos.rate.limit.errors?tag=backend:postgres")
    for method in ["GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS"]:
        add("business-health-"+method, method, "/api/v1/health", csrf=True)
    add("business-health-no-csrf", "POST", "/api/v1/health")
    add("business-health-down", path="/api/v1/health", databaseDown=True)
    add("business-health-model", path="/api/v1/health", modelConfigured=True)
    for accept in ["text/html", "text/event-stream", "application/json", "application/*+json", "application/problem+json", "application/json;q=0", "*/*"]:
        add("business-health-accept-"+str(len(rows)), path="/api/v1/health", accept=accept)
    add("business-health-head-html", "HEAD", "/api/v1/health", accept="text/html")
    def multipart(parts, close=True):
        body = ""
        for disposition, content_type, data in parts:
            body += "--fixture-boundary\r\nContent-Disposition: form-data; " + disposition + "\r\n"
            if content_type is not None: body += "Content-Type: " + content_type + "\r\n"
            body += "\r\n" + data + "\r\n"
        return body + ("--fixture-boundary--\r\n" if close else "")
    file_part = ('name="file"; filename="fixture.txt"', "text/plain", "hello")
    upload_bodies = [
        ("valid", multipart([file_part])),
        ("empty-file", multipart([('name="file"; filename="empty.txt"', "text/plain", "")])),
        ("empty-filename", multipart([('name="file"; filename=""', None, "")])),
        ("no-content-type", multipart([('name="file"; filename="data.bin"', None, "hello")])),
        ("unicode", multipart([('name="file"; filename="周报.txt"', "text/plain", "你好")])),
        ("path-filename", multipart([('name="file"; filename="../private/fixture.txt"', "text/plain", "hello")])),
        ("duplicate", multipart([file_part, ('name="file"; filename="second.txt"', "text/plain", "second")])),
        ("missing", multipart([('name="other"; filename="other.txt"', "text/plain", "hello")])),
        ("field-only", multipart([('name="file"', None, "hello")])),
        ("at-file-limit", multipart([('name="file"; filename="limit.bin"', "application/octet-stream", "x"*64)])),
        ("over-file-limit", multipart([('name="file"; filename="large.bin"', "application/octet-stream", "x"*65)])),
        ("other-file-over-limit", multipart([file_part, ('name="other"; filename="large.bin"', None, "x"*65)])),
        ("field-over-file-limit", multipart([file_part, ('name="title"', None, "x"*65)])),
        ("over-request-limit", multipart([file_part, ('name="title"', None, "x"*8192)])),
        ("at-part-limit", multipart([file_part] + [('name="f"', None, "x")]*49)),
        ("over-part-limit", multipart([file_part] + [('name="f"', None, "x")]*50)),
        ("at-header-limit", multipart([('name="file"; filename="' + 'x'*(512-len('Content-Disposition: form-data; name="file"; filename=""\r\nContent-Type: text/plain\r\n\r\n')) + '"', 'text/plain', 'x')])),
        ("over-header-limit", multipart([('name="file"; filename="' + 'x'*(513-len('Content-Disposition: form-data; name="file"; filename=""\r\nContent-Type: text/plain\r\n\r\n')) + '"', 'text/plain', 'x')])),
        ("missing-close", multipart([file_part], close=False)),
        ("empty-body", ""),
    ]
    for path in ["/api/v1/fixture-upload", "/api/v1/fixture-part"]:
        for name, body in upload_bodies:
            add("upload-"+path.rsplit("/", 1)[-1]+"-"+name, "POST", path, body=body, contentType="multipart/form-data; boundary=fixture-boundary", csrf=True, login=True)
        for content_type in ["application/json", "multipart/form-data", "multipart/form-data; boundary=wrong"]:
            add("upload-media-"+str(len(rows)), "POST", path, body=upload_bodies[0][1], contentType=content_type, csrf=True, login=True)
    accepts = [
        "application/json;q=0.1,application/problem+json;q=0.9",
        "application/problem+json;q=0.1,application/json;q=0.9",
        "application/*;q=0.9,application/problem+json;q=0.1",
        "application/json;q=0.1,application/vnd.spring-boot.actuator.v3+json;q=0.9",
        "garbage,application/json", "application/json;q=2", "application/json;q=NaN",
        "APPLICATION/JSON", "application/json;charset=UTF-8", "application/json;charset=UTF-16BE",
        "application/json;charset=UTF-16LE", "application/json;charset=UTF-32BE", "application/json;charset=UTF-32LE",
        "application/json;charset=UTF-16", "application/json;charset=ISO-8859-1", "application/json;charset=unsupported",
        'application/json;profile="a,b"', "application/json;q=0,text/html;q=1", ",",
    ]
    for path in ["/api/v1/auth/me", "/api/v1/health", "/actuator/health"]:
        for accept in accepts:
            add("negotiation-"+str(len(rows)), path=path, accept=accept)
    for name, method, path in [("management-health", "GET", "/actuator/health"), ("management-head", "HEAD", "/actuator/health"),
                               ("management-subpath", "GET", "/actuator/health/db"), ("management-business", "GET", "/api/v1/auth/me")]:
        add(name, method, path, management=True)
    for path in ["/api/v1//auth/me", "/api/v1/./auth/me", "/api/v1/../auth/me", "/api/v1/auth/me;v=1",
                 "/api/v1/auth%2fme", "/api/v1/auth/%2e%2e/me", "/%2Fapi/v1/auth/me", "/api/v1/auth%5cme",
                 "/api/v1/auth%25me", "/api/v1/auth/me%00", "/api/v1/auth/me%0a"]:
        add("firewall-" + str(len(rows)), path=path)
    for method in ["TRACE", "PRIVATE", "get"]:
        add("firewall-method-" + method, method=method)
    for name, parts in [
        ("valid", [('name="_csrf"', None, "__FIXTURE_CSRF__"), file_part]),
        ("invalid", [('name="_csrf"', None, "fixture-invalid-token"), file_part]),
        ("oversize", [('name="_csrf"', None, "__FIXTURE_CSRF__"), ('name="file"; filename="large.bin"', None, "x"*65)]),
    ]:
        add("body-csrf-" + name, "POST", "/api/v1/auth/logout", csrf=True, bodyCsrf=True,
            contentType="multipart/form-data; boundary=fixture-boundary", body=multipart(parts))
    return rows


def generate(m2):
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    pom = ET.fromstring(run(["git", "show", baseline + ":backend/pom.xml"], cwd=ROOT))
    boot = pom.find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    def version(name): return bom.find("m:properties/m:" + name + ".version", NS).text
    artifacts = [("org/springframework/boot", "spring-boot" + suffix, boot) for suffix in ["", "-autoconfigure", "-servlet", "-web-server", "-tomcat", "-webmvc", "-http-converter", "-http-codec", "-jackson", "-validation", "-security", "-actuator", "-actuator-autoconfigure", "-health", "-micrometer-metrics", "-micrometer-observation"]]
    artifacts += [("org/springframework", "spring-"+name, version("spring-framework")) for name in ["core", "beans", "context", "expression", "aop", "web", "webmvc", "tx", "jdbc"]]
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
        for name in SOURCES + AUTH_SOURCES + HEALTH_SOURCES:
            relative = "backend/src/main/java/com/personalos/" + name
            source = run(["git", "show", baseline + ":" + relative], cwd=ROOT)
            path = Path(directory, name)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source)
            sources.append(str(path))
            hashes[relative] = hashlib.sha256(source.encode()).hexdigest()
        # 模型运行时属于P3：这里仅提供HealthService编译所需接口适配器，不声称执行原模型实现。
        model_adapter = Path(directory, "fixture-adapters/com/personalos/common/ai/AiChatClientProvider.java")
        model_adapter.parent.mkdir(parents=True, exist_ok=True)
        model_adapter.write_text("package com.personalos.common.ai; public class AiChatClientProvider { public boolean isConfigured() { return false; } }")
        sources.append(str(model_adapter))
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
                    if request["name"] == "metrics-list" and "names" in response["body"]:
                        # JVM等平台指标不在Go运行时伪造；差分只比较真实记录的共同业务指标。
                        response["body"]["names"] = [name for name in response["body"]["names"] if name.startswith("personalos.rate.limit.")]
                    if "timestamp" in response["body"]:
                        response["body"]["timestamp"] = "<generated>"
                    data = response["body"].get("data")
                    if isinstance(data, dict) and data.get("token") is not None: data["token"] = "<generated>"
                    if isinstance(data, dict) and "timestamp" in data: data["timestamp"] = "<generated>"
                scenarios.append(dict(auth=auth, csrf=csrf, monitoring=management, request=request, response=response))
    return dict(baseline_commit=baseline, spring_boot=boot, sources_sha256=hashes,
                jars_sha256={jar.name: hashlib.sha256(jar.read_bytes()).hexdigest() for jar in jars},
                scope="real Servlet/Security/Jackson/Validator/Actuator and original foundation/health sources; synthetic credentials, JdbcTemplate, model-state adapter, health indicator, fixed metrics and multipart-only fixture endpoints; no upload business implementation", cases=scenarios)


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
