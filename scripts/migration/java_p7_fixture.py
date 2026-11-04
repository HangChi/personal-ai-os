#!/usr/bin/env python3
"""Generate/check offline P7 contracts compiled from the immutable Java baseline."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'backend-go/internal/business/mcp/testdata/java-p7.json'
SOURCES = [
    'mcp/tool/McpToolNaming.java', 'mcp/tool/McpToolPolicy.java',
    'mcp/confirmation/McpConfirmationSummary.java', 'mcp/confirmation/McpToolCall.java',
    'mcp/server/PersonalMcpTools.java', 'export/ImportPayload.java',
    'knowledge/governance/SourceInfo.java',
    *['common/search/' + s + '.java' for s in ['SearchPatterns', 'SearchContributor',
      'SearchHit', 'SearchGroup', 'GlobalSearchResult', 'GlobalSearchService', 'SearchController']],
]


def inputs():
    invalid = ['', 'null', '[]', '{}', '{broken', '{"formatVersion":"2"}',
               '{"FormatVersion":"1"}', '{"formatVersion":true}',
               '{"formatVersion":"1","tasks":[{"id":"invalid"}]}',
               '{"formatVersion":"1","tasks":[{"title":{}}]}',
               '{"formatVersion":"1","tasks":[{"tags":{}}]}',
               '{"formatVersion":"1","memories":[{"hitCount":2147483648}]}',
               '{"formatVersion":"1","knowledgeDocuments":[{"document":{"id":"invalid"}}]}',
               '{"formatVersion":"1","knowledgeDocuments":[{"source":{"spaceId":"invalid"}}]}',
               '{"formatVersion":"1","tasks":[{"createdAt":"yesterday"}]}']
    valid = [dict(formatVersion=v) for v in ['1', ' 1 ', 1]]
    valid += [dict(formatVersion='1', tasks=[dict(id='1-2-3-4-5', title='restored', tags=['one', 'two'])]),
              dict(formatVersion=1, FormatVersion='2', tasks=[dict(ID='invalid', title=42, description=True, priority='HIGH', tags=['a', 3, None])],
                   memories=[dict(pinned='TRUE', hitCount=2.9)], calendarEvents=[dict(allDay=1, reminderLeadMinutes='20')],
                   knowledgeDocuments=[dict(document=dict(sizeBytes='4294967296'))]),
              dict(formatVersion='1', tasks=[None, dict(Title='ignored', unknown={'x': 1})])]
    # Exercise both integral range and scalar coercion without a database.
    valid += [dict(formatVersion='1', memories=[dict(hitCount=v)]) for v in [None, -1, 0, 2147483647, '2', '', 'null']]
    valid += [dict(formatVersion='1', calendarEvents=[dict(allDay=v)]) for v in [None, True, False, 0, -1, 'false', 'True']]
    return dict(names=[['My-Server.v2', 'Read-File'], ['', ' '], ['中文', '工具'], ['a___b', 'c'],
                       ['İ', 'Iİ'], ['ΟΣ', 'ς'], ['S' * 150, 'read'], ['___', '---'], ['Mixed', 'read.file']],
                policies=[dict(name=n, automatic=a) for n, a in [('read', ['read']), ('write', ['read']), ('Read', ['read']), ('mcp_x_read', ['read']), ('write', [])]],
                summaries=[dict(server='files', tool='write', arguments=a) for a in [None, {}, {'path': '/tmp/synthetic'},
                           {'z': 1, 'b': 'line\nline\rnext', 'a': '长' * 81, 'c': None},
                           {'array': ['x', 2, True], 'object': {'one': 'value'}}, {'a': '😀' * 41}]],
                patterns=[' 100%_\\ ', 'Vue API', 'İ ΟΣ', '中文', '\u3000x\u3000', '\u00a0x\u00a0', 'a\\b%_c', ''],
                searches=[dict(keyword=k, limit=n) for k, n in [(' 词 ', 5), ('x', -3), ('x', 100), (' ', 5), ('😀' * 50, 20), ('😀' * 51, 5), ('\u00a0', 1)]],
                imports=invalid + [json.dumps(v, ensure_ascii=False) for v in valid])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    baseline = json.loads((ROOT / 'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
    reports = sorted((ROOT / 'backend/target/surefire-reports').glob('TEST-*.xml'))
    cp = next(p.attrib['value'] for p in ET.parse(reports[0]).find('properties') if p.attrib.get('name') == 'java.class.path')
    cp = os.pathsep.join([str(ROOT / 'backend/target/classes'), *(p for p in cp.split(os.pathsep) if p.endswith('.jar'))])
    cases, hashes = inputs(), {}
    with tempfile.TemporaryDirectory(prefix='personalos-p7-oracle-') as tmp:
        sources = []
        for source in SOURCES:
            path = 'backend/src/main/java/com/personalos/' + source
            data = subprocess.check_output(['git', 'show', baseline + ':' + path], cwd=ROOT)
            target = Path(tmp, source)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(data)
            sources.append(str(target))
            hashes[path] = hashlib.sha256(data).hexdigest()
        subprocess.run(['javac', '-parameters', '-encoding', 'UTF-8', '-proc:none', '-sourcepath', '', '-cp', cp,
                        '-d', tmp, *sources, str(ROOT / 'scripts/migration/P7Oracle.java')], check=True, timeout=90)
        result = subprocess.run(['java', '-cp', tmp + os.pathsep + cp, 'com.personalos.migration.P7Oracle'],
                                input=json.dumps(cases), text=True, capture_output=True, check=True, timeout=90)
    expected = json.loads(next(s[7:] for s in result.stdout.splitlines() if s.startswith('ORACLE ')))
    artifact = dict(baseline_commit=baseline, sources_sha256=hashes, inputs=cases, expected=expected)
    if not TARGET.is_file() or json.loads(TARGET.read_text()) != artifact:
        if not args.write:
            raise SystemExit('P7 Java oracle drift: ' + str(TARGET))
        TARGET.parent.mkdir(parents=True, exist_ok=True)
        TARGET.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + '\n')
    print('P7 Java oracle:', {k: len(v) for k, v in expected.items()})


if __name__ == '__main__':
    main()
