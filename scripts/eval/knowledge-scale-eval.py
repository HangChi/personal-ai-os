#!/usr/bin/env python3
"""Frozen-pool RRF vs cross-encoder evaluation on an isolated knowledge snapshot.
Reuses product recall and answer implementations through a local-only Java harness.
"""
import argparse
import asyncio
import contextvars
from collections import Counter
import importlib.util
import json
import math
import os
import re
from pathlib import Path
import random
import secrets
import statistics
import subprocess
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]
def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

scale = module('scale', Path(__file__).with_name('knowledge-scale.py'))
comparison = module('scale_comparison', Path(__file__).with_name('ragas-compare.py'))
save, load, sha, now = scale.save, scale.load, scale.sha, scale.now

def append(path, row):
    with path.open('a') as stream:
        stream.write(json.dumps(row, ensure_ascii=False, allow_nan=False) + chr(10))
    path.chmod(0o600)

def records(path):
    return [json.loads(line) for line in path.read_text().splitlines()] if path.exists() else []

def normalized(value):
    return ''.join(c.lower() for c in value if not c.isspace() and c not in '*_' + chr(96) + chr(92))

def rank_metrics(hits, expected):
    ids = list(dict.fromkeys(h['documentId'] for h in hits))
    gold = set(expected)
    rank = next((i + 1 for i, d in enumerate(ids) if d in gold), None)
    result = dict(rank=rank, reciprocal_rank=1 / rank if rank else 0, candidate_docs=len(ids),
        candidate_recall=len(gold.intersection(ids)) / len(gold) if gold else None)
    for k in [1, 3, 5, 10, 20]:
        gains = [int(d in gold) for d in ids[:k]]
        relevant = sum(gains)
        ideal = sum(1 / math.log2(i + 2) for i in range(min(k, len(gold))))
        result['precision_at_' + str(k)] = relevant / k
        result['recall_at_' + str(k)] = relevant / len(gold) if gold else None
        result['hit_at_' + str(k)] = int(relevant > 0)
        result['ndcg_at_' + str(k)] = sum(g / math.log2(i + 2) for i, g in enumerate(gains)) / ideal if ideal else None
        result['all_sources_at_' + str(k)] = gold.issubset(ids[:k]) if gold else None
    return result

def select_context(hits, limit=5):
    selected, counts = [], Counter()
    for cap in [1, 2]:
        for hit in hits:
            if len(selected) == limit:
                return selected
            key = (hit['documentId'], hit['chunkIndex'])
            if counts[hit['documentId']] < cap and all((h['documentId'], h['chunkIndex']) != key for h in selected):
                selected.append(hit)
                counts[hit['documentId']] += 1
    return selected

