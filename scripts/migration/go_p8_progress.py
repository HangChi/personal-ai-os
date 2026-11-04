#!/usr/bin/env python3
"""Record explicitly reviewed P8 retention responsibilities; never elevate release gates."""
import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SPEC = ROOT / "specs/033-go-rewrite"
EVIDENCE = ["backend-go/internal/platform/maintenance/retention.go",
            "backend-go/internal/platform/maintenance/retention_test.go",
            "backend-go/internal/platform/config/retention.go",
            "backend-go/internal/app/foundation_p8_integration_test.go",
            "specs/033-go-rewrite/p8-completion.md"]


def selected(entry):
    return Path(entry.get("path", "")).name in {"DataRetentionService.java", "DataRetentionServiceTest.java"}


def update():
    progress = json.loads((SPEC / "progress.json").read_text())
    inventory = json.loads((SPEC / "inventory.json").read_text())
    for path in EVIDENCE:
        if not (ROOT / path).is_file():
            raise ValueError("Missing P8 evidence: " + path)
    selected_ids = set()
    for group in ("files", "runtime_hooks", "java_test_cases"):
        for entry in inventory[group]:
            if not selected(entry):
                continue
            selected_ids.add(entry["id"])
            existing = progress["items"].get(entry["id"], {})
            progress["items"][entry["id"]] = dict(
                status="verified" if existing.get("status") == "verified" else "implemented",
                evidence=list(dict.fromkeys(existing.get("evidence", []) + EVIDENCE)),
                note="P8/T801a：默认永久保留、独立UTC截止时间/严格小于、负值拒绝、本地cron/关闭；真实TLS库验证且保护TOOL IN_PROGRESS。原声明：" + entry.get("handler", {}).get("name", "DataRetentionService"))
    if len(selected_ids) != 7:
        raise ValueError("P8 retention scope changed; review required")
    (SPEC / "progress.json").write_text(json.dumps(progress, ensure_ascii=False, indent=2) + "\n")
    print("P8 retention: 7 implemented items; all other items and release gates preserved")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", required=True)
    parser.parse_args()
    update()
