import assert from 'node:assert/strict'
import test from 'node:test'
import { gcmSnapshot } from './chrome_push_diagnostics.mjs'

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