def prepare(args):
    out, corpus_path = args.output.resolve(), args.corpus.resolve()
    out.mkdir(parents=True, exist_ok=True, mode=0o700)
    if (out / 'cases.json').exists():
        raise ValueError('Cases already frozen; refusing to rewrite ground truth')
    corpus = load(corpus_path / 'corpus.json')
    new = {d['slug']: d for d in corpus['documents']}
    imported = load(corpus_path / 'import-manifest.json')['documents'] if (corpus_path / 'import-manifest.json').exists() else {}
    if any(imported.get(d['slug'],{}).get('status')!='SUCCEEDED' or
           not imported.get(d['slug'],{}).get('provenanceSaved') for d in corpus['documents']):
        raise ValueError('Finish and verify all corpus imports before freezing evaluation cases')
    old_manifest = load(args.legacy_corpus / 'import-manifest.json')['documents']
    rows = load(ROOT / 'scripts/eval/ragas-comparison-cases.json') + load(ROOT / 'scripts/eval/knowledge-scale-cases.json')
    by_slug = {}
    for slug, doc in new.items():
        content = (corpus_path / 'corpus' / doc['filename']).read_bytes()
        if sha(content) != doc['sha256']:
            raise ValueError('Source hash mismatch: ' + slug)
        doc_id = imported[slug]['documentId']
        by_slug[slug] = dict(document_id=doc_id, sha256=doc['sha256'], text=content.decode())
    for slug, doc in old_manifest.items():
        path = args.legacy_corpus / 'documents' / (slug + '.md')
        if sha(path.read_bytes()) != doc['sha256']:
            raise ValueError('Legacy source hash mismatch')
        by_slug[slug] = dict(document_id=doc['documentId'], sha256=doc['sha256'], text=path.read_text())
    for row in rows:
        text = chr(10).join(by_slug[s]['text'] for s in row['sources'])
        for anchor in row.get('anchors', []):
            if normalized(anchor) not in normalized(text):
                raise ValueError('Reference anchor missing: ' + row['id'] + ': ' + anchor)
        row['expected_document_ids'] = [by_slug[s]['document_id'] for s in row['sources']]
        row['source_sha256'] = {s: by_slug[s]['sha256'] for s in row['sources']}
        row['suite'] = 'semantic'
    referenced = {s for r in rows for s in r['sources']}
    sample = sorted((d for d in corpus['documents'] if d['slug'] not in referenced), key=lambda d: sha('holdout-20261108|' + d['slug']))[:args.title_cases]
    for doc in sample:
        rows.append(dict(id='title-' + doc['slug'], category='title_lookup', suite='title_lookup',
            question='查找' + doc['version'] + '参考文档：' + doc['title'].split('】')[-1],
            sources=[doc['slug']], reference='', expected_document_ids=[by_slug[doc['slug']]['document_id']],
            source_sha256={doc['slug']: doc['sha256']}))
    if len({r['id'] for r in rows}) != len(rows):
        raise ValueError('Duplicate case ID')
    save(out / 'cases.json', rows)
    save(out / 'dataset.json', dict(created_at=now(), cases=len(rows),expected_documents=corpus['target'],counts=dict(Counter(r['category'] for r in rows)),
        cases_sha256=sha((out / 'cases.json').read_bytes()), corpus_manifest_sha256=sha((corpus_path / 'corpus.json').read_bytes()),
        references_frozen_before_responses=True, relevance_labels='Known supporting documents; not exhaustive pooled human relevance judgments',
        title_lookup='Deterministic document lookup sample; reported separately from semantic questions'))
    print(json.dumps(load(out / 'dataset.json'), ensure_ascii=False, indent=2), flush=True)

def rerank(client, env, query, hits):
    if not hits:
        return [], {}
    docs = [h['content'][:2000] for h in hits]
    model = env['RAG_RERANKER_MODEL']
    protocol = env.get('RAG_RERANKER_PROTOCOL', 'cohere')
    payload = dict(model=model, query=query, documents=docs, top_n=len(docs))
    if protocol == 'dashscope':
        payload = dict(model=model, input=dict(query=query, documents=docs),
                       parameters=dict(top_n=len(docs), return_documents=False))
    response = client.post(env['RAG_RERANKER_ENDPOINT'], json=payload,
        headers={'Authorization': 'Bearer ' + env.get('RAG_RERANKER_API_KEY', '')})
    response.raise_for_status()
    data = response.json()
    ranks = data.get('output', data)['results']
    if len(ranks) != len(hits) or {r['index'] for r in ranks} != set(range(len(hits))):
        raise ValueError('Incomplete or duplicate rerank indices')
    if any(not math.isfinite(r['relevance_score']) or not 0 <= r['relevance_score'] <= 1 for r in ranks):
        raise ValueError('Invalid relevance score')
    ranks.sort(key=lambda r: r['relevance_score'], reverse=True)
    return [hits[r['index']] for r in ranks], dict(results=ranks, usage=data.get('usage', {}))

def response_metrics(case, response, contexts, chunks):
    answer = response.get('answer', '')
    citations = response.get('citations', [])
    marks = {int(x) for x in re.findall(r'\[(\d+)\]', answer)}
    required = case.get('required', [])
    points = [any(normalized(t) in normalized(answer) for t in choices) for choices in required]
    text = normalized(chr(10).join(h['content'] for h in contexts))
    anchors = case.get('anchors', [])
    return dict(citation_marks_valid=(marks == {c['index'] for c in citations}) if marks or citations else None,
        citation_chunks_valid=all(chunks.get((c['documentId'], c['chunkIndex'])) == sha(c['content']) for c in citations) if citations else None,
        has_citations=bool(citations),
        citation_count=len(citations), keypoint_coverage=sum(points) / len(points) if points else None,
        all_keypoints=all(points) if points else None,
        anchor_coverage=sum(normalized(a) in text for a in anchors) / len(anchors) if anchors else None,
        context_chars=sum(len(h['content']) for h in contexts),
        refusal_text=bool(re.search('没有.{0,8}(相关|内容|提供|找到)|无法.{0,8}(回答|确定)|资料不足|缺少.{0,8}(资料|信息|证据)|未.{0,6}(收录|提供|找到)', answer)),
        answered_flag=response.get('answered'))

