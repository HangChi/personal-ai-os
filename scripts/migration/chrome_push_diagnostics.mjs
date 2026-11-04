// Runs inside the disposable GCM internals page; never returns device IDs, keys or log text.
export function gcmSnapshot(document) {
  const value = id => document.getElementById(id)?.textContent.trim() ?? ''
  const connection = value('connection-state')
  const states = ['CONNECTED', 'CONNECTING', 'NOT_CONNECTED', 'DISCONNECTED', 'IDLE', 'OFFLINE',
    'ONLINE', 'LOGIN', 'AUTHENTICATING', 'BACKOFF', 'CLOSED', 'NO_CONNECTION', 'CONNECTION_FAILED']
  return {
    available: connection.length > 0,
    connectionState: states.includes(connection) ? connection : connection ? 'OTHER' : null,
    recording: value('recording') === 'Stop Recording',
    connectionLogRows: document.querySelectorAll('#connection-info > tr').length,
    receivedLogRows: document.querySelectorAll('#receive-info > tr').length,
    decryptionFailureRows: document.querySelectorAll('#decryption-failure-info > tr').length,
  }
}
