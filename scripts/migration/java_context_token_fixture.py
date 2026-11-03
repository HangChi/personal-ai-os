#!/usr/bin/env python3
"""执行原Java PromptTokenCounter；固定词表和Unicode/模型边界，不启动应用。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT/'backend-go/internal/business/context/testdata/java-tokens.json'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    baseline = json.loads((ROOT/'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
    env = {k: v for k, v in os.environ.items() if k in {'PATH', 'HOME', 'TMPDIR', 'JAVA_HOME', 'LANG'}}
    report = ROOT/'backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml'
    cp = next(x.attrib['value'] for x in ET.parse(report).find('properties') if x.attrib.get('name') == 'java.class.path')
    cp = os.pathsep.join([str(ROOT/'backend/target/classes'), *[p for p in cp.split(os.pathsep) if p.endswith('.jar')]])
    models = ['gpt-4', 'gpt-4o-mini', 'qwen-fixture', 'text-davinci-003', 'davinci']
    texts = ['', 'hello world', '中文测试。', '😀🍜👨‍👩‍👧', "I'm HERE, wasn't THAT hard?", '  ab\t c\r\n\n',
             '\u00a0\u0085\u2007\u3000\u2028\u2029', 'ABCabcHTTPCode XYZNext', '١٢٣٤５６７', '<|endoftext|>',
             'a'*300, '😀'*100, ' ' * 100, 'a'*31+'中文', 'a'*30+'中文中文中文中文中文中文']
    rng = random.Random(40401)
    alphabet = list("abcXYZABCéåöÑ中文你好世界𐐀𐐨😀0123 .,'!?/:\"\\\n\r\t\v\f\u00a0\u0085\u2007\u3000\u2028\u2029")
    texts += [''.join(rng.choices(alphabet, k=rng.randrange(1, 180))) for _ in range(250)]
    cases = [dict(text=t, model=m) for t in texts for m in models]
    hashes = {}
    with tempfile.TemporaryDirectory(prefix='personalos-context-oracle-') as directory:
        files = []
        for name in ('TokenEstimation', 'PromptTokenCounter', 'ContextBudgetService'):
            source = 'backend/src/main/java/com/personalos/common/ai/'+name+'.java'
            raw = subprocess.check_output(['git', 'show', baseline+':'+source], cwd=ROOT)
            target = Path(directory)/(name+'.java'); target.write_bytes(raw); files.append(str(target))
            hashes[source] = hashlib.sha256(raw).hexdigest()
        subprocess.run(['javac', '-parameters', '-encoding', 'UTF-8', '-proc:none', '-sourcepath', '', '-cp', cp, '-d', directory,
                        *files, str(ROOT/'scripts/migration/ContextTokenOracle.java')], env=env, check=True, timeout=90)
        output = subprocess.run(['java', '-cp', directory+os.pathsep+cp, 'com.personalos.migration.ContextTokenOracle'],
                                input=json.dumps(cases), text=True, capture_output=True, check=True, env=env, timeout=90).stdout
    expected = json.loads(next(line[7:] for line in output.split('\n') if line.startswith('ORACLE ')))
    fixture = json.dumps(dict(baseline_commit=baseline, sources_sha256=hashes, cases=cases, expected=expected), ensure_ascii=True, indent=2)+'\n'
    if args.write:
        TARGET.parent.mkdir(parents=True, exist_ok=True); TARGET.write_text(fixture)
    elif TARGET.read_text() != fixture:
        raise SystemExit('原Java计数期望发生变化，请审查后--write')
    print('原Java上下文token计数：'+str(len(cases))+'场景')


if __name__ == '__main__':
    main()
