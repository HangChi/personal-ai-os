#!/usr/bin/env python3
"""隔离库中的真实模型 RAG 评测。不会清空或导入正式知识库。"""
import argparse
from datetime import datetime, timezone
import hashlib
import http.cookiejar
import importlib.util
import json
import os
from pathlib import Path
import signal
import statistics
import subprocess
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / '.cache' / 'rag-live-eval'
STATE = WORK / 'state.json'
CASES = ROOT / 'scripts/eval/rag-live-cases.json'
DB_CONTAINER = 'personal-ai-os-postgres'


def runtime():
    spec = importlib.util.spec_from_file_location('local_runtime', ROOT / 'scripts/dev/local-runtime.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def private_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as output:
        json.dump(data, output, ensure_ascii=False, indent=2)


def sql(database, query):
    if database != 'personal_ai_os' and not database.startswith('paios_rag_eval_'):
        raise ValueError('Refusing unowned database')
    result = subprocess.run(['docker', 'exec', DB_CONTAINER, 'psql', '-U', 'personalos', '-d', database,
                             '-XAt', '-v', 'ON_ERROR_STOP=1', '-c', query], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Database operation failed; raw output withheld')
    return result.stdout.strip()


class Client:
    def __init__(self, base):
        self.base = base.rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = None
        self.header = 'X-CSRF-TOKEN'

    def request(self, path, method='GET', body=None, content_type=None, timeout=100):
        headers = {}
        if body is not None and not isinstance(body, bytes):
            body = json.dumps(body).encode()
            content_type = 'application/json'
        if content_type:
            headers['Content-Type'] = content_type
        if self.csrf and method != 'GET':
            headers[self.header] = self.csrf
        req = urllib.request.Request(self.base + path, data=body, headers=headers, method=method)
        with self.opener.open(req, timeout=timeout) as response:
            data = response.read()
            return json.loads(data)['data'] if 'application/json' in response.headers.get('Content-Type', '') else data

    def token(self):
        value = self.request('/auth/csrf')
        self.csrf, self.header = value.get('token'), value.get('headerName', 'X-CSRF-TOKEN')

    def login(self):
        config = runtime().env_file(ROOT / '.env')
        self.token()
        self.request('/auth/login', 'POST', {'username': config.get('AUTH_USERNAME', 'admin'), 'password': config['AUTH_PASSWORD']})
        self.token()

    def upload(self, path, title):
        boundary = 'paios-' + uuid.uuid4().hex
        body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="title"\r\n\r\n' + title + '\r\n').encode()
        body += ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + path.name + '"\r\nContent-Type: text/markdown\r\n\r\n').encode()
        body += path.read_bytes() + ('\r\n--' + boundary + '--\r\n').encode()
        return self.request('/knowledge/documents', 'POST', body, 'multipart/form-data; boundary=' + boundary)


def cases():
    records = json.loads(CASES.read_text())
    for record in records:
        if not any(record['evidence'] in (ROOT / source).read_text() for source in record['sources']):
            raise ValueError('Ground truth evidence missing: ' + record['id'])
    return records


def ready(port, process):
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline and process.poll() is None:
        try:
            with urllib.request.urlopen('http://127.0.0.1:' + str(port) + '/actuator/health', timeout=2) as response:
                if json.load(response).get('status') == 'UP':
                    return
        except Exception:
            pass
        time.sleep(.5)
    raise RuntimeError('Evaluation process failed health check')


def start(jar):
    if STATE.exists():
        raise ValueError('Evaluation state exists; use evaluate or cleanup first')
    records = cases()
    module = runtime()
    values = os.environ.copy()
    values.update(module.env_file(ROOT / '.env'))
    values.update(module.env_file(ROOT / '.env.integrations.local'))
    profile = json.loads(sql('personal_ai_os', "SELECT row_to_json(t) FROM (SELECT api_key,chat_model FROM ai_model_profile WHERE active LIMIT 1)t"))
    if profile.get('api_key', '').startswith('enc:'):
        raise ValueError('Encrypted profile requires app credential resolution')
    values['OPENAI_API_KEY'] = profile['api_key']
    values['OPENAI_CHAT_MODEL'] = profile['chat_model']
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d%H%M%S') + uuid.uuid4().hex[:6]
    database = 'paios_rag_eval_' + stamp
    subprocess.run(['docker', 'exec', DB_CONTAINER, 'createdb', '-U', 'personalos', database], check=True, stdout=subprocess.DEVNULL)
    state = {'database': database, 'jar': str(jar.resolve()), 'createdAt': stamp, 'index': 'personalos-eval-' + stamp,
             'processes': [], 'documents': {}, 'corpusHashes': {}, 'commit': subprocess.check_output(['git', '-C', str(ROOT), 'rev-parse', 'HEAD'], text=True).strip()}
    private_json(STATE, state)
    for name, port, management in [('baseline', 18081, 18091), ('enhanced', 18082, 18092)]:
        env = values.copy()
        env.update(POSTGRES_DB=database, SERVER_PORT=str(port), RABBITMQ_ENABLED='false', S3_ENABLED='false',
                   RAG_ENABLED='false' if name == 'baseline' else 'true',
                   OPENSEARCH_ENABLED='false' if name == 'baseline' else 'true', OPENSEARCH_INDEX=state['index'],
                   REDIS_NAMESPACE='personal-ai-os-eval-' + stamp, AI_CHAT_UPLOAD_DIR=str(WORK / 'uploads'),
                   APP_RATE_LIMIT_ENABLED='false')
        log = WORK / (name + '-' + stamp + '.log')
        fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, 'w') as output:
            process = subprocess.Popen([module.JAVA, '-Xmx512m', '-jar', str(jar.resolve()), '--spring.profiles.active=monitoring',
                                        '--server.address=127.0.0.1', '--management.server.address=127.0.0.1', '--management.server.port=' + str(management),
                                        '--app.knowledge.jobs.enabled=false', '--app.knowledge.rag.ingestion-review-enabled=false'],
                                       cwd=ROOT, env=env, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        state['processes'].append({'name': name, 'pid': process.pid, 'port': port, 'management': management, 'log': str(log)})
        private_json(STATE, state)
        ready(management, process)
        print('Evaluation app ready: ' + name, flush=True)
    client = Client('http://127.0.0.1:18081/api/v1')
    client.login()
    paths = sorted({source for record in records for source in record['sources']})
    for source in paths:
        path = ROOT / source
        document = client.upload(path, source)
        state['documents'][source] = document['id']
        state['corpusHashes'][source] = hashlib.sha256(path.read_bytes()).hexdigest()
        private_json(STATE, state)
        print('Seeded ' + str(len(state['documents'])) + '/' + str(len(paths)) + ': ' + source, flush=True)
    enhanced = Client('http://127.0.0.1:18082/api/v1')
    enhanced.login()
    deadline = time.monotonic() + 180
    while enhanced.request('/knowledge/search-index')['pending'] and time.monotonic() < deadline:
        time.sleep(1)
    if enhanced.request('/knowledge/search-index')['pending']:
        raise RuntimeError('Projection has not caught up')
    print('Isolated corpus ready; production knowledge untouched', flush=True)


def counters(port):
    with urllib.request.urlopen('http://127.0.0.1:' + str(port) + '/actuator/prometheus', timeout=5) as response:
        text = response.read().decode()
    prefixes = ('personalos_knowledge_rag_tokens_total', 'personalos_knowledge_rag_rerank_tokens_total')
    return {line.split(' ')[0]: float(line.split(' ')[1]) for line in text.splitlines() if line.startswith(prefixes)}


def evaluate(limit):
    state = json.loads(STATE.read_text())
    records = cases()[:limit] if limit else cases()
    rows = []
    for process in state['processes']:
        name = process['name']
        client = Client('http://127.0.0.1:' + str(process['port']) + '/api/v1')
        client.login()
        before = counters(process['management'])
        for record in records:
            started = time.monotonic()
            try:
                result = client.request('/knowledge/search', 'POST', {'query': record['query'], 'topK': 5})
                hits = result['hits']
                expected = {state['documents'][source] for source in record['sources']}
                seen = set()
                ids = []
                for hit in hits:
                    if hit['documentId'] not in seen:
                        ids.append(hit['documentId'])
                        seen.add(hit['documentId'])
                rank = next((i + 1 for i, value in enumerate(ids) if value in expected), None)
                trace = result.get('retrieval') or {}
                row = {'mode': name, 'id': record['id'], 'rank': rank, 'durationMs': round((time.monotonic() - started) * 1000),
                       'status': trace.get('status', 'LEGACY'), 'warnings': trace.get('warnings', []), 'stages': trace.get('stages', []),
                       'realRerank': any(value.get('rerank') is not None for value in trace.get('rankingScores', {}).values()),
                       'titles': [hit['documentTitle'] for hit in hits]}
            except (urllib.error.URLError, ValueError, KeyError) as error:
                row = {'mode': name, 'id': record['id'], 'rank': None, 'durationMs': round((time.monotonic() - started) * 1000), 'status': 'ERROR', 'errorType': type(error).__name__}
            rows.append(row)
            print(json.dumps({key: row[key] for key in ['mode', 'id', 'rank', 'durationMs', 'status']}, ensure_ascii=False), flush=True)
        after = counters(process['management'])
        process['evaluationTokens'] = {key: value - before.get(key, 0) for key, value in after.items()}
    summary = {}
    for name in ['baseline', 'enhanced']:
        items = [row for row in rows if row['mode'] == name]
        times = sorted(row['durationMs'] for row in items)
        summary[name] = {'count': len(items), 'recallAt1': sum(row['rank'] == 1 for row in items) / len(items),
                         'recallAt3': sum(row['rank'] is not None and row['rank'] <= 3 for row in items) / len(items),
                         'recallAt5': sum(row['rank'] is not None and row['rank'] <= 5 for row in items) / len(items),
                         'mrr': sum(1 / row['rank'] if row['rank'] else 0 for row in items) / len(items),
                         'p50Ms': statistics.median(times), 'p95Ms': times[max(0, int(len(times) * .95 + .999) - 1)],
                         'statusCounts': {status: sum(row['status'] == status for row in items) for status in sorted({row['status'] for row in items})}}
    result = {'createdAt': datetime.now(timezone.utc).isoformat(), 'commit': state['commit'], 'corpusHashes': state['corpusHashes'],
              'scope': 'Project documents; not private user knowledge; same corpus and embedding model for both modes',
              'summary': summary, 'tokens': {process['name']: process.get('evaluationTokens', {}) for process in state['processes']}, 'rows': rows}
    report = WORK / ('result-' + datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S') + '.json')
    private_json(report, result)
    print(json.dumps(summary, ensure_ascii=False, indent=2), flush=True)
    print('Report: ' + str(report), flush=True)


def cleanup():
    state = json.loads(STATE.read_text())
    if not state['database'].startswith('paios_rag_eval_') or not state['index'].startswith('personalos-eval-'):
        raise ValueError('Refusing non-evaluation resources')
    for process in state['processes']:
        command = subprocess.run(['ps', '-p', str(process['pid']), '-o', 'command='], capture_output=True, text=True).stdout
        if state['jar'] in command and '--management.server.port=' + str(process['management']) in command:
            os.kill(process['pid'], signal.SIGTERM)
    deadline = time.monotonic() + 45
    for process in state['processes']:
        while time.monotonic() < deadline:
            command = subprocess.run(['ps', '-p', str(process['pid']), '-o', 'command='], capture_output=True, text=True).stdout
            if state['jar'] not in command:
                break
            time.sleep(.25)
        else:
            raise RuntimeError('Evaluation process still stopping; database preserved')
    subprocess.run(['docker', 'exec', DB_CONTAINER, 'dropdb', '-U', 'personalos', '--if-exists', state['database']], check=True)
    config = runtime().env_file(ROOT / '.env.integrations.local')
    req = urllib.request.Request(config['OPENSEARCH_ENDPOINT'].rstrip('/') + '/' + state['index'], method='DELETE')
    try:
        with urllib.request.urlopen(req, timeout=10):
            pass
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
    STATE.unlink()
    print('Owned evaluation processes/database/index removed; reports retained')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['start', 'evaluate', 'cleanup'])
    parser.add_argument('--jar', type=Path)
    parser.add_argument('--limit', type=int, default=0)
    args = parser.parse_args()
    if args.command == 'start':
        if not args.jar:
            parser.error('start requires --jar')
        start(args.jar)
    elif args.command == 'evaluate':
        evaluate(args.limit)
    else:
        cleanup()


if __name__ == '__main__':
    main()
