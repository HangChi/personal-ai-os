#!/usr/bin/env python3
"""Append official documentation to an existing KB; never delete or replace data.
Runtime evidence belongs in the ignored private output directory.
Dependencies: httpx, beautifulsoup4, markdownify.
"""
import argparse
from collections import Counter
from datetime import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tarfile
import time
import uuid
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
NL = chr(10)
NAMESPACE = uuid.UUID('6ae28470-9cdd-47a8-b668-03f7d7489f3d')

def now():
    return datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()

def sha(data):
    return hashlib.sha256(data if isinstance(data, bytes) else data.encode()).hexdigest()

def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temp = path.with_suffix(path.suffix + '.tmp')
    temp.write_text(json.dumps(data, ensure_ascii=False, indent=2) + NL)
    temp.chmod(0o600)
    temp.replace(path)

def load(path):
    return json.loads(path.read_text())

def sql(query):
    if not query.lstrip().upper().startswith('SELECT '):
        raise ValueError('Only read-only verification SQL is allowed')
    result = subprocess.run(['docker', 'exec', 'personal-ai-os-postgres', 'psql', '-U', 'personalos',
        '-d', 'personal_ai_os', '-XAt', '-v', 'ON_ERROR_STOP=1', '-c', query], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError('Knowledge verification SQL failed; raw output withheld')
    return json.loads(result.stdout)

class Api:
    def __init__(self, base, credentials=None):
        import httpx
        spec = importlib.util.spec_from_file_location('scale_runtime', ROOT / 'scripts/dev/local-runtime.py')
        runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(runtime)
        config = credentials if credentials is not None else runtime.env_file(ROOT / '.env')
        self.client = httpx.Client(base_url=base.rstrip('/'), timeout=150, trust_env=False)
        self.header, self.token = 'X-CSRF-TOKEN', None
        self.csrf()
        self.call('POST', '/auth/login', {'username': config.get('AUTH_USERNAME', 'admin'), 'password': config['AUTH_PASSWORD']})
        self.csrf()
    def csrf(self):
        value = self.call('GET', '/auth/csrf')
        self.header, self.token = value.get('headerName', 'X-CSRF-TOKEN'), value['token']
    def call(self, method, path, data=None, **kwargs):
        headers = dict(kwargs.pop('headers', {}))
        if self.token and method != 'GET':
            headers[self.header] = self.token
        for attempt in range(6):
            response = self.client.request(method, path, json=data, headers=headers, **kwargs)
            if response.status_code == 429 and attempt < 5:
                time.sleep(min(20, 2 ** attempt))
                continue
            if response.is_error:
                raise RuntimeError('HTTP ' + str(response.status_code) + ': ' + method + ' ' + path.split('?')[0])
            value = response.json()
            if value.get('code') != 0:
                raise RuntimeError('API business error ' + str(value.get('code')))
            return value['data']
        raise RuntimeError('API rate limit did not recover')
    def upload(self, doc, content, job_id):
        import httpx
        for attempt in range(3):
            try:
                return self.call('POST', '/knowledge/jobs', params={'title': doc['title']},
                    files={'file': (doc['filename'], content, 'text/markdown')}, headers={'Idempotency-Key': job_id})
            except httpx.TransportError:
                if attempt == 2:
                    raise
                time.sleep(2 ** attempt)

def convert_html(raw, group, relative):
    from bs4 import BeautifulSoup
    from markdownify import markdownify
    from urllib.parse import urljoin
    soup = BeautifulSoup(raw, 'html.parser')
    body = soup.select_one('div.body') if group == 'python' else soup.select_one('#docContent')
    if body is None and group == 'postgresql':
        body = soup.body
    if body is None:
        raise ValueError('Missing document body: ' + relative)
    for item in body.select('script, style, .navheader, .navfooter, .headerlink, .toc, .feedback, .correction-form'):
        item.decompose()
    heading = body.select_one('h1, h2, h3')
    title = (heading or soup.title).get_text(' ', strip=True).strip('¶ ')
    base = 'https://docs.python.org/3.14/' if group == 'python' else 'https://www.postgresql.org/docs/17/'
    for link in body.select('a[href]'):
        link['href'] = urljoin(base + relative, link['href'])
    text = markdownify(str(body), heading_style='ATX', strip=['img']).strip()
    return ' '.join(title.split()), text + NL

def prepare(args):
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    if (out / 'corpus.json').exists():
        raise ValueError('Frozen corpus already exists; use import or choose a new output')
    api = Api(args.base)
    original = api.call('GET', '/knowledge/documents')
    missing = args.target - len(original)
    if missing <= 0:
        raise ValueError('Target already reached')
    save(out / 'before-documents.json', original)
    save(out / 'before-chunks.json', sql(CHUNK_QUERY))
    preferred_py = {'library/' + p + '.html' for p in ['bisect', 'heapq', 'functools', 'itertools', 'collections',
        're', 'asyncio-task', 'asyncio-sync', 'concurrent.futures', 'contextvars', 'sqlite3', 'json', 'decimal',
        'pathlib', 'dataclasses', 'typing', 'logging.handlers', 'zoneinfo', 'subprocess', 'tomllib']}
    preferred_pg = {p + '.html' for p in ['transaction-iso', 'explicit-locking', 'indexes-expressional',
        'indexes-partial', 'indexes-multicolumn', 'indexes-unique', 'sql-createindex', 'sql-insert', 'sql-select',
        'sql-vacuum', 'sql-explain', 'mvcc', 'routine-vacuuming', 'backup-dump', 'backup-pitr-recovery',
        'wal-intro', 'ddl-constraints', 'queries-with', 'functions-json', 'pgtrgm']}
    groups, archives, seen = {}, [], set()
    for path, group, version, preferred in [(args.python_archive, 'python', 'Python 3.14', preferred_py),
            (args.postgres_archive, 'postgresql', 'PostgreSQL 17.9', preferred_pg)]:
        archives.append(dict(group=group, filename=path.name, sha256=sha(path.read_bytes()), version=version))
        docs = []
        with tarfile.open(path) as archive:
            for member in sorted(archive.getmembers(), key=lambda m: m.name):
                if not member.isfile() or not member.name.endswith('.html'):
                    continue
                if group == 'python':
                    relative = member.name.split('/', 1)[1]
                    if relative.split('/')[0] not in {'library', 'c-api', 'howto', 'tutorial', 'reference', 'faq', 'using', 'extending', 'builtins'} or relative.endswith('/index.html'):
                        continue
                else:
                    if '/html/' not in member.name:
                        continue
                    relative = member.name.split('/html/', 1)[1]
                    if '/' in relative or relative in {'index.html', 'bookindex.html'} or relative.startswith('release-'):
                        continue
                raw = archive.extractfile(member).read()
                try:
                    title, text = convert_html(raw, group, relative)
                except ValueError:
                    continue  # Offline archives contain redirect-only compatibility pages.
                digest = sha(' '.join(text.split()))
                if len(text) < 500 or (len(text) > 100000 and relative not in preferred) or digest in seen:
                    continue
                seen.add(digest)
                slug = group + '-' + relative[:-5].replace('/', '-')
                url = ('https://docs.python.org/3.14/' if group == 'python' else 'https://www.postgresql.org/docs/17/') + relative
                content = NL.join(['# ' + title, '', '来源：' + url, '版本：' + version, '采集时间：' + now(),
                    '收录范围：官方离线发行包完整单页正文；去除站点导航，保留代码与链接。', '', text])
                docs.append(dict(slug=slug, filename=slug + '.md', title=('【官方正文｜' + version + '】' + title)[:300],
                    group=group, version=version, relative=relative, source_url=url, source_archive=path.name,
                    raw_sha256=sha(raw), body_sha256=digest, sha256=sha(content), chars=len(content), preferred=relative in preferred, content=content))
        docs.sort(key=lambda d: (not d['preferred'], sha(d['relative'])))
        groups[group] = docs
        print(group, 'eligible official pages:', len(docs), flush=True)
    blogs = load(args.juejin_manifest)['documents'] if args.juejin_manifest else []
    if len(blogs) > missing:
        raise ValueError('Blog corpus exceeds requested additions')
    py_count = min(args.python_count, missing - len(blogs), len(groups['python']))
    pg_count = missing - len(blogs) - py_count
    if pg_count > len(groups['postgresql']):
        raise ValueError('Not enough independent eligible official pages')
    chosen = blogs + groups['python'][:py_count] + groups['postgresql'][:pg_count]
    existing = {d['filename'] for d in original}
    if any(d['filename'] in existing for d in chosen):
        raise ValueError('Selected filename already exists; refusing unowned duplicates')
    for doc in chosen:
        content = doc.pop('content') if 'content' in doc else (args.juejin_manifest.parent / 'corpus' / doc['filename']).read_text()
        path = out / 'corpus' / doc['filename']
        path.parent.mkdir(exist_ok=True)
        path.write_text(content)
        path.chmod(0o600)
    save(out / 'corpus.json', dict(created_at=now(), target=args.target, before=len(original), count=len(chosen),
        groups=dict(Counter(d['group'] for d in chosen)), archives=archives, body_chars=sum(d['chars'] for d in chosen), documents=chosen))
    print('Frozen', len(chosen), 'documents;', sum(d['chars'] for d in chosen), 'characters', flush=True)

CHUNK_QUERY = "SELECT coalesce(json_agg(t),'[]'::json) FROM (SELECT metadata->>'document_id' AS document_id, count(*) AS chunks, md5(string_agg(md5(content),'' ORDER BY id)) AS content_md5 FROM vector_store GROUP BY metadata->>'document_id' ORDER BY 1)t"

def ingest(args):
    out = args.output.resolve()
    corpus = load(out / 'corpus.json')
    manifest = out / 'import-manifest.json'
    state = load(manifest) if manifest.exists() else dict(started_at=now(), documents={}, spaces={})
    api = Api(args.base)
    docs = api.call('GET', '/knowledge/documents')
    owned = {d['documentId'] for d in state['documents'].values() if 'documentId' in d}
    expected = {d['id'] for d in load(out / 'before-documents.json')} | owned
    if {d['id'] for d in docs} - expected:
        raise ValueError('Unexpected concurrent documents; refusing to exceed target')
    spaces = api.call('GET', '/knowledge/governance/spaces')
    for group in corpus['groups']:
        default_names = {'juejin':'掘金博客正文·扩充1000', 'python':'官方技术正文·Python 3.14',
            'postgresql':'官方技术正文·PostgreSQL 17.9'}
        name = corpus.get('space_names', {}).get(group) or default_names.get(group)
        if not name:
            raise ValueError('Explicit provenance space required for group: ' + group)
        space = next((s for s in spaces if s['name'] == name), None)
        if not space:
            space = api.call('POST', '/knowledge/governance/spaces', {'name': name})
        state['spaces'][group] = space['id']
    save(manifest, state)
    pending = {}
    started = time.monotonic()
    last_report = time.monotonic()
    def finish(doc, record, job):
        record.update(status=job['status'], attempts=job['attempts'])
        if job['status'] == 'FAILED':
            save(manifest, state)
            raise RuntimeError('Index job failed: ' + doc['slug'] + '; ' + str(job.get('error')))
        if job['status'] != 'SUCCEEDED':
            return False
        result = json.loads(job['result'])
        if result['chunkCount'] < 1:
            raise ValueError('Indexed document has no chunks')
        if not record.get('provenanceSaved'):
            api.call('PUT', '/knowledge/governance/sources/' + job['documentId'], dict(spaceId=state['spaces'][doc['group']],
                version=doc['version'], scope=doc.get('scope') or ('掘金作者博客；' if doc['group'] == 'juejin' else '官方技术参考；') + doc['relative'], authority=doc['source_url'],
                lifecycle='ACTIVE', effectiveFrom=None, effectiveUntil=None))
        record.update(documentId=job['documentId'], chars=result['charCount'], chunks=result['chunkCount'], provenanceSaved=True)
        save(manifest, state)
        return True
    def poll():
        nonlocal last_report
        if time.monotonic() - started > args.deadline:
            raise TimeoutError('Import deadline reached; manifest can resume with identical job IDs')
        for slug in list(pending):
            doc, record = pending[slug]
            if finish(doc, record, api.call('GET', '/knowledge/jobs/' + record['jobId'])):
                del pending[slug]
        if time.monotonic() - last_report > 25:
            count = sum(d.get('status') == 'SUCCEEDED' for d in state['documents'].values())
            print(now(), 'indexed', count, '/', corpus['count'], 'pending', len(pending), flush=True)
            last_report = time.monotonic()
    for doc in corpus['documents']:
        record = state['documents'].get(doc['slug'])
        content = (out / 'corpus' / doc['filename']).read_bytes()
        if sha(content) != doc['sha256']:
            raise ValueError('Frozen corpus hash mismatch: ' + doc['slug'])
        if record and record['sha256'] != doc['sha256']:
            raise ValueError('Submitted payload changed')
        if record and record.get('status') == 'SUCCEEDED' and record.get('provenanceSaved'):
            continue
        if record and record.get('status') not in ('SUCCEEDED', 'SUBMITTING'):
            actual = api.call('GET', '/knowledge/jobs/' + record['jobId'])
            record.update(status=actual['status'], attempts=actual['attempts'])
            save(manifest, state)
        if record and record.get('status') == 'FAILED':
            actual = api.call('GET', '/knowledge/jobs/' + record['jobId'])
            if actual['status'] != 'FAILED' or record['documentId'] in {d['id'] for d in api.call('GET', '/knowledge/documents')}:
                raise ValueError('Failed operation has uncertain persistent state; manual reconciliation required')
            history = record.setdefault('failed_jobs', [])
            if len(history) >= 2:
                raise ValueError('Repeated terminal indexing failure: ' + doc['slug'])
            history.append(dict(jobId=record['jobId'], attempts=actual['attempts'], error=actual.get('error'), observed_at=now()))
            job_id = str(uuid.uuid5(NAMESPACE, str(out) + '|' + doc['slug'] + '|' + doc['sha256'] + '|retry-' + str(len(history))))
            record.update(jobId=job_id, documentId=job_id, status='SUBMITTING')
            save(manifest, state)
        while len(pending) >= args.inflight:
            poll()
            if len(pending) >= args.inflight:
                time.sleep(2)
        if not record:
            job_id = str(uuid.uuid5(NAMESPACE, str(out) + '|' + doc['slug'] + '|' + doc['sha256']))
            record = dict(jobId=job_id, documentId=job_id, sha256=doc['sha256'], status='SUBMITTING')
            state['documents'][doc['slug']] = record
            save(manifest, state)
        job = api.upload(doc, content, record['jobId'])
        if not finish(doc, record, job):
            pending[doc['slug']] = (doc, record)
        time.sleep(.1)
    while pending:
        poll()
        if pending:
            time.sleep(2)
    deadline = time.monotonic() + 600
    while time.monotonic() < deadline:
        projection = api.call('GET', '/knowledge/search-index')
        if not projection['pending']:
            break
        time.sleep(2)
    docs = api.call('GET', '/knowledge/documents')
    old_docs = {d['id']: d for d in load(out / 'before-documents.json')}
    old_chunks = {d['document_id']: d for d in load(out / 'before-chunks.json')}
    after = {d['document_id']: d for d in sql(CHUNK_QUERY)}
    preserved = all(d == next((v for v in docs if v['id'] == k), None) and old_chunks.get(k) == after.get(k) for k, d in old_docs.items())
    owned = {d['documentId'] for d in state['documents'].values()}
    imported = [d for d in docs if d['id'] in owned]
    counts_match = all(after.get(d['id'], {}).get('chunks') == d['chunkCount'] for d in docs)
    provenance = sql("SELECT count(*) FROM knowledge_source WHERE document_id IN (" + ','.join("'" + d + "'" for d in owned) + ')')
    last_page = max(0, (len(docs)-1)//20)
    pagination = [api.call('GET', '/knowledge/documents/search?page=' + str(p) + '&size=20') for p in sorted({0, last_page//2, last_page})]
    report = dict(completed_at=now(), target=corpus['target'], before=corpus['before'], added=len(imported), total=len(docs),
        groups=corpus['groups'], added_chars=sum(d['charCount'] for d in imported), added_bytes=sum(d['sizeBytes'] for d in imported),
        added_chunks=sum(d['chunkCount'] for d in imported), total_chunks=sum(d['chunkCount'] for d in docs),
        original_documents_and_content_preserved=preserved, chunk_counts_match=counts_match, source_metadata_count=provenance,
        projection=projection, pagination=[dict(page=p['page'], count=len(p['items']), total=p['total']) for p in pagination],
        elapsed_seconds=round(time.monotonic() - started, 2))
    save(out / 'ingestion-report.json', report)
    save(out / 'after-documents.json', docs)
    if len(docs) != corpus['target'] or len(imported) != corpus['count'] or not preserved or not counts_match or projection['pending'] or provenance != corpus['count']:
        raise ValueError('Post-import verification failed; inspect private report')
    print(json.dumps(report, ensure_ascii=False, indent=2), flush=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'import'])
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--base', default='http://127.0.0.1:8088/api/v1')
    parser.add_argument('--target', type=int, default=1000)
    parser.add_argument('--python-count', type=int, default=450)
    parser.add_argument('--python-archive', type=Path)
    parser.add_argument('--postgres-archive', type=Path)
    parser.add_argument('--juejin-manifest', type=Path)
    parser.add_argument('--inflight', type=int, default=8)
    parser.add_argument('--deadline', type=int, default=10800)
    args = parser.parse_args()
    if args.command == 'prepare':
        if not args.python_archive or not args.postgres_archive:
            parser.error('prepare requires both official archives')
        prepare(args)
    else:
        ingest(args)

if __name__ == '__main__':
    main()
