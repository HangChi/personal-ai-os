#!/usr/bin/env python3
"""固定基线全部YAML文档、配置record字段和@Value入口；不是绑定行为验收。"""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

from go_inventory import annotations, closing, masked_java

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "specs/033-go-rewrite"
NS = {"m": "http://maven.apache.org/POM/4.0.0"}


def run(command, **kwargs):
    result = subprocess.run(command, capture_output=True, text=True, timeout=60, **kwargs)
    if result.returncode:
        raise RuntimeError(result.stdout + result.stderr)
    return result.stdout


def value_annotations(source):
    result = []
    for annotation in annotations(source):
        if annotation["name"] != "Value":
            continue
        match = re.fullmatch(r'@(?:[\w.]+\.)?Value\s*\(\s*("(?:\\.|[^"\\])*")\s*\)', annotation["text"])
        if not match:
            raise ValueError("@Value表达式不是单个字面量，需人工扩展索引器：line " + str(annotation["line"]))
        result.append({"line": annotation["line"], "expression": json.loads(match[1])})
    return result


def configuration_class(source):
    config = [row for row in annotations(source) if row["name"] == "ConfigurationProperties"]
    if not config:
        return None
    if len(config) != 1:
        raise ValueError("多个配置声明需人工审查")
    package = re.search(r'\bpackage\s+([\w.]+)\s*;', masked_java(source))
    declaration = re.match(r'\s*(?:(?:public|protected|private|static|final)\s+)*record\s+(\w+)\s*\(',
                           masked_java(source)[config[0]["end"]:])
    if package is None or declaration is None:
        raise ValueError("配置声明不是支持的顶级record")
    return package[1] + "." + declaration[1]


def property_lookups(source):
    mask = masked_java(source)
    result = []
    for match in re.finditer(r'\b(\w+)\.(getProperty|getRequiredProperty)\s*\(', mask):
        opening = mask.index("(", match.start())
        end = closing(mask, opening)
        arguments = source[opening + 1:end - 1]
        literal = re.match(r'\s*("(?:\\.|[^"\\])*")\s*(?:,|$)', arguments)
        result.append({"line": source.count("\n", 0, match.start()) + 1, "receiver": match[1],
                       "method": match[2], "arguments": arguments.strip(),
                       "literal_key": json.loads(literal[1]) if literal else None,
                       "dynamic_key": literal is None})
    return result


