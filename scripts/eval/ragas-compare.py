#!/usr/bin/env python3
"""Compare current Java/Go RAG on an isolated, identical knowledge snapshot.

collect uses only owned databases/indexes and writes resumable JSONL evidence.
judge runs official RAGAS metrics; summarize can run without model calls.
Credentials remain in memory/environment, never in result files or arguments.
"""
import argparse
import asyncio
from collections import Counter
from datetime import datetime
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import secrets
import socket
import statistics
import subprocess
import time
from zoneinfo import ZoneInfo

ROOT = Path(__file__).resolve().parents[2]
CASES = ROOT / 'scripts/eval/ragas-comparison-cases.json'
CONTAINER = 'personal-ai-os-postgres'
TABLES = ('flyway_schema_history', 'ai_model_config', 'ai_model_profile',
          'ai_model_profile_model', 'knowledge_document', 'vector_store',
          'knowledge_space', 'knowledge_source', 'knowledge_conflict',
          'knowledge_conflict_audit', 'knowledge_candidate_generation')
os.environ.setdefault('RAGAS_DO_NOT_TRACK', 'true')


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


runtime = module('ragas_local_runtime', ROOT / 'scripts/dev/local-runtime.py')
live = module('ragas_live_client', ROOT / 'scripts/eval/rag-live-eval.py')


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n')
    temporary.chmod(0o600)
    temporary.replace(path)


def append(path, value):
    with path.open('a') as target:
        target.write(json.dumps(value, ensure_ascii=False, allow_nan=False) + '\n')
    path.chmod(0o600)


def records(path):
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []


def sql(database, query):
    if database != 'personal_ai_os' and not re.fullmatch(r'paios_ragas_[a-z0-9_]+', database):
        raise ValueError('Database outside evaluation scope')
    if database == 'personal_ai_os' and not query.lstrip().upper().startswith('SELECT '):
        raise ValueError('Only SELECT is allowed on original database')
    result = subprocess.run(['docker', 'exec', CONTAINER, 'psql', '-U', 'personalos',
                             '-d', database, '-XAt', '-v', 'ON_ERROR_STOP=1', '-c', query],
                            capture_output=True, text=True, check=False)
    if result.returncode:
        raise RuntimeError('Evaluation SQL failed; raw output withheld')
    return result.stdout.strip()


def profile():
    row = json.loads(sql('personal_ai_os',
        'SELECT row_to_json(p) FROM (SELECT base_url,api_key,chat_model,embedding_model,temperature '
        'FROM ai_model_profile WHERE active ORDER BY sort_order LIMIT 1) p'))
    if not row.get('api_key') or row['api_key'].startswith('enc:'):
        raise ValueError('A usable configured active model profile is required')
    return row


def config():
    env = dict(os.environ)
    env.update(runtime.env_file(ROOT / '.env'))
    env.update(runtime.env_file(ROOT / '.env.integrations.local'))
    return env


def snapshot(database):
    tables = ('knowledge_document', 'vector_store', 'knowledge_source', 'knowledge_space', 'knowledge_conflict')
    return {table: json.loads(sql(database,
        'SELECT json_build_object(\'count\',count(*),\'md5\',md5(COALESCE('
        'string_agg(md5(row_to_json(t)::text),\'\' ORDER BY md5(row_to_json(t)::text)),\'\'))) FROM '
        + table + ' t')) for table in tables}


