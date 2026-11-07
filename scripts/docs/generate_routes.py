#!/usr/bin/env python3
"""Generate a source-linked inventory of explicit Spring Controller routes."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[2]
SOURCE = ROOT / "backend/src/main/java"
OUTPUT = ROOT / "docs/reference/http-routes.md"
MAPPING = re.compile(r"@(Request|Get|Post|Put|Patch|Delete|Head|Options)Mapping\b")
LEXEMES = re.compile(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\n]*|/\*[\s\S]*?\*/')


def strip_comments(source: str) -> str:
    """Keep string literals and source line numbers, discard Java comments."""
    return LEXEMES.sub(
        lambda m: m[0] if not m[0].startswith("/") else re.sub(r"[^\n]", " ", m[0]),
        source,
    )


def annotation_arguments(source: str, end: int) -> str:
    cursor = end
    while cursor < len(source) and source[cursor].isspace():
        cursor += 1
    if cursor == len(source) or source[cursor] != "(":
        return ""
    start, depth, quoted, escaped = cursor + 1, 1, False, False
    for cursor in range(start, len(source)):
        char = source[cursor]
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char == "(":
            depth += 1
        elif char == ")":
            depth -= 1
            if depth == 0:
                return source[start:cursor]
    raise ValueError("Unclosed mapping annotation")


def split_arguments(arguments: str) -> list[str]:
    """Split annotation/array arguments without splitting quoted commas."""
    parts, stack, start, quoted, escaped = [], [], 0, False, False
    for cursor, char in enumerate(arguments):
        if quoted:
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
        elif char in "{([":
            stack.append(char)
        elif char in "})]":
            if not stack or stack.pop() != {"}": "{", ")": "(", "]": "["}[char]:
                raise ValueError(f"Unbalanced mapping expression: {arguments}")
        elif char == "," and not stack:
            part = arguments[start:cursor].strip()
            if not part:
                raise ValueError(f"Empty mapping argument: {arguments}")
            parts.append(part)
            start = cursor + 1
    if quoted or stack:
        raise ValueError(f"Unclosed mapping expression: {arguments}")
    if arguments[start:].strip():
        parts.append(arguments[start:].strip())
    return parts


def paths(arguments: str) -> list[str]:
    value = None
    for argument in split_arguments(arguments):
        named = re.fullmatch(r"([A-Za-z_]\w*)\s*=\s*(.+)", argument, re.S)
        if named and named[1] not in ("path", "value"):
            if named[1] not in ("name", "consumes", "produces", "method", "headers", "params"):
                raise ValueError(f"Unsupported mapping attribute: {argument}")
            continue
        if value is not None:
            raise ValueError(f"Multiple mapping path expressions: {arguments}")
        value = named[2].strip() if named else argument
    if value is None:
        return [""]
    values = split_arguments(value[1:-1]) if value.startswith("{") and value.endswith("}") else [value]
    if not all(re.fullmatch(r'"(?:\\.|[^"\\])*"', item) for item in values):
        raise ValueError(f"Unsupported mapping path expression: {value}")
    return [json.loads(item) for item in values] or [""]


def collect_routes() -> list[tuple[str, str, str, int]]:
    routes = []
    for file in sorted(SOURCE.rglob("*Controller.java")):
        source = strip_comments(file.read_text())
        if "@RestController" not in source and "@Controller" not in source:
            continue
        declaration = re.search(r"\bclass\s+\w+", source)
        if not declaration:
            raise ValueError(f"Controller class missing: {file}")
        base = [""]
        annotations = list(MAPPING.finditer(source))
        for annotation in annotations:
            if annotation.start() < declaration.start():
                if annotation[1] != "Request":
                    raise ValueError(f"Unexpected class mapping: {file}")
                base = paths(annotation_arguments(source, annotation.end()))
        for annotation in annotations:
            if annotation.start() < declaration.start():
                continue
            arguments = annotation_arguments(source, annotation.end())
            methods = [annotation[1].upper()]
            if annotation[1] == "Request":
                methods = re.findall(r"RequestMethod\.(\w+)", arguments) or ["ANY"]
            line = source.count("\n", 0, annotation.start()) + 1
            for prefix in base:
                for suffix in paths(arguments):
                    if prefix and suffix:
                        path = prefix.rstrip("/") + "/" + suffix.lstrip("/")
                    else:
                        path = prefix or suffix
                    if path and not path.startswith(("/", "${")):
                        path = "/" + path
                    for method in methods:
                        routes.append((path or "/", method, file.relative_to(ROOT).as_posix(), line))
    return sorted(routes)


def render() -> str:
    routes = collect_routes()
    lines = [
        "# Java Controller HTTP 路由参考", "",
        "> 由 `python3 scripts/docs/generate_routes.py` 从当前 Java Controller 注解生成。不要手工维护；用 `--check` 检查漂移。", "",
        "本清单仅含显式 Controller 路由，不含 Spring Security、Actuator、MCP 框架动态端点或隐式 HEAD/OPTIONS。`ANY` 表示注解未限制方法；配置占位符保留原文。它不是 OpenAPI，也不是 Go 兼容性认证。", "",
        f"共 **{len(routes)}** 项方法/路径映射。认证、SSE、幂等、文件响应与错误语义见 [API 指南](../api.md)；Go 模块装配与差异见 [双版本说明](../backend-parity.md)。", "",
        "| 方法 | 完整路径 | 源码 |", "| --- | --- | --- |",
    ]
    for path, method, file, line in routes:
        label = Path(file).name
        lines.append(f"| `{method}` | `{path}` | [{label}:{line}](../../{file}#L{line}) |")
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail on drift without writing")
    args = parser.parse_args()
    expected = render()
    if args.check:
        if not OUTPUT.exists() or OUTPUT.read_text() != expected:
            print("Route inventory is stale; run python3 scripts/docs/generate_routes.py", file=sys.stderr)
            return 1
        print(f"OK: {len(collect_routes())} Controller route mappings match source")
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(expected)
        print(f"Wrote {OUTPUT.relative_to(ROOT)} ({len(collect_routes())} mappings)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
