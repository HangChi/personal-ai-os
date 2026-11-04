#!/usr/bin/env python3
"""P6 real facilities, always disposable; never reads application secrets or existing stacks."""
import json
import os
import secrets
import socket
import subprocess
import time
import urllib.request
import uuid

from run_go_redis_tests import IMAGE, run_package


def capture(args):
    return subprocess.check_output(args, text=True, timeout=120).strip()


def main():
    containers = []
    try:
        suffix = uuid.uuid4().hex[:12]
        rabbit_image = capture(['docker', 'image', 'inspect', 'rabbitmq:4.4.0-management', '--format', '{{index .RepoDigests 0}}'])
        search_image = capture(['docker', 'image', 'inspect', 'opensearchproject/opensearch:3.9.0', '--format', '{{index .RepoDigests 0}}'])
        password = secrets.token_urlsafe(24)
        env = dict(os.environ, RABBITMQ_DEFAULT_PASS=password)
        configurations = [
            ('rabbit', ['--memory', '512m', '--tmpfs', '/var/lib/rabbitmq:rw,nosuid,uid=999,gid=999,size=256m',
                        '--env', 'RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS=+S 2:2 +A 4',
                        '--publish', '127.0.0.1::5672', '--env', 'RABBITMQ_DEFAULT_USER=go_fixture',
                        '--env', 'RABBITMQ_DEFAULT_PASS', rabbit_image], 5672),
            ('search', ['--memory', '1536m', '--tmpfs', '/usr/share/opensearch/data:rw,nosuid,uid=1000,gid=1000,size=512m',
                        '--publish', '127.0.0.1::9200', '--env', 'discovery.type=single-node',
                        '--env', 'DISABLE_SECURITY_PLUGIN=true', '--env', 'DISABLE_INSTALL_DEMO_CONFIG=true',
                        '--env', 'OPENSEARCH_JAVA_OPTS=-Xms512m -Xmx512m', search_image], 9200),
        ]
        ports = {}
        for name, options, port in configurations:
            container = subprocess.check_output(['docker', 'run', '--detach', '--rm', '--name', 'personalos-p6-' + name + '-' + suffix,
                                                 '--label', 'personalos.go-p6-test=true', *options], env=env, text=True, timeout=120).strip()
            if len(container) != 64 or any(c not in '0123456789abcdef' for c in container):
                raise RuntimeError('invalid disposable container ID')
            containers.append(container)
            info = json.loads(capture(['docker', 'inspect', container]))[0]
            bindings = info['NetworkSettings']['Ports'][str(port) + '/tcp']
            if len(bindings) != 1 or bindings[0]['HostIp'] != '127.0.0.1' or any(m['Type'] == 'volume' for m in info['Mounts']):
                raise RuntimeError('fixture isolation violated')
            ports[name] = bindings[0]['HostPort']
        deadline = time.monotonic() + 120
        while True:
            try:
                with socket.create_connection(('127.0.0.1', int(ports['rabbit'])), timeout=2):
                    rabbit_ready = True
                with urllib.request.urlopen('http://127.0.0.1:' + ports['search'] + '/_cluster/health', timeout=3) as r:
                    search_ready = json.load(r)['status'] in ('yellow', 'green')
                if rabbit_ready and search_ready:
                    break
            except Exception:
                pass
            if time.monotonic() >= deadline:
                raise TimeoutError('P6 disposable facilities did not become ready')
            time.sleep(1)
        os.environ['GO_TEST_KNOWLEDGE_RABBIT_URI'] = 'amqp://go_fixture:' + password + '@127.0.0.1:' + ports['rabbit'] + '/'
        os.environ['GO_TEST_KNOWLEDGE_SEARCH_URL'] = 'http://127.0.0.1:' + ports['search']
        print('P6 disposable RabbitMQ/OpenSearch + TLS Redis/PostgreSQL: ' + rabbit_image + ' ' + search_image, flush=True)
        run_package(IMAGE, 'knowledge', tls_mode=True)
        run_package(IMAGE, 'app', tls_mode=True, test_pattern='^TestFoundationP6')
    finally:
        for container in reversed(containers):
            subprocess.run(['docker', 'rm', '--force', container], check=True, capture_output=True, timeout=30)
        print('P6 disposable facilities removed', flush=True)


if __name__ == '__main__':
    main()
