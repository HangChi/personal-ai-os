#!/usr/bin/env python3
"""离线对照固定 Java 配置解析与密文格式；需要已通过原 Maven verify 的本地构建产物。

不读 .env、不启动 Spring、不创建模型/数据库连接、不下载依赖。只用合成密钥。
--write 显式更新期望值；默认复验原 Java 输出并验证 Go 新密文可由 Java 解密。
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / "backend-go/internal/business/model/testdata/java-config-read.json"
PREFIX = "backend/src/main/java/com/personalos/common/ai/config/"
SOURCES = [PREFIX + name + ".java" for name in [
    "ModelConfigService", "AiModelProfileEntity", "AiModelConfigEntity", "ModelKeyConverter", "ModelProtocol", "dto/ModelConfigDto"]]
DEFAULTS = {"spring.ai.openai.base-url": "https://api.openai.com", "spring.ai.openai.api-key": "",
            "spring.ai.openai.chat.model": "gpt-4o-mini", "spring.ai.openai.embedding.model": "text-embedding-3-small",
            "spring.ai.openai.chat.temperature": "0.7"}


def cases():
    rows = []

    def add(name, properties=None, variables=None, values=None):
        rows.append({"name": name, "properties": DEFAULTS | (properties or {}), "variables": variables or {}, "values": values})

    add("yaml-defaults")
    add("builtin-defaults", {key: "" for key in DEFAULTS})
    variables = {"OPENAI_BASE_URL": " https://env.invalid/v1 ", "OPENAI_API_KEY": "synthetic-env-only-abcd",
                 "OPENAI_CHAT_MODEL": "env-chat", "OPENAI_EMBEDDING_MODEL": "env-embed"}
    properties = dict(zip(list(DEFAULTS)[:4], variables.values()))
    add("environment", properties, variables)
    add("mixed-database", properties, variables, {"chatModel": "db-chat", "temperature": 0})
    add("database-all", properties, variables, {"baseUrl": "  https://db.invalid  ", "apiKey": "synthetic-db-only-wxyz",
        "chatModel": "db-chat", "embeddingModel": "db-embed", "temperature": 1.2, "audioModel": "\t asr \u3000"})
    add("blank-db-values", properties, variables, {"baseUrl": "\u3000", "apiKey": "\t", "chatModel": "\u001c", "audioModel": "\u3000"})
    add("nbsp-is-not-blank", values={"baseUrl": "\u00a0", "apiKey": "\u2007", "chatModel": "\u202f"})
    add("blank-environment", {"spring.ai.openai.api-key": "\t\u3000"}, {"OPENAI_API_KEY": "\t\u3000"})
    add("direct-property-source", {"spring.ai.openai.base-url": "https://direct.invalid"})
    for temperature in ["", "bad", "0", "-0.0", "1f", "0x1.8p-1", "NaN", "Infinity", "-Infinity", "1e999", "\u3000.5\u3000", "1_0", "inf", "0x10"]:
        add("temperature-" + repr(temperature), {"spring.ai.openai.chat.temperature": temperature})
    return rows


def run(command, env, **kwargs):
    return subprocess.run(command, env=env, check=True, capture_output=True, text=True, timeout=90, **kwargs).stdout


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    args = parser.parse_args()
    env = {key: value for key, value in os.environ.items() if key in {"PATH", "HOME", "TMPDIR", "JAVA_HOME", "CC", "CXX", "CGO_ENABLED"}}
    env.update({"GOPROXY": "off", "GOTOOLCHAIN": "local"})
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    # 精确使用本次原 Java 门禁的依赖；不从整个 Maven 缓存里猜版本。
    report = ROOT / "backend/target/surefire-reports/TEST-com.personalos.common.ai.config.ModelConfigServiceTest.xml"
    properties = ET.parse(report).find("properties")
    classpath = next(item.attrib["value"] for item in properties if item.attrib.get("name") == "java.class.path")
    jars = [Path(path) for path in classpath.split(os.pathsep) if path.endswith(".jar")]
    if not jars or any(not jar.is_file() for jar in jars):
        raise RuntimeError("原 Java 门禁依赖不完整；请先离线执行 verify，不自动下载")
    cp = os.pathsep.join([str(ROOT / "backend/target/classes"), *(str(jar) for jar in jars)])
    existing = json.loads(TARGET.read_text()) if TARGET.exists() and not args.write else {}
    input_data = {"cases": cases(), "javaCiphertext": existing.get("javaCiphertext"),
                  "maskInputs": ["", "\u3000", "abc", "abcd", "12345", "key😀ab", "key😀abc", "密钥😀尾巴"]}
    with tempfile.TemporaryDirectory(prefix="personalos-java-model-config-") as folder:
        hashes, files = {}, []
        for source in SOURCES:
            content = run(["git", "show", f"{baseline}:{source}"], env, cwd=ROOT)
            path = Path(folder) / source.removeprefix(PREFIX)
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
            hashes[source] = hashlib.sha256(content.encode()).hexdigest()
            files.append(str(path))
        run(["javac", "-encoding", "UTF-8", "-proc:none", "-sourcepath", "", "-cp", cp, "-d", folder,
             *files, str(ROOT / "scripts/migration/ModelConfigReadOracle.java")], env, cwd=ROOT)
        oracle_cp = folder + os.pathsep + cp
        output = run(["java", "-cp", oracle_cp, "ModelConfigReadOracle"], env, input=json.dumps(input_data), cwd=ROOT)
        result = json.loads(next(line.removeprefix("ORACLE ") for line in output.splitlines() if line.startswith("ORACLE ")))
        expected = result.pop("cases")
        if len(expected) != len(input_data["cases"]):
            raise RuntimeError("原 Java 返回场景数量不一致")
        result["cases"] = [case | {"expected": value} for case, value in zip(input_data["cases"], expected)]
        result["baseline_commit"] = baseline
        result["sources_sha256"] = hashes
        result["scope"] = "configuration merge/DTO/key converter only; not HTTP, CRUD, runtime model or full P3 acceptance"
        # Go→Java 也必须验证，不能只证明 Go 能读自己的密文。
        with tempfile.TemporaryDirectory(prefix=".model-key-oracle-", dir=ROOT / "backend-go") as go_folder:
            go_source = '''package main
import("fmt";"os";"personal-ai-os/backend-go/internal/business/model")
func main(){c,e:=model.NewKeyCodec(os.Args[1]);if e!=nil{panic(e)};p:=os.Args[2];v,e:=c.Encrypt(&p);if e!=nil{panic(e)};fmt.Print(*v)}
'''
            Path(go_folder, "main.go").write_text(go_source)
            encrypted = run(["go", "run", "./" + Path(go_folder).name, result["syntheticMaster"], result["syntheticPlaintext"]], env, cwd=ROOT / "backend-go")
        check = run(["java", "-cp", oracle_cp, "ModelConfigReadOracle", "decrypt-go", encrypted], env, cwd=ROOT)
        if "ORACLE GO_ROUNDTRIP_OK" not in check:
            raise RuntimeError("Go 密文未通过原 Java 转换器验证")
    output = json.dumps(result, ensure_ascii=True, indent=2) + "\n"
    if args.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(output)
    elif TARGET.read_text() != output:
        raise SystemExit("原 Java 模型配置期望值发生变化，请审查后显式 --write")
    print(f"原 Java 模型配置 {len(result['cases'])} 场景、{len(result['masks'])} 掩码及双向 AES-GCM 互通通过")


if __name__ == "__main__":
    main()
