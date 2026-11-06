#!/usr/bin/env python3
"""Record immutable Java tool-schema formatting and token counts, without services."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'backend-go/internal/business/context/testdata/java-tool-schemas.json'
SOURCES = ['agent/tool/TaskTools', 'agent/plan/tool/AgentPlanTools',
           'agent/interaction/tool/AgentUserTools', 'calendar/tool/CalendarTools',
           'memory/tool/MemoryTools', 'knowledge/tool/KnowledgeTools',
           'common/ai/TokenEstimation', 'common/ai/PromptTokenCounter', 'common/ai/ContextBudgetService']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    baseline = json.loads((ROOT / 'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
    report = ROOT / 'backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml'
    cp = next(p.attrib['value'] for p in ET.parse(report).find('properties')
              if p.attrib.get('name') == 'java.class.path')
    cp = os.pathsep.join([str(ROOT / 'backend/target/classes'),
                         *(p for p in cp.split(os.pathsep) if p.endswith('.jar'))])
    env = {k: v for k, v in os.environ.items() if k in {'PATH', 'HOME', 'TMPDIR', 'JAVA_HOME', 'LANG'}}
    hashes = {}
    with tempfile.TemporaryDirectory(prefix='personalos-context-schema-') as directory:
        files = []
        for source in SOURCES:
            path = 'backend/src/main/java/com/personalos/' + source + '.java'
            raw = subprocess.check_output(['git', 'show', baseline + ':' + path], cwd=ROOT)
            target = Path(directory) / (source.rsplit('/', 1)[-1] + '.java')
            target.write_bytes(raw)
            files.append(str(target))
            hashes[path] = hashlib.sha256(raw).hexdigest()
        subprocess.run(['javac', '-parameters', '-encoding', 'UTF-8', '-proc:none', '-sourcepath', '',
                        '-cp', cp, '-d', directory, *files, str(ROOT / 'scripts/migration/ContextSchemaOracle.java')],
                       env=env, check=True, capture_output=True, timeout=90)
        result = subprocess.run(['java', '-cp', directory + os.pathsep + cp,
                                 'com.personalos.migration.ContextSchemaOracle'],
                                env=env, check=True, capture_output=True, text=True, timeout=90)
    rows = json.loads(next(line[7:] for line in result.stdout.splitlines() if line.startswith('ORACLE ')))
    artifact = dict(baseline_commit=baseline, sources_sha256=hashes, tools=rows,
                    scope='Raw Java callback schemas versus Go compact JSON; no provider usage claim.')
    if args.write:
        TARGET.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + '\n')
    elif json.loads(TARGET.read_text()) != artifact:
        raise SystemExit('Java schema fixture drift; review before --write')
    print('Fixed Java schema counts:', len(rows), 'tools;', sum(row['raw_tokens'] for row in rows), 'raw tokens')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr or ''
        if isinstance(detail, bytes):
            detail = detail.decode('utf-8', errors='replace')
        raise SystemExit(f'{exc.cmd[0]} failed ({exc.returncode}): ' + detail[:4000]) from None
