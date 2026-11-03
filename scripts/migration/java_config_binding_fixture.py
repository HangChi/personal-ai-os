#!/usr/bin/env python3
"""全部12类真实Spring配置record的对照；不读取宿主环境或YAML。"""

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
    "mcp": ("mcp", "com.personalos.mcp.config.McpProperties"),
    "plan": ("app.plan", "com.personalos.agent.plan.PlanProperties"),
    "calendar": ("app.calendar", "com.personalos.calendar.config.CalendarProperties"),
    "ai": ("app.ai", "com.personalos.common.ai.AiProperties"),
    "context": ("app.context", "com.personalos.common.ai.ContextProperties"),
    "protection": ("app.context.protection", "com.personalos.common.ai.ContextProtectionProperties"),
    "app": ("app", "com.personalos.common.config.AppProperties"),
    "knowledge": ("app.knowledge", "com.personalos.knowledge.config.KnowledgeProperties"),
    "memory": ("app.memory", "com.personalos.memory.MemoryProperties"),
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
    result += mcp_cases()
    result += remaining_record_cases()
    return result


def remaining_record_cases():
    result = []
    def add(kind, key, values):
        prefix = RECORDS[kind][0]
        for value in values:
            result.append({"kind": kind, "input": {prefix + "." + key: value}})
    integers = ["", " ", "0", "-1", "1", "+12", "0012", "2147483647", "2147483648", "-2147483648",
                "-2147483649", "0x10", "#10", "+0x10", "-0x10", "1.0", "1_000", "1,000",
                " 1\t2\n", "1\u30002", "１２", "١٢", "𝟙𝟚", "0xＦＦ", "++1", "--1", "0x+10", "1\u00a02"]
    for kind, keys in {
        "plan": ["max-inject-chars"],
        "calendar": ["max-range-days", "default-query-limit", "max-query-limit"],
        "ai": ["memory.max-messages", "memory.context-window-tokens"],
        "context": ["summary.min-messages", "summary.max-excerpt-chars", "summary.max-summary-chars", "auto-memory.max-items"],
        "protection": ["output-reserve-tokens", "safety-margin-tokens", "image-reserve-tokens", "max-retries"],
        "knowledge": ["embedding-dimensions", "top-k", "min-chunk-size-chars", "chunk-size-chars", "chunk-overlap-chars", "rrf-k"],
        "memory": ["pinned-max-count", "ranking.half-life-days", "backfill.batch-size"],
    }.items():
        for key in keys:
            add(kind, key, integers)
    for kind, key in [("calendar", "scan-interval-ms"), ("calendar", "scan-initial-delay-ms"), ("knowledge", "max-file-size-bytes")]:
        add(kind, key, integers + ["9223372036854775807", "-9223372036854775808", "9223372036854775808", "0x7fffffffffffffff"])
    for kind, keys in {"plan": ["enabled"], "calendar": ["scan-enabled"], "context": ["summary.enabled", "auto-memory.enabled"],
                       "knowledge": ["hybrid-search-enabled"], "memory": ["ranking.enabled", "backfill.enabled"]}.items():
        for key in keys:
            add(kind, key, ["", " ", "true", "TRUE", "on", "yes", "1", "false", "off", "NO", "0", " true\t", "\u3000true", "bad"])
    for kind, keys in {"calendar": ["scan-lookahead", "scan-lookback", "default-reminder-lead"],
                       "context": ["summary.timeout"], "memory": ["ranking.timeout"]}.items():
        for key in keys:
            add(kind, key, ["", " ", "0", "-1", "1", "1ns", "PT0S", "PT-0.5S", "2m", "P8D", "1.5s", "bad",
                            "9223372036854775807ns", "9223372036854775808ns", "PT9223372036854775807S",
                            "PT-9223372036854775808S", "PT-9223372036854775808.000000001S"])
    for kind, key in [("knowledge", "similarity-threshold"), ("knowledge", "keyword-channel-weight"), ("memory", "similar-threshold")]:
        add(kind, key, ["", " ", "0", "-0.0", "-1", "1", "1.1", "0.5", " 0.5 ", "0. 5", "0.\u30005", "０.５",
                        "NaN", "+NaN", "-NaN", "nan", "Infinity", "+Infinity", "-Infinity", "Inf", "1e400", "1e-400",
                        "0.5f", "0.5D", "0x1.0p-1", "0X1P-1f", "0x1", "1_0", ".5", "1.", "1e", "bad"])
    for kind, key in [("ai", "system-prompt"), ("app", "name"), ("app", "version")]:
        add(kind, key, ["", " ", "\t\u001c\u3000", "  合成提示😀  ", "\u00a0fixture\u00a0", "a\u0000b", "${literal}"])
    add("knowledge", "allowed-extensions", ["", " ", "md,txt", " md , txt ", ",", ",md,,txt,", "MD,md,😀", "'md,txt'", "md\\,txt", "md\u3000,txt"])
    for values in [
        {"app.knowledge.allowed-extensions[0]": "MD", "app.knowledge.allowed-extensions[1]": " txt "},
        {"app.knowledge.allowed-extensions[1]": "gap"},
        {"app.knowledge.allowed-extensions[0]": "md", "app.knowledge.allowed-extensions[2]": "gap"},
        {"app.knowledge.allowed-extensions": "txt", "app.knowledge.allowed-extensions[0]": "md"},
        {"app.knowledge.allowed-extensions[00]": "md"},
        {"app.knowledge.allowed-extensions[-1]": "md"},
    ]:
        result.append({"kind": "knowledge", "input": values})
    for kind, keys in {"ai": ["memory.unknown"], "context": ["summary.unknown", "auto-memory.unknown"],
                       "memory": ["ranking.unknown", "backfill.unknown"]}.items():
        for key in keys:
            add(kind, key, ["ignored"])
    return result


