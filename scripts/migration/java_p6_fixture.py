#!/usr/bin/env python3
"""Generate/check P6 contracts against immutable Java sources, without network or services."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'backend-go/internal/business/knowledge/testdata/java-p6.json'
SOURCES = ['service/DocumentFilenameSupport.java', 'service/KeywordExtractor.java',
           'splitter/MarkdownStructureTextSplitter.java', 'config/KnowledgeProperties.java',
           'service/KnowledgeRetrievalService.java', 'service/KnowledgeQaService.java',
           'tool/KnowledgeTools.java', 'dto/KnowledgeHitDto.java']

def inputs(baseline):
    historical = runpy.run_path(str(ROOT / 'scripts/eval/queries.py'))
    queries = historical['QUERIES']
    live = json.loads((ROOT / 'scripts/eval/rag-live-cases.json').read_text())
    recalls=[]
    for i,case in enumerate(live):
        docs=[]
        for j,item in enumerate([case,live[(i+1)%len(live)],live[(i+2)%len(live)]]):
            path=item['sources'][0]
            raw=subprocess.check_output(['git','show',baseline+':'+path],cwd=ROOT).decode()
            offset=raw.find(item['evidence'])
            if offset<0: raise ValueError('ground truth missing: '+item['id'])
            excerpt=raw[max(0,offset-120):offset+len(item['evidence'])+240]
            docs.append(dict(id=f'{i+1:08x}-{j+1:04x}-4000-8000-000000000001',title=path,filename=Path(path).name,content=excerpt,score=[.94,.72,.10][j]))
        recalls.append(dict(id=case['id'],query=case['query'],documents=docs,expectedSource=case['sources'][0]))
    return dict(filenames=[None,'','  ','../x.TXT','C:\\fakepath\\x.pdf','a/','.hidden','中'*350+'.pdf','x'*350+'.longextension'],
                queries=['2PC / 3PC 的区别','what is ip_hash using C++ and C#','Object.defineProperty. foo.bar','123 2026 3.14','纯中文问题']+[q['q'] for q in queries]+[c['query'] for c in live],
                splits=[dict(text=text,target=target,minimum=minimum,overlap=overlap)
                        for text in ['', '# A\n正文内容。\n\n## B\n第二节正文。\n# C\n末节正文。', '# Title\n```go\n# not heading\n```\n\nnext paragraph.', '没有标题。'*80, '# '+'长标题'*60+'\n'+('段落文字。\n\n'*30)]
                        for target,minimum,overlap in [(1200,10,0),(100,30,10),(35,10,0)]],
                answers=['没有编号','[2] 再引用[2][1][9][0]','[9]','依据[1]'],recalls=recalls)

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    baseline=json.loads((ROOT/'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
    report=ROOT/'backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml'
    cp=next(p.attrib['value'] for p in ET.parse(report).find('properties') if p.attrib.get('name')=='java.class.path')
    cp=os.pathsep.join([str(ROOT/'backend/target/classes'),*(p for p in cp.split(os.pathsep) if p.endswith('.jar'))])
    cases=inputs(baseline);hashes={}
    with tempfile.TemporaryDirectory(prefix='personalos-p6-oracle-') as tmp:
        paths=[]
        for source in SOURCES:
            path='backend/src/main/java/com/personalos/knowledge/'+source
            data=subprocess.check_output(['git','show',baseline+':'+path],cwd=ROOT);target=Path(tmp,source);target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data);paths.append(str(target));hashes[path]=hashlib.sha256(data).hexdigest()
        subprocess.run(['javac','-parameters','-encoding','UTF-8','-proc:none','-sourcepath','','-cp',cp,'-d',tmp,*paths,str(ROOT/'scripts/migration/P6Oracle.java')],check=True,timeout=90)
        result=subprocess.run(['java','-cp',tmp+os.pathsep+cp,'com.personalos.migration.P6Oracle'],input=json.dumps(cases),text=True,capture_output=True,check=True,timeout=90)
    expected=json.loads(next(s[7:] for s in result.stdout.splitlines() if s.startswith('ORACLE ')))
    # Runtime timings are recorded by integration evaluation, not frozen as contracts.
    timings=[row.pop('latencyMicros') for row in expected['recalls']]
    data=dict(baseline_commit=baseline,sources_sha256=hashes,inputs=cases,expected=expected)
    artifacts={TARGET:data,ROOT/'backend-go/internal/business/agent/tool-definitions-p6.json':expected['tools']}
    for path,artifact in artifacts.items():
        # Java Map.of iteration order varies between JVMs; compare JSON contracts.
        if path.is_file() and json.loads(path.read_text()) == artifact:
            continue
        content=json.dumps(artifact,ensure_ascii=False,indent=2)+'\n'
        if args.write: path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content)
        elif path.read_text()!=content: raise SystemExit('P6 Java oracle drift: '+str(path))
    if args.write:
        (ROOT/'specs/033-go-rewrite/p6-java-eval.json').write_text(json.dumps(dict(baseline_commit=baseline,
            suite='controlled_java_retrieval',cases=len(cases['recalls']),latency_micros=timings,
            uses_mock_vector_and_keyword_channels=True,semantic_quality_measured=False),indent=2)+'\n')
    print('P6 Java oracle:',len(cases['queries']),'queries,',len(cases['splits']),'split cases,',len(cases['recalls']),'controlled live-corpus recalls')

if __name__=='__main__':main()
