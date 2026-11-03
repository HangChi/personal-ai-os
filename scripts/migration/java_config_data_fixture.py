#!/usr/bin/env python3
"""真实Boot ConfigData、来源优先级与占位符对照；只读Git基线、临时YAML和虚构输入。"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import tempfile
import xml.etree.ElementTree as ET

from java_config_scalar_fixture import NS, ROOT, run

TARGET = ROOT / "backend-go/internal/platform/config/testdata/java-config-data.json"
BASELINE = ROOT / "backend-go/internal/platform/config/baseline.json"


def cases():
    rows = [{"system": {}, "overrides": {}, "dotenv": {}}]
    def add(system=None, overrides=None, dotenv=None):
        rows.append({"system": system or {}, "overrides": overrides or {}, "dotenv": dotenv or {}})
    for profile in ["runtime-api", "worker-index", "worker-review", "worker-delivery", "monitoring", "prod", "unknown",
                    "worker-index,runtime-api", "runtime-api,worker-index", "prod,worker-review,monitoring", "", " ",
                    "worker-index,worker-index", "!prod", "a b", " worker-index , runtime-api "]:
        add(system={"SPRING_PROFILES_ACTIVE": profile})
        add(dotenv={"SPRING_PROFILES_ACTIVE": profile})
    for profile in ["worker-index", "runtime-api,worker-delivery", "", "unknown"]:
        add(system={"SPRING_PROFILES_DEFAULT": profile})
    for key, value in {
        "POSTGRES_HOST":"fixture-db", "POSTGRES_PORT":"6543", "POSTGRES_DB":"fixture-db-name", "POSTGRES_PASSWORD":"fixture-password",
        "AUTH_ENABLED":"true", "AUTH_PASSWORD":"fixture-auth-password", "SESSION_COOKIE_SECURE":"true",
        "SERVER_SERVLET_SESSION_COOKIE_SECURE":"true", "APP_RUNTIME_ROLE":"delivery", "AI_RATE_LIMIT_CAPACITY":"7",
        "APP_RATELIMIT_CAPACITY":"8", "APP_RATE_LIMIT_CAPACITY":"9", "app.rate-limit.capacity":"10",
        "APP_CONTEXT_SUMMARY_MINMESSAGES":"8", "APP_CONTEXT_SUMMARY_MIN_MESSAGES":"9",
        "SERVER_PORT":"8181", "APP_NAME":"fixture-name", "MCP_ENABLED":"true",
        "REDIS_SESSION_URI":"redis://fixture-only:1", "OPENAI_API_KEY":"fixture-openai-key", "AI_CHAT_PROVIDER":"none",
    }.items():
        add(system={key:value})
        add(dotenv={key:value})
        add(system={key:value}, dotenv={key:"low-priority"})
    for profile in ["prod", "worker-index", "runtime-api,worker-review"]:
        add(system={"SPRING_PROFILES_ACTIVE":profile, "APP_RUNTIME_ROLE":"delivery", "AUTH_ENABLED":"false"},
            dotenv={"APP_RUNTIME_ROLE":"api", "AUTH_ENABLED":"false", "SERVER_PORT":"8182"})
    for value in ["${fixture.missing}", "${fixture.missing:default}", "${fixture.left:${fixture.right:fallback}}",
                  "${fixture.present:fallback}", "${fixture.empty:fallback}", "${fixture.loop}", "${fixture.a}",
                  "before ${fixture.present} after", "${fixture.present}${fixture.present}", "${fixture.${fixture.selector}:fallback}",
                  "\\${fixture.present}", "\\\\${fixture.present}", "${unclosed", "a:b", "${fixture.missing:}",
                  "${fixture.missing:http://example.invalid:123/a}"]:
        add(overrides={"fixture.result":value, "fixture.present":"合成😀", "fixture.empty":"", "fixture.loop":"${fixture.loop}",
                       "fixture.a":"${fixture.b}", "fixture.b":"${fixture.a}", "fixture.selector":"present"})
    add(system={"AUTH_PASSWORD":"${fixture.missing}"})
    add(system={"SPRING_PROFILES_ACTIVE":"${fixture.profile}", "fixture.profile":"worker-index"})
    add(system={"SPRING_PROFILES_INCLUDE":"monitoring", "SPRING_PROFILES_ACTIVE":"runtime-api"})
    for values in [
        {"APP_AUTH_ENABLED":"false", "app.auth.enabled":"true"},
        {"APP_RATELIMIT_CAPACITY":"8", "APP_RATE_LIMIT_CAPACITY":"9"},
        {"APP_CONTEXT_SUMMARY_MINMESSAGES":"8", "APP_CONTEXT_SUMMARY_MIN_MESSAGES":"9"},
        {"SPRING_PROFILES_ACTIVE":"runtime-api", "spring.profiles.active":"worker-index"},
    ]:
        add(system=values)
        add(dotenv=values)
    for value in ["${fixture.present:${fixture.missing}}", "${fixture.missing:${fixture.missing:fallback}}", "${fixture.present:unused:${fixture.missing}}"]:
        add(overrides={"fixture.result":value,"fixture.present":"fixture-only"})
    return rows


def generate(m2):
    inventory = json.loads((ROOT / "specs/033-go-rewrite/config-inventory.json").read_text())
    baseline = inventory["baseline_commit"]
    pom = ET.fromstring(run(["git", "show", baseline+":backend/pom.xml"], cwd=ROOT))
    boot = pom.find("m:parent/m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    spring = bom.find("m:properties/m:spring-framework.version", NS).text
    yaml = bom.find("m:properties/m:snakeyaml.version", NS).text
    artifacts = [("org/springframework/boot", "spring-boot", boot), ("org/yaml", "snakeyaml", yaml)]
    artifacts += [("org/springframework", "spring-"+name, spring) for name in ["core", "context", "beans", "expression"]]
    artifacts += [("commons-logging", "commons-logging", "1.4.0")]
    jars = [m2 / f"{group}/{name}/{version}/{name}-{version}.jar" for group,name,version in artifacts]
    for path in jars:
        if not path.is_file(): raise FileNotFoundError("离线依赖不存在，不下载："+str(path))
    documents = []
    yaml_paths = sorted({doc["path"] for doc in inventory["yaml_documents"]})
    for doc in inventory["yaml_documents"]:
        documents.append({"file":Path(doc["path"]).name, "index":doc["document_index"], "properties":{row["key"]:row["value"] for row in doc["properties"]}})
    bundle = {"baseline_commit":baseline, "sources_sha256":{path:inventory["sources_sha256"][path] for path in yaml_paths}, "documents":documents}
    keys = sorted({row["key"] for doc in inventory["yaml_documents"] for row in doc["properties"]} - {"spring.config.activate.on-profile"}
                  | {"server.servlet.session.cookie.secure", "fixture.result", "fixture.missing", "fixture.present", "fixture.empty"})
    rows = cases()
    with tempfile.TemporaryDirectory(prefix="personalos-config-data-") as directory:
        folder = Path(directory)
        for path in yaml_paths:
            source = run(["git", "show", baseline+":"+path], cwd=ROOT)
            if hashlib.sha256(source.encode()).hexdigest() != inventory["sources_sha256"][path]: raise ValueError("YAML来源哈希不符")
            (folder / Path(path).name).write_text(source.replace("@project.version@", inventory["project_version"]))
        (folder / "keys.txt").write_text("\n".join(keys)+"\n")
        cp = os.pathsep.join(str(path) for path in jars)
        run(["javac", "-encoding", "UTF-8", "-cp", cp, "-d", directory, str(ROOT / "scripts/migration/ConfigDataOracle.java")])
        inputs=[]
        for row in rows:
            fields=[]
            for key, tag in [("overrides","MAP"),("system","ENV"),("dotenv","DOT")]:
                fields.append(tag)
                for name,value in sorted(row[key].items()): fields += [base64.b64encode(name.encode()).decode(),base64.b64encode(value.encode()).decode()]
            inputs.append("\t".join(fields))
        output=run(["java", "-cp", directory+os.pathsep+cp, "ConfigDataOracle", directory, str(folder/"keys.txt")], input="\n".join(inputs)+"\n").splitlines()
    if len(output)!=len(rows): raise ValueError("ConfigData结果数量不符")
    for row,encoded in zip(rows,output):
        value=base64.b64decode(encoded).decode()
        row["java_error"]=value=="error"
        row["value"]=None if row["java_error"] else json.loads(value)
    fixture={"baseline_commit":baseline,"spring_boot":boot,"jars_sha256":{path.name:hashlib.sha256(path.read_bytes()).hexdigest() for path in jars},"keys":keys,"cases":rows}
    return bundle,fixture


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2",type=Path,default=Path.home()/".m2/repository")
    parser.add_argument("--write",action="store_true")
    args=parser.parse_args()
    bundle,fixture=generate(args.m2)
    for path,data in [(BASELINE,bundle),(TARGET,fixture)]:
        output=json.dumps(data,ensure_ascii=False,indent=2)+"\n"
        if args.write: path.write_text(output)
        elif path.read_text()!=output: raise SystemExit("ConfigData快照变化，需审查后显式--write")
    print(f"真实ConfigData对照通过：{len(fixture['cases'])}场景，{len(fixture['keys'])}属性，{len(bundle['documents'])}文档")


if __name__=="__main__": main()
