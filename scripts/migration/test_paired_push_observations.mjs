import assert from 'node:assert/strict'
import test from 'node:test'
import {pairedReceipts} from './paired_push_observations.mjs'

test('distinct backend markers prevent a late Go receipt counting as Java delivery', async () => {
  const receipts = pairedReceipts()
  let javaReceived = false
  receipts.received('java').then(() => { javaReceived = true })
  assert.equal(receipts.record('P8 local control fixture'), false)
  assert.equal(receipts.record(receipts.marker('go')), true)
  await receipts.received('go')
  assert.equal(javaReceived, false)
  assert.deepEqual(receipts.counts(), {go: 1, java: 0})
  receipts.record(receipts.marker('java'))
  await receipts.received('java')
  assert.equal(javaReceived, true)
})

test('late receipts retain their first timestamp without changing prior deadline decisions', async () => {
  let clock = 100
  const receipts = pairedReceipts(() => clock)
  const goDeliveredByDeadline = receipts.counts().go > 0
  clock = 16101
  receipts.record(receipts.marker('go'))
  clock = 17000
  receipts.record(receipts.marker('go'))
  assert.equal(goDeliveredByDeadline, false)
  assert.deepEqual(receipts.firstReceiptMs(), {go: 16001, java: null})
  assert.deepEqual(receipts.counts(), {go: 2, java: 0})
})
