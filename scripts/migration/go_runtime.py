#!/usr/bin/env python3
"""Go 候选进程启停与 Worker 发现；只用显式环境，不读取 Java .env 或 PID。"""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
ROLES = {"combined": (18081, 18091), "api": (18081, 18091),
         "index": (18083, 18093), "review": (18084, 18094), "delivery": (18085, 18095)}
WORKERS = ("index", "review", "delivery")


def private_json(path, value):
    temporary = path.with_suffix(".tmp")
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        with os.fdopen(descriptor, "w") as output:
            json.dump(value, output, ensure_ascii=False, indent=2)
            output.write("\n")
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


@contextmanager
def locked(directory):
    directory = directory.resolve()
    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    if directory.stat().st_uid != os.getuid() or directory.stat().st_mode & 0o077:
        raise ValueError("Go 状态目录必须归当前用户所有且权限为0700")
    descriptor = os.open(directory / ".lock", os.O_WRONLY | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        yield directory
    finally:
        os.close(descriptor)


def identity(pid):
    # 不读取命令参数或环境；仅核对自己记录的PID、启动时间和可执行文件名。
    if not isinstance(pid, int) or pid <= 1:
        return None
    result = subprocess.run(["ps", "-p", str(pid), "-o", "lstart=", "-o", "stat=", "-o", "comm="],
                            text=True, capture_output=True, timeout=5)
    parts = result.stdout.strip().split(maxsplit=6)
    if result.returncode or len(parts) != 7 or parts[5].startswith("Z"):
        return None
    return {"started": " ".join(parts[:5]), "command": parts[6]}


def current(directory, role):
    path = directory / (role + ".json")
    if not path.exists():
        return None
    if path.is_symlink() or path.stat().st_uid != os.getuid() or path.stat().st_mode & 0o077:
        raise ValueError("不安全的 Go 状态文件")
    state = json.loads(path.read_text())
    if state.get("role") != role or state.get("formatVersion") != 1:
        raise ValueError("Go 状态文件格式或角色不匹配")
    return state


def owned(state):
    expected = state.get("identity") if state else None
    return bool(isinstance(expected, dict) and expected.get("started") and expected.get("command")
                and identity(state.get("pid")) == expected)


def environment(source, role):
    required = ("POSTGRES_HOST", "POSTGRES_PORT", "POSTGRES_DB", "POSTGRES_USER", "POSTGRES_PASSWORD")
    if any(not source.get(key) for key in required):
        raise ValueError("显式设置 POSTGRES_HOST/PORT/DB/USER/PASSWORD 后才能启动 Go")
    if source.get("AUTH_ENABLED", "").lower() != "true" or source.get("APP_SECURITY_CSRF_ENABLED", "").lower() != "true":
        raise ValueError("Go 候选进程要求显式开启 AUTH_ENABLED 和 APP_SECURITY_CSRF_ENABLED")
    values = dict(source)
    values.update(GO_DOTENV_ENABLED="false", APP_RUNTIME_ROLE=role,
                  SPRING_PROFILES_ACTIVE="monitoring" + (",worker-" + role if role in WORKERS else ",runtime-api" if role == "api" else ""))
    return values


def health(port):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    try:
        with opener.open("http://127.0.0.1:" + str(port) + "/actuator/health", timeout=2) as response:
            return json.load(response).get("status") == "UP"
    except (OSError, ValueError):
        return False


def publish_targets(directory):
    targets = [{"targets": ["127.0.0.1:" + str(state["managementPort"])], "labels": {"role": role}}
               for role in WORKERS if owned(state := current(directory, role))]
    private_json(directory / "worker-targets.json", targets)


def stop(directory, role):
    state = current(directory, role)
    if owned(state):
        os.kill(state["pid"], signal.SIGTERM)
        deadline = time.monotonic() + 45
        while owned(state) and time.monotonic() < deadline:
            time.sleep(.2)
        if owned(state):
            raise RuntimeError(role + " 优雅关闭超时；保留状态以便核查，未强制终止")
    (directory / (role + ".json")).unlink(missing_ok=True)


def start(directory, role, binary, migrations, source, port=None, management=None):
    if owned(current(directory, role)):
        raise ValueError(role + " 已在运行；未启动重复进程")
    # combined与api默认占同一业务端口，也不能与本脚本的另一入口同时运行。
    other = "api" if role == "combined" else "combined" if role == "api" else None
    if other and owned(current(directory, other)):
        raise ValueError("不能同时运行 combined 和 api")
    binary, migrations = binary.resolve(), migrations.resolve()
    if not binary.is_file() or not os.access(binary, os.X_OK) or not migrations.is_dir():
        raise ValueError("需要已有的可执行 Go 文件和原迁移目录")
    values = environment(source, role)
    port, management = port or ROLES[role][0], management or ROLES[role][1]
    if not 1 <= port <= 65535 or not 1 <= management <= 65535 or port == management:
        raise ValueError("业务和管理端口必须有效且独立")
    for candidate in (port, management):
        with socket.socket() as probe:
            probe.bind(("127.0.0.1", candidate))
    values.update(SERVER_PORT=str(port), MANAGEMENT_SERVER_PORT=str(management))
    digest = hashlib.sha256()
    with binary.open("rb") as source_file:
        for block in iter(lambda: source_file.read(1024 * 1024), b""):
            digest.update(block)
    log = directory / (role + "-" + str(time.time_ns()) + ".log")
    descriptor = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w") as output:
        process = subprocess.Popen([str(binary), "--foundation", "--p8", "--migrations", str(migrations)],
                                   cwd=ROOT, env=values, stdin=subprocess.DEVNULL,
                                   stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    state = {"formatVersion": 1, "role": role, "pid": process.pid, "identity": identity(process.pid),
             "binary": str(binary), "sha256": digest.hexdigest(),
             "log": str(log), "port": port, "managementPort": management,
             "startedAt": datetime.now(timezone.utc).isoformat()}
    if not state["identity"]:
        process.wait(timeout=5)
        raise RuntimeError("Go 进程启动失败；检查受保护日志 " + str(log))
    try:
        private_json(directory / (role + ".json"), state)
        deadline = time.monotonic() + 60
        while process.poll() is None and time.monotonic() < deadline:
            if health(management):
                print(json.dumps({"role": role, "status": "UP", "pid": process.pid, "log": str(log)}), flush=True)
                return state
            time.sleep(.25)
        raise RuntimeError("Go 健康检查未通过；检查受保护日志 " + str(log))
    except BaseException:
        if process.poll() is None:
            process.terminate()
        try:
            process.wait(timeout=45)
        except subprocess.TimeoutExpired:
            raise RuntimeError("Go 启动失败且优雅退出超时；保留状态供核查") from None
        (directory / (role + ".json")).unlink(missing_ok=True)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("start", "stop", "status", "targets"))
    parser.add_argument("--role", choices=(*ROLES, "workers"), default="combined")
    parser.add_argument("--binary", type=Path)
    parser.add_argument("--migrations", type=Path, default=ROOT / "backend/src/main/resources/db/migration")
    parser.add_argument("--state-dir", type=Path, default=ROOT / ".cache/go-runtime")
    parser.add_argument("--port", type=int)
    parser.add_argument("--management-port", type=int)
    args = parser.parse_args()
    if args.role == "workers" and (args.port or args.management_port):
        parser.error("workers 使用各自端口；单角色才能覆盖端口")
    if args.command == "start" and not args.binary:
        parser.error("start 需要 --binary")
    roles = WORKERS if args.role == "workers" else (args.role,)
    with locked(args.state_dir) as directory:
        started = []
        try:
            if args.command == "start":
                try:
                    for role in roles:
                        start(directory, role, args.binary, args.migrations, os.environ, args.port, args.management_port)
                        started.append(role)
                except BaseException:
                    for role in reversed(started):
                        stop(directory, role)
                    raise
            elif args.command == "stop":
                for role in reversed(roles):
                    stop(directory, role)
            elif args.command == "status":
                print(json.dumps([{"role": role, "running": owned(state := current(directory, role)), "state": state}
                                  for role in roles], ensure_ascii=False))
        finally:
            publish_targets(directory)
        if args.command == "targets":
            print(directory / "worker-targets.json")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        raise SystemExit(str(error))
