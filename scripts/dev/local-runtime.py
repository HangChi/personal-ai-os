#!/usr/bin/env python3
"""管理本项目宿主机 JAR；凭据仅经环境传递，保留原 JAR 供回退。"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import signal
import shlex
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / '.cache' / 'local-runtime'
STATE = RUNTIME / 'state.json'
LEGACY_PID = Path('/tmp/personal-ai-os-monitored-backend.pid')
JAVA = '/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home/bin/java'


def env_file(path):
    values = {}
    if path.is_file():
        for raw in path.read_text().splitlines():
            line = raw.strip().removeprefix('export ')
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            values[key.strip()] = value
    return values


def current():
    if STATE.exists():
        return json.loads(STATE.read_text())
    if LEGACY_PID.exists():
        return {'pid': int(LEGACY_PID.read_text().strip()),
                'jar': str(ROOT / 'backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar'),
                'legacy': True}
    return None


def owned(state):
    if not state:
        return False
    result = subprocess.run(['ps', '-p', str(state['pid']), '-o', 'command='], capture_output=True, text=True)
    command = result.stdout.strip()
    if result.returncode != 0 or 'monitoring' not in command:
        return False
    try:
        args = shlex.split(command)
        actual = Path(args[args.index('-jar') + 1])
        if not actual.is_absolute():
            actual = ROOT / actual
        return actual.resolve() == Path(state['jar']).resolve()
    except (ValueError, IndexError):
        return False


def private_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(value, output, indent=2)


def stop():
    state = current()
    if not owned(state):
        print('本项目后端未运行；未停止其他进程')
        return
    private_json(RUNTIME / 'previous.json', state)
    os.kill(state['pid'], signal.SIGTERM)
    deadline = time.monotonic() + 45
    while owned(state) and time.monotonic() < deadline:
        time.sleep(.25)
    if owned(state):
        raise RuntimeError('优雅停止未完成，未强制终止进程')
    STATE.unlink(missing_ok=True)
    LEGACY_PID.unlink(missing_ok=True)
    print('本项目后端已优雅停止')


def start(jar, without_integrations=False, distributed=False):
    if owned(current()):
        raise RuntimeError('后端已经运行，请先停止；未启动重复进程')
    jar = jar.resolve()
    if not jar.is_file() or jar.suffix != '.jar':
        raise RuntimeError('发布 JAR 不存在')
    values = os.environ.copy()
    values.update(env_file(ROOT / '.env'))
    if not without_integrations:
        values.update(env_file(ROOT / '.env.integrations.local'))
    if values.get('AUTH_ENABLED', 'false').lower() != 'true':
        raise RuntimeError('部署保留访问认证；请先配置 AUTH_ENABLED=true')
    RUNTIME.mkdir(parents=True, exist_ok=True)
    log = RUNTIME / ('backend-' + datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S') + '.log')
    fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    profiles = 'monitoring,runtime-api' if distributed else 'monitoring'
    with os.fdopen(fd, 'w') as output:
        process = subprocess.Popen([JAVA, '-jar', str(jar), '--spring.profiles.active=' + profiles,
                                    '--management.server.address=127.0.0.1', '--server.address=127.0.0.1'],
                                   cwd=ROOT, env=values, stdin=subprocess.DEVNULL, stdout=output,
                                   stderr=subprocess.STDOUT, start_new_session=True)
    commit = subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    state = {'pid': process.pid, 'jar': str(jar), 'log': str(log), 'commit': commit,
             'startedAt': datetime.now(timezone.utc).isoformat(), 'integrations': not without_integrations, 'distributed': distributed}
    private_json(STATE, state)
    LEGACY_PID.write_text(str(process.pid))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline and process.poll() is None:
        try:
            with opener.open('http://127.0.0.1:9091/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    print(json.dumps({'status': 'UP', 'pid': process.pid, 'commit': commit, 'log': str(log)}))
                    return
        except Exception:
            pass
        time.sleep(.5)
    raise RuntimeError('后端未在等待窗口内健康就绪，请检查受保护的运行日志：' + str(log))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['status', 'stop', 'start'])
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--without-integrations', action='store_true')
    parser.add_argument('--distributed', action='store_true', help='Run the API role with separate workers')
    args = parser.parse_args()
    if args.command == 'status':
        state = current()
        print(json.dumps({'running': owned(state), 'state': state}, ensure_ascii=False))
    elif args.command == 'stop':
        stop()
    else:
        if not args.jar:
            parser.error('start 需要 --jar')
        start(args.jar, args.without_integrations, args.distributed)


if __name__ == '__main__':
    try:
        main()
    except (RuntimeError, OSError, ValueError) as error:
        raise SystemExit(str(error))
