#!/usr/bin/env python3
"""在自身创建的临时 Redis 中验证 Go；不读 .env、不连现有服务或持久卷。"""

import argparse
import json
import os
from pathlib import Path
import secrets
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "redis@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232"
PACKAGES = ["redis", "auth"]


def capture(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, check=True,
                          timeout=120, **kwargs).stdout.strip()


def run_package(image, package):
    password = secrets.token_urlsafe(24)
    docker_env = dict(os.environ, GO_TEST_REDIS_PASSWORD=password)
    container = None
    try:
        print(f"启动 {package} 专用 Redis（tmpfs、随机回环端口）", flush=True)
        container = capture([
            "docker", "run", "--detach", "--rm", "--name", "personalos-go-redis-" + uuid.uuid4().hex[:12],
            "--label", "personalos.go-redis-test=true", "--memory", "128m",
            "--tmpfs", "/data:rw,nosuid,size=64m", "--publish", "127.0.0.1::6379",
            "--env", "GO_TEST_REDIS_PASSWORD", image, "sh", "-c",
            'exec redis-server --save "" --appendonly no --requirepass "$GO_TEST_REDIS_PASSWORD"',
        ], env=docker_env)
        if not container or not all(char in "0123456789abcdef" for char in container):
            raise RuntimeError("未返回可验证的独立 Redis 容器 ID")
        deadline = time.monotonic() + 30
        while True:
            result = subprocess.run(["docker", "exec", container, "sh", "-c",
                                     'REDISCLI_AUTH="$GO_TEST_REDIS_PASSWORD" redis-cli ping'],
                                    capture_output=True, timeout=5)
            if result.returncode == 0 and result.stdout.strip() == b"PONG":
                break
            if time.monotonic() >= deadline:
                raise TimeoutError("独立 Redis 启动超时")
            time.sleep(0.2)
        info = json.loads(capture(["docker", "inspect", container]))[0]
        bindings = info["NetworkSettings"]["Ports"]["6379/tcp"]
        if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
            raise RuntimeError("Redis 测试必须只绑定随机回环端口")
        if any(mount["Type"] == "volume" for mount in info["Mounts"]):
            raise RuntimeError("Redis 测试不允许持久卷")
        port = bindings[0]["HostPort"]
        child_env = {key: value for key, value in os.environ.items() if key in {
            "PATH", "HOME", "TMPDIR", "CC", "CXX", "CGO_ENABLED", "SSL_CERT_FILE", "SSL_CERT_DIR",
        }}
        child_env.update({
            "GOCACHE": "/private/tmp/personal-ai-os-go-build", "GOPATH": "/private/tmp/personal-ai-os-go",
            "GOTOOLCHAIN": "local", "GOPROXY": "off", "GO_TEST_REDIS": "disposable",
            "GO_TEST_REDIS_URI": f"redis://:{password}@127.0.0.1:{port}/0",
        })
        subprocess.run(["go", "test", "-race", "-count=1", "-v", "-timeout=2m", "-tags=integration",
                        "./internal/platform/" + package], cwd=ROOT / "backend-go", env=child_env,
                       check=True, timeout=180)
    finally:
        if container:
            subprocess.run(["docker", "rm", "--force", container], check=True, capture_output=True, timeout=30)
            print("已清理本次专用 Redis 容器", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default=IMAGE)
    parser.add_argument("--package", choices=PACKAGES, action="append")
    args = parser.parse_args()
    for package in args.package or PACKAGES:
        run_package(args.image, package)


if __name__ == "__main__":
    main()