def clone(database):
    subprocess.run(['docker', 'exec', CONTAINER, 'createdb', '-U', 'personalos', database],
                   check=True, capture_output=True)
    command = ['docker', 'exec', CONTAINER, 'pg_dump', '-U', 'personalos',
               '--no-owner', '--no-privileges', '-d', 'personal_ai_os']
    for options in (['--schema-only'], ['--data-only', '--disable-triggers', *[arg for t in TABLES for arg in ('-t', t)]]):
        source = subprocess.Popen(command + options, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        target = subprocess.run(['docker', 'exec', '-i', CONTAINER, 'psql', '-U', 'personalos',
                                 '-d', database, '-Xq', '-v', 'ON_ERROR_STOP=1'],
                                stdin=source.stdout, capture_output=True)
        source.stdout.close()
        source.communicate(timeout=90)
        if source.returncode or target.returncode:
            errors = [line for line in target.stderr.decode(errors='replace').splitlines() if 'ERROR:' in line]
            raise RuntimeError('Snapshot restore failed: ' + '; '.join(errors)[:300])


def dataset(corpus):
    rows = json.loads(CASES.read_text())
    manifest = json.loads((corpus.parent / 'import-manifest.json').read_text())['documents']
    if len({row['id'] for row in rows}) != len(rows):
        raise ValueError('Duplicate evaluation case ID')
    for row in rows:
        texts = [(corpus / (slug + '.md')).read_text() for slug in row['sources']]
        if any(not any(anchor in text for text in texts) for anchor in row.get('anchors', [])):
            raise ValueError('Reference anchor missing: ' + row['id'])
        for slug in row['sources']:
            if hashlib.sha256((corpus / (slug + '.md')).read_bytes()).hexdigest() != manifest[slug]['sha256']:
                raise ValueError('Corpus manifest hash mismatch: ' + slug)
        row['expected_document_ids'] = [manifest[slug]['documentId'] for slug in row['sources']]
        row['source_sha256'] = {slug: manifest[slug]['sha256'] for slug in row['sources']}
    return rows


def port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def normalized(text):
    # Markdown renders RESULT\_UNKNOWN as RESULT_UNKNOWN; do not count that as a missing identifier.
    text = text.replace(chr(92) + '_', '_')
    return re.sub(r'\s+', '', text).lower()


def measure(case, response, chunks):
    retrieval = response.get('retrieval') or {}
    hits = retrieval.get('hits') or []
    ids = list(dict.fromkeys(h['documentId'] for h in hits))
    expected = set(case['expected_document_ids'])
    rank = next((i + 1 for i, doc in enumerate(ids) if doc in expected), None)
    citations = response.get('citations') or []
    answer = response.get('answer') or ''
    marks = set(int(i) for i in re.findall(r'\[(\d+)\]', answer))
    actual_marks = set(c['index'] for c in citations)
    chunks_valid = all(chunks.get((c['documentId'], c['chunkIndex'])) ==
                       hashlib.sha256(c['content'].encode()).hexdigest() for c in citations)
    required = case.get('required', [])
    points = [any(normalized(term) in normalized(answer) for term in choices) for choices in required]
    status = retrieval.get('status')
    # Infrastructure failures are never counted as correct negative-control refusals.
    refusal = (not response.get('answered') and status in ('INSUFFICIENT', 'CLARIFY', 'CONFLICT')
               and bool(re.search('没有找到|资料不足|未提供|未收录|没有.*证据|无法.*回答|缺少|尚不明确|未决|未.*提供|未找到', answer)))
    return dict(rank=rank, document_ids=ids, expected_document_coverage_at_5=
                len(expected.intersection(ids[:5])) / len(expected) if expected else None,
                all_expected_documents_at_5=expected.issubset(ids[:5]) if expected else None,
                status=status, answered=bool(response.get('answered')), warnings=retrieval.get('warnings', []),
                stages=retrieval.get('stages', []), citation_count=len(citations),
                citation_marks_valid=marks == actual_marks, citation_chunks_valid=chunks_valid,
                citation_expected_source=bool(expected.intersection(c['documentId'] for c in citations)),
                keypoint_coverage=sum(points) / len(points) if points else None,
                all_keypoints=all(points) if points else None, safe_refusal=refusal,
                context_chars=sum(len(h.get('content', '')) for h in hits))


def collect(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True, mode=0o700)
    if (output / 'responses.jsonl').exists():
        raise ValueError('Output already has responses; choose a new output directory')
    cases = dataset(args.corpus_dir.resolve())
    if args.limit:
        cases = cases[:args.limit]
    save(output / 'cases.json', cases)
    env, model = config(), profile()
    stamp = datetime.now(ZoneInfo('Asia/Shanghai')).strftime('%Y%m%d_%H%M%S') + '_' + secrets.token_hex(3)
    database = 'paios_ragas_' + stamp
    import httpx
    search = env.get('OPENSEARCH_ENABLED', 'false').lower() == 'true'
    search_index = 'personalos-ragas-' + stamp.replace('_', '-')
    original_index = env.get('OPENSEARCH_INDEX', 'personalos-chunks-v1')
    search_client = httpx.Client(base_url=env.get('OPENSEARCH_ENDPOINT', 'http://127.0.0.1:9200'),
        auth=(env.get('OPENSEARCH_USERNAME', ''), env.get('OPENSEARCH_PASSWORD', ''))
        if env.get('OPENSEARCH_USERNAME') else None, timeout=120, trust_env=False)
    processes = []
    created_index = False
    original = snapshot('personal_ai_os')
    meta = dict(started_at=datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
                commit=subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                dataset_sha256=hashlib.sha256(CASES.read_bytes()).hexdigest(), cases=len(cases),
                models={k:v for k,v in model.items() if k != 'api_key'},
                boot_embedding_provider=env.get('AI_EMBEDDING_PROVIDER', 'ollama'),
                boot_embedding_model=env.get('OPENAI_EMBEDDING_MODEL', 'text-embedding-3-small'),
                reranker_model=env.get('RAG_RERANKER_MODEL', ''),
                source_snapshot=original, database=database, opensearch_enabled=search,
                embedding_cache=False, candidate_cache=False, top_k=5,
                rag_budget_ms=env.get('RAG_BUDGET_MS', '20000'),
                evidence_timeout_ms=env.get('RAG_EVIDENCE_TIMEOUT_MS', '12000'),
                artifacts={}, limitations=['Existing chunk/index snapshot; ingestion quality not re-tested.',
                'One live run per backend/case; model nondeterminism and shared upstream latency remain.',
                'Source cards are not full articles; synthetic facts are not production settings.'])
    save(output / 'metadata.json', meta)
    try:
        clone(database)
        if snapshot(database) != original:
            raise ValueError('Knowledge snapshot mismatch; comparison aborted')
        if search:
            info = search_client.get('/' + original_index)
            info.raise_for_status()
            definition = next(iter(info.json().values()))
            response = search_client.put('/' + search_index, json={'mappings': definition['mappings'],
                'settings': {'number_of_shards': 1, 'number_of_replicas': 0, 'index.knn': True}})
            response.raise_for_status()
            created_index = True
            copied = search_client.post('/_reindex?refresh=true', json={
                'source': {'index': original_index}, 'dest': {'index': search_index}})
            copied.raise_for_status()
            if copied.json().get('failures'):
                raise ValueError('Search snapshot copy failed')
            meta['search_snapshot_documents'] = copied.json().get('total')
        chunks = {}
        for record in json.loads(sql(database, 'SELECT COALESCE(json_agg(t),\'[]\') FROM '
                '(SELECT metadata,md5(content) AS unused,content FROM vector_store) t')):
            data = record['metadata']
            chunks[(data['document_id'], int(data['chunk_index']))] = hashlib.sha256(record['content'].encode()).hexdigest()
        env.update(POSTGRES_DB=database, POSTGRES_HOST='127.0.0.1', APP_DOTENV_ENABLED='false',
            GO_DOTENV_ENABLED='false', GO_POSTGRES_SSL_MODE='disable',
            SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:' + env['POSTGRES_PORT'] + '/' + database,
            SPRING_PROFILES_ACTIVE='monitoring,runtime-api', APP_RUNTIME_ROLE='api',
            AUTH_ENABLED='true', APP_SECURITY_CSRF_ENABLED='true',
            AUTH_USERNAME='ragas-eval', AUTH_PASSWORD=secrets.token_urlsafe(24),
            APP_RATE_LIMIT_ENABLED='false', REDIS_RATE_LIMIT_ENABLED='false', REDIS_SESSION_ENABLED='false',
            REDIS_ENABLED='false', RABBITMQ_ENABLED='false', S3_ENABLED='false',
            WEB_PUSH_ENABLED='false', REMINDER_WEBHOOK_ENABLED='false',
            AI_CONTEXT_SUMMARY_ENABLED='false', AI_CONTEXT_AUTO_MEMORY_ENABLED='false',
            APP_MEMORY_BACKFILL_ENABLED='false', AI_CALENDAR_SCAN_ENABLED='false',
            MCP_ENABLED='false', PERSONAL_MCP_SERVER_ENABLED='false', SPRING_AI_MCP_SERVER_ENABLED='false',
            RAG_ENABLED='true', APP_KNOWLEDGE_JOBS_ENABLED='false',
            APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED='false',
            EMBEDDING_CACHE_ENABLED='false', RAG_CANDIDATE_CACHE_ENABLED='false',
            OPENSEARCH_INDEX=search_index, AI_CHAT_UPLOAD_DIR=str(output / 'uploads'))
        clients = {}
        for backend in ('java', 'go'):
            binary = args.java_jar.resolve() if backend == 'java' else args.go_binary.resolve()
            if not binary.is_file():
                raise FileNotFoundError('Build the current ' + backend + ' artifact first')
            artifact_hash = hashlib.sha256(binary.read_bytes()).hexdigest()
            meta['artifacts'][backend] = {'path':str(binary), 'sha256':artifact_hash}
            api, management = port(), port()
            values = dict(env, SERVER_PORT=str(api), MANAGEMENT_SERVER_PORT=str(management))
            log = output / (backend + '.log')
            command = ([runtime.JAVA, '-Xmx512m', '-Dapp.dotenv.enabled=false', '-jar', str(binary),
                        '--server.address=127.0.0.1', '--management.server.address=127.0.0.1',
                        '--app.knowledge.jobs.enabled=false', '--app.knowledge.rag.ingestion-review-enabled=false']
                       if backend == 'java' else [str(binary), '--foundation', '--p8', '--migrations',
                                                 str(ROOT / 'backend/src/main/resources/db/migration')])
            with log.open('w') as stream:
                log.chmod(0o600)
                process = subprocess.Popen(command, cwd=output, env=values, stdout=stream,
                    stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
            processes.append(process)
            live.ready(management, process)
            client = live.Client('http://127.0.0.1:' + str(api) + '/api/v1')
            client.token()
            client.request('/auth/login', 'POST', {'username':values['AUTH_USERNAME'], 'password':values['AUTH_PASSWORD']})
            client.token()
            clients[backend] = client
            print(backend + ': healthy current artifact', flush=True)
        save(output / 'metadata.json', meta)
        # Alternate order to reduce time-of-run/upstream drift. Never run two RAG requests at once.
        for index, case in enumerate(cases):
            order = ('java', 'go') if index % 2 == 0 else ('go', 'java')
            for backend in order:
                started = time.monotonic()
                row = dict(backend=backend, case_id=case['id'], category=case['category'])
                try:
                    response = clients[backend].request('/knowledge/ask', 'POST',
                        {'question':case['question'], 'topK':5}, timeout=120)
                    row.update(response=response, metrics=measure(case, response, chunks))
                except Exception as error:
                    row['error_type'] = type(error).__name__
                row['duration_ms'] = round(1000 * (time.monotonic() - started), 2)
                append(output / 'responses.jsonl', row)
                print(json.dumps({k:row[k] for k in ('backend','case_id','duration_ms')} |
                    {'status':row.get('metrics',{}).get('status'), 'error':row.get('error_type')}, ensure_ascii=False), flush=True)
        meta['snapshot_after'] = snapshot(database)
        meta['isolated_snapshot_unchanged'] = meta['snapshot_after'] == original
        meta['collection_complete'] = True
    finally:
        for process in processes:
            if process.poll() is None:
                process.terminate()
        for process in processes:
            process.wait(timeout=45)
        subprocess.run(['docker','exec',CONTAINER,'dropdb','-U','personalos','--if-exists',database],
                       check=True, capture_output=True)
        if created_index:
            search_client.delete('/' + search_index).raise_for_status()
        search_client.close()
        meta['cleanup_complete'] = True
        meta['finished_at'] = datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
        save(output / 'metadata.json', meta)


async def judge(args):
    import httpx
    import ragas
    from openai import AsyncOpenAI
    from ragas.llms import llm_factory
    from ragas.metrics.collections import Faithfulness, FactualCorrectness, ContextPrecision, ContextRecall
    output = args.output.resolve()
    cases = {row['id']:row for row in json.loads((output / 'cases.json').read_text())}
    old = {(row['backend'],row['case_id'],row['metric']):row for row in records(output / 'ragas.jsonl')}
    model = profile()
    metadata = json.loads((output / 'metadata.json').read_text())
    if not metadata.get('collection_complete'):
        raise ValueError('Finish collection before judging, so judge traffic cannot affect latency')
    responses = records(output / 'responses.jsonl')
    expected = {(backend, case) for backend in ('java', 'go') for case in cases}
    if len(responses) != len(expected) or {(r['backend'], r['case_id']) for r in responses} != expected:
        raise ValueError('Collection must contain exactly one response per backend/case')
    judge_model = args.judge_model or metadata['models']['chat_model']
    existing = json.loads((output / 'judge.json').read_text()) if (output / 'judge.json').exists() else {}
    if existing and existing.get('model') != judge_model:
        raise ValueError('Do not mix scores from different judge models in one output directory')
    usage = Counter()
    async def receipt(response):
        usage['requests'] += 1
        if response.status_code == 200:
            await response.aread()
            data = response.json().get('usage', {})
            usage.update({k:data.get(k,0) for k in ('prompt_tokens','completion_tokens','total_tokens')})
        else:
            usage['http_errors'] += 1
    transport = httpx.AsyncClient(timeout=90, event_hooks={'response':[receipt]})
    client = AsyncOpenAI(api_key=model['api_key'], base_url=model['base_url'],
                         max_retries=1, http_client=transport)
    llm = llm_factory(judge_model, client=client, temperature=0, max_tokens=4096,
                      extra_body={'enable_thinking':False})
    scorers = {'faithfulness':Faithfulness(llm=llm),
               'factual_correctness':FactualCorrectness(llm=llm, mode='f1'),
               'context_precision':ContextPrecision(llm=llm), 'context_recall':ContextRecall(llm=llm)}
    semaphore = asyncio.Semaphore(args.concurrency)
    async def score(row, name):
        case = cases[row['case_id']]
        key = (row['backend'],row['case_id'],name)
        if key in old and (old[key].get('value') is not None or old[key].get('reason') in
                ('backend_request_error', 'negative_control_separate_refusal_metric')):
            return
        response = row.get('response', {})
        contexts = [h['content'] for h in (response.get('retrieval') or {}).get('hits', [])]
        result = dict(backend=row['backend'], case_id=row['case_id'], metric=name)
        params = dict(response=response.get('answer',''), reference=case['reference'])
        if name == 'faithfulness':
            params = dict(user_input=case['question'], response=response.get('answer',''), retrieved_contexts=contexts)
        elif name.startswith('context_'):
            params = dict(user_input=case['question'], reference=case['reference'], retrieved_contexts=contexts)
        if row.get('error_type'):
            result.update(value=None, reason='backend_request_error')
        elif case['category'] == 'negative':
            result.update(value=None, reason='negative_control_separate_refusal_metric')
        elif not contexts and name != 'factual_correctness':
            # Missing evidence must not vanish from averages or call RAGAS on invalid input.
            result.update(value=0.0, reason='no_retrieved_context')
        else:
            async with semaphore:
                started = time.monotonic()
                try:
                    value = float((await asyncio.wait_for(scorers[name].ascore(**params), timeout=180)).value)
                    if not math.isfinite(value):
                        raise ValueError('Nonfinite metric')
                    result['value'] = value
                except Exception as error:
                    result.update(value=None, error_type=type(error).__name__)
                result['duration_ms'] = round(1000 * (time.monotonic() - started))
        append(output / 'ragas.jsonl', result)
        print(json.dumps(result, ensure_ascii=False), flush=True)
    try:
        await asyncio.gather(*(score(row,name) for row in responses for name in scorers))
    finally:
        await client.close()
        previous = existing.get('usage', {})
        usage.update(previous)
        save(output / 'judge.json', dict(ragas_version=ragas.__version__,
            model=judge_model, temperature=0,
            same_model_as_generator=judge_model == metadata['models']['chat_model'],
            concurrency=args.concurrency, usage=dict(usage),
            metrics=list(scorers), language='RAGAS default English prompts over Chinese inputs'))


def average(values):
    values = [v for v in values if v is not None]
    return statistics.mean(values) if values else None


def diagnostics(rows):
    warnings = Counter()
    stages = Counter()
    warning_responses = 0
    for row in rows:
        measured = row.get('metrics', {})
        if measured.get('warnings'):
            warning_responses += 1
        for warning in measured.get('warnings', []):
            warnings[warning if isinstance(warning, str) else json.dumps(warning, ensure_ascii=False)] += 1
        for stage in measured.get('stages', []):
            stages[stage['name'] + ':' + stage['status']] += 1
    return dict(warning_responses=warning_responses, warning_counts=dict(warnings), stage_status_counts=dict(stages))


def summarize(args):
    output = args.output.resolve()
    cases = {c['id']:c for c in json.loads((output / 'cases.json').read_text())}
    responses = records(output / 'responses.jsonl')
    latest = {(r['backend'],r['case_id'],r['metric']):r for r in records(output / 'ragas.jsonl')}
    keypoint_diagnostics = []
    for row in responses:
        required = cases[row['case_id']].get('required', [])
        answer = normalized(row.get('response', {}).get('answer', ''))
        points = [any(normalized(term) in answer for term in choices) for choices in required]
        coverage = sum(points) / len(points) if points else None
        complete = all(points) if points else None
        row.setdefault('metrics', {}).update(keypoint_coverage=coverage, all_keypoints=complete)
        keypoint_diagnostics.append(dict(backend=row['backend'], case_id=row['case_id'],
            matched=points, coverage=coverage, complete=complete))
    save(output / 'keypoint-diagnostics.json', dict(normalization='case, whitespace, Markdown escaped underscore',
        rows=keypoint_diagnostics))
    result = {}
    for backend in ('java','go'):
        rows = [r for r in responses if r['backend'] == backend]
        positive = [r for r in rows if cases[r['case_id']]['category'] != 'negative']
        negative = [r for r in rows if cases[r['case_id']]['category'] == 'negative']
        times = sorted(r['duration_ms'] for r in rows)
        ranks = [r.get('metrics',{}).get('rank') for r in positive]
        metrics = {name:dict(mean=average(latest.get((backend,r['case_id'],name),{}).get('value') for r in positive),
            scored=sum(latest.get((backend,r['case_id'],name),{}).get('value') is not None for r in positive),
            expected=len(positive)) for name in ('faithfulness','factual_correctness','context_precision','context_recall')}
        result[backend] = dict(total=len(rows), answerable=len(positive), negatives=len(negative),
            request_errors=sum('error_type' in r for r in rows),
            hit_at_1=average(rank == 1 for rank in ranks),
            hit_at_3=average(rank is not None and rank <= 3 for rank in ranks),
            hit_at_5=average(rank is not None and rank <= 5 for rank in ranks),
            mrr=average(1 / rank if rank else 0 for rank in ranks),
            all_expected_documents_at_5=average(r.get('metrics',{}).get('all_expected_documents_at_5',False) for r in positive),
            expected_document_coverage_at_5=average(r.get('metrics',{}).get('expected_document_coverage_at_5',0) for r in positive),
            answered_rate=average(r.get('metrics',{}).get('answered',False) for r in positive),
            all_keypoints_rate=average(r.get('metrics',{}).get('all_keypoints',False) for r in positive),
            keypoint_coverage=average(r.get('metrics',{}).get('keypoint_coverage',0) for r in positive),
            safe_refusal_rate=average(r.get('metrics',{}).get('safe_refusal',False) for r in negative),
            citation_integrity_rate=average(r['metrics']['citation_marks_valid'] and r['metrics']['citation_chunks_valid']
                for r in rows if r.get('metrics',{}).get('citation_count',0) > 0),
            citation_present_rate=average(r.get('metrics',{}).get('citation_count',0) > 0 for r in positive),
            p50_ms=statistics.median(times) if times else None,
            p95_ms=times[max(0,math.ceil(len(times)*.95)-1)] if times else None,
            status_counts=dict(Counter(r.get('metrics',{}).get('status','ERROR') for r in rows)),
            diagnostics=diagnostics(rows), ragas=metrics)
        result[backend]['categories'] = {}
        for category in sorted({cases[r['case_id']]['category'] for r in rows}):
            group = [r for r in rows if cases[r['case_id']]['category'] == category]
            result[backend]['categories'][category] = dict(count=len(group),
                ready=sum(r.get('metrics',{}).get('status') == 'READY' for r in group),
                all_keypoints=sum(r.get('metrics',{}).get('all_keypoints') is True for r in group),
                safe_refusals=sum(r.get('metrics',{}).get('safe_refusal') is True for r in group))
    paired = {}
    for name in ('faithfulness','factual_correctness','context_precision','context_recall'):
        deltas = [latest[('go',c,name)]['value'] - latest[('java',c,name)]['value']
            for c in cases if all(latest.get((b,c,name),{}).get('value') is not None for b in ('java','go'))]
        if deltas:
            import random
            rng = random.Random(20261106)
            samples = sorted(statistics.mean(rng.choices(deltas,k=len(deltas))) for _ in range(2000))
            paired[name] = dict(pairs=len(deltas), go_minus_java=average(deltas),
                paired_bootstrap_95_ci=[samples[49],samples[1949]])
    save(output / 'summary.json', dict(backends=result,paired_ragas=paired))
    print(json.dumps(dict(backends=result,paired_ragas=paired),ensure_ascii=False,indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=('collect','judge','summarize'))
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--corpus-dir',type=Path,default=ROOT / '.cache/manual-corpus-20261106/documents')
    parser.add_argument('--java-jar',type=Path,default=ROOT / 'backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--go-binary',type=Path,default=ROOT / '.cache/ragas-go-current')
    parser.add_argument('--limit',type=int,default=0)
    parser.add_argument('--concurrency',type=int,default=4)
    parser.add_argument('--judge-model',default=None,help='Defaults to configured active model; explicit override permitted')
    args = parser.parse_args()
    if args.command == 'collect':
        collect(args)
    elif args.command == 'judge':
        asyncio.run(judge(args))
    else:
        summarize(args)


if __name__ == '__main__':
    main()
