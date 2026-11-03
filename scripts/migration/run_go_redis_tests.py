#!/usr/bin/env python3
"""在自身创建的临时 Redis 中验证 Go；不读 .env、不连现有服务或持久卷。"""

import argparse
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import uuid

ROOT = Path(__file__).resolve().parents[2]
IMAGE = "redis@sha256:987c376c727652f99625c7d205a1cba3cb2c53b92b0b62aade2bd48ee1593232"
PACKAGES = ["redis", "auth", "ratelimit"]


def capture(command, **kwargs):
    return subprocess.run(command, text=True, capture_output=True, check=True,
                          timeout=120, **kwargs).stdout.strip()


def tls_fixture(directory):
    """只生成本次测试 CA/服务端证书；CA 私钥不挂载入容器。"""
    folder = Path(directory)
    server = folder / "server"
    server.mkdir(mode=0o700)
    configuration = folder / "ca.cnf"
    configuration.write_text("""[req]
distinguished_name=dn
x509_extensions=v3_ca
prompt=no
[dn]
CN=personalos-go-test-ca
[v3_ca]
basicConstraints=critical,CA:true
keyUsage=critical,keyCertSign,cRLSign
subjectKeyIdentifier=hash
""")
    environment = {"PATH": os.environ.get("PATH", "/usr/bin:/bin")}
    capture(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-sha256", "-days", "2",
             "-config", str(configuration), "-keyout", str(folder / "ca.key"),
             "-out", str(folder / "ca.crt")], env=environment)
    server_configuration = folder / "server.cnf"
    server_configuration.write_text("[req]\ndistinguished_name=dn\nprompt=no\n[dn]\nCN=localhost\n")
    capture(["openssl", "req", "-new", "-newkey", "rsa:2048", "-nodes", "-sha256",
             "-config", str(server_configuration),
             "-keyout", str(server / "server.key"), "-out", str(folder / "server.csr")], env=environment)
    extensions = folder / "server.ext"
    extensions.write_text("basicConstraints=critical,CA:false\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                          "extendedKeyUsage=serverAuth\nsubjectKeyIdentifier=hash\nauthorityKeyIdentifier=keyid,issuer\n"
                          "subjectAltName=DNS:localhost,IP:127.0.0.1\n")
    capture(["openssl", "x509", "-req", "-in", str(folder / "server.csr"), "-CA", str(folder / "ca.crt"),
             "-CAkey", str(folder / "ca.key"), "-CAcreateserial", "-days", "2", "-sha256",
             "-extfile", str(extensions), "-out", str(server / "server.crt")], env=environment)
    shutil.copyfile(folder / "ca.crt", server / "ca.crt")
    capture(["openssl", "verify", "-CAfile", str(folder / "ca.crt"), str(server / "server.crt")], env=environment)
    return server, (folder / "ca.crt").read_text()


def run_package(image, package, tls_mode=False):
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
        child_env = {key: value for key, value in os.environ.items() if key in {
            "PATH", "HOME", "TMPDIR", "CC", "CXX", "CGO_ENABLED", "SSL_CERT_FILE", "SSL_CERT_DIR",
        }}
        child_env.update({
            "GOCACHE": "/private/tmp/personal-ai-os-go-build", "GOPATH": "/private/tmp/personal-ai-os-go",
            "GOTOOLCHAIN": "local", "GOPROXY": "off", "GO_TEST_REDIS": "disposable",
            "GO_TEST_REDIS_URI": f"{'rediss' if tls_mode else 'redis'}://:{password}@127.0.0.1:{port}/0",
            "GO_TEST_REDIS_CA_PEM": root_pem,
        })
        subprocess.run(["go", "test", "-race", "-count=1", "-v", "-timeout=2m", "-tags=integration",
                        "./internal/platform/" + package], cwd=ROOT / "backend-go", env=child_env,
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
    parser.add_argument("--tls", action="store_true", help="新建临时 CA/证书，在真实 rediss 服务运行同一组测试")
    args = parser.parse_args()
    for package in args.package or PACKAGES:
        run_package(args.image, package, args.tls)


if __name__ == "__main__":
    main()
