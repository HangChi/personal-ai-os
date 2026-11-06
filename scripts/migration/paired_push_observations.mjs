export function pairedReceipts(now = () => performance.now()) {
  const started = now()
  const elapsed = () => Math.round(now() - started)
  const receipts = new Map()
  for (const name of ['go', 'java']) {
    let resolve
    const received = new Promise(done => { resolve = done })
    receipts.set(name, {marker: 'P8 paired ' + name + ' transport fixture', received, resolve, count: 0, firstReceiptMs: null})
  }
  return {
    elapsed,
    marker: name => receipts.get(name).marker,
    received: name => receipts.get(name).received,
    record: marker => {
      for (const receipt of receipts.values()) {
        if (marker === receipt.marker) {
          if (receipt.count === 0) receipt.firstReceiptMs = elapsed()
          receipt.count++; receipt.resolve(); return true
        }
      }
      return false
    },
    counts: () => Object.fromEntries([...receipts].map(([name, receipt]) => [name, receipt.count])),
    firstReceiptMs: () => Object.fromEntries([...receipts].map(([name, receipt]) => [name, receipt.firstReceiptMs])),
  }
}
