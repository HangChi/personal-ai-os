"""在035临时数据库上启动临时Java服务，检查真实前端；清理本次进程。"""
from pathlib import Path
import os
import socket
import subprocess
import tempfile
import time
import urllib.request
ROOT=Path(__file__).resolve().parents[2]
def run(env):
    folder=ROOT/'.cache/automation-ui';folder.mkdir(parents=True,exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='personalos-workspace-ui-') as temp:
        cpfile=Path(temp)/'classpath.txt'
        subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','--no-transfer-progress','dependency:build-classpath',
            '-DincludeScope=runtime','-Dmdep.outputFile='+str(cpfile)],env=env,check=True,stdout=subprocess.DEVNULL)
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        origin=f'http://127.0.0.1:{port}'
        runtime={key:env[key] for key in ('PATH','HOME','JAVA_HOME','TMPDIR') if key in env}
        runtime.update(SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{env['GO_TEST_POSTGRES_PORT']}/personalos_go_test",
            SPRING_DATASOURCE_USERNAME='go_fixture',SPRING_DATASOURCE_PASSWORD=env['GO_TEST_POSTGRES_PASSWORD'])
        log=open(folder/'backend.log','w')
        process=subprocess.Popen([str(Path(env['JAVA_HOME'])/'bin/java'),'-Dapp.dotenv.enabled=false','-cp',str(ROOT/'backend/target/classes')+os.pathsep+cpfile.read_text().strip(),
            'com.personalos.PersonalOsApplication','--server.address=127.0.0.1',f'--server.port={port}','--spring.ai.model.chat=none',
            '--app.calendar.scan-enabled=false','--app.knowledge.jobs.enabled=false','--app.knowledge.rag.ingestion-review-enabled=false'],cwd=temp,env=runtime,stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+60
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            while True:
                if process.poll() is not None:raise RuntimeError('035临时Java服务启动失败，见.cache/automation-ui/backend.log')
                try:
                    with opener.open(origin+'/api/v1/projects',timeout=1) as r:
                        if r.status==200:break
                except Exception:pass
                if time.monotonic()>deadline:raise TimeoutError('035临时Java启动超时')
                time.sleep(.3)
            subprocess.run(['node',str(ROOT/'scripts/automation/browser_checks.mjs'),origin,str(folder),temp],env=runtime,cwd=ROOT,check=True,timeout=120)
        finally:
            process.terminate()
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
            log.close()
