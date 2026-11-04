import assert from 'node:assert/strict'
import test from 'node:test'
import { chromeLaunchArgs, gcmSnapshot } from './chrome_push_diagnostics.mjs'

test('headed comparison changes only headless flag and retains disposable profile', () => {
  const args = chromeLaunchArgs('/owned/random/profile', 'http://127.0.0.1:1234')
  const headed = chromeLaunchArgs('/owned/random/profile', 'http://127.0.0.1:1234', true)
  assert.deepEqual(args, ['--headless', ...headed])
  assert.ok(headed.includes('--user-data-dir=/owned/random/profile'))
  assert.ok(headed.includes('--remote-debugging-port=0'))
  assert.equal(headed.at(-1), 'http://127.0.0.1:1234')
})

function page(fields = {}, counts = {}) {
  return {
    getElementById: id => Object.hasOwn(fields, id) ? {textContent: fields[id]} : null,
    querySelectorAll: selector => Array.from({length: counts[selector] ?? 0}, () => ({textContent: 'private log detail'})),
  }
}

test('connection and counters contain no device or subscription details', () => {
  const report = gcmSnapshot(page({
    'connection-state': ' CONNECTED ', recording: 'Stop Recording',
    'android-secret': 'private device secret', 'registered-app-ids': 'private subscription identifier',
  }, {'#connection-info > tr': 2, '#receive-info > tr': 3, '#decryption-failure-info > tr': 1}))
  assert.deepEqual(report, {available: true, connectionState: 'CONNECTED', recording: true,
    connectionLogRows: 2, receivedLogRows: 3, decryptionFailureRows: 1})
  assert.equal(JSON.stringify(report).includes('private'), false)
})

test('unknown state text is redacted and missing internals remain unavailable', () => {
  assert.equal(gcmSnapshot(page({'connection-state': 'connection failed for private address'})).connectionState, 'OTHER')
  assert.deepEqual(gcmSnapshot(page()), {available: false, connectionState: null, recording: false,
    connectionLogRows: 0, receivedLogRows: 0, decryptionFailureRows: 0})
})

test('display capitalization and separators normalize only to known states', () => {
  assert.equal(gcmSnapshot(page({'connection-state': 'Connected'})).connectionState, 'CONNECTED')
  assert.equal(gcmSnapshot(page({'connection-state': 'Not connected'})).connectionState, 'NOT_CONNECTED')
  assert.equal(gcmSnapshot(page({'connection-state': 'Connection-failed'})).connectionState, 'CONNECTION_FAILED')
  assert.equal(gcmSnapshot(page({'connection-state': 'Connected for private address'})).connectionState, 'OTHER')
})
