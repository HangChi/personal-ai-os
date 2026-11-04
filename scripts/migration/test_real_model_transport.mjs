import assert from 'node:assert/strict'
import test from 'node:test'
import { createTransport } from './real_model_transport.mjs'

const config = { base_url: 'https://models.example.invalid/v1', api_key: 'test-only-secret' }

test('pooled runtime targets only configured routes and keeps headers out of results', async () => {
  const calls = []
  const forward = createTransport(config, async (url, options) => {
    calls.push({ url, options })
    return new Response('{"data":[]}', { status: 200 })
  })
  for (let index = 0; index < 2; index++) {
    const result = await forward({ url: config.base_url + '/embeddings', body: '{"model":"test"}' })
    assert.equal(result.status, 200)
    assert.equal(Buffer.from(result.body, 'base64').toString(), '{"data":[]}')
    assert(!JSON.stringify(result).includes(config.api_key))
  }
  assert.equal(calls.length, 2)
  assert.equal(calls[0].options.redirect, 'manual')
  assert.equal(calls[0].options.headers.Authorization, 'Bearer ' + config.api_key)
  const rejected = await forward({ url: 'https://other.invalid/embeddings', body: '{}' })
  assert.equal(rejected.error, 'REQUEST_REJECTED')
  assert.equal(calls.length, 2)
})

test('provider redirects are returned without following or inventing success', async () => {
  const forward = createTransport(config, async () => new Response('', { status: 302 }))
  assert.equal((await forward({ url: config.base_url + '/embeddings', body: '{}' })).status, 302)
})

test('network errors are redacted and never retried', async () => {
  let calls = 0
  const forward = createTransport(config, async () => {
    calls++
    throw new Error('test-only-secret actual-text', { cause: { code: 'ECONNRESET' } })
  })
  const result = await forward({ url: config.base_url + '/embeddings', body: '{}' })
  assert.deepEqual(result, { error: 'ECONNRESET' })
  assert.equal(calls, 1)
})

test('configuration never accepts plaintext or credential-bearing URLs', () => {
  for (const base_url of ['http://models.example.invalid', 'https://secret@models.example.invalid',
    'https://models.example.invalid?key=secret']) {
    assert.throws(() => createTransport({ ...config, base_url }), /CONFIG_INVALID/)
  }
})
