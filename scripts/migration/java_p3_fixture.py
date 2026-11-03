#!/usr/bin/env python3
"""固定原Java的P3协议/窗口/MVC绑定差分；仅合成数据与临时回环HTTP，无模型/现有存储连接。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'backend-go/internal/business/model/testdata/java-p3.json'
PREFIX = 'backend/src/main/java/com/personalos/common/ai/'
SOURCES = [PREFIX + name + '.java' for name in [
    'ModelContextWindows', 'ModelWindowReferences', 'config/ModelListProbeService',
    'config/ModelProfileService', 'config/ModelProfileController', 'config/ModelConfigController',
    'config/ModelConfigService', 'config/AiModelProfileEntity', 'config/AiModelProfileModelEntity',
    'config/ModelProtocol']]


def cases():
    metadata = []
    fields = ['context_length', 'context_window', 'max_model_len', 'max_context_length', 'max_context_window',
              'context_window_tokens', 'max_context_window_tokens', 'max_position_embeddings', 'n_ctx',
              'max_input_tokens', 'max_prompt_tokens']
    for field in fields:
        metadata += [{field: 8192}, {'metadata': {'nested': [{field: '131,072'}]}}]
    metadata += [{'context_length': value} for value in [None, True, 1000, 1024, 33554432, 33554433, 8192.5, '8k', '8,192', ' 8192', '0008192']]
    metadata += [{'model_info': {'llama.context_length': 32768}},
                 {'extra_info': {'default_envs': {'max_tokens': 131072, 'max_input_tokens': 129024}}},
                 {'extra_info': {'default_envs': {'max_input_tokens': 9000}}},
                 {'context_length': 100000, 'max_input_tokens': 90000},
                 {'metadata': {'first': {'context_length': 4096}, 'context_length': 8192}},
                 {'metadata': {'a': {'b': {'c': {'d': {'e': {'context_length': 8192}}}}}}}]
    known = [None, '', ' QWEN_MAX:latest ', 'azure/gpt-4o-2024-08-06', 'cmaas-qwen3.8-flash', 'unknown', 'avoid-o1-alias', 'gpt-4otherrandom']
    content = subprocess.check_output(['git', 'show', f'cb5d694d62fa93742b5789b97273e76d931ce335:{PREFIX}ModelContextWindows.java'], cwd=ROOT, text=True)
    for name in re.findall(r'Map.entry\("([^"]+)"', content):
        known += [name, 'gateway/' + name + '-latest']
    references = [{'name': name, 'base': base} for name in ['qwen3.8-flash', 'deepseek-v4-pro', 'deepseek-v4-pro-20261031', 'deepseek-v4-pro-wrong', 'cmaas-glm-5.3', 'minimax-m2.5', 'deepseek-v4.1-flash'] for base in ['https://dashscope.aliyuncs.com/compatible-mode/v1', 'https://gateway.invalid/v1']]
    bindings = []
    def bind(body, legacy=False, media='application/json'):
        bindings.append({'method': 'PUT' if legacy else 'POST', 'path': '/api/v1/settings/model' + ('' if legacy else '/profiles'), 'contentType': media, 'body': body})
    for body in ['', 'null', '[]', '{', '{}', '{"name":"fixture"}', '{"Name":"wrong case"}', '{"name":"fixture"} {}', '{"name":"   "}', '{"name":12}', '{"name":true}', '{"name":{}}', '{"name":"fixture","chatModels":{}}', '{"name":"fixture","chatModels":[null,12,true,"x"]}', '{"name":"fixture","contextWindows":{"x":"8192"}}', '{"name":"fixture","contextWindows":{"x":8192.9}}', '{"name":"fixture","contextWindows":{"x":true}}']:
        bind(body)
    for field, limit in [('name',60),('baseUrl',500),('apiKey',500),('chatModel',120),('embeddingModel',120),('presetId',60),('protocol',20)]:
        bind(json.dumps({'name': 'fixture', field: 'x' * (limit + 1)}))
    bind(json.dumps({'name': '😀' * 31}))
    bind(json.dumps({'name': 'fixture', 'chatModels': ['x'] * 501}))
    bind(json.dumps({'name': 'fixture', 'audioModels': ['x' * 121]}))
    for value in [-1, 0, 2, 3, '0.7', 'NaN', 'Infinity', False, {}, None]:
        bind(json.dumps({'name':'fixture','temperature':value}))
    for url in ['file:///tmp/a', 'javascript:x', 'HTTPS://x', ' http://fixture.invalid ', 'https://']:
        bind(json.dumps({'name': 'fixture', 'baseUrl': url}))
    bind('{"name":"fixture"}', media='text/plain')
    for body in ['{}','null','{"temperature":3}','{"apiKey":{}}','{"baseUrl":"file:///x"}','{"chatModel":12}','{"temperature":"bad"}']:
        bind(body, legacy=True)
    # 同时非法时，保留原控制器长度/清单 → 温度/URL → 服务名称的报错顺序。
    for body in [
        {'name':'fixture','presetId':'x'*61,'temperature':3},
        {'name':'fixture','protocol':'x'*21,'baseUrl':'file:///x'},
        {'name':'fixture','chatModels':['x']*501,'baseUrl':'file:///x'},
        {'name':'','temperature':3},
    ]:
        bind(json.dumps(body))
    probes = []
    def probe(operation, protocol='OPENAI', model='custom', listing=None, detail=None):
        probes.append({'operation':operation,'protocol':protocol,'model':model,
                       'list':listing or {'status':200,'body':'{"data":[{"id":"custom"}]}'},
                       'detail':detail or {'status':200,'body':'{}'}})
    for status in [401,403,404,405,429,500]:
        probe('models',listing={'status':status,'body':'{}'})
        probe('window',detail={'status':status,'body':'{}'})
    for body in ['not json', 'null', '{}', '{"data":[{"id":"B","context_length":8192},{"id":"a"},{"id":"B"}]}', '[{"id":"custom","max_input_tokens":4096}]']:
        probe('models',listing={'status':200,'body':body})
    for body in ['{"context_length":131072,"max_input_tokens":120000}', '{"max_input_tokens":32768}', '{"model_info":{"llama.context_length":8192}}', '{"extra_info":{"default_envs":{"max_tokens":131072,"max_input_tokens":129024}}}', 'not json']:
        probe('window',detail={'status':200,'body':body})
    probe('window',model='gpt-4o',listing={'status':200,'body':'{"data":[{"id":"gpt-4o"}]}'} )
    probe('models',protocol='OLLAMA',listing={'status':200,'body':'{"models":[{"name":"custom"}]}'} ,detail={'status':200,'body':'{"model_info":{"llama.context_length":4096}}'})
    probe('models',protocol='ANTHROPIC',listing={'status':200,'body':'{"data":[{"id":"custom","context_length":8192}]}'} )
    return {'metadata':[json.dumps(v,ensure_ascii=True) for v in metadata], 'known':known, 'references':references,'bindings':bindings,'probes':probes}


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--write',action='store_true');args=parser.parse_args()
    env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','JAVA_HOME'}}
    baseline=json.loads((ROOT/'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
    report=ROOT/'backend/target/surefire-reports/TEST-com.personalos.common.ai.config.ModelConfigServiceTest.xml'
    cp=next(p.attrib['value'] for p in ET.parse(report).find('properties') if p.attrib.get('name')=='java.class.path')
    cp=os.pathsep.join([str(ROOT/'backend/target/classes'),*[p for p in cp.split(os.pathsep) if p.endswith('.jar')]])
    inputs=cases();hashes={}
    with tempfile.TemporaryDirectory(prefix='personalos-p3-oracle-') as folder:
        files=[]
        for source in SOURCES:
            content=subprocess.check_output(['git','show',baseline+':'+source],cwd=ROOT,text=True,env=env)
            path=Path(folder)/source.removeprefix(PREFIX);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content);files.append(str(path));hashes[source]=hashlib.sha256(content.encode()).hexdigest()
        subprocess.run(['javac','-encoding','UTF-8','-proc:none','-sourcepath','','-cp',cp,'-d',folder,*files,str(ROOT/'scripts/migration/P3ContractOracle.java')],env=env,check=True,timeout=90)
        output=subprocess.run(['java','-cp',folder+os.pathsep+cp,'com.personalos.common.ai.config.P3ContractOracle'],input=json.dumps(inputs),capture_output=True,text=True,check=True,timeout=90,env=env).stdout
    result=json.loads(next(line.removeprefix('ORACLE ') for line in output.splitlines() if line.startswith('ORACLE ')))
    fixture={'baseline_commit':baseline,'sources_sha256':hashes,'inputs':inputs,'expected':result}
    text=json.dumps(fixture,ensure_ascii=True,indent=2)+'\n'
    if args.write:TARGET.write_text(text)
    elif TARGET.read_text()!=text:raise SystemExit('P3原Java期望变化，请审查后--write')
    print('原Java P3差分通过：'+', '.join(k+'='+str(len(v)) for k,v in inputs.items()))


if __name__=='__main__': main()
