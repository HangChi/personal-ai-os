#!/usr/bin/env python3
"""仅使用本次创建、无持久卷的临时库验证035；不读取.env或033冻结证据。"""
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile
import time
import uuid
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/workspace'))
sys.path.insert(0,str(ROOT/'scripts/migration'))
from java_flyway_fixture import dependencies

def run(cmd,**kw):
    return subprocess.run(cmd,check=True,text=True,**kw)

def main():
    import argparse
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--ui",action="store_true");args=parser.parse_args()
    image='pgvector/pgvector:pg17'
    password=secrets.token_urlsafe(24)
    env={**os.environ,'POSTGRES_PASSWORD':password}
    container=None
    try:
        container=subprocess.check_output(['docker','run','-d','--rm','--name','personalos-workspace-test-'+uuid.uuid4().hex[:10],
            '--label','personalos.workspace-test=true','--tmpfs','/var/lib/postgresql/data:rw,nosuid,size=384m','-p','127.0.0.1::5432',
            '-e','POSTGRES_PASSWORD','-e','POSTGRES_USER=go_fixture','-e','POSTGRES_DB=personalos_go_test',image],env=env,text=True).strip()
        if len(container)!=64 or any(c not in '0123456789abcdef' for c in container):raise ValueError('临时容器ID无效')
        deadline=time.monotonic()+60
        while subprocess.run(['docker','exec',container,'pg_isready','-U','go_fixture','-d','personalos_go_test'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode:
            if time.monotonic()>deadline:raise TimeoutError('临时数据库启动超时')
            time.sleep(.2)
        import json
        info=json.loads(subprocess.check_output(['docker','inspect',container],text=True))[0]
        port=info['NetworkSettings']['Ports']['5432/tcp'][0]['HostPort']
        env={**os.environ,'GO_TEST_POSTGRES':'disposable','GO_TEST_FLYWAY':'disposable',
            'GO_TEST_POSTGRES_HOST':'127.0.0.1','GO_TEST_POSTGRES_PORT':port,'GO_TEST_POSTGRES_USER':'go_fixture',
            'GO_TEST_POSTGRES_DB':'personalos_go_test','GO_TEST_POSTGRES_PASSWORD':password,'GO_TEST_POSTGRES_SSL_MODE':'disable'}
        java_home=Path('/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home')
        env['JAVA_HOME']=str(java_home);env['PATH']=str(java_home/'bin')+os.pathsep+env['PATH']
        with tempfile.TemporaryDirectory(prefix='personalos-workspace-java-') as temp:
            _,_,jars=dependencies(Path.home()/'.m2/repository')
            cp=os.pathsep.join(map(str,jars))
            run([str(java_home/'bin/javac'),'-encoding','UTF-8','-cp',cp,'-d',temp,str(ROOT/'scripts/workspace/WorkspaceFlyway.java')],env=env)
            run([str(java_home/'bin/java'),'-cp',temp+os.pathsep+cp,'WorkspaceFlyway',str(ROOT/'backend/src/main/resources/db/migration')],env=env)
        run(['go','test','-race','-count=1','-v','-tags=integration','-p','1','-run','TestCalendarExchange|TestCaptureAtomicVersions|TestReadOnlyWorkflows|TestMaintenanceCenter','./internal/business/workflow','./internal/business/calendar','./internal/business/knowledge','./internal/business/maintenance'],cwd=ROOT/'backend-go',env=env)
        if args.ui:
            from automation_ui import run as check_ui
            check_ui(env)
    finally:
        if container:
            subprocess.run(['docker','rm','-f',container],check=True,stdout=subprocess.DEVNULL)
            print('已清理本次035临时容器',flush=True)
if __name__=='__main__':main()
