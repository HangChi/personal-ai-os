#!/usr/bin/env python3
"""运行固定基线真实 Java P4 参数、预算及 Spring 工具元数据；不连接服务。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import tempfile
import xml.etree.ElementTree as ET
ROOT=Path(__file__).resolve().parents[2]
TARGET=ROOT/'backend-go/internal/business/chat/testdata/java-p4.json'
DEFINITIONS=ROOT/'backend-go/internal/business/agent/tool-definitions.json'
def main():
 parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
 baseline=json.loads((ROOT/'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
 cp=next(x.attrib['value'] for x in ET.parse(ROOT/'backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml').find('properties') if x.attrib.get('name')=='java.class.path')
 cp=os.pathsep.join([str(ROOT/'backend/target/classes'),*[p for p in cp.split(os.pathsep) if p.endswith('.jar')]])
 rng=random.Random(40409);budgets=[]
 for _ in range(150):
  actions=[dict(op=rng.choice(['model','tool','record']),fp=rng.choice(['a','b','c']),status=rng.choice(['OK','ERROR','REJECTED','REQUIRES_CONFIRMATION','DUPLICATE'])) for _ in range(30)]
  budgets.append(dict(models=rng.randint(1,12),failures=rng.randint(1,5),actions=actions))
 bindings=[dict(content='hi',topK=n,useKnowledge=k,thinkingLevel=t) for n in [None,-1,0,1,20,21,100,2147483647] for k in [None,True,False] for t in [None,'off','standard','deep','DEEP',' deep ']]
 bindings += [dict(content=v,useKnowledge=k,topK=n,attachments=a) for v in [None,1,True,[],{},'text'] for k in ['true','FALSE','bad',0,1] for n in ['3','3.5',1.9] for a in [None,[],[None], [dict(id='x',size='5')]]]
 arguments=[dict(kind=k,text=s) for k,ss in {'uuid':[None,'','  ','1-1-1-1-1','1-2-3-4-5','00000000-0000-0000-0000-000000000001',' 00000000-0000-0000-0000-000000000001 ','bad','-1-1-1-1-1','000000000-0-0-0-0'], 'instant':[None,'','  ','2026-11-04','2026-11-04T10:00:00Z','2026-11-04t10:00:00z','2026-11-04T10:00:00+08:00','2026-11-04T10:00:00','2026-02-30','2026-11-04T24:00:00Z','2016-12-31T23:59:60Z']}.items() for s in ss]
 usages=[dict(prompt=p,completion=c,text=t,model=m) for p,c in [(None,None),(0,0),(120,0),(0,8),(3071,5),(None,8),(120,None)] for t in [None,'','reply','中文😀'] for m in [None,'reported']]
 estimates=[dict(sample=s,chars=n,reply=r) for s in [None,'','hello world','中文😀','abc中文'] for n in [0,1,7,1600,5500] for r in [None,'','reply','中文😀']]
 inputs=dict(budgets=budgets,bindings=bindings,arguments=arguments,usages=usages,estimates=estimates)
 sources=['common/ai/AgentTurnBudget.java','common/ai/AgentExecutionProperties.java','common/ai/tool/ToolExecutor.java','common/ai/tool/ToolArguments.java','chat/dto/StreamChatRequest.java','chat/dto/ThinkingLevel.java','agent/tool/TaskTools.java','agent/plan/tool/AgentPlanTools.java','agent/interaction/tool/AgentUserTools.java','chat/support/ChatResponseInspection.java','chat/support/StreamUsage.java','common/ai/TokenEstimation.java']
 hashes={};env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','JAVA_HOME','LANG'}}
 with tempfile.TemporaryDirectory(prefix='personalos-p4-oracle-') as d:
  files=[]
  for name in sources:
   path='backend/src/main/java/com/personalos/'+name;raw=subprocess.check_output(['git','show',baseline+':'+path],cwd=ROOT);target=Path(d)/name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(raw);files.append(str(target));hashes[path]=hashlib.sha256(raw).hexdigest()
  subprocess.run(['javac','-parameters','-encoding','UTF-8','-proc:none','-sourcepath','','-cp',cp,'-d',d,*files,str(ROOT/'scripts/migration/P4Oracle.java')],check=True,env=env,timeout=90)
  out=subprocess.run(['java','-cp',d+os.pathsep+cp,'com.personalos.migration.P4Oracle'],input=json.dumps(inputs),text=True,capture_output=True,check=True,env=env,timeout=90).stdout
 expected=json.loads(next(s[7:] for s in out.splitlines() if s.startswith('ORACLE ')))
 artifacts={TARGET:json.dumps(dict(baseline_commit=baseline,sources_sha256=hashes,inputs=inputs,expected=expected),ensure_ascii=True,indent=2)+'\n',DEFINITIONS:json.dumps(expected['definitions'],ensure_ascii=False,indent=2)+'\n'}
 for path,data in artifacts.items():
  if args.write:path.parent.mkdir(parents=True,exist_ok=True);path.write_text(data)
  elif path.read_text()!=data:raise SystemExit('Java P4 fixture drift: '+str(path))
 print('Java P4: %d budget turns / %d actions, %d request bindings, %d arguments, %d tool definitions'%(len(budgets),sum(len(x['actions']) for x in budgets),len(bindings),len(arguments),len(expected['definitions'])))
 print('Java P4 usage: %d response/zero/partial snapshots, %d content estimates'%(len(usages),len(estimates)))
if __name__=='__main__':main()
