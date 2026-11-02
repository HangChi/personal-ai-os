# -*- coding: utf-8 -*-
import importlib.util,pathlib,json,tempfile,time
root=pathlib.Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('eval',root/'scripts/eval/rag-live-eval.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
state=json.loads(m.STATE.read_text())
if not state['database'].startswith('paios_rag_eval_'):raise SystemExit('Refusing non-evaluation resources')
client=m.Client('http://127.0.0.1:18082/api/v1');client.login();created=[];rows=[]
space=next((s for s in client.request('/knowledge/governance/spaces') if s['name']=='合成冲突评测-'+state['createdAt']),None)
if space is None:space=client.request('/knowledge/governance/spaces','POST',{'name':'合成冲突评测-'+state['createdAt']})
try:
 for label,amount in [('A',500),('B',800)]:
  text='# 研发部国内差旅住宿政策 2026\n\n研发部员工在2026年国内出差，住宿费用报销上限为每天'+str(amount)+'元。金额适用于研发部正式员工的普通国内差旅，员工需要提交酒店发票与出差审批记录。\n\n'
  text+='报销申请应记录出差日期、目的地、入住及离店时间，发票抬头应符合公司财务要求。部门应审核票据完整性，补贴仅涵盖住宿，不包含交通或餐饮。超出上限的费用由员工自行承担，审批流程不改变上述每天的住宿限额。\n\n'*3
  with tempfile.TemporaryDirectory() as directory:
   path=pathlib.Path(directory)/('policy-'+label+'.md');path.write_text(text)
   doc=client.upload(path,'合成差旅制度 '+label);created.append(doc['id'])
  client.request('/knowledge/governance/sources/'+doc['id'],'PUT',{'spaceId':space['id'],'version':'2026正式版','scope':'研发部正式员工国内差旅','effectiveFrom':'2026-01-01','effectiveUntil':'2026-12-31','authority':'隔离评测合成资料','lifecycle':'ACTIVE'})
 deadline=time.monotonic()+45
 while client.request('/knowledge/search-index')['pending'] and time.monotonic()<deadline:time.sleep(.5)
 for name,question in [('sameScope','2026年研发部正式员工国内出差，每天住宿报销上限是多少？'),('ambiguous','研发部出差住宿每天能报多少钱？')]:
  start=time.monotonic();answer=client.request('/knowledge/ask','POST',{'question':question,'topK':1})
  rows.append({'case':name,'expected':'CONFLICT','status':answer['retrieval']['status'],'answered':answer['answered'],'citations':len(answer['citations']),'hasBothSources':set(created).issubset({x['documentId'] for x in answer['citations']}),'durationMs':round((time.monotonic()-start)*1000),'stages':answer['retrieval']['stages']})
  print(json.dumps(rows[-1],ensure_ascii=False),flush=True)
 # Explicit non-overlapping validity intervals must request clarification, not choose the latest upload.
 client.request('/knowledge/governance/sources/'+created[0],'PUT',{'spaceId':space['id'],'version':'2025版','scope':'研发部正式员工国内差旅','effectiveFrom':'2025-01-01','effectiveUntil':'2025-12-31','authority':'隔离评测合成资料','lifecycle':'ACTIVE'})
 start=time.monotonic();answer=client.request('/knowledge/ask','POST',{'question':'研发部普通国内出差的住宿报销标准是多少？','topK':1})
 rows.append({'case':'differentPeriods','expected':'CLARIFY','status':answer['retrieval']['status'],'answered':answer['answered'],'citations':len(answer['citations']),'hasBothSources':set(created).issubset({x['documentId'] for x in answer['citations']}),'durationMs':round((time.monotonic()-start)*1000),'stages':answer['retrieval']['stages']})
 print(json.dumps(rows[-1],ensure_ascii=False),flush=True)
finally:
 for identity in created:client.request('/knowledge/documents/'+identity,'DELETE')
 m.private_json(m.WORK/('conflicts-'+state['createdAt']+'.json'),{'cases':rows,'scope':'Synthetic policies in isolated evaluation database; real configured models'})

if not all(row["status"]==row["expected"] and not row["answered"] and row["hasBothSources"] for row in rows):
 raise SystemExit("Conflict evaluation failed; report retained")
