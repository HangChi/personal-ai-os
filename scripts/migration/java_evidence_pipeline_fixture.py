#!/usr/bin/env python3
"""039 当前 Java 多阶段 RAG 对照；独立于冻结基线，不访问外部服务。"""
import argparse
import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'specs/039-evidence-aware-answers/contracts/java-rag-pipeline.json'
SOURCES = [
    'rag/RagRetrievalPipeline.java', 'rag/EvidenceReview.java', 'rag/RagResult.java',
    'rag/RagModelGateway.java', 'rag/CrossEncoderReranker.java', 'rag/EvidenceAnswer.java',
    'config/KnowledgeProperties.java', 'dto/KnowledgeHitDto.java',
    'service/KnowledgeRetrievalService.java', 'spi/KnowledgeSearchPort.java',
    'governance/KnowledgeGovernance.java', 'governance/SourceInfo.java',
]


def cases():
    hits = [dict(documentId=f'{index:08d}-1111-4111-8111-111111111111',
                 documentTitle=f'Policy {index}', content=f'Policy {index}: annual leave is {index + 4} days.')
            for index in range(1, 4)]
    base = dict(query='annual leave', topK=1, hits=hits, rewrite=dict(queries=[]),
                rerank=dict(order=[1, 2, 3]), evidence=dict(
                    evidence=[dict(index=i, grade='SUPPORTED') for i in range(1, 4)],
                    conflicts=[], clarification=''))
    rows = []

    def add(name, **changes):
        row = copy.deepcopy(base)
        row.update(changes, name=name)
        rows.append(row)
        return row

    add('ready_top_one')
    row = add('conflict_top_one')
    row['evidence']['conflicts'] = [dict(left=1, right=2, leftQuote='5 days', rightQuote='6 days', reason='different limits')]
    row = add('clarify')
    row['evidence']['clarification'] = 'Which policy applies?'
    row = add('insufficient')
    for item in row['evidence']['evidence']:
        item['grade'] = 'IRRELEVANT'
    row = copy.deepcopy(row)
    row.update(name='irrelevant_with_clarification')
    row['evidence']['clarification'] = 'Which policy applies?'
    rows.append(row)
    add('incomplete_rerank', rerank=dict(order=[1, 2]))
    add('duplicate_rerank', rerank=dict(order=[1, 1, 3]))
    row = add('ungrounded_conflict')
    row['evidence']['conflicts'] = [dict(left=1, right=2, leftQuote='not present', rightQuote='6 days', reason='invalid')]
    add('partial_rewrite', rewrite=dict(queries=['leave entitlement', '']))
    add('quoted_rerank', rerank=dict(order=['3', '2', '1']))
    return rows


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
    inputs, hashes = cases(), {}
    with tempfile.TemporaryDirectory(prefix='personalos-rag-oracle-') as tmp:
        files = []
        for source in SOURCES:
            path = 'backend/src/main/java/com/personalos/knowledge/' + source
            raw = (ROOT / path).read_bytes()
            target = Path(tmp, source)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(raw)
            files.append(str(target))
            hashes[path] = hashlib.sha256(raw).hexdigest()
        subprocess.run(['javac', '-parameters', '-encoding', 'UTF-8', '-proc:none', '-sourcepath', '',
                        '-cp', cp, '-d', tmp, *files, str(ROOT / 'scripts/migration/RagPipelineOracle.java')],
                       env=env, check=True, timeout=90, capture_output=True)
        result = subprocess.run(['java', '-cp', tmp + os.pathsep + cp,
                                 'com.personalos.knowledge.rag.RagPipelineOracle'],
                                input=json.dumps(inputs), text=True, capture_output=True, check=True,
                                timeout=90, env=env)
    expected = json.loads(next(s[7:] for s in result.stdout.splitlines() if s.startswith('ORACLE ')))
    artifact = dict(baseline_commit=baseline, sources_sha256=hashes, inputs=inputs, expected=expected,
                    scope='039 current working sources; fixed stage responses and protected-answer fallback. No live semantic-quality claim.')
    if args.write:
        TARGET.write_text(json.dumps(artifact, ensure_ascii=False, indent=2) + '\n')
    elif json.loads(TARGET.read_text()) != artifact:
        raise SystemExit('Java RAG fixture drift; review before --write')
    print('Current Java RAG pipeline:', len(inputs), 'cases; no external services or model calls')


if __name__ == '__main__':
    try:
        main()
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr or ''
        if isinstance(detail, bytes):
            detail = detail.decode('utf-8', errors='replace')
        raise SystemExit(f'{exc.cmd[0]} failed ({exc.returncode}): ' + detail[:4000]) from None