def mcp_cases():
    result = []
    def canonical(values):
        result.append({"kind": "mcp", "input": values})
    def sources(*values):
        result.append({"kind": "mcp", "input": {}, "sources": list(values)})
    def env(values):
        return {"environment": True, "values": values}
    def props(values):
        return {"environment": False, "values": values}
    for key in ["enabled", "servers.fetch.command", "servers.fetch.args", "servers.fetch.auto-tools", "servers.fetch.env.API_KEY", "servers.fetch.connect-timeout"]:
        for value in ["", " ", "fixture-only", "true", "-1", "30s"]:
            canonical({"mcp." + key: value})
    for value in ["a,b", "a, b ,a", ",", ",a,,b,", "'a,b'", " a ", "a\u3000,b\t", "x\\,y", "a\nb", "😀,中文"]:
        for key in ["args", "auto-tools"]:
            canonical({"mcp.servers.fetch." + key: value})
    for values in [
        {"mcp.enabled": "true"},
        {"mcp.server.fetch.command": "singular-unused"},
        {"mcp.servers.fetch.unknown": "unbound"},
        {"mcp.servers.fetch.env.API_KEY": "fixture-private", "mcp.servers.fetch.env.HTTP_PROXY": "fixture-proxy"},
        {"mcp.servers.fetch.env.[API_KEY]": "fixture-private", "mcp.servers.fetch.env.[a.b]": "dotted"},
        {"mcp.servers.[case.Name/😀].command": "fixture-only"},
        {"mcp.servers.CaseName.command": "fixture-only"},
        {"mcp.servers.fetch.args[0]": "first", "mcp.servers.fetch.args[1]": "second"},
        {"mcp.servers.fetch.args[1]": "gap"},
        {"mcp.servers.fetch.args[0]": "first", "mcp.servers.fetch.args[2]": "gap"},
        {"mcp.servers.fetch.args": "scalar", "mcp.servers.fetch.args[0]": "indexed"},
        {"mcp.servers.fetch.auto-tools[0]": "read_file", "mcp.servers.fetch.auto-tools[1]": "read_file"},
        {"mcp.servers.fetch.connect-timeout": "0"},
        {"mcp.servers.fetch.connect-timeout": "-1ns"},
        {"mcp.servers.fetch.connect-timeout": "9223372036855ms"},
        {"mcp.servers.fetch.connect-timeout": "PT-9223372036854775808S"},
        {"mcp.servers.fetch.connect-timeout": "-9223372036854775808d"},
        {"mcp.servers.fetch.connect-timeout": "PT9223372036854775808S"},
        {"mcp.servers.fetch.connect-timeout": "PT-9223372036854775808.000000001S"},
        {"mcp.servers.fetch.connect-timeout": "-PT9223372036854775807.999999999S"},
        {"mcp.servers.fetch.connect-timeout": "-PT-9223372036854775808S"},
        {"mcp.servers.fetch.command": "fixture-only", "mcp.servers.other.args": "one,two"},
        {"mcp.servers.fetch.env.a.b": "dotted", "mcp.servers.fetch.env./key": "slash", "mcp.servers.fetch.env.[/key]": "bracket"},
        {"mcp.servers.fetch.connect-timeout": "", "mcp.servers.fetch.command": "fixture-only"},
        {"mcp.servers.fetch.auto-tools": "\ue000,😀"},
    ]: canonical(values)
    sources(env({"MCP_ENABLED": "true", "MCP_SERVERS_FETCH_COMMAND": "uvx",
                 "MCP_SERVERS_FETCH_ARGS": "mcp-server-fetch", "MCP_SERVERS_FETCH_AUTO_TOOLS": "fetch",
                 "MCP_SERVERS_FETCH_CONNECT_TIMEOUT": "30s"}))
    for values in [
        {"MCP_SERVER_FETCH_COMMAND": "singular-unused"},
        {"MCP_SERVERS_FETCH_ENV_API_KEY": "fixture-private"},
        {"MCP_SERVERS_FETCH_ARGS_0_": "first", "MCP_SERVERS_FETCH_ARGS_1_": "second"},
        {"MCP_SERVERS_MY_FETCH_COMMAND": "fixture-only"},
        {"MCP_SERVERS_FETCH_AUTOTOOLS": "a,b", "MCP_SERVERS_FETCH_CONNECTTIMEOUT": "2s"},
        {"mcp.servers.fetch.command": "lowercase-dotted"},
        {"MCP_SERVERS_FETCH_COMMAND": "upper", "mcp.servers.fetch.command": "lower"},
    ]: sources(env(values))
    sources(props({"mcp.servers.fetch.command": "high"}), props({"mcp.servers.fetch.command": "low", "mcp.servers.fetch.args": "low-a,low-b", "mcp.servers.other.command": "other"}))
    sources(env({"MCP_SERVERS_FETCH_COMMAND": "high", "MCP_SERVERS_FETCH_ARGS": ""}), env({"MCP_SERVERS_FETCH_COMMAND": "low", "MCP_SERVERS_FETCH_ARGS": "low-a,low-b"}))
    sources(props({"mcp.servers.fetch.args[0]": "high"}), props({"mcp.servers.fetch.args[0]": "low-a", "mcp.servers.fetch.args[1]": "low-b"}))
    sources(props({"mcp.servers.fetch.env.[API_KEY]": "high"}), props({"mcp.servers.fetch.env.[API_KEY]": "low", "mcp.servers.fetch.env.[OTHER]": "other"}))
    sources(props({"mcp.servers.fetch.auto-tools": "high"}), props({"mcp.servers.fetch.auto-tools": "low-a,low-b"}))
    sources(props({"mcp.servers.fetch.connect-timeout": ""}), props({"mcp.servers.fetch.connect-timeout": "30s"}))
    sources(props({"mcp.servers.fetch.args[1]": "high-gap"}), props({"mcp.servers.fetch.args[0]": "low-first"}))
    for key in ["mcp", "mcp.servers", "mcp.servers.fetch", "mcp.servers.fetch.env",
                "mcp.servers.fetch.args[00]", "mcp.servers.fetch.args[-1]", "mcp.servers.fetch.args[2147483648]",
                "mcp.servers.fetch.args[0].extra", "mcp.servers.fetch.env.a-b", "mcp.servers.fetch.env.a_b",
                "mcp.servers.fetch.env.ékey", "mcp.servers.case-name.command", "mcp.servers.case_name.command",
                "mcp.servers[fetch].command", "mcp.servers.fetch.autoTools", "mcp.servers.fetch.connectTimeout",
                "mcp.servers.fetch.auto.tools", "mcp.servers.fetch.connect.timeout"]:
        for value in ["", "fixture-only"]:
            canonical({key: value})
    for key in ["MCP_SERVERS_FETCH_ENV_HTTP_PROXY", "MCP_SERVERS_FETCH_ENV_APIKEY", "MCP_SERVERS_FETCH_ARGS_0",
                "mcp_servers_fetch_command", "MCP_SERVERS_CaseName_COMMAND", "MCP_SERVERS_CASE-NAME_COMMAND"]:
        sources(env({key: "fixture-only"}))
    sources(props({"mcp.servers.fetch.args[0]": "high"}), props({"mcp.servers.fetch.args": "low-a,low-b"}))
    sources(props({"mcp.servers.fetch.command": "high"}), props({"mcp.servers.fetch.connect-timeout": "bad-low"}))
    sources(props({"mcp.servers.fetch.connect-timeout": "20s"}), props({"mcp.servers.fetch.connect-timeout": "bad-low"}))
    sources(env({"MCP_SERVERS_FETCH_COMMAND": "env-high"}), props({"mcp.servers.fetch.command": "map-low"}))
    sources(props({"mcp.servers.fetch.command": "map-high"}), env({"MCP_SERVERS_FETCH_COMMAND": "env-low"}))
    for key in ["mcp", "mcp.servers", "mcp.servers.fetch", "mcp.servers.fetch.env"]:
        for value in ["", "fixture-only"]:
            canonical({key: value, "mcp.enabled": "true", "mcp.servers.fetch.command": "fixture-command", "mcp.servers.fetch.env.[KEY]": "fixture-env"})
    sources(props({"mcp.servers.fetch.args": "high"}), props({"mcp.servers.fetch.args[-1]": "bad-low"}))
    sources(props({"mcp.servers.fetch.args": "high"}), props({"mcp.servers.fetch.args[0].extra": "bad-low"}))
    sources(props({"mcp.servers.fetch.env": ""}), props({"mcp.servers.fetch.env.[KEY]": "low"}))
    sources(props({"mcp.servers.fetch": ""}), props({"mcp.servers.fetch.command": "low"}))
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
            for source in row.get("sources", [{"environment": False, "values": row["input"]}]):
                fields.append("ENV" if source["environment"] else "MAP")
                for key, value in sorted(source["values"].items()):
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
        if row["kind"] == "mcp" and row["value"]:
            # Set没有顺序契约，只为跨语言快照使用相同Unicode标量顺序。
            for server in row["value"]["servers"].values():
                server["autoTools"].sort()
        def duration_overflow(value):
            if not isinstance(value, dict):
                return False
            duration_keys = {"lockout", "period", "connectTimeout", "timeout", "scanLookahead", "scanLookback", "defaultReminderLead"}
            return any((key in duration_keys and child is not None and not -(2**63) <= int(child) <= 2**63 - 1)
                       or duration_overflow(child) for key, child in value.items())
        row["go_overflow"] = duration_overflow(row["value"])
    return {"baseline_commit": baseline, "spring_boot": boot, "spring_framework": spring,
            "jars_sha256": {path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in jars},
            "sources_sha256": hashes,
            "scope": "Binder.bindOrCreate; resolved String map/explicit synthetic SystemEnvironment sources; no host environment/placeholders/YAML/application assembly",
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
