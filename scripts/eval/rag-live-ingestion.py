# -*- coding: utf-8 -*-
from pathlib import Path
import importlib.util,json,time,tempfile
root=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('eval',root/'scripts/eval/rag-live-eval.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
state=json.loads(m.STATE.read_text());assert state['database'].startswith('paios_rag_eval_')
# Only this owned sandbox database is modified. Existing formal knowledge is untouched.
m.sql(state['database'],'DELETE FROM knowledge_review_job;')
client=m.Client('http://127.0.0.1:18082/api/v1');client.login();created=[];result={}
space=next(s for s in client.request('/knowledge/governance/spaces') if s['name']=='合成冲突评测-'+state['createdAt'])
try:
 for label,amount in [('A',500),('B',800)]:
  text='# 研发部住宿报销限额\n\n研发部正式员工2026年国内差旅住宿报销上限是每天'+str(amount)+'元。该标准适用于相同年度、相同部门、相同费用类型，不区分目的地，所有普通国内出差均按此金额执行。\n\n'
  text+='员工需要提交酒店发票、审批记录、出差日期和目的地；票据应包含入住离店信息，财务审核确认后付款，住宿之外的交通餐饮费用另行处理。\n\n'*6
  assert 350<len(text)<1100
  with tempfile.TemporaryDirectory() as tmp:
   path=Path(tmp)/('ingestion-'+label+'.md');path.write_text(text);doc=client.upload(path,'录入审查合成政策 '+label);created.append(doc['id'])
  client.request('/knowledge/governance/sources/'+doc['id'],'PUT',{'spaceId':space['id'],'version':'2026版','scope':'研发部正式员工国内差旅','effectiveFrom':'2026-01-01','effectiveUntil':'2026-12-31','authority':'隔离评测合成资料','lifecycle':'ACTIVE'})
 jar=Path(next(p for p in state['processes'] if p['name']=='enhanced').get('jar',state['jar']))
 m.restart_enhanced(jar,True)
 client=m.Client('http://127.0.0.1:18082/api/v1');client.login();deadline=time.monotonic()+110;retried=set()
 while time.monotonic()<deadline:
  sources={s['documentId']:s['source']['reviewState'] for s in client.request('/knowledge/governance/sources') if s['documentId'] in created}
  conflicts=[c for c in client.request('/knowledge/governance/conflicts') if {c['leftId'],c['rightId']}==set(created) and c['status']=='OPEN']
  result={'states':sources,'openConflicts':len(conflicts),'passed':len(sources)==2 and all(v=='CHECKED_CANDIDATES' for v in sources.values()) and bool(conflicts)}
  if result['passed']:break
  for identity,phase in sources.items():
   if phase=='UNAVAILABLE' and identity not in retried:
    client.request('/knowledge/governance/sources/'+identity+'/review','POST');retried.add(identity)
  time.sleep(2)
 result['retriedSources']=len(retried);result['scope']='Only owned isolated database; real configured models'
 print(json.dumps(result,ensure_ascii=False,indent=2),flush=True)
finally:
 for identity in created:client.request('/knowledge/documents/'+identity,'DELETE')
 m.private_json(m.WORK/'ingestion-review.json',result)
if not result.get('passed'):raise SystemExit('Live ingestion review did not finish successfully')
