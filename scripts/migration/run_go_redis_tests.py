#!/usr/bin/env python3
"""在自身创建的临时 Redis 中验证 Go；不读 .env、不连现有服务或持久卷。"""

import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import tempfile
import time
import uuid

from disposable_tls import create_tls_fixture as tls_fixture

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "redis@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232"
PACKAGES = ["redis", "auth", "ratelimit", "app", "command", "model"]


def capture(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, check=True,
                          timeout=120, **kwargs).stdout.strip()


def run_package(image, package, tls_mode=False, backup=False):
    password = secrets.token_urlsafe(24)
    docker_env = dict(os.environ, GO_TEST_REDIS_PASSWORD=password)
    container = None
    certificates = tempfile.TemporaryDirectory(prefix="personalos-go-redis-tls-") if tls_mode else None
    try:
        print(f"启动 {package} 专用 Redis{' TLS' if tls_mode else ''}（tmpfs、随机回环端口）", flush=True)
        mounts = []
        root_pem = ""
        server_options = ""
        if tls_mode:
            folder, root_pem = tls_fixture(certificates.name)
            mounts = ["--mount", f"type=bind,source={folder},target=/tls,readonly"]
            server_options = (" --port 0 --tls-port 6379 --tls-cert-file /tls/server.crt"
                              " --tls-key-file /tls/server.key --tls-ca-cert-file /tls/ca.crt --tls-auth-clients no")
        container = capture([
            "docker", "run", "--detach", "--rm", "--name", "personalos-go-redis-" + uuid.uuid4().hex[:12],
            "--label", "personalos.go-redis-test=true", "--memory", "128m",
            "--tmpfs", "/data:rw,nosuid,size=64m", "--publish", "127.0.0.1::6379",
            "--env", "GO_TEST_REDIS_PASSWORD", *mounts, image, "sh", "-c",
            'exec redis-server --save "" --appendonly no --requirepass "$GO_TEST_REDIS_PASSWORD"' + server_options,
        ], env=docker_env)
        if not container or not all(char in "0123456789abcdef" for char in container):
            raise RuntimeError("未返回可验证的独立 Redis 容器 ID")
        deadline = time.monotonic() + 30
        while True:
            cli_options = " --tls --cacert /tls/ca.crt --sni localhost" if tls_mode else ""
            result = subprocess.run(["docker", "exec", container, "sh", "-c",
                                     'REDISCLI_AUTH="$GO_TEST_REDIS_PASSWORD" redis-cli' + cli_options + ' ping'],
                                    capture_output=True, timeout=5)
            if result.returncode == 0 and result.stdout.strip() == b"PONG":
                break
            if time.monotonic() >= deadline:
                logs = capture(["docker", "logs", container])
                detail = result.stderr.decode(errors="replace") + result.stdout.decode(errors="replace")
                raise TimeoutError("独立 Redis 启动超时: " + (logs + "\n" + detail).replace(password, "[redacted]"))
            time.sleep(0.2)
        info = json.loads(capture(["docker", "inspect", container]))[0]
        bindings = info["NetworkSettings"]["Ports"]["6379/tcp"]
        if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
            raise RuntimeError("Redis 测试必须只绑定随机回环端口")
        if any(mount["Type"] == "volume" for mount in info["Mounts"]):
            raise RuntimeError("Redis 测试不允许持久卷")
        port = bindings[0]["HostPort"]
        # 只为本次独立客户端包建立精确键空间/命令权限，不开放管理命令。
        acl_uris = {}
        if package in {"redis", "app", "command", "model"}:
            session_namespace = "fixture:app-sessions" if package in {"app", "command"} else "fixture:acl-session"
            rate_namespace = "fixture:app-rate" if package in {"app", "command"} else "fixture:acl-rate"
            rate_key = "personalos:rate:" + hashlib.sha256(rate_namespace.encode()).hexdigest()
            cache_key = "personalos:embedding:" + hashlib.sha256(b"fixture:embedding").hexdigest() + ":*"
            for name, key, commands in [
                ("go_session", session_namespace + ":go:v1:session:*",
                 ["get", "pttl", "pexpire", "exists", "set", "del"]),
                ("go_rate", rate_key, ["hmget", "time", "hset", "pexpire"]),
                ("go_embedding", cache_key, ["get", "psetex"]),
            ]:
                acl_password = secrets.token_urlsafe(24)
                result = capture(["docker", "exec", container, "sh", "-c",
                                  'REDISCLI_AUTH="$GO_TEST_REDIS_PASSWORD" exec redis-cli' + cli_options + ' "$@"',
                                  "redis-acl", "ACL", "SETUSER", name, "reset", "on", ">" + acl_password,
                                  "~" + key, "+hello", "+auth", "+ping", "+eval",
                                  *["+" + command for command in commands]])
                if result != "OK":
                    raise RuntimeError("专用 Redis ACL 创建失败")
                acl_uris["GO_TEST_REDIS_" + name.removeprefix("go_").upper() + "_URI"] = (
                    f"{'rediss' if tls_mode else 'redis'}://{name}:{acl_password}@127.0.0.1:{port}/0")
        child_env = {key: value for key, value in os.environ.items() if key in {
            "PATH", "HOME", "TMPDIR", "CC", "CXX", "CGO_ENABLED", "SSL_CERT_FILE", "SSL_CERT_DIR",
        }}
        child_env.update({
            "GOCACHE": "/private/tmp/personal-ai-os-go-build", "GOPATH": "/private/tmp/personal-ai-os-go",
            "GOTOOLCHAIN": "local", "GOPROXY": "off", "GO_TEST_REDIS": "disposable",
            "GO_TEST_REDIS_URI": f"{'rediss' if tls_mode else 'redis'}://:{password}@127.0.0.1:{port}/0",
            "GO_TEST_REDIS_CA_PEM": root_pem,
        })
        child_env.update(acl_uris)
        if package in {"app", "command"}:
            # 外层持有自己的Redis，内层仅创建/清理自己的PostgreSQL；失败逐层finally清理。
            from run_go_postgres_tests import IMAGE as POSTGRES_IMAGE, run_package as run_postgres
            run_postgres(POSTGRES_IMAGE, package, tls_mode=tls_mode, flyway=True, backup=backup and package == "app", redis_env=child_env)
        else:
            subprocess.run(["go", "test", "-race", "-count=1", "-v", "-timeout=2m", "-tags=integration",
                            ("./internal/business/model" if package == "model" else "./internal/platform/" + package)], cwd=ROOT / "backend-go", env=child_env,
                           check=True, timeout=180)
    finally:
        try:
            if container:
                subprocess.run(["docker", "rm", "--force", container], check=True, capture_output=True, timeout=30)
                print("已清理本次专用 Redis 容器", flush=True)
        finally:
            if certificates:
                certificates.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default=IMAGE)
    parser.add_argument("--package", choices=PACKAGES, action="append")
    parser.add_argument("--backup", action="store_true", help="app组合测试同时验证本次合成pg_dump恢复库的基础启动")
    parser.add_argument("--tls", action="store_true", help="新建临时 CA/证书，在真实 rediss 服务运行同一组测试")
    args = parser.parse_args()
    for package in args.package or PACKAGES:
        run_package(args.image, package, args.tls, args.backup)


if __name__ == "__main__":
    main()