def generate(m2):
    inventory = json.loads((SPEC / "inventory.json").read_text())
    baseline = inventory["baseline_commit"]
    paths = [row["path"] for row in inventory["files"]]
    pom = ET.fromstring(run(["git", "show", baseline + ":backend/pom.xml"], cwd=ROOT))
    boot = pom.find("m:parent/m:version", NS).text
    project_version = pom.find("m:version", NS).text
    bom = ET.parse(m2 / f"org/springframework/boot/spring-boot-dependencies/{boot}/spring-boot-dependencies-{boot}.pom")
    spring = bom.find("m:properties/m:spring-framework.version", NS).text
    yaml = bom.find("m:properties/m:snakeyaml.version", NS).text
    artifacts = [("org/springframework/boot", "spring-boot", boot), ("org/yaml", "snakeyaml", yaml)]
    artifacts += [("org/springframework", "spring-" + name, spring) for name in ["core", "context", "beans", "expression"]]
    artifacts += [("commons-logging", "commons-logging", "1.4.0")]
    jars = [m2 / f"{group}/{name}/{version}/{name}-{version}.jar" for group, name, version in artifacts]
    for path in jars:
        if not path.is_file():
            raise FileNotFoundError("基线依赖未缓存，不自动下载：" + str(path))
    hashes, configs, injected, lookups, yaml_paths = {}, [], [], [], []
    with tempfile.TemporaryDirectory(prefix="personalos-config-inventory-") as directory:
        folder = Path(directory)
        sources = []
        for path in sorted(paths):
            if not (path.startswith("backend/src/main/java/") and path.endswith(".java")) and not (
                    path.startswith("backend/src/main/resources/application") and path.endswith(".yml")):
                continue
            source = run(["git", "show", baseline + ":" + path], cwd=ROOT)
            if path.endswith(".yml"):
                yaml_paths.append(path)
                hashes[path] = hashlib.sha256(source.encode()).hexdigest()
                # 只替换原构建期version token；不展开任何环境占位符。
                (folder / Path(path).name).write_text(source.replace("@project.version@", project_version))
                continue
            entries = value_annotations(source)
            injected += [{"path": path, **row} for row in entries]
            direct = property_lookups(source)
            lookups += [{"path": path, **row} for row in direct]
            name = configuration_class(source)
            if entries or direct or name:
                hashes[path] = hashlib.sha256(source.encode()).hexdigest()
            if name or path.endswith("/ProductionSecurityValidator.java"):
                target = folder / Path(path).name
                target.write_text(source)
                sources.append(str(target))
            if name:
                configs.append({"class": name, "path": path})
        classpath = os.pathsep.join(str(path) for path in jars)
        run(["javac", "-encoding", "UTF-8", "-cp", classpath, "-d", directory, *sources,
             str(ROOT / "scripts/migration/ConfigInventoryOracle.java")])
        output = run(["java", "-cp", directory + os.pathsep + classpath, "ConfigInventoryOracle",
                      directory, *[row["class"] for row in configs]])
    documents, fields, prefixes, resolution = {}, [], {}, []
    for line in output.splitlines():
        values = [base64.b64decode(value).decode() for value in line.split("\t")]
        if values[0] == "YAML":
            _, name, index, key, kind, value = values
            path = "backend/src/main/resources/" + name
            if path not in yaml_paths:
                raise ValueError("出现未纳入基线的YAML")
            document = documents.setdefault((path, int(index)), {"path": path, "document_index": int(index), "properties": []})
            document["properties"].append({"key": key, "java_type": kind, "value": value})
        elif values[0] == "CONFIG":
            _, name, prefix = values
            prefixes[name] = prefix
        elif values[0] == "FIELD":
            _, owner, key, java_type, present, default = values
            fields.append({"owner": owner, "key": key, "java_type": java_type,
                           "default_annotation": None if present == "absent" else default.split("\u001f")})
        elif values[0] == "RESOLVE":
            _, name, declared, consumed, guard = values
            resolution.append({"scenario": name, "yaml_cookie_secure": declared == "true",
                               "guard_cookie_secure": consumed == "true", "production_guard": guard})
        else:
            raise ValueError("未知Java配置索引输出")
    if len(prefixes) != len(configs) or len(yaml_paths) != len({key[0] for key in documents}) or len(resolution) != 4:
        raise ValueError("配置类/YAML文件有遗漏")
    for row in configs:
        row["prefix"] = prefixes[row["class"]]
    return {"schema_version": 1, "baseline_commit": baseline, "project_version": project_version,
            "jars_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in jars},
            "sources_sha256": hashes, "configuration_records": configs,
            "record_fields": fields, "value_injections": injected, "property_lookups": lookups,
            "cookie_resolution_cases": resolution,
            "yaml_documents": list(documents.values()),
            "limitations": ["只反射record与构造器DefaultValue；紧凑构造器的归一化仍需行为验证。",
                            "YAML按原文件/文档保留，未合并profile/激活条件或展开环境占位符。",
                            "动态Map的record值展开为*；String Map/List/Set保留原类型，不能枚举运行时键。",
                            "@Value是词法单字面量索引，非SpEL/占位符求值或完整Binder验收。",
                            "Spring/第三方动态配置面仍需结合全部145环境引用与运行钩子审查。"]}


