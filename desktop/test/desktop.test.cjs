'use strict';
const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const http = require('node:http');
const { ConfigStore, validateConfig } = require('../src/config.cjs');
const { backendEnvironment, systemEnvironment, redact } = require('../src/runtime.cjs');
const { startProxy } = require('../src/proxy.cjs');

const model = { chatUrl: 'http://127.0.0.1:11434/v1', chatModel: 'qwen2.5:7b', chatKey: 'private-chat-key',
  embeddingUrl: 'https://example.com/embedding/v1', embeddingModel: 'bge-m3', embeddingKey: 'private-embedding-key' };

test('configuration rejects executable URLs, inline credentials and header injection', () => {
  for (const url of ['file:///secret', 'javascript:alert(1)', 'https://user:secret@example.com', 'https://example.com/?token=secret']) {
    assert.throws(() => validateConfig({ ...model, chatUrl: url }));
  }
  assert.throws(() => validateConfig({ ...model, chatKey: 'key\r\nBad: header' }));
  assert.throws(() => validateConfig({ ...model, embeddingModel: '' }));
  assert.equal(validateConfig({ ...model, chatUrl: ' http://127.0.0.1:11434/v1/ ' }).chatUrl, model.chatUrl);
});

test('restart retains database identity and encrypted configuration without exposing secrets', t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-config-'));
  t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
  const encryption = { isEncryptionAvailable: () => true,
    encryptString: value => Buffer.from(value).map(byte => byte ^ 63),
    decryptString: value => Buffer.from(value).map(byte => byte ^ 63).toString() };
  const store = new ConfigStore(directory, encryption);
  const before = store.initialize();
  store.configure(model);
  const restarted = new ConfigStore(directory, encryption).read();
  assert.equal(restarted.databasePassword, before.databasePassword);
  assert.equal(restarted.authPassword, before.authPassword);
  assert.equal(restarted.chatKey, model.chatKey);
  assert.ok(!fs.readFileSync(store.file, 'utf8').includes(model.chatKey));
  if (process.platform !== 'win32') assert.equal(fs.statSync(store.file).mode & 0o777, 0o600);
  assert.throws(() => new ConfigStore(directory, { isEncryptionAvailable: () => false }).read(), /加密/);
});

test('desktop isolates service configuration from developer environment', () => {
  assert.equal(systemEnvironment({ PATH: '/bin', JAVA_TOOL_OPTIONS: '-javaagent:external.jar', POSTGRES_HOST: 'real-database',
    OPENAI_API_KEY: 'real-key', SPRING_PROFILES_ACTIVE: 'prod' }).POSTGRES_HOST, undefined);
  const config = { ...model, databasePassword: 'db-secret', authPassword: 'auth-secret', encryptionKey: 'encryption-secret' };
  const env = backendEnvironment(config, '/private/desktop', 51000, 52000);
  assert.equal(env.POSTGRES_HOST, '127.0.0.1');
  assert.equal(env.AUTH_ENABLED, 'true');
  assert.equal(env.APP_SECURITY_CSRF_ENABLED, 'true');
  assert.equal(env.SPRING_AI_OPENAI_EMBEDDING_BASE_URL, model.embeddingUrl);
  assert.equal(env.OPENAI_BASE_URL, model.chatUrl);
  assert.equal(env.MCP_ENABLED, 'false');
  const logged = redact(`Password: db-secret postgres://postgres:db-secret@127.0.0.1 ${model.chatKey}`, config);
  assert.ok(!logged.includes('db-secret') && !logged.includes(model.chatKey));
});

test('proxy streams requests and events, preserves cookies, and rejects cross-site access', async t => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'desktop-proxy-'));
  fs.writeFileSync(path.join(directory, 'index.html'), '<html>desktop</html>');
  let uploaded = '';
  const upstream = http.createServer(async (req, res) => {
    for await (const data of req) uploaded += data.toString();
    assert.equal(req.headers.cookie, 'JSESSIONID=test-cookie');
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Set-Cookie': 'JSESSIONID=next; HttpOnly; SameSite=Lax' });
    res.write('event: delta\ndata: one\n\n');
    setTimeout(() => res.end('event: done\ndata: two\n\n'), 50);
  });
  await new Promise(resolve => upstream.listen(0, '127.0.0.1', resolve));
  const proxy = await startProxy(directory, upstream.address().port);
  t.after(async () => {
    await proxy.close();
    await new Promise(resolve => upstream.close(resolve));
    fs.rmSync(directory, { recursive: true, force: true });
  });
  assert.equal((await fetch(proxy.origin + '/tasks')).status, 200);
  assert.equal((await fetch(proxy.origin + '/missing.js')).status, 404);
  assert.equal((await fetch(proxy.origin, { headers: { Origin: 'https://external.example' } })).status, 403);
  assert.equal((await fetch(proxy.origin, { headers: { 'Sec-Fetch-Site': 'cross-site' } })).status, 403);
  const forgedHost = await new Promise(resolve => {
    http.get(proxy.origin, { headers: { Host: 'external.example' } }, response => { response.resume(); resolve(response.statusCode); });
  });
  assert.equal(forgedHost, 403);
  const response = await fetch(proxy.origin + '/api/v1/stream', {
    method: 'POST', headers: { Cookie: 'JSESSIONID=test-cookie' }, body: 'streamed-body',
  });
  assert.equal(response.headers.getSetCookie()[0], 'JSESSIONID=next; HttpOnly; SameSite=Lax');
  const reader = response.body.getReader();
  const first = await reader.read();
  assert.match(Buffer.from(first.value).toString(), /one/);
  let rest = '';
  while (true) { const chunk = await reader.read(); if (chunk.done) break; rest += Buffer.from(chunk.value).toString(); }
  assert.match(rest, /two/);
  assert.equal(uploaded, 'streamed-body');
});
