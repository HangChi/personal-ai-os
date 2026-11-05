#!/usr/bin/env python3
"""固定 Java 迁移基线；生成清单、校验源文件漂移并阻止提前宣布迁移完成。

仅使用 Python 标准库。Java 符号和注解是词法索引，不替代编译器或行为测试。
所有受 Git 管理的基线文件均纳入 SHA-256 清单，避免只盘点 Controller。
"""

import argparse
import hashlib
import itertools
import json
import re
import subprocess
import sys
from collections import Counter
from pathlib import Path

import go_scope_changes

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "specs/033-go-rewrite"
MANIFEST = SPEC / "inventory.json"
PROGRESS = SPEC / "progress.json"
EXCLUDED = ("backend-go/", "specs/033-go-rewrite/", "scripts/migration/", ".github/workflows/go.yml")
HOOKS = {"Scheduled", "EventListener", "TransactionalEventListener", "PostConstruct", "PreDestroy",
         "RabbitListener", "Bean", "ConfigurationProperties", "ConditionalOnProperty", "ConditionalOnExpression"}
MAPPINGS = {"GetMapping": "GET", "PostMapping": "POST", "PutMapping": "PUT",
            "PatchMapping": "PATCH", "DeleteMapping": "DELETE", "RequestMapping": "ANY"}


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def masked_java(text, strings=True):
    """用等长空白遮蔽注释/字面量，保留坐标与换行，避免注释中的注解被计数。"""
    pattern = re.compile(r'//[^\n]*|/\*[\s\S]*?\*/|"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'')
    def replace(match):
        value = match.group()
        if not strings and not value.startswith(("//", "/*")):
            return value
        return "".join("\n" if char == "\n" else " " for char in value)
    return pattern.sub(replace, text)


def closing(mask, start, left="(", right=")"):
    depth = 0
    for index in range(start, len(mask)):
        if mask[index] == left:
            depth += 1
        elif mask[index] == right:
            depth -= 1
            if depth == 0:
                return index + 1
    raise ValueError("Unbalanced Java declaration at offset %d" % start)


def annotations(text):
    mask = masked_java(text)
    result = []
    for match in re.finditer(r"@([A-Za-z_$][\w.$]*)", mask):
        end = match.end()
        while end < len(mask) and mask[end].isspace():
            end += 1
        if end < len(mask) and mask[end] == "(":
            end = closing(mask, end)
        result.append({"name": match[1].split(".")[-1], "start": match.start(), "end": end,
                       "line": text.count("\n", 0, match.start()) + 1,
                       "text": text[match.start():end].strip()})
    return result


def declarations(text):
    mask = masked_java(text)
    result = []
    for match in re.finditer(r"\b(class|interface|record|enum)\s+([A-Za-z_$]\w*)", mask):
        end = match.end()
        opening = end
        while opening < len(mask) and mask[opening].isspace():
            opening += 1
        if match[1] == "record" and opening < len(mask) and mask[opening] == "(":
            end = closing(mask, opening)
        result.append({"kind": match[1], "name": match[2],
                       "line": text.count("\n", 0, match.start()) + 1,
                       "signature": " ".join(text[match.start():end].split())})
    return result


def signature_after(text, offset):
    mask = masked_java(text)
    # 该基线的 Controller / Tool / Test 使用普通方法声明；找不到就停止生成，禁止静默遗漏。
    pattern = re.compile(r"\b(?:public|protected|private|void)\b[\w\s.$<>?,\[\]@]*?\b([A-Za-z_$]\w*)\s*\(")
    match = pattern.search(mask, offset)
    if not match:
        return {"name": "<review-required>", "line": text.count("\n", 0, offset) + 1, "signature": ""}
    opening = mask.index("(", match.start())
    end = closing(mask, opening)
    return {"name": match[1], "line": text.count("\n", 0, match.start()) + 1,
            "signature": " ".join(text[match.start():end].split())}


def mapping_paths(annotation):
    value = annotation["text"]
    if "(" not in value:
        return [""]
    arguments = value[value.index("(") + 1:value.rfind(")")].strip()
    match = re.search(r'\b(?:value|path)\s*=\s*(\{[^}]*\}|"(?:\\.|[^"\\])*")', arguments)
    if match:
        expression = match[1]
    elif arguments.startswith(('"', '{')):
        expression = arguments.split("}", 1)[0] + "}" if arguments.startswith("{") else arguments
        if not arguments.startswith("{"):
            literal = re.match(r'"(?:\\.|[^"\\])*"', arguments)
            expression = literal.group() if literal else ""
    else:
        return [""]
    values = re.findall(r'"((?:\\.|[^"\\])*)"', expression)
    if not values:
        raise ValueError("Cannot resolve mapping: " + value)
    return values


def target_for(path):
    if path.startswith("backend/src/main/resources/db/migration/"):
        return "P1", "保留原始 SQL；迁移执行器与 Flyway 历史兼容性待验证"
    if path.startswith("backend/src/main/java/") or path.startswith("backend/src/test/java/"):
        relative = path.split("com/personalos/", 1)[-1]
        module = relative.split("/", 1)[0]
        if module == "common":
            sub = relative.split("/")[1]
            if sub == "ai":
                if "/config/" in relative:
                    return "P3", "backend-go/internal/model/"
                if "/cache/" in relative:
                    return "P3", "backend-go/internal/platform/cache/"
                if any(word in relative for word in ["Context", "Token", "Window", "Prompt"]):
                    return "P4", "backend-go/internal/context/"
                return "P4", "backend-go/internal/agent/"
            if sub in {"confirmation", "idempotency"}:
                return "P2", "backend-go/internal/platform/%s/" % sub
            if sub == "search":
                return "P7", "backend-go/internal/search/"
            if sub == "maintenance":
                return "P8", "backend-go/internal/platform/maintenance/"
            return "P1", "backend-go/internal/platform/%s/" % sub
        mapping = {"task": ("P2", "task"), "memory": ("P5", "memory"),
                   "chat": ("P4", "chat"), "agent": ("P4", "agent"),
                   "knowledge": ("P6", "knowledge"), "calendar": ("P5", "calendar"),
                   "mcp": ("P7", "mcp"), "export": ("P7", "transfer"),
                   "list": ("P7", "search"), "support": ("P1", "testsupport")}
        phase, package = mapping.get(module, ("P1", "app"))
        return phase, "backend-go/internal/%s/" % package
    if path.startswith("frontend/"):
        return "P8", "复用现有前端；用 Go 后端执行契约与端到端验收"
    if path.startswith("backend/"):
        return "P1", "Go 配置/构建/测试等效实现；保留 Java 对照"
    if path.startswith(("docs/", "specs/", ".specify/")):
        return "P0-P8", "保留业务规格；按 Go 实现核对并追加差异"
    return "P8", "复用或适配部署、运维、评估及仓库约定；验证后确认"


def category(path):
    if path.startswith("backend/src/main/java/"): return "java_source"
    if path.startswith("backend/src/test/java/"): return "java_test"
    if path.startswith("backend/src/main/resources/db/migration/"): return "sql_migration"
    if path.startswith("backend/src/main/resources/"): return "java_resource"
    if path.startswith("backend/src/test/resources/"): return "java_test_resource"
    if path.startswith("backend/"): return "java_build"
    if path.startswith("frontend/src/"): return "frontend_source"
    if path.startswith("frontend/test/"): return "frontend_test"
    if path.startswith("frontend/"): return "frontend_build"
    if path.startswith("specs/"): return "specification"
    if path.startswith("docs/") or path.endswith(".md"): return "documentation"
    return "operations_and_repository"


def collect(commit):
    paths = git("ls-tree", "-r", "--name-only", commit).decode().splitlines()
    paths = [path for path in paths if not path.startswith(EXCLUDED)]
    # 从固定 Git 对象读入，而不是碰 .env、日志、备份或本机运行数据。
    process = subprocess.run(["git", "cat-file", "--batch"], cwd=ROOT,
                             input="".join("%s:%s\n" % (commit, path) for path in paths).encode(),
                             stdout=subprocess.PIPE, check=True)
    stream = process.stdout
    cursor = 0
    entries, routes, tools, hooks, tests, environment = [], [], [], [], [], []
    for path in paths:
        boundary = stream.index(b"\n", cursor)
        header = stream[cursor:boundary].decode().split()
        if len(header) != 3 or header[1] != "blob":
            raise ValueError("Expected tracked blob: " + path)
        length = int(header[2])
        data = stream[boundary + 1:boundary + 1 + length]
        cursor = boundary + length + 2
        text = data.decode("utf-8", errors="replace")
        phase, target = target_for(path)
        entry = {"id": "file:" + path, "path": path, "sha256": digest(data),
                 "category": category(path), "lines": data.count(b"\n"),
                 "phase": phase, "go_target": target}
        if path.endswith(".java"):
            anns = annotations(text)
            entry["declarations"] = declarations(text)
            # 记录所有注解，不只选定的调度/工具注解；验证、事务和条件装配均可追溯。
            entry["annotations"] = [{key: value for key, value in ann.items() if key not in {"start", "end"}} for ann in anns]
            code = masked_java(text)
            first_type = re.search(r"\b(?:class|interface|record|enum)\s+\w+", code)
            type_offset = first_type.start() if first_type else len(code)
            class_mappings = [ann for ann in anns if ann["name"] == "RequestMapping" and ann["start"] < type_offset]
            prefixes = mapping_paths(class_mappings[0]) if class_mappings else [""]
            for ann in anns:
                common = {"path": path, "line": ann["line"], "annotation": ann["text"]}
                if entry["category"] == "java_source" and ann["name"] in MAPPINGS and ann["start"] > type_offset:
                    methods = re.findall(r"RequestMethod\.(GET|POST|PUT|PATCH|DELETE|OPTIONS|HEAD|TRACE)", ann["text"])
                    methods = methods or [MAPPINGS[ann["name"]]]
                    signature = signature_after(text, ann["end"])
                    if signature["name"] == "<review-required>":
                        raise ValueError("Unresolved endpoint: %s:%s" % (path, ann["line"]))
                    for prefix, suffix, method in itertools.product(prefixes, mapping_paths(ann), methods):
                        route = prefix.rstrip("/") + ("/" + suffix.lstrip("/") if suffix else "")
                        route = route or "/"
                        routes.append(dict(common, id="route:%s:%s:%s" % (method, route, path),
                                           method=method, route=route, handler=signature))
                if entry["category"] == "java_source" and ann["name"] in {"Tool", "McpTool"}:
                    name = re.search(r'\bname\s*=\s*"([^"]+)"', ann["text"])
                    if not name: raise ValueError("Unresolved tool name: " + path)
                    tools.append(dict(common, id="tool:%s:%s" % (ann["name"], name[1]),
                                      kind=ann["name"], name=name[1], handler=signature_after(text, ann["end"])))
                if entry["category"] == "java_source" and ann["name"] in HOOKS:
                    hooks.append(dict(common, id="hook:%s:%d:%s" % (path, ann["line"], ann["name"]), kind=ann["name"]))
                if ann["name"] in {"Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate"}:
                    tests.append(dict(common, id="test:%s:%d" % (path, ann["line"]),
                                      handler=signature_after(text, ann["end"])))
        if path.endswith((".java", ".yml", ".yaml", ".properties")) or path.endswith(".example"):
            env_text = masked_java(text, strings=False) if path.endswith(".java") else text
            for match in re.finditer(r"\$\{([A-Z][A-Z0-9_]*)(?::([^}\n]*))?\}", env_text):
                environment.append({"path": path, "line": env_text.count("\n", 0, match.start()) + 1,
                                    "name": match[1], "default": match[2]})
        entries.append(entry)
    return {"schema_version": 1, "baseline_commit": commit, "baseline_date": "2026-11-03",
            "exclusions": list(EXCLUDED), "files": entries,
            "http_routes": routes, "tools": tools, "runtime_hooks": hooks,
            "java_test_cases": tests, "environment_references": environment,
            "limitations": ["词法索引不是 Java AST；文件哈希是完整性基准。",
                            "注解路由不含 Spring 自动装配的 MCP/Actuator/框架错误路径，另见 contracts.md。",
                            "Test 注解数不等于参数化测试实际执行数。",
                            "清单完整不代表行为等价；verified 需要契约/集成/故障恢复证据。"]}


def markdown(manifest):
    counts = Counter(entry["category"] for entry in manifest["files"])
    lines = ["# Java → Go 迁移基线清单", "", "本文件由 `scripts/migration/go_inventory.py generate` 生成，请勿手改。", "",
             "- 基线提交：`%s`" % manifest["baseline_commit"],
             "- 跟踪文件：%d；注解 HTTP 路由：%d；工具：%d；测试声明：%d。" % (
                 len(manifest["files"]), len(manifest["http_routes"]), len(manifest["tools"]), len(manifest["java_test_cases"])),
             "- 每项默认 `not_started`；状态与证据单独写入 `progress.json`。", "",
             "类别 | 文件数", "--- | ---"]
    lines += ["%s | %d" % item for item in sorted(counts.items())]
    for cat in sorted(counts):
        lines += ["", "## " + cat, "", "基线文件 | SHA-256（前 12 位） | 阶段 | Go 去向 / 复用要求", "--- | --- | --- | ---"]
        for entry in manifest["files"]:
            if entry["category"] == cat:
                lines.append("[%s](../../%s) | `%s` | %s | %s" % (
                    entry["path"], entry["path"], entry["sha256"][:12], entry["phase"], entry["go_target"]))
    lines += ["", "## 提取边界", ""] + ["- " + item for item in manifest["limitations"]]
    return "\n".join(lines) + "\n"


def surface_markdown(manifest):
    lines = ["# 自动提取的接口、工具与运行钩子", "", "由清单脚本生成；参数注解与处理器签名保存在 `inventory.json`。", "",
             "## HTTP 路由", "", "方法 | 路径 | Java 处理器", "--- | --- | ---"]
    for entry in manifest["http_routes"]:
        lines.append("%s | `%s` | [%s.%s](../../%s#L%d)" % (
            entry["method"], entry["route"], Path(entry["path"]).stem,
            entry["handler"]["name"], entry["path"], entry["line"]))
    lines += ["", "## 工具", "", "类型 | 名称 | 来源", "--- | --- | ---"]
    for entry in manifest["tools"]:
        lines.append("%s | `%s` | [%s](../../%s#L%d)" % (
            entry["kind"], entry["name"], Path(entry["path"]).stem, entry["path"], entry["line"]))
    lines += ["", "## 运行钩子、装配条件及类型化配置", "", "类型 | 来源 | 声明", "--- | --- | ---"]
    for entry in manifest["runtime_hooks"]:
        declaration = " ".join(entry["annotation"].split()).replace("|", "&#124;")
        lines.append("%s | [%s:%d](../../%s#L%d) | `%s`" % (
            entry["kind"], Path(entry["path"]).stem, entry["line"], entry["path"], entry["line"], declaration))
    lines += ["", "## 环境变量引用", "", "变量 | 引用次数", "--- | ---"]
    lines += ["`%s` | %d" % item for item in sorted(Counter(x["name"] for x in manifest["environment_references"]).items())]
    lines += ["", "配置属性的完整默认值与 profile 以清单中的 application*.yml、@Value、@ConfigurationProperties 和 .env.example 为准。",
              "Spring relaxed binding、动态 MCP 配置和程序读取环境变量不一定使用 ${...}，不能只迁移本表。", ""]
    return "\n".join(lines)


def as_json(value):
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def coverage_ids(manifest):
    return {item["id"] for group in ("files", "http_routes", "tools", "runtime_hooks", "java_test_cases") for item in manifest[group]}


def check(manifest, complete=False):
    errors = []
    reproduced = collect(manifest["baseline_commit"])
    if reproduced != manifest:
        errors.append("清单与固定 Git 基线的提取结果不一致，请审查提取器/基线变更。")
    try:
        scope, amended = go_scope_changes.load(manifest, ROOT, lambda revision, path: git("show", revision + ":" + path))
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        errors.append("增量范围校验失败：" + str(error))
        scope, amended = {"changes": []}, {}
    for entry in manifest["files"]:
        path = ROOT / entry["path"]
        expected = amended.get(entry["path"], {}).get("candidate_sha256", entry["sha256"])
        if not path.is_file() or digest(path.read_bytes()) != expected:
            errors.append("基线文件发生漂移：" + entry["path"])
    known = {entry["path"] for entry in manifest["files"]}
    live = git("ls-files", "--cached", "--others", "--exclude-standard").decode().splitlines()
    for path in live:
        if path not in known and path not in amended and not path.startswith(EXCLUDED):
            errors.append("新增文件尚未纳入迁移基线：" + path)
    for name, expected in [("inventory.md", markdown(manifest)), ("surface.md", surface_markdown(manifest))]:
        path = SPEC / name
        if not path.exists() or path.read_text() != expected:
            errors.append("派生文档不一致：" + name)
    progress = json.loads(PROGRESS.read_text())
    if progress.get("baseline_commit") != manifest["baseline_commit"]:
        errors.append("进度文件的基线提交不一致。")
    valid = coverage_ids(manifest)
    statuses = {"not_started", "in_progress", "implemented", "verified"}
    for key, item in progress.get("items", {}).items():
        if key not in valid:
            errors.append("进度项不存在于基线：" + key)
        if item.get("status") not in statuses:
            errors.append("无效的迁移状态：" + key)
        if item.get("status") in {"implemented", "verified"} and not item.get("evidence"):
            errors.append("已实现/已验证项目缺少证据：" + key)
        for evidence in item.get("evidence", []):
            evidence_path = Path(evidence)
            if evidence_path.is_absolute() or ".." in evidence_path.parts or not (ROOT / evidence_path).is_file():
                errors.append("证据必须是仓库内的现有文件：" + evidence)
    if complete:
        for change in scope["changes"]:
            if change["status"] != "verified":
                errors.append("增量范围尚未验证：" + change["id"])
        remaining = sorted(key for key in valid if progress.get("items", {}).get(key, {}).get("status") != "verified")
        if remaining:
            errors.append("迁移未完成：%d/%d 项尚未 verified。前 10 项：\n%s" % (len(remaining), len(valid), "\n".join(remaining[:10])))
        for gate in ("frontend_e2e", "java_go_differential", "database_upgrade_restore", "failure_recovery", "operations_cutover"):
            if progress.get("release_gates", {}).get(gate, {}).get("status") != "verified":
                errors.append("发布门禁尚未验证：" + gate)
            elif not progress["release_gates"][gate].get("evidence"):
                errors.append("发布门禁缺少证据：" + gate)
            else:
                for evidence in progress["release_gates"][gate]["evidence"]:
                    evidence_path = Path(evidence)
                    if evidence_path.is_absolute() or ".." in evidence_path.parts or not (ROOT / evidence_path).is_file():
                        errors.append("发布门禁证据不存在：" + evidence)
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print("基线检查通过：%d 文件、%d 路由、%d 工具、%d 运行钩子、%d 测试声明。" % (
        len(manifest["files"]), len(manifest["http_routes"]), len(manifest["tools"]),
        len(manifest["runtime_hooks"]), len(manifest["java_test_cases"])))
    if scope["changes"]:
        print("独立增量范围：%d 组、%d 候选文件；%d 组尚未 verified，不代表迁移完成。" % (
            len(scope["changes"]), len(amended), sum(change["status"] != "verified" for change in scope["changes"])))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["generate", "check", "check-complete"])
    parser.add_argument("--baseline", help="仅 generate：固定的 Java 基线提交，禁止隐式使用移动的 HEAD")
    args = parser.parse_args()
    if args.action == "generate":
        if not args.baseline:
            parser.error("generate 必须显式指定 --baseline")
        commit = git("rev-parse", "--verify", args.baseline + "^{commit}").decode().strip()
        if PROGRESS.exists() and json.loads(PROGRESS.read_text()).get("baseline_commit") != commit:
            parser.error("已有进度对应其他基线；请先显式审查并迁移进度，禁止覆盖")
        manifest = collect(commit)
        SPEC.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(as_json(manifest))
        (SPEC / "inventory.md").write_text(markdown(manifest))
        (SPEC / "surface.md").write_text(surface_markdown(manifest))
        if not PROGRESS.exists():
            PROGRESS.write_text(as_json({"schema_version": 1, "baseline_commit": commit, "items": {},
                                        "release_gates": {key: {"status": "not_started", "evidence": []} for key in (
                                            "frontend_e2e", "java_go_differential", "database_upgrade_restore", "failure_recovery", "operations_cutover")}}))
        print("已生成固定基线：%s，%d 个文件。" % (commit, len(manifest["files"])))
        return 0
    if not MANIFEST.exists():
        parser.error("请先 generate 固定基线")
    return check(json.loads(MANIFEST.read_text()), complete=args.action == "check-complete")


if __name__ == "__main__":
    sys.exit(main())
