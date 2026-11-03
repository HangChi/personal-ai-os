#!/usr/bin/env python3
"""仅在本次创建的无持久卷 MinIO 上验证 P4；不读取 .env 或现有服务。"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import urllib.request
import uuid
ROOT=Path(__file__).resolve().parents[2]
IMAGE='minio/minio@sha256:6d770d7f255cda1f18d841ffc4365cb7e0d237f6af6a15fcdb587480cd7c3b93'
def capture(args,**kw):
    return subprocess.check_output(args,text=True,timeout=60,**kw).strip()
def main():
    container=None
    try:
        secret=secrets.token_urlsafe(24)
        env={k:v for k,v in os.environ.items() if k in {'PATH','HOME','TMPDIR','CC','CXX','CGO_ENABLED'}}
        env.update(MINIO_ROOT_USER='disposable-fixture',MINIO_ROOT_PASSWORD=secret)
        container=capture(['docker','run','--detach','--rm','--pull=never','--name','personalos-go-s3-'+uuid.uuid4().hex[:12],'--label','personalos.go-test=true','--memory','512m','--tmpfs','/data:rw,nosuid,size=256m','--publish','127.0.0.1::9000','--env','MINIO_ROOT_USER','--env','MINIO_ROOT_PASSWORD',IMAGE,'server','/data','--address',':9000'],env=env)
        if len(container)!=64 or any(c not in '0123456789abcdef' for c in container):raise ValueError('无效独立容器 ID')
        ports=json.loads(capture(['docker','inspect',container]))[0]['NetworkSettings']['Ports']['9000/tcp']
        if len(ports)!=1 or ports[0]['HostIp']!='127.0.0.1':raise ValueError('只能使用回环端口')
        endpoint='http://127.0.0.1:'+ports[0]['HostPort']
        deadline=time.monotonic()+45
        while True:
            try:
                with urllib.request.urlopen(endpoint+'/minio/health/ready',timeout=1) as response:
                    if response.status==200:break
            except (OSError,TimeoutError):pass
            if time.monotonic()>deadline:raise TimeoutError('临时 MinIO 未就绪')
            time.sleep(0.2)
        env.update(GO_TEST_S3='disposable',GO_TEST_S3_ENDPOINT=endpoint,GO_TEST_S3_ACCESS=env['MINIO_ROOT_USER'],GO_TEST_S3_SECRET=secret,GOCACHE='/private/tmp/personal-ai-os-go-build',GOPATH='/private/tmp/personal-ai-os-go',GOPROXY='off',GOTOOLCHAIN='local')
        subprocess.run(['go','test','-race','-count=1','-tags=integration','-run','TestS3Disposable','-v','./internal/business/chat'],cwd=ROOT/'backend-go',env=env,check=True,timeout=120)
    finally:
        if container:
            subprocess.run(['docker','rm','--force',container],capture_output=True,timeout=30,check=True)
            print('已清理本次独立 S3 容器')
if __name__=='__main__':main()
