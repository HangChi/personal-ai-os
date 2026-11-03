#!/usr/bin/env python3
"""在独立、无持久卷的 PostgreSQL/pgvector 容器中运行 Go 集成测试。

从不读取 .env、不连接现有数据库、不操作 Compose；finally 只清理本脚本创建的容器 ID。
镜像取自 Java Compose 的 pg17 系列，固定为已验证的本地镜像摘要。
"""

import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d"


def capture(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, check=True, timeout=120, **kwargs).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default=IMAGE, help="仅用于显式测试其他 PostgreSQL/pgvector 镜像")
    args = parser.parse_args()
    password = secrets.token_urlsafe(24)
    docker_env = dict(os.environ, POSTGRES_PASSWORD=password)
    container = None
    try:
        print("启动独立 PostgreSQL/pgvector 容器（无持久卷、随机回环端口）", flush=True)
        container = capture([
            "docker", "run", "--detach", "--rm", "--name", "personalos-go-test-" + uuid.uuid4().hex[:12],
            "--label", "personalos.go-test=true", "--memory", "512m",
            "--tmpfs", "/var/lib/postgresql/data:rw,nosuid,size=384m",
            "--publish", "127.0.0.1::5432", "--env", "POSTGRES_PASSWORD",
            "--env", "POSTGRES_USER=go_fixture", "--env", "POSTGRES_DB=personalos_go_test", args.image,
        ], env=docker_env)
        if not container or not all(char in "0123456789abcdef" for char in container):
            raise RuntimeError("Docker 未返回可验证的独立容器 ID")
        deadline = time.monotonic() + 60
        while True:
            result = subprocess.run(["docker", "exec", container, "pg_isready", "-U", "go_fixture", "-d", "personalos_go_test"], capture_output=True, timeout=10)
            if result.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise TimeoutError("独立 PostgreSQL 启动超时")
            time.sleep(0.2)
        info = json.loads(capture(["docker", "inspect", container]))[0]
        bindings = info["NetworkSettings"]["Ports"]["5432/tcp"]
        if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
            raise RuntimeError("测试数据库必须仅绑定随机回环端口")
        port = bindings[0]["HostPort"]
        # 最小 Go 子进程环境，不继承任何 PG* 或现有应用配置。
        go_env = {key: value for key, value in os.environ.items() if key in {
            "PATH", "HOME", "TMPDIR", "CC", "CXX", "CGO_ENABLED", "SSL_CERT_FILE", "SSL_CERT_DIR",
        }}
        go_env.update({
            "GOCACHE": "/private/tmp/personal-ai-os-go-build", "GOPATH": "/private/tmp/personal-ai-os-go",
            "GOTOOLCHAIN": "local", "GOPROXY": "off", "GO_TEST_POSTGRES": "disposable",
            "GO_TEST_POSTGRES_HOST": "127.0.0.1", "GO_TEST_POSTGRES_PORT": port,
            "GO_TEST_POSTGRES_USER": "go_fixture", "GO_TEST_POSTGRES_DB": "personalos_go_test",
            "GO_TEST_POSTGRES_PASSWORD": password,
        })
        print("运行驱动/事务/共享限流与全部 42 个原 SQL 隔离重放", flush=True)
        subprocess.run(["go", "test", "-race", "-count=1", "-v", "-timeout=2m", "-tags=integration",
                        "./internal/platform/database", "./internal/platform/ratelimit", "./internal/platform/migration"],
                       cwd=ROOT / "backend-go", env=go_env, check=True, timeout=180)
    finally:
        if container:
            subprocess.run(["docker", "rm", "--force", container], check=True, capture_output=True, timeout=30)
            print("已清理本次独立测试容器", flush=True)


if __name__ == "__main__":
    main()
