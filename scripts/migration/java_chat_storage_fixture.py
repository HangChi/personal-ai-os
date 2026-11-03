#!/usr/bin/env python3
"""固定原 Java 的会话存储/MVC/Markdown 契约；不加载应用配置或连接用户存储。"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
TARGET = ROOT / 'backend-go/internal/business/chat/testdata/java-chat-storage.json'
PREFIX = 'backend/src/main/java/com/personalos/'
SOURCES = [PREFIX + path + '.java' for path in [
    'chat/service/ChatSessionService', 'chat/service/ChatMessageService',
    'chat/service/ChatCitationService', 'chat/service/ChatMarkdownExporter',
    'chat/service/ChatAttachmentService', 'chat/controller/ChatSessionController',
    'chat/entity/ChatSessionEntity', 'chat/entity/ChatMessageEntity',
    'chat/repository/ChatSessionRepository', 'chat/repository/ChatMessageRepository',
    'chat/repository/ChatMessageCitationRepository', 'chat/dto/ChatSessionDto',
    'chat/dto/ChatMessageDto', 'chat/dto/ChatSessionQuery', 'chat/dto/ChatCitationDto',
    'chat/dto/ChatAttachmentDto', 'chat/dto/CreateSessionRequest',
    'chat/dto/RenameSessionRequest', 'chat/dto/SetSessionModelRequest',
    'common/text/Texts', 'common/search/SearchPatterns', 'common/json/JsonCodec']]


def cases():
    bindings = []
    def bind(method, path, body='', media='application/json'):
        bindings.append(dict(method=method, path='/api/v1/chat/sessions'+path,
                             body=body, contentType=media))
    titles = [None, '', ' \t\u3000', ' 中文\u3000', '\u00a0', '\u0085',
              'x'*200, 'x'*201, '😀'*100, '😀'*101, ' '*20+'x'*190+' '*20]
    for method, path in [('POST', ''), ('PATCH', '/c-existing')]:
        for title in titles:
            bind(method, path, json.dumps({'title': title}, ensure_ascii=True))
        for body in ['', 'null', '{}', '[]', 'true', '{', '{"title":"a"} {}',
                     '{"title":12}', '{"title":true}', '{"title":{}}',
                     '{"TITLE":"upper"}', '{"title":"first","title":"last"}',
                     '{"title":"fixture","ignored":[]}']:
            bind(method, path, body)
        bind(method, path, '{"title":"fixture"}', 'text/plain')
    for path in ['/c-existing/model', '/missing/model']:
        for body in ['', 'null', '{}', '{"model":null}', '{"model":" \u3000"}',
                     '{"model":" custom "}', '{"model":12}', '{"model":false}',
                     '{"model":{}}', json.dumps({'model':'x'*120}),
                     json.dumps({'model':'x'*121}), json.dumps({'model':'😀'*61})]:
            bind('PUT', path, body)
    for path in ['/bad_id', '/missing']:
        for title in [None, '', 'fixture', 'x'*201]:
            bind('PATCH', path, json.dumps({'title':title}))
    for query in ['', '?limit=3', '?limit=3&beforeSeq=5', '?beforeSeq=0',
                  '?limit=0', '?limit=201', '?limit=2&beforeSeq=0', '?limit=abc',
                  '?limit=2147483648', '?limit=', '?limit=%20', '?limit=%202%20',
                  '?limit=2&limit=4', '?limit=+2', '?limit=%EF%BC%92',
                  '?limit=0x2', '?limit=%232', '?limit=02', '?limit=1%202',
                  '?limit=%E3%80%80', '?limit=-0x2', '?limit=0x80000000',
                  '?limit=0x%2B2', '?limit=0x-2', '?limit=%F0%9D%9F%9A']:
        bind('GET', '/c-existing/messages'+query)
        bind('GET', '/missing/messages'+query)
    for query in ['', '?fromSequenceNo=0', '?fromSequenceNo=3', '?fromSequenceNo=abc']:
        bind('POST','/c-existing/rewind'+query)
    bind('DELETE','/c-existing')
    bind('DELETE','/missing')
    messages = [dict(id=1, role='USER', content='  需求\n```ts\nconst a=1;', sequenceNo=1,
                     citations=[], attachments=[dict(id='att-1',filename='需求.txt',contentType='text/plain',size=12)]),
                dict(id=2,role='ASSISTANT',content='答复 [1]',sequenceNo=2,
                     attachments=[],citations=[dict(index=1,documentId='deleted-doc',documentTitle='说明',filename='a.md',chunkIndex=2,content='不导出片段原文')]),
                dict(id=3,role='TOOL',content='result',sequenceNo=3,citations=[],attachments=[])]
    exports=[]
    for title in ['', ' \u3000', '排课讨论', 'bad\\/:*?"<>|\r\nname',
                  '标'*80, 'a'*59+'😀'*2, '*~ 标题']:
        for zone in ['Asia/Shanghai','UTC','America/New_York']:
            exports.append(dict(title=title,zone=zone,messages=messages))
    exports.append(dict(title='空会话',zone='Asia/Shanghai',messages=[]))
    snapshots=[None,'',' \u3000','not-json','null','[]','{}','[null]','[{}]',
               '[{"id":"a","filename":"需求.txt","contentType":"text/plain","size":12}]',
               '[{"ID":"a","Filename":"wrong","SIZE":12}]',
               '[{"id":12,"filename":true,"size":1.9}]',
               '[{"size":"12"}]','[{"size":" 12 "}]','[{"size":""}]',
               '[{"size":null}]','[{"size":true}]','[{"size":9223372036854775808}]',
               '[{"id":"a"},null]','[{"size":1e3}]','[{"size":"1.9"}]',
               '[{"id":"a"}] {}']
    queries=[dict(keyword=k,page=p,size=s) for k in ['', ' \u3000', 'a_b%\\', ' İ ΣΟΣ ']
             for p,s in [(0,20),(-1,0),(2147483647,1000)]]
    titles=[dict(title=t,content=c,count=n) for t in ['新会话','自定义']
            for c,n in [('  第一条\n\t消息  ',0),('中'*40,0),('😀'*20,0),('x'*29+'😀',0),('new',1),(' \u3000',0)]]
    return dict(bindings=bindings,exports=exports,snapshots=snapshots,queries=queries,titles=titles)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write',action='store_true');args=parser.parse_args()
    env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','JAVA_HOME'}}
    baseline=json.loads((ROOT/'specs/033-go-rewrite/inventory.json').read_text())['baseline_commit']
    report=ROOT/'backend/target/surefire-reports/TEST-com.personalos.chat.service.ChatSessionServiceTest.xml'
    cp=next(p.attrib['value'] for p in ET.parse(report).find('properties') if p.attrib.get('name')=='java.class.path')
    cp=os.pathsep.join([str(ROOT/'backend/target/classes'),*[p for p in cp.split(os.pathsep) if p.endswith('.jar')]])
    inputs=cases();hashes={}
    with tempfile.TemporaryDirectory(prefix='personalos-chat-storage-oracle-') as folder:
        files=[]
        for source in SOURCES:
            content=subprocess.check_output(['git','show',baseline+':'+source],cwd=ROOT,env=env)
            path=Path(folder)/source.removeprefix(PREFIX);path.parent.mkdir(parents=True,exist_ok=True)
            path.write_bytes(content);files.append(str(path));hashes[source]=hashlib.sha256(content).hexdigest()
        subprocess.run(['javac','-parameters','-encoding','UTF-8','-proc:none','-sourcepath','','-cp',cp,'-d',folder,
                        *files,str(ROOT/'scripts/migration/ChatStorageOracle.java')],check=True,env=env,timeout=90)
        output=subprocess.run(['java','-cp',folder+os.pathsep+cp,'com.personalos.chat.ChatStorageOracle'],
                              input=json.dumps(inputs),text=True,capture_output=True,check=True,env=env,timeout=90).stdout
    expected=json.loads(next(line.removeprefix('ORACLE ') for line in output.split('\n') if line.startswith('ORACLE ')))
    fixture=json.dumps(dict(baseline_commit=baseline,sources_sha256=hashes,inputs=inputs,expected=expected),ensure_ascii=True,indent=2)+'\n'
    if args.write:TARGET.parent.mkdir(parents=True,exist_ok=True);TARGET.write_text(fixture)
    elif TARGET.read_text()!=fixture:raise SystemExit('会话存储原Java期望发生变化，请审查后--write')
    print('原Java会话存储差分：'+', '.join(k+'='+str(len(v)) for k,v in inputs.items()))


if __name__=='__main__':main()
