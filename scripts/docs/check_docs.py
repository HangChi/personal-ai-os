#!/usr/bin/env python3
"""Check maintained documentation locally; never connect to services or models."""

from __future__ import annotations

import hashlib
import html
import json
from pathlib import Path
import re
import sys
import unicodedata
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[2]


def prose(text: str) -> str:
    """Remove fenced examples while retaining line numbers for diagnostics."""
    lines, fence = [], None
    for line in text.splitlines():
        marker = re.match(r"^\s*(`{3,}|~{3,})", line)
        if marker:
            run = marker[1]
            if fence is None:
                fence = (run[0], len(run))
            elif run[0] == fence[0] and len(run) >= fence[1]:
                fence = None
            lines.append("")
        else:
            lines.append("" if fence else line)
    return "\n".join(lines)


def anchors(file: Path) -> set[str]:
    found, counts = set(), {}
    for line in prose(file.read_text()).splitlines():
        heading = re.match(r"^ {0,3}#{1,6}\s+(.+?)\s*#*\s*$", line)
        if not heading:
            continue
        label = html.unescape(re.sub(r"<[^>]*>", "", heading[1]))
        label = re.sub(r"!?\[([^]]*)\]\([^)]*\)", r"\1", label).lower()
        slug = "".join(c for c in label if c in "_- " or c.isspace() or unicodedata.category(c)[0] in "LNM")
        slug = re.sub(r"\s", "-", slug)
        count = counts.get(slug, 0)
        counts[slug] = count + 1
        found.add(slug if count == 0 else f"{slug}-{count}")
    found.update(re.findall(r'\b(?:id|name)=["\']([^"\']+)["\']', file.read_text()))
    return found


def maintained_files() -> list[Path]:
    files = [ROOT / name for name in ("README.md", "CONTRIBUTING.md", "SECURITY.md", "backend/README.md", "frontend/README.md", "scripts/eval/README.md", "scripts/docs/README.md", "specs/README.md", "specs/033-go-rewrite/README.md", "scripts/e2e/reports/README.md")]
    files.extend((ROOT / "docs").rglob("*.md"))
    files.extend((ROOT / "backend-go").rglob("*.md"))
    return sorted(set(files))


def check_links(files: list[Path]) -> tuple[list[str], int]:
    errors, checked, cached = [], 0, {}
    for file in files:
        text = prose(file.read_text())
        targets = [(m.start(), m[1]) for m in re.finditer(r"!?\[[^]\n]*\]\(<?([^\s)>]+)>?(?:\s+[\"'][^\n]*?[\"'])?\)", text)]
        targets += [(m.start(), m[1]) for m in re.finditer(r"^\s{0,3}\[[^]]+\]:\s*<?([^\s>]+)>?", text, re.M)]
        targets += [(m.start(), m[1]) for m in re.finditer(r"<(?:img|a)\b[^>]*\b(?:src|href)=[\"']([^\"']+)[\"']", text)]
        for offset, target in targets:
            url = urlsplit(html.unescape(target))
            if url.scheme or url.netloc:
                continue
            path = (file.parent / unquote(url.path)).resolve() if url.path else file
            location = f"{file.relative_to(ROOT)}:{text.count(chr(10), 0, offset) + 1}"
            checked += 1
            if not path.exists():
                errors.append(f"{location}: missing target {target}")
            elif url.fragment and path.is_file() and path.suffix.lower() == ".md":
                if path not in cached:
                    cached[path] = anchors(path)
                if unquote(url.fragment) not in cached[path]:
                    errors.append(f"{location}: missing heading #{url.fragment} in {path.relative_to(ROOT)}")
    return errors, checked


def check_tools() -> tuple[list[str], int]:
    java = set()
    for path in (ROOT / "backend/src/main/java").rglob("*.java"):
        java.update(re.findall(r'@Tool\(\s*name\s*=\s*"([^"]+)"', path.read_text()))
    go = set()
    for path in (ROOT / "backend-go/internal/business/agent").glob("tool-definitions*.json"):
        go.update(item["name"] for item in json.loads(path.read_text()))
    optional = (ROOT / "backend-go/internal/business/agent/web_search.go").read_text()
    go.update(re.findall(r'Name:\s*"(searchWeb)"', optional))
    doc = set(re.findall(r"^\| `([A-Za-z][A-Za-z0-9]+)` \|", (ROOT / "docs/agent-tools.md").read_text().split("## 返回值、审计与幂等")[0], re.M))
    errors = []
    if java != go:
        errors.append(f"Java/Go tool names differ: Java-only={sorted(java-go)}, Go-only={sorted(go-java)}")
    if doc != java:
        errors.append(f"Tool documentation differs: missing={sorted(java-doc)}, extra={sorted(doc-java)}")
    return errors, len(java)


def check_migrations() -> tuple[list[str], int]:
    directory = ROOT / "backend/src/main/resources/db/migration"
    files = sorted(directory.glob("V*__*.sql"))
    versions = sorted(int(re.match(r"V(\d+)__", p.name)[1]) for p in files)
    errors = []
    if versions != list(range(1, len(files) + 1)):
        errors.append("SQL migration versions contain gaps or duplicates")
    manifest = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())
    prefix = "backend/src/main/resources/db/migration/"
    baseline = [item for item in manifest["files"] if item["path"].startswith(prefix) and item["path"].endswith(".sql")]
    for item in baseline:
        file = ROOT / item["path"]
        if not file.exists() or hashlib.sha256(file.read_bytes()).hexdigest() != item["sha256"]:
            errors.append(f"Immutable baseline SQL differs: {item['path']}")
    if list((ROOT / "backend-go").rglob("V*__*.sql")):
        errors.append("Go contains a duplicate versioned SQL source")
    for file in (ROOT / "README.md", ROOT / "docs/architecture.md", ROOT / "docs/backend-parity.md", ROOT / "backend-go/README.md"):
        if f"V1–V{len(files)}" not in file.read_text():
            errors.append(f"Current SQL range is stale in {file.relative_to(ROOT)}")
    if not baseline:
        errors.append("No fixed Java baseline migrations found")
    return errors, len(baseline)


def main() -> int:
    files = maintained_files()
    errors, links = check_links(files)
    tool_errors, tools = check_tools()
    sql_errors, baseline = check_migrations()
    errors += tool_errors + sql_errors
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"OK: {len(files)} Markdown files, {links} local links/anchors; {tools} Java/Go tool names; {baseline} unchanged baseline SQL files and one current migration source")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
