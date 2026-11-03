#!/usr/bin/env python3
"""隔离环境启动真实 Go 进程，验证五种角色的 HTTP 与 SIGTERM 生命周期。

只向子进程传入最小环境，禁止 .env，不沿用任何数据库/认证/模型配置。
不启动 Worker，不连接现有数据，端口由操作系统分配后交给子进程。
"""

import argparse
import http.client
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import time


def request(port, method, path):
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        connection.request(method, path, headers={"X-Request-Id": "process-smoke-123"})
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


def check_role(binary, role, directory):
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        port = reservation.getsockname()[1]
    environment = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "GO_DOTENV_ENABLED": "false", "APP_DOTENV_ENABLED": "false",
        "SERVER_PORT": str(port), "APP_RUNTIME_ROLE": role,
    }
    child = subprocess.Popen([str(binary)], cwd=directory, env=environment,
                             stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        deadline = time.monotonic() + 10
        while True:
            if child.poll() is not None:
                raise RuntimeError(f"{role}: 进程在就绪前退出，code={child.returncode}")
            try:
                status, headers, data = request(port, "GET", "/livez")
                break
            except (OSError, http.client.HTTPException):
                if time.monotonic() >= deadline:
                    raise TimeoutError(f"{role}: HTTP 启动超时")
                time.sleep(0.05)
        diagnostic = json.loads(data)
        assert status == 200 and diagnostic == {
            "process": "UP", "phase": "bootstrap", "ready": False, "role": role,
        }, (role, "livez contract")
        assert headers.get("X-Request-Id") == "process-smoke-123", (role, "request id")
        status, _, data = request(port, "GET", "/readyz")
        assert status == 503 and json.loads(data)["ready"] is False, (role, "readiness")
        for path, expected in (("/livez", 200), ("/readyz", 503)):
            status, _, data = request(port, "HEAD", path)
            assert status == expected and not data, (role, "HEAD", path)
            status, headers, data = request(port, "POST", path)
            assert status == 405 and headers.get("Allow") == "GET, HEAD", (role, "405", path)
            assert json.loads(data)["code"] == 40005, (role, "405 envelope")
        status, _, data = request(port, "GET", "/api/v1/tasks")
        assert status == 404, (role, "unimplemented API must not succeed")
        if role in ("index", "review", "delivery"):
            assert not data, (role, "worker business isolation")
        else:
            assert json.loads(data)["code"] == 40004, (role, "API 404 envelope")
        child.send_signal(signal.SIGTERM)
        _, error_output = child.communicate(timeout=10)
        assert child.returncode == 0, (role, "SIGTERM exit", child.returncode, error_output)
        with socket.socket() as probe:
            probe.settimeout(0.5)
            assert probe.connect_ex(("127.0.0.1", port)) != 0, (role, "listener leaked")
        print(f"PASS {role}: HTTP/HEAD/405/404、未就绪与 SIGTERM 退出")
    finally:
        if child.poll() is None:
            child.terminate()
            try:
                child.communicate(timeout=10)
            except subprocess.TimeoutExpired:
                child.kill()
                child.communicate(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True, help="显式指定已经构建的 personalos 二进制")
    args = parser.parse_args()
    binary = args.binary.resolve(strict=True)
    with tempfile.TemporaryDirectory(prefix="personalos-go-smoke-") as directory:
        for role in ("combined", "api", "index", "review", "delivery"):
            check_role(binary, role, directory)


if __name__ == "__main__":
    main()
