#!/usr/bin/env python3
"""Run isolated local workers with the same release and private environment as the API."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
STATE = ROOT / '.cache/local-runtime/workers'
ROLES = {'index': (18083, 18093, '384m'), 'review': (18084, 18094, '384m'),
         'delivery': (18085, 18095, '256m')}
spec = importlib.util.spec_from_file_location('local_runtime', ROOT / 'scripts/dev/local-runtime.py')
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def state_path(role):
    return STATE / (role + '.json')


def current(role):
    path = state_path(role)
    return json.loads(path.read_text()) if path.exists() else None


def owned(value):
    if not value or value.get('role') not in ROLES:
        return False
    result = subprocess.run(['ps', '-p', str(value['pid']), '-o', 'command='], capture_output=True, text=True)
    if result.returncode:
        return False
    try:
        args = shlex.split(result.stdout)
        return (Path(args[args.index('-jar') + 1]).resolve() == Path(value['jar']).resolve()
                and '--spring.profiles.active=monitoring,worker-' + value['role'] in args)
    except (ValueError, IndexError):
        return False


def stop(role):
    value = current(role)
    if owned(value):
        os.kill(value['pid'], signal.SIGTERM)
        deadline = time.monotonic() + 45
        while owned(value) and time.monotonic() < deadline:
            time.sleep(.2)
        if owned(value):
            raise RuntimeError(role + ': graceful stop timed out; no force kill performed')
    state_path(role).unlink(missing_ok=True)


def start(role, jar):
    if owned(current(role)):
        raise RuntimeError(role + ': already running; stop the owned worker before changing its release')
    jar = jar.resolve()
    if not jar.is_file() or jar.suffix != '.jar':
        raise ValueError('Worker JAR does not exist')
    values = os.environ.copy()
    values.update(runtime.env_file(ROOT / '.env'))
    values.update(runtime.env_file(ROOT / '.env.integrations.local'))
    if values.get('AUTH_ENABLED', 'false').lower() != 'true':
        raise RuntimeError('Workers retain application authentication')
    port, management, heap = ROLES[role]
    STATE.mkdir(parents=True, exist_ok=True)
    log = STATE / (role + '-' + str(time.time_ns()) + '.log')
    fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w') as output:
        process = subprocess.Popen([runtime.JAVA, '-Xmx' + heap, '-jar', str(jar),
            '--spring.profiles.active=monitoring,worker-' + role,
            '--server.address=127.0.0.1', '--server.port=' + str(port),
            '--management.server.address=127.0.0.1', '--management.server.port=' + str(management)],
            cwd=ROOT, env=values, stdin=subprocess.DEVNULL, stdout=output,
            stderr=subprocess.STDOUT, start_new_session=True)
    value = {'role': role, 'pid': process.pid, 'jar': str(jar), 'log': str(log),
             'port': port, 'managementPort': management}
    runtime.private_json(state_path(role), value)
    deadline = time.monotonic() + 60
    while process.poll() is None and time.monotonic() < deadline:
        try:
            with opener.open('http://127.0.0.1:' + str(management) + '/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    print(json.dumps({'role': role, 'status': 'UP', 'pid': process.pid}), flush=True)
                    return
        except (OSError, ValueError):
            pass
        time.sleep(.5)
    stop(role)
    raise RuntimeError(role + ': failed health check; see protected log ' + str(log))


def publish_monitoring_targets():
    directory = ROOT / 'deploy/monitoring/worker-targets'
    directory.mkdir(parents=True, exist_ok=True)
    targets = [{'targets': ['host.docker.internal:' + str(ROLES[role][1])],
                'labels': {'role': role}} for role in ROLES if owned(current(role))]
    temp = directory / '.active.tmp'
    temp.write_text(json.dumps(targets) + '\n')
    temp.replace(directory / 'active.json')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start', 'stop', 'status'])
    parser.add_argument('--role', choices=['all', *ROLES], default='all')
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    roles = list(ROLES) if args.role == 'all' else [args.role]
    if args.command == 'start':
        if not args.jar:
            parser.error('start requires --jar')
        started = []
        try:
            for role in roles:
                start(role, args.jar)
                started.append(role)
        except Exception:
            for role in reversed(started):
                stop(role)
            raise
        finally:
            publish_monitoring_targets()
    elif args.command == 'stop':
        try:
            for role in reversed(roles):
                stop(role)
        finally:
            publish_monitoring_targets()
    else:
        print(json.dumps([{'role': role, 'running': owned(current(role)), 'state': current(role)} for role in roles]))


if __name__ == '__main__':
    try:
        main()
    except (OSError, RuntimeError, ValueError) as error:
        raise SystemExit(str(error))
