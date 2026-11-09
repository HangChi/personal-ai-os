'use strict';

// Real native database, Flyway, authentication, model calls, restart persistence,
// API streaming proxy and graceful shutdown. Model requests stay on loopback.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const { ConfigStore } = require('../src/config.cjs');
const { LocalRuntime } = require('../src/runtime.cjs');
if (process.platform !== 'win32') process.umask(0o077);
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'personal-ai-os-desktop-smoke-'));
const resources = path.resolve(process.env.DESKTOP_SMOKE_RESOURCES || path.join(__dirname, '../resources'));
const report = path.resolve(process.env.DESKTOP_SMOKE_REPORT || path.join(__dirname, '../artifacts/smoke.json'));
const requests = [];

async function main() {
  const model = http.createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
    requests.push({ path: req.url, model: body.model, service: req.socket.localPort === model.address().port ? 'chat' : 'embedding' });
    res.setHeader('Content-Type', 'application/json');
    if (req.url.includes('embeddings')) {
      const inputs = Array.isArray(body.input) ? body.input : [body.input];
      res.end(JSON.stringify({ object: 'list', model: 'smoke-embedding',
        data: inputs.map((_value, index) => ({ object: 'embedding', index, embedding: [1, ...Array(1023).fill(0)] })),
        usage: { prompt_tokens: 1, total_tokens: 1 } }));
    } else if (body.stream) {
      res.setHeader('Content-Type', 'text/event-stream');
      for (const [delta, finish] of [[{ role: 'assistant', content: '本地桌面模型连接成功。' }, null], [{}, 'stop']]) {
        res.write(`data: ${JSON.stringify({ id: 'smoke', object: 'chat.completion.chunk', created: 1,
          model: 'smoke-chat', choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`);
      }
      res.end('data: [DONE]\n\n');
    } else {
      res.end(JSON.stringify({ id: 'smoke', object: 'chat.completion', created: 1, model: 'smoke-chat',
        choices: [{ index: 0, message: { role: 'assistant', content: '本地桌面模型连接成功。' }, finish_reason: 'stop' }],
        usage: { prompt_tokens: 1, completion_tokens: 1, total_tokens: 2 } }));
    }
  });
  await new Promise(resolve => model.listen(0, '127.0.0.1', resolve));
  const embeddingModel = http.createServer(model.listeners('request')[0]);
  await new Promise(resolve => embeddingModel.listen(0, '127.0.0.1', resolve));
  const modelUrl = `http://127.0.0.1:${model.address().port}`;
  const store = new ConfigStore(directory);
  const config = store.configure({ chatUrl: modelUrl, chatModel: 'smoke-chat', chatKey: '',
    embeddingUrl: `http://127.0.0.1:${embeddingModel.address().port}`, embeddingModel: 'smoke-embedding', embeddingKey: '' });
  let runtime;
  const stages = [];
  try {
    runtime = new LocalRuntime(resources, directory, config, state => { stages.push(state.stage); console.log(state.message); });
    let { origin, cookie } = await runtime.start();
    assert.equal(requests.length, 0, 'startup must not contact model');
    assert.equal((await fetch(`${origin}/api/v1/tasks`)).status, 401);
    assert.equal((await fetch(`${origin}/api/v1/health`, { headers: { Origin: 'https://untrusted.example' } })).status, 403);
    assert.match(await (await fetch(origin)).text(), /<html/);
    assert.equal((await fetch(`${origin}/tasks`)).status, 200, 'SPA fallback');
    let headers = { Cookie: `JSESSIONID=${cookie}` };
    const csrf = (await (await fetch(`${origin}/api/v1/auth/csrf`, { headers })).json()).data.token;
    headers = { ...headers, 'Content-Type': 'application/json', 'X-CSRF-TOKEN': csrf };
    const call = async (url, body, method = 'POST') => {
      const response = await fetch(`${origin}/api/v1${url}`, { method, headers, body: JSON.stringify(body), signal: AbortSignal.timeout(60000) });
      const result = await response.json();
      assert.equal(response.ok, true, JSON.stringify(result));
      assert.equal(result.code, 0, JSON.stringify(result));
      return result.data;
    };
    const rejected = await fetch(`${origin}/api/v1/tasks`, { method: 'POST',
      headers: { Cookie: `JSESSIONID=${cookie}`, 'Content-Type': 'application/json' }, body: JSON.stringify({ title: 'rejected' }) });
    assert.equal(rejected.status, 403, 'CSRF required');
    const task = await call('/tasks', { title: 'Desktop persistence smoke test', priority: 'MEDIUM' });
    assert.ok(task.id);
    const connection = await call('/settings/model/test', {});
    assert.ok(connection);
    assert.ok(requests.some(value => value.path.includes('chat/completions')), 'actual chat request');
    const form = new FormData();
    form.append('file', new Blob(['Desktop knowledge verification. The local test code is bluebird. '.repeat(12)], { type: 'text/plain' }), 'desktop-smoke.txt');
    const upload = await fetch(`${origin}/api/v1/knowledge/documents`, {
      method: 'POST', headers: { Cookie: `JSESSIONID=${cookie}`, 'X-CSRF-TOKEN': csrf },
      body: form, signal: AbortSignal.timeout(60000),
    });
    const uploaded = await upload.json();
    assert.equal(uploaded.code, 0, JSON.stringify(uploaded));
    assert.ok(uploaded.data.id);
    assert.ok(requests.some(value => value.path.includes('embeddings') && value.service === 'embedding'),
      'knowledge ingestion must call the independently configured embedding endpoint');
    const embedding = await runtime.pg(['psql', '--name', 'desktop', '-c',
      'SELECT count(*) FROM flyway_schema_history WHERE success; SELECT extname FROM pg_extension ORDER BY extname;']);
    assert.match(embedding.stdout, /49/);
    assert.match(embedding.stdout, /vector/);
    assert.match(embedding.stdout, /hstore/);
    const firstDatabasePort = runtime.databasePort;
    await runtime.stop();
    assert.equal((await runtime.databaseInfo()).running, false, 'database stopped');
    runtime = new LocalRuntime(resources, directory, store.read(), state => stages.push(state.stage));
    ({ origin, cookie } = await runtime.start());
    const persisted = await fetch(`${origin}/api/v1/tasks/${task.id}`, { headers: { Cookie: `JSESSIONID=${cookie}` } });
    assert.equal((await persisted.json()).data.title, 'Desktop persistence smoke test');
    await runtime.stop();
    assert.equal((await runtime.databaseInfo()).running, false);
    fs.mkdirSync(path.dirname(report), { recursive: true });
    fs.writeFileSync(report, JSON.stringify({ passed: true, platform: `${process.platform}-${process.arch}`,
      stages, checks: ['offline runtime extraction', 'Flyway V1-V49', 'loopback auth', 'CSRF', 'origin protection',
        'static frontend and deep links', 'real chat request to synthetic local model',
        'multipart knowledge ingestion and independent 1024-dimension embedding endpoint', 'task persistence across restart', 'graceful stop'],
      modelRequests: requests, databaseVersion: '18.1.0', firstDatabasePort }, null, 2) + '\n');
    console.log(`Desktop smoke passed. Report: ${report}`);
  } catch (error) {
    console.error(`Smoke artifacts retained: ${directory}`);
    throw error;
  } finally {
    if (runtime) await runtime.stop();
    await new Promise(resolve => model.close(resolve));
    await new Promise(resolve => embeddingModel.close(resolve));
  }
}

main().catch(error => { console.error(error.stack); process.exitCode = 1; });
