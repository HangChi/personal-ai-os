#!/usr/bin/env python3
"""Adapt the fixed Java dashboard using only Go metrics that the exporter actually collects."""
import argparse
import copy
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[2]
SOURCE = "deploy/monitoring/grafana/dashboards/runtime.json"
TARGET = ROOT / "backend-go/deploy/monitoring/grafana/dashboards/runtime.json"


def generate():
    baseline = json.loads((ROOT / "specs/033-go-rewrite/inventory.json").read_text())["baseline_commit"]
    original = json.loads(subprocess.check_output(["git", "show", baseline + ":" + SOURCE], cwd=ROOT))
    result = copy.deepcopy(original)
    result.update(uid="personalos-go-runtime", title="Personal AI OS Go 运行状态", version=1)
    panels = {panel["id"]: panel for panel in result["panels"]}
    panels[5]["title"] = "Go 数据库连接池使用率"
    panels[5]["targets"][0]["expr"] = 'personalos_go_database_connections{state="active"} / ignoring(state) personalos_go_database_connections{state="max"}'
    panels[5]["fieldConfig"]["defaults"]["unit"] = "percentunit"
    panels[6]["title"] = "Go 已分配堆内存"
    panels[6]["targets"][0]["expr"] = "go_memory_used_bytes"
    panels[20]["targets"][0]["expr"] = 'up{job="personal-ai-os-go-worker"}'
    for panel in result["panels"]:
        panel["datasource"]["uid"] = "personalos-go-prometheus"
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    arguments = parser.parse_args()
    content = json.dumps(generate(), ensure_ascii=False, indent=2) + chr(10)
    if arguments.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(content)
    elif not TARGET.is_file() or TARGET.read_text() != content:
        raise SystemExit("Go dashboard changed; review before --write")
    print("Go dashboard: 20 original panels, explicit pool/memory/worker adaptations")


if __name__ == "__main__":
    main()