def render(data):
    documents = data["yaml_documents"]
    lines = ["# Java配置迁移对照索引", "", "固定基线 `" + data["baseline_commit"] + "`，由真实Spring YAML加载器和Java record反射生成。只记录原始来源，不能据此标记已迁移。", "",
             f"覆盖 {len(data['configuration_records'])} 个配置record、{len(data['record_fields'])} 个字段/容器节点、{len(data['value_injections'])} 处@Value、{len(data['property_lookups'])} 处直接getProperty读取、{len(documents)} 个YAML文档/{sum(len(row['properties']) for row in documents)} 个属性出现项。", "",
             "## 配置record", "", "| 前缀 | Java类 | 字段/容器节点 |", "| --- | --- | --- |"]
    for row in data["configuration_records"]:
        count = sum(field["owner"] == row["class"] for field in data["record_fields"])
        lines.append(f"| `{row['prefix']}` | `{row['class']}` | {count} |")
    lines += ["", "## 原始YAML文档", "", "文档序号从0开始；同名属性在不同文档/profile分别保留，不提前合并覆盖。构建期@project.version@仅替换成基线pom版本，环境占位符保持原文。", "", "| 文件 | 文档序号 | 属性数 |", "| --- | --- | --- |"]
    for row in documents:
        lines.append(f"| `{row['path']}` | {row['document_index']} | {len(row['properties'])} |")
    lines += ["", "## 已实测的Cookie配置路径差异", "",
              "基线YAML在`spring.servlet.session.cookie.secure`放值，原SecurityConfig/SharedSessionConfig/ProductionSecurityValidator读取`server.servlet.session.cookie.secure`。以下使用真实Spring属性解析和原生产守卫，只注入合成map，未读取宿主环境。", "",
              "| 合成场景 | YAML路径值 | 守卫读取值 | 原生产守卫 |", "| --- | --- | --- | --- |"]
    for row in data["cookie_resolution_cases"]:
        lines.append(f"| `{row['scenario']}` | {str(row['yaml_cookie_secure']).lower()} | {str(row['guard_cookie_secure']).lower()} | {row['production_guard']} |")
    lines += ["", "prod-cookie-alias使用有效虚构密码、SESSION_COOKIE_SECURE=true及prod YAML，仍因守卫读到false拒绝；额外显式SERVER_SERVLET_SESSION_COOKIE_SECURE=true才通过该原守卫。Go LoadSecurity已按这份真实来源对照修正，不再把SESSION_COOKIE_SECURE/prod错误路径伪造成true；先前生产守卫参数级测试不能证明来源绑定等价。保留原Java/YAML并记录该历史差异，完整属性绑定保持T104开放；Go生产监听仍被禁止，不能据此放开生产。", "",
              "## 动态与默认值边界", "", "- MCP的`mcp.servers.*`与其env/args/auto-tools不能按静态YAML清单封死；JSON保留完整泛型类型和嵌套record通配节点。",
              "- `@DefaultValue`、YAML默认占位符、record紧凑构造器归一化是三个来源，不能只选其中一个。",
              "- @Value注入/直接getProperty读取的原表达式及行号、全部字段和YAML值见`config-inventory.json`；动态键调用单独标明，需回到调用方常量表/循环审查，不能按字面键过滤掉。索引生成器遇到不支持的声明会失败。",
              "- 此处只读基线Git内容与离线依赖，不读真实.env、不绑定系统配置、不启动应用。", "", "## 复验", "", "```bash", "python3 scripts/migration/go_config_inventory.py", "```", "", "默认重生成并比较；只有显式`--write`才更新这两份索引。另须继续运行go_inventory.py check；该索引不替代全量绑定/行为/Java-Go差分和发布门禁。", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--m2", type=Path, default=Path.home() / ".m2/repository")
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    data = generate(args.m2)
    outputs = {SPEC / "config-inventory.json": json.dumps(data, ensure_ascii=False, indent=2) + "\n",
               SPEC / "config-inventory.md": render(data)}
    for path, content in outputs.items():
        if args.write:
            path.write_text(content)
        elif not path.is_file() or path.read_text() != content:
            raise SystemExit("配置索引不同，需审查后显式--write：" + str(path))
    print("配置索引通过：record=" + str(len(data["configuration_records"])) + ", fields=" + str(len(data["record_fields"])) + ", @Value=" + str(len(data["value_injections"])))


if __name__ == "__main__":
    main()
