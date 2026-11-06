"""只对035临时库启动Java/Go临时服务，用同一前端验证；清理自己创建的进程。"""
from pathlib import Path
import os
import socket
import subprocess
import tempfile
import time
import urllib.request
ROOT=Path(__file__).resolve().parents[2]
def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));return sock.getsockname()[1]
def check(command,runtime,temp,port,folder,label):
    folder.mkdir(parents=True,exist_ok=True)
    origin=f'http://127.0.0.1:{port}'
    with open(folder/'backend.log','w') as log:
        process=subprocess.Popen(command,cwd=temp,env=runtime,stdout=log,stderr=subprocess.STDOUT)
        try:
            deadline=time.monotonic()+60
            opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
            while True:
                if process.poll() is not None:raise RuntimeError(f'035临时{label}启动失败，见{folder.name}/backend.log')
                try:
                    with opener.open(origin+'/api/v1/projects',timeout=1) as r:
                        if r.status==200:break
                except Exception:pass
                if time.monotonic()>deadline:raise TimeoutError(f'035临时{label}启动超时')
                time.sleep(.3)
            subprocess.run(['node',str(ROOT/'scripts/automation/browser_checks.mjs'),origin,str(folder),temp,label],env=runtime,cwd=ROOT,check=True,timeout=150)
        finally:
            process.terminate()
            try:process.wait(timeout=20)
            except subprocess.TimeoutExpired:process.kill();process.wait(timeout=5)
def run(env):
    folder=ROOT/'.cache/automation-ui'
    with tempfile.TemporaryDirectory(prefix='personalos-workspace-ui-') as temp:
        cpfile=Path(temp)/'classpath.txt'
        subprocess.run([str(ROOT/'backend/mvnw'),'-f',str(ROOT/'backend/pom.xml'),'-B','--no-transfer-progress','dependency:build-classpath',
            '-DincludeScope=runtime','-Dmdep.outputFile='+str(cpfile)],env=env,check=True,stdout=subprocess.DEVNULL)
        basic={key:env[key] for key in ('PATH','HOME','JAVA_HOME','TMPDIR') if key in env}
        runtime={**basic,'SPRING_DATASOURCE_URL':f"jdbc:postgresql://127.0.0.1:{env['GO_TEST_POSTGRES_PORT']}/personalos_go_test",
            'SPRING_DATASOURCE_USERNAME':'go_fixture','SPRING_DATASOURCE_PASSWORD':env['GO_TEST_POSTGRES_PASSWORD']}
        port=free_port()
        check([str(Path(env['JAVA_HOME'])/'bin/java'),'-Dapp.dotenv.enabled=false','-cp',str(ROOT/'backend/target/classes')+os.pathsep+cpfile.read_text().strip(),
            'com.personalos.PersonalOsApplication','--spring.flyway.locations=filesystem:'+str(ROOT/'backend/src/main/resources/db/migration'),
            '--server.address=127.0.0.1',f'--server.port={port}','--spring.ai.model.chat=none','--app.calendar.scan-enabled=false',
            '--app.knowledge.jobs.enabled=false','--app.knowledge.rag.ingestion-review-enabled=false'],runtime,temp,port,folder/'java','JAVA')
        binary=Path(temp)/'go-backend'
        subprocess.run(['go','build','-o',str(binary),'./cmd/personalos'],cwd=ROOT/'backend-go',env=env,check=True)
        port=free_port()
        runtime={**basic,'GO_DOTENV_ENABLED':'false','APP_DOTENV_ENABLED':'false','SERVER_PORT':str(port),
            'POSTGRES_HOST':'127.0.0.1','POSTGRES_PORT':env['GO_TEST_POSTGRES_PORT'],'POSTGRES_DB':'personalos_go_test',
            'POSTGRES_USER':'go_fixture','POSTGRES_PASSWORD':env['GO_TEST_POSTGRES_PASSWORD'],'GO_POSTGRES_SSL_MODE':'disable',
            'SPRING_AI_MODEL_CHAT':'none','APP_RUNTIME_ROLE':'combined','APP_CALENDAR_SCAN_ENABLED':'false',
            'APP_KNOWLEDGE_JOBS_ENABLED':'false','APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED':'false','APP_RATE_LIMIT_ENABLED':'false'}
        check([str(binary),'--foundation','--p8','--migrations',str(ROOT/'backend/src/main/resources/db/migration')],runtime,temp,port,folder/'go','GO')
