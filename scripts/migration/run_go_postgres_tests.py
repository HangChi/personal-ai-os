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
import tempfile
import time
import uuid

from disposable_tls import create_tls_fixture

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "pgvector/pgvector@sha256:ac08538c6f8b9904c33c8224c5e5706dbe760aca29db1d096972b4052c22a75d"


def capture(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, check=True, timeout=120, **kwargs).stdout.strip()


BUSINESS_PACKAGES = {"idempotency", "confirmation", "task", "model", "chat", "calendar", "knowledge"}
PACKAGES = ["database", "ratelimit", "migration", "coordination", "modelgate", "health", *sorted(BUSINESS_PACKAGES), "app", "command"]


def run_package(image, package, tls_mode=False, flyway=False, backup=False, baseline=False, redis_env=None, test_pattern=None):
    password = secrets.token_urlsafe(24)
    docker_env = dict(os.environ, POSTGRES_PASSWORD=password)
    container = None
    certificates = tempfile.TemporaryDirectory(prefix="personalos-go-postgres-tls-") if tls_mode else None
    try:
        print("启动 %s 专用 PostgreSQL/pgvector%s 容器（无持久卷、随机回环端口）" % (package, " TLS" if tls_mode else ""), flush=True)
        mounts, command = [], []
        root_cert = ""
        if tls_mode:
            folder, _ = create_tls_fixture(certificates.name)
            root_cert = str(folder / "ca.crt")
            mounts = ["--mount", f"type=bind,source={folder},target=/tls-source,readonly"]
            command = ["sh", "-c",
                       "mkdir -p /tls && cp /tls-source/* /tls/ && chown postgres:postgres /tls/* && "
                       "chmod 600 /tls/server.key && exec docker-entrypoint.sh postgres -c ssl=on "
                       "-c ssl_cert_file=/tls/server.crt -c ssl_key_file=/tls/server.key -c ssl_ca_file=/tls/ca.crt"]
        container = capture([
            "docker", "run", "--detach", "--rm", "--name", "personalos-go-test-" + uuid.uuid4().hex[:12],
            "--label", "personalos.go-test=true", "--memory", "512m",
            "--tmpfs", "/var/lib/postgresql/data:rw,nosuid,size=384m",
            "--publish", "127.0.0.1::5432", "--env", "POSTGRES_PASSWORD",
            "--env", "POSTGRES_USER=go_fixture", "--env", "POSTGRES_DB=personalos_go_test", *mounts, image, *command,
        ], env=docker_env)
        if not container or not all(char in "0123456789abcdef" for char in container):
            raise RuntimeError("Docker 未返回可验证的独立容器 ID")
        deadline = time.monotonic() + 60
        while True:
            result = subprocess.run(["docker", "exec", container, "pg_isready", "-h", "127.0.0.1", "-U", "go_fixture", "-d", "personalos_go_test"], capture_output=True, timeout=10)
            if result.returncode == 0:
                break
            if time.monotonic() >= deadline:
                raise TimeoutError("独立 PostgreSQL 启动超时: " + capture(["docker", "logs", container]).replace(password, "[redacted]"))
            time.sleep(0.2)
        if tls_mode:
            # 只修改本脚本创建的临时集群，拒绝明文并保留全部既有认证规则。
            capture(["docker", "exec", "--user", "postgres", container, "sh", "-c",
                     "{ printf 'hostnossl all all 0.0.0.0/0 reject\nhostnossl all all ::/0 reject\n'; "
                     'cat "$PGDATA/pg_hba.conf"; } > "$PGDATA/pg_hba.conf.go" && '
                     'mv "$PGDATA/pg_hba.conf.go" "$PGDATA/pg_hba.conf" && pg_ctl reload -D "$PGDATA"'])
        info = json.loads(capture(["docker", "inspect", container]))[0]
        bindings = info["NetworkSettings"]["Ports"]["5432/tcp"]
        if len(bindings) != 1 or bindings[0]["HostIp"] != "127.0.0.1":
            raise RuntimeError("测试数据库必须仅绑定随机回环端口")
        port = bindings[0]["HostPort"]
        # 最小 Go 子进程环境，不继承任何 PG* 或现有应用配置。
        go_env = {key: value for key, value in os.environ.items() if key in {
            "PATH", "HOME", "TMPDIR", "CC", "CXX", "CGO_ENABLED", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "GO_TEST_REMINDER_RABBIT_URI", "GO_TEST_REMINDER_BROWSER_SUBSCRIPTION",
            "GO_TEST_KNOWLEDGE_RABBIT_URI", "GO_TEST_KNOWLEDGE_SEARCH_URL",
        }}
        go_env.update({
            "GOCACHE": "/private/tmp/personal-ai-os-go-build", "GOPATH": "/private/tmp/personal-ai-os-go",
            "GOTOOLCHAIN": "local", "GOPROXY": "off", "GO_TEST_POSTGRES": "disposable",
            "GO_TEST_POSTGRES_HOST": "127.0.0.1", "GO_TEST_POSTGRES_PORT": port,
            "GO_TEST_POSTGRES_USER": "go_fixture", "GO_TEST_POSTGRES_DB": "personalos_go_test",
            "GO_TEST_POSTGRES_PASSWORD": password,
            "GO_TEST_POSTGRES_SSL_MODE": "verify-full" if tls_mode else "disable",
            "GO_TEST_POSTGRES_ROOT_CERT": root_cert,
        })
        if redis_env is not None:
            if package not in {"app", "command", "knowledge"} or redis_env.get("GO_TEST_REDIS") != "disposable":
                raise ValueError("组合测试只能接收脚本新建的app专用Redis")
            go_env.update({key: redis_env[key] for key in {
                "GO_TEST_REDIS", "GO_TEST_REDIS_URI", "GO_TEST_REDIS_CA_PEM",
                "GO_TEST_REDIS_SESSION_URI", "GO_TEST_REDIS_RATE_URI", "GO_TEST_REDIS_EMBEDDING_URI",
                "GO_TEST_REDIS_CANDIDATE_URI",
            } if key in redis_env})
        if (flyway and package == "migration") or package in BUSINESS_PACKAGES | {"app", "command"}:
            if baseline:
                go_env["GO_TEST_FLYWAY_BASELINE"] = "disposable"
            from java_flyway_fixture import migrate_disposable
            migrate_disposable(go_env)
            go_env["GO_TEST_FLYWAY"] = "disposable"
            if backup:
                from disposable_postgres_backup import verify_backup_fixture
                verify_backup_fixture(container)
                go_env["GO_TEST_BACKUP"] = "disposable"
        print("运行 %s 集成测试" % package, flush=True)
        target = ("./cmd/personalos" if package == "command" else "./internal/app" if package == "app"
                  else "./internal/business/" + package if package in BUSINESS_PACKAGES else "./internal/platform/" + package)
        test_filter = ["-run", test_pattern] if test_pattern else []
        subprocess.run(["go", "test", "-race", "-count=1", "-v", "-timeout=2m", "-tags=integration", *test_filter, target],
                       cwd=ROOT / "backend-go", env=go_env, check=True, timeout=180)
        if package == "command":
            from smoke_go_foundation import run as smoke_foundation
            with tempfile.TemporaryDirectory(prefix="personalos-go-foundation-build-") as directory:
                binary = Path(directory, "personalos")
                subprocess.run(["go", "build", "-race", "-o", str(binary), "./cmd/personalos"],
                               cwd=ROOT / "backend-go", env=go_env, check=True, timeout=120)
                smoke_foundation(binary, go_env)
            active = capture(["docker", "exec", container, "psql", "-U", "go_fixture", "-d", "personalos_go_test", "-Atc",
                              "SELECT count(*) FROM pg_stat_activity WHERE application_name='personalos-go'"])
            if active != "0":
                raise AssertionError("基础进程退出后仍有数据库连接")
    finally:
        try:
            if container:
                subprocess.run(["docker", "rm", "--force", container], check=True, capture_output=True, timeout=30)
                print("已清理本次独立测试容器", flush=True)
        finally:
            if certificates:
                certificates.cleanup()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image", default=IMAGE, help="仅用于显式测试其他 PostgreSQL/pgvector 镜像")
    parser.add_argument("--package", choices=PACKAGES, action="append", help="只测试指定包，可重复；各包独占新容器")
    parser.add_argument("--run", help="将Go测试名过滤表达式传给go test -run；仍使用独立迁移容器")
    parser.add_argument("--tls", action="store_true", help="新建 CA/服务端证书，以 verify-full 重跑并拒绝明文连接")
    parser.add_argument("--flyway", action="store_true", help="migration包先以基线Java Flyway真实执行迁移；app包始终先迁移独立库")
    parser.add_argument("--backup", action="store_true", help="隐含--flyway，只在新建容器填合成数据并备份恢复到另一空库")
    parser.add_argument("--baseline", action="store_true", help="隐含--flyway，另建非空旧schema验证真实baseline-on-migrate")
    args = parser.parse_args()
    for package in args.package or PACKAGES:
        run_package(args.image, package, args.tls, args.flyway or args.backup or args.baseline, args.backup, args.baseline, test_pattern=args.run)


if __name__ == "__main__":
    main()