def collect(args):
    import httpx
    out = args.output.resolve()
    cases = load(out / 'cases.json')
    if load(out/'dataset.json').get('collection_manifest_pending'):
        raise ValueError('Bind the frozen questions to the final verified collection before collecting responses')
    env, model = comparison.config(), comparison.profile()
    original = comparison.snapshot('personal_ai_os')
    if original['knowledge_document']['count'] != load(out / 'dataset.json')['expected_documents']:
        raise ValueError('Knowledge size no longer matches the frozen dataset')
    previous = load(out / 'metadata.json') if (out / 'metadata.json').exists() else {}
    if previous and previous.get('cleanup_complete') and not records(out/'responses.jsonl') and not records(out/'candidates.jsonl'):
        append(out/'preflight-history.jsonl',previous)
        previous = {}
    artifact_hash = sha(args.java_jar.read_bytes())
    collector_hash=sha(Path(__file__).read_bytes())
    harness_hash = sha((ROOT / 'scripts/eval/java/KnowledgeScaleHarness.java').read_bytes())
    cases_hash = sha((out / 'cases.json').read_bytes())
    if previous.get('collection_complete'):
        raise ValueError('Collection already complete')
    if previous and previous.get('collector_script_sha256',collector_hash)!=collector_hash:
        raise ValueError('Cannot resume with a changed collector script')
    if previous and previous['source_snapshot'] != original:
        raise ValueError('Cannot resume against a changed knowledge snapshot')
    if previous and any(previous[k] != v for k,v in dict(artifact_sha256=artifact_hash,
            harness_sha256=harness_hash,cases_sha256=cases_hash,
            reranker_model=env['RAG_RERANKER_MODEL'],
            boot_embedding_provider=env.get('AI_EMBEDDING_PROVIDER'),
            boot_embedding_model=env.get('OPENAI_EMBEDDING_MODEL'),
            models={k:v for k,v in model.items() if k!='api_key'}).items()):
        raise ValueError('Cannot resume with changed models, artifact, harness or dataset')
    protocol = env.get('RAG_RERANKER_PROTOCOL','cohere')
    endpoint_hash = sha(env['RAG_RERANKER_ENDPOINT'])
    if previous and (previous.get('reranker_protocol')!=protocol or previous.get('reranker_endpoint_sha256')!=endpoint_hash):
        raise ValueError('Cannot resume with an unverified or changed reranker protocol/endpoint')
    stamp = time.strftime('%Y%m%d_%H%M%S') + '_' + secrets.token_hex(3)
    database, index = 'paios_ragas_' + stamp, 'personalos-scale-' + stamp.replace('_', '-')
    meta = dict(previous, started_at=previous.get('started_at', now()), source_snapshot=original,
        models={k:v for k,v in model.items() if k != 'api_key'}, reranker_model=env['RAG_RERANKER_MODEL'],
        reranker_protocol=protocol,reranker_endpoint_sha256=endpoint_hash,
        cleanup_complete=False,collection_complete=False,
        artifact_sha256=artifact_hash,harness_sha256=harness_hash,candidate_limit=40,context_limit=args.context_limit,
        collector_script_sha256=collector_hash,
        commit=subprocess.check_output(['git','rev-parse','HEAD'],cwd=ROOT,text=True).strip(),
        worktree_dirty=bool(subprocess.check_output(['git','status','--porcelain'],cwd=ROOT,text=True).strip()),
        boot_embedding_provider=env.get('AI_EMBEDDING_PROVIDER'),
        boot_embedding_model=env.get('OPENAI_EMBEDDING_MODEL'),
        rerank_input_chars=2000, embedding_cache=False, candidate_cache=False, temperature=0,
        cases_sha256=cases_hash,database=database,search_index=index,
        experiment='Frozen business candidate pool: RRF vs cross-encoder; not production pipeline A/B',
        limitations=['Known-source qrels are not exhaustive relevance judgments.',
            'Fixed top-5 diversified contexts bypass production evidence sufficiency and conflict guards.',
            'Negative cases measure textual refusal only, not production guarded status.',
            'Title lookup is reported separately. One run per question and variant.'])
    meta['include_pipeline'] = args.include_pipeline
    meta['temperature'] = model['temperature'] if args.preserve_temperature else 0
    if previous and (previous.get('include_pipeline', False) != args.include_pipeline or previous.get('temperature') != meta['temperature'] or previous.get('context_limit')!=args.context_limit):
        raise ValueError('Cannot resume with changed pipeline or temperature configuration')
    if args.include_pipeline:
        meta['experiment']='Frozen-pool RRF/cross-encoder ablation plus a separately reported native production pipeline'
        meta['pipeline_budget_ms']=int(env.get('RAG_BUDGET_MS','20000'))
        meta['evidence_timeout_ms']=int(env.get('RAG_EVIDENCE_TIMEOUT_MS','12000'))
        meta['limitations']=['Baseline/rerank use a shared original-query candidate pool and bypass evidence guards.',
            'Production uses native rewrite, rerank, evidence review, conflict and refusal behavior; it is not an isolated reranker ablation.',
            'Single run per question; actual selected evidence is independently model judged.']
    save(out / 'metadata.json', meta)
    client = httpx.Client(timeout=120, trust_env=False)
    search = httpx.Client(base_url=env.get('OPENSEARCH_ENDPOINT', 'http://127.0.0.1:9200'),
        auth=(env['OPENSEARCH_USERNAME'], env.get('OPENSEARCH_PASSWORD', '')) if env.get('OPENSEARCH_USERNAME') else None,
        timeout=180, trust_env=False)
    process, created_db, created_index = None, False, False
    try:
        created_db = True
        comparison.clone(database)
        if comparison.snapshot(database) != original:
            raise ValueError('Snapshot copy mismatch')
        if not args.preserve_temperature:
            comparison.sql(database, 'UPDATE ai_model_profile SET temperature=0 WHERE active')
        if env.get('OPENSEARCH_ENABLED', 'false').lower() == 'true':
            info = search.get('/' + env.get('OPENSEARCH_INDEX', 'personalos-chunks-v1'))
            info.raise_for_status()
            definition = next(iter(info.json().values()))
            r = search.put('/' + index, json=dict(mappings=definition['mappings'],
                settings={'number_of_shards':1,'number_of_replicas':0,'index.knn':True}))
            r.raise_for_status()
            created_index = True
            r = search.post('/_reindex?refresh=true', json={'source':{'index':env.get('OPENSEARCH_INDEX','personalos-chunks-v1')},'dest':{'index':index}})
            r.raise_for_status()
            if r.json().get('failures'):
                raise ValueError('Search snapshot failed')
            meta['search_snapshot_documents'] = r.json().get('total')
        raw = json.loads(comparison.sql(database, "SELECT coalesce(json_agg(t),'[]') FROM (SELECT metadata,content FROM vector_store)t"))
        chunks = {(r['metadata']['document_id'],int(r['metadata']['chunk_index'])):sha(r['content']) for r in raw}
        stage = out / 'java-stage'
        stage.mkdir(exist_ok=True)
        with zipfile.ZipFile(args.java_jar) as jar:
            for name in jar.namelist():
                if name.startswith(('BOOT-INF/classes/', 'BOOT-INF/lib/')):
                    jar.extract(name, stage)
        classpath = str(stage / 'BOOT-INF/classes') + os.pathsep + str(stage / 'BOOT-INF/lib/*')
        java = Path(comparison.runtime.JAVA)
        subprocess.run([str(java.with_name('javac')), '-cp', classpath, '-d', str(stage),
            str(ROOT / 'scripts/eval/java/KnowledgeScaleHarness.java')], check=True, capture_output=True)
        api_port, management = comparison.port(), comparison.port()
        password = secrets.token_urlsafe(24)
        env.update(POSTGRES_DB=database, POSTGRES_HOST='127.0.0.1', APP_DOTENV_ENABLED='false',
            SPRING_DATASOURCE_URL='jdbc:postgresql://127.0.0.1:' + env['POSTGRES_PORT'] + '/' + database,
            SPRING_PROFILES_ACTIVE='monitoring,runtime-api', APP_RUNTIME_ROLE='api',
            AUTH_ENABLED='true', APP_SECURITY_CSRF_ENABLED='true', AUTH_USERNAME='scale-eval', AUTH_PASSWORD=password,
            SERVER_PORT=str(api_port), MANAGEMENT_SERVER_PORT=str(management), KNOWLEDGE_SCALE_ISOLATED_EVAL='true',
            APP_RATE_LIMIT_ENABLED='false', REDIS_RATE_LIMIT_ENABLED='false', REDIS_SESSION_ENABLED='false',
            REDIS_ENABLED='false', RABBITMQ_ENABLED='false', S3_ENABLED='false', WEB_PUSH_ENABLED='false',
            REMINDER_WEBHOOK_ENABLED='false', AI_CONTEXT_SUMMARY_ENABLED='false', AI_CONTEXT_AUTO_MEMORY_ENABLED='false',
            APP_MEMORY_BACKFILL_ENABLED='false', AI_CALENDAR_SCAN_ENABLED='false', MCP_ENABLED='false',
            PERSONAL_MCP_SERVER_ENABLED='false', SPRING_AI_MCP_SERVER_ENABLED='false', RAG_ENABLED='true' if args.include_pipeline else 'false',
            APP_KNOWLEDGE_JOBS_ENABLED='false', APP_KNOWLEDGE_RAG_INGESTION_REVIEW_ENABLED='false',
            EMBEDDING_CACHE_ENABLED='false', RAG_CANDIDATE_CACHE_ENABLED='false', OPENSEARCH_INDEX=index,
            AI_CHAT_UPLOAD_DIR=str(out / 'uploads'))
        with (out / 'java.log').open('a') as log:
            (out / 'java.log').chmod(0o600)
            process = subprocess.Popen([str(java), '-Xmx768m', '-Dapp.dotenv.enabled=false', '-cp',
                str(stage) + os.pathsep + classpath, 'evaluation.KnowledgeScaleHarness',
                '--server.address=127.0.0.1','--management.server.address=127.0.0.1'],
                cwd=out, env=env, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
        comparison.live.ready(management, process)
        api = scale.Api('http://127.0.0.1:' + str(api_port) + '/api/v1',
                        {'AUTH_USERNAME':'scale-eval','AUTH_PASSWORD':password})
        pools = {r['case_id']:r for r in records(out / 'candidates.jsonl')}
        done = {(r['variant'],r['case_id']) for r in records(out / 'responses.jsonl')}
        save(out / 'metadata.json', meta)
        for i, case in enumerate(cases):
            if case['id'] not in pools:
                start = time.monotonic()
                hits = api.call('POST', '/knowledge/eval/candidates', {'query':case['question']})
                recall_ms = 1000 * (time.monotonic() - start)
                if not all(chunks.get((h['documentId'],h['chunkIndex'])) == sha(h['content']) for h in hits):
                    raise ValueError('Candidate content does not match frozen snapshot')
                start = time.monotonic()
                ordered, receipt = rerank(client, env, case['question'], hits)
                pool = dict(case_id=case['id'], baseline=hits, rerank=ordered, recall_ms=recall_ms,
                    rerank_ms=1000 * (time.monotonic()-start), rerank_receipt=receipt)
                append(out / 'candidates.jsonl', pool)
                pools[case['id']] = pool
            pool = pools[case['id']]
            order = ['baseline','rerank'] if i % 2 == 0 else ['rerank','baseline']
            for variant in order:
                if (variant,case['id']) in done:
                    continue
                hits = pool[variant]
                contexts = select_context(hits,args.context_limit)
                row = dict(variant=variant, case_id=case['id'], suite=case['suite'], category=case['category'],
                    ranking=rank_metrics(hits,case['expected_document_ids']), contexts=contexts,
                    recall_ms=pool['recall_ms'], rerank_ms=pool['rerank_ms'] if variant=='rerank' else 0)
                start = time.monotonic()
                if case['suite'] == 'semantic':
                    try:
                        response = api.call('POST','/knowledge/eval/answer',dict(question=case['question'],hits=contexts))
                        row.update(response=response, metrics=response_metrics(case,response,contexts,chunks))
                    except Exception as error:
                        row['error_type'] = type(error).__name__
                row['generation_ms'] = 1000 * (time.monotonic()-start)
                row['total_ms'] = row['recall_ms']+row['rerank_ms']+row['generation_ms']
                append(out / 'responses.jsonl',row)
                print(json.dumps(dict(case=case['id'],variant=variant,rank=row['ranking']['rank'],
                    ms=round(row['total_ms']),error=row.get('error_type')),ensure_ascii=False),flush=True)
            if args.include_pipeline and ('production',case['id']) not in done and case['suite']=='semantic':
                start=time.monotonic()
                row=dict(variant='production',case_id=case['id'],suite=case['suite'],category=case['category'])
                try:
                    response=api.call('POST','/knowledge/ask',dict(question=case['question'],topK=args.context_limit))
                    contexts=response.get('retrieval',{}).get('hits',[])
                    if not all(chunks.get((h['documentId'],h['chunkIndex'])) == sha(h['content']) for h in contexts):
                        raise ValueError('Production evidence changed from frozen chunks')
                    row.update(response=response,contexts=contexts,metrics=response_metrics(case,response,contexts,chunks),
                        ranking=rank_metrics(contexts,case['expected_document_ids']),ranking_scope='selected_evidence_only')
                except Exception as error:
                    row.update(error_type=type(error).__name__,contexts=[])
                row['total_ms']=1000*(time.monotonic()-start)
                append(out/'responses.jsonl',row)
                print(json.dumps(dict(case=case['id'],variant='production',status=row.get('response',{}).get('retrieval',{}).get('status'),
                    ms=round(row['total_ms']),error=row.get('error_type')),ensure_ascii=False),flush=True)
        meta['collection_complete'] = True
        after_snapshot=comparison.snapshot(database)
        meta['isolated_snapshot_unchanged'] = after_snapshot==original
        meta['isolated_core_unchanged'] = all(after_snapshot[t]==original[t] for t in original if t!='knowledge_conflict')
        meta['isolated_post_snapshot']=after_snapshot
        meta['original_snapshot_unchanged'] = comparison.snapshot('personal_ai_os')==original
        if not meta['isolated_core_unchanged'] or not meta['original_snapshot_unchanged']:
            meta['collection_complete']=False
            raise ValueError('Knowledge snapshot changed during collection; judgments must not proceed')
    finally:
        if process and process.poll() is None:
            process.terminate()
            process.wait(timeout=45)
        if created_db:
            subprocess.run(['docker','exec',comparison.CONTAINER,'dropdb','-U','personalos','--if-exists',database],check=True,capture_output=True)
        if created_index:
            search.delete('/'+index).raise_for_status()
        search.close()
        client.close()
        meta.update(cleanup_complete=True, finished_at=now())
        save(out / 'metadata.json',meta)

async def judge(args):
    import httpx
    import ragas
    from openai import AsyncOpenAI
    from ragas.llms import llm_factory
    from ragas.metrics.collections import Faithfulness, FactualCorrectness, ContextPrecision, ContextRecall, AnswerRelevancy
    from ragas.embeddings import OpenAIEmbeddings
    out = args.output.resolve()
    meta = load(out / 'metadata.json')
    if not meta.get('collection_complete'):
        raise ValueError('Complete collection before judging')
    cases = {r['id']:r for r in load(out / 'cases.json')}
    rows = records(out / 'responses.jsonl')
    variants=['baseline','rerank']+(['production'] if meta.get('include_pipeline') else [])
    expected = {(v,c) for v in variants for c in cases if v!='production' or cases[c]['suite']=='semantic'}
    if len(rows)!=len(expected) or {(r['variant'],r['case_id']) for r in rows} != expected:
        raise ValueError('Require exactly one response for every case and variant')
    old = {(r['variant'],r['case_id'],r['metric']):r for r in records(out / 'ragas.jsonl')}
    model = comparison.profile()
    env = comparison.config()
    judge_model = args.judge_model or meta['models']['chat_model']
    previous = load(out / 'judge.json') if (out / 'judge.json').exists() else {}
    if previous and previous['model'] != judge_model:
        raise ValueError('Cannot mix judge models')
    usage = Counter(previous.get('usage',{}))
    async def receipt(response):
        usage['requests'] += 1
        kind = 'embedding' if response.request.url.path.endswith('/embeddings') else 'chat'
        usage[kind + '_requests'] += 1
        if response.status_code == 200:
            await response.aread()
            data = response.json().get('usage',{})
            usage.update({k:data.get(k,0) for k in ['prompt_tokens','completion_tokens','total_tokens']})
            usage[kind + '_total_tokens'] += data.get('total_tokens',0)
        else:
            usage['http_errors'] += 1
    transport = httpx.AsyncClient(timeout=90,event_hooks={'response':[receipt]},trust_env=False)
    client = AsyncOpenAI(api_key=model['api_key'],base_url=model['base_url'],max_retries=1,http_client=transport)
    embedding_client = AsyncOpenAI(api_key=env.get('OPENAI_API_KEY') or model['api_key'],
        base_url=env.get('OPENAI_BASE_URL') or model['base_url'],max_retries=1,http_client=transport)
    embedding_model = meta['boot_embedding_model']
    embeddings = OpenAIEmbeddings(client=embedding_client,model=embedding_model)
    llm = llm_factory(judge_model,client=client,temperature=0,max_tokens=4096,extra_body={'enable_thinking':False})
    trace_context=contextvars.ContextVar('ragas_case_metric',default={})
    if args.trace_judge:
        original_generate=llm.agenerate
        async def traced_generate(prompt,response_model):
            result=await original_generate(prompt,response_model)
            append(out/'ragas-traces.jsonl',dict(**trace_context.get(),schema=response_model.__name__,
                prompt=prompt,parsed=result.model_dump(mode='json'),recorded_at=now()))
            return result
        llm.agenerate=traced_generate
    scorers = dict(faithfulness=Faithfulness(llm=llm),factual_correctness=FactualCorrectness(llm=llm,mode='f1'),
        context_precision=ContextPrecision(llm=llm),context_recall=ContextRecall(llm=llm),
        answer_relevancy=AnswerRelevancy(llm=llm,embeddings=embeddings,strictness=3))
    semaphore = asyncio.Semaphore(args.concurrency)
    async def score(row,name):
        case = cases[row['case_id']]
        if case['suite']!='semantic' or not case['expected_document_ids']:
            return
        key = (row['variant'],row['case_id'],name)
        if key in old and (old[key].get('value') is not None or old[key].get('reason')=='generation_error'):
            return
        result = dict(variant=row['variant'],case_id=row['case_id'],metric=name)
        answer = row.get('response',{}).get('answer','')
        contexts = [h['content'] for h in row['contexts']]
        params = dict(response=answer,reference=case['reference'])
        if name=='faithfulness':
            params = dict(user_input=case['question'],response=answer,retrieved_contexts=contexts)
        elif name=='answer_relevancy':
            params = dict(user_input=case['question'],response=answer)
        elif name.startswith('context_'):
            params = dict(user_input=case['question'],reference=case['reference'],retrieved_contexts=contexts)
        if row.get('error_type'):
            result.update(value=None,reason='generation_error')
        elif not contexts and name not in ('factual_correctness','answer_relevancy'):
            result.update(value=0.,reason='empty_context')
        else:
            async with semaphore:
                start = time.monotonic()
                try:
                    trace_context.set(dict(variant=row['variant'],case_id=row['case_id'],metric=name))
                    value = float((await asyncio.wait_for(scorers[name].ascore(**params),180)).value)
                    if not math.isfinite(value):
                        raise ValueError('Nonfinite RAGAS score')
                    result['value'] = value
                except Exception as error:
                    result.update(value=None,error_type=type(error).__name__)
                result['duration_ms'] = round(1000*(time.monotonic()-start))
        append(out / 'ragas.jsonl',result)
        print(json.dumps(result,ensure_ascii=False),flush=True)
    try:
        await asyncio.gather(*(score(row,name) for row in rows for name in scorers))
    finally:
        await client.close()
        await embedding_client.close()
        save(out / 'judge.json',dict(ragas_version=ragas.__version__,model=judge_model,
            same_model_as_generator=judge_model==meta['models']['chat_model'],temperature=0,concurrency=args.concurrency,
            embedding_model=embedding_model,answer_relevancy_strictness=3,
            usage=dict(usage),metrics=list(scorers),factual_correctness_mode='f1',
            parsed_intermediates_saved=args.trace_judge,
            language='RAGAS default English prompts over Chinese questions and mixed-language contexts'))

def mean(values):
    values = [float(v) for v in values if v is not None]
    return statistics.mean(values) if values else None

def quantile(values,p):
    values = sorted(values)
    if not values:
        return None
    x = (len(values)-1)*p
    lo = int(x)
    return values[lo]+(values[min(lo+1,len(values)-1)]-values[lo])*(x-lo)

def paired_delta(left,right):
    keys = sorted(set(left) & set(right))
    values = [right[k]-left[k] for k in keys]
    if not values:
        return dict(n=0,delta=None,ci95=None)
    rng = random.Random(20261108)
    samples = sorted(statistics.mean(rng.choices(values,k=len(values))) for _ in range(2000))
    return dict(n=len(values),delta=statistics.mean(values),ci95=[quantile(samples,.025),quantile(samples,.975)])

def summarize(args):
    out = args.output.resolve()
    rows = records(out / 'responses.jsonl')
    cases = {r['id']:r for r in load(out / 'cases.json')}
    scores = {(r['variant'],r['case_id'],r['metric']):r for r in records(out / 'ragas.jsonl')}
    groups = {}
    for group in ['semantic_positive','new_fulltext','legacy_positive','negative','title_lookup'] + sorted({r['category'] for r in cases.values()}):
        group_rows = [r for r in rows if (group=='semantic_positive' and r['suite']=='semantic' and cases[r['case_id']]['expected_document_ids'])
            or (group=='new_fulltext' and r['category'] in ['official_python','official_postgres','juejin_full'])
            or (group=='legacy_positive' and r['suite']=='semantic' and cases[r['case_id']]['expected_document_ids'] and r['category'] not in ['official_python','official_postgres','juejin_full'])
            or (group=='negative' and not cases[r['case_id']]['expected_document_ids'])
            or r['category']==group]
        entry = {}
        for variant in ['baseline','rerank']:
            selected = [r for r in group_rows if r['variant']==variant]
            valid = [r for r in selected if not r.get('error_type')]
            rank_keys = ['reciprocal_rank','candidate_recall','candidate_docs']+[m+'_at_'+str(k) for k in [1,3,5,10,20] for m in ['precision','recall','hit','ndcg','all_sources']]
            entry[variant] = dict(n=len(selected),errors=len(selected)-len(valid),
                retrieval={k:mean(r['ranking'].get(k) for r in selected) for k in rank_keys},
                answer={k:mean(r.get('metrics',{}).get(k) for r in valid) for k in
                    ['keypoint_coverage','all_keypoints','anchor_coverage','has_citations','citation_marks_valid','citation_chunks_valid','refusal_text']},
                latency={k:dict(p50=quantile([r[k] for r in valid],.5),p95=quantile([r[k] for r in valid],.95))
                    for k in ['recall_ms','rerank_ms','generation_ms','total_ms']},ragas={})
            if cases and group!='title_lookup':
                for name in ['faithfulness','factual_correctness','context_precision','context_recall','answer_relevancy']:
                    eligible = [r for r in selected if cases[r['case_id']]['expected_document_ids'] and r['suite']=='semantic']
                    metric_rows = [scores.get((variant,r['case_id'],name),{}) for r in eligible]
                    values = [r.get('value') for r in metric_rows if r.get('value') is not None]
                    entry[variant]['ragas'][name] = dict(mean=mean(values),valid=len(values),eligible=len(eligible),
                        failed_or_missing=len(eligible)-len(values))
        entry['paired_delta_rerank_minus_baseline'] = {}
        for name in ['precision_at_5','recall_at_5','hit_at_1','ndcg_at_5','reciprocal_rank']:
            data = [{r['case_id']:r['ranking'][name] for r in group_rows if r['variant']==v and r['ranking'].get(name) is not None} for v in ['baseline','rerank']]
            entry['paired_delta_rerank_minus_baseline'][name] = paired_delta(*data)
        for name in ['faithfulness','factual_correctness','context_precision','context_recall','answer_relevancy']:
            data = [{r['case_id']:scores[(v,r['case_id'],name)]['value'] for r in group_rows
                if r['variant']==v and scores.get((v,r['case_id'],name),{}).get('value') is not None} for v in ['baseline','rerank']]
            entry['paired_delta_rerank_minus_baseline'][name] = paired_delta(*data)
        groups[group] = entry
    save(out / 'summary.json',dict(created_at=now(),groups=groups,
        qrels='Known supporting documents only; unjudged documents treated as nonrelevant; Precision is conservative.',
        rank_unit='Unique documents ordered by first chunk occurrence; fixed k denominator.',
        ci='Paired bootstrap across questions, 2000 resamples, seed 20261108; not repeated-run uncertainty.'))
    print(json.dumps(groups['semantic_positive'],ensure_ascii=False,indent=2),flush=True)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command',choices=['prepare','collect','judge','summarize'])
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--corpus',type=Path,default=ROOT/'.cache/knowledge-1000-20261107')
    parser.add_argument('--legacy-corpus',type=Path,default=ROOT/'.cache/manual-corpus-20261106')
    parser.add_argument('--title-cases',type=int,default=60)
    parser.add_argument('--java-jar',type=Path,default=ROOT/'.cache/local-runtime/releases/backend-scale-batch16-20261108.jar')
    parser.add_argument('--judge-model')
    parser.add_argument('--concurrency',type=int,default=4)
    parser.add_argument('--include-pipeline',action='store_true',help='Also exercise the real production QA endpoint in isolation')
    parser.add_argument('--preserve-temperature',action='store_true',help='Keep the active product model temperature')
    parser.add_argument('--trace-judge',action='store_true',help='Save parsed RAGAS fact/NLI/reverse-question intermediates privately')
    parser.add_argument('--context-limit',type=int,choices=range(1,21),default=5)
    args = parser.parse_args()
    if args.command=='judge':
        asyncio.run(judge(args))
    else:
        globals()[args.command](args)

if __name__=='__main__':
    main()
