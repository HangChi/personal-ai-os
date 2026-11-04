import { createInterface } from 'node:readline'
import { fileURLToPath } from 'node:url'

const maximum = 32 * 1024 * 1024

// 同一fetch运行时复用HTTPS连接；stdio协议不输出配置、请求或原始异常。
export function createTransport(config, fetchImpl = globalThis.fetch) {
  const base = new URL(config.base_url)
  if (
    base.protocol !== 'https:' ||
    base.username ||
    base.password ||
    base.search ||
    base.hash ||
    !config.api_key
  ) {
    throw new Error('CONFIG_INVALID')
  }
  const prefix = config.base_url.replace(/\/$/, '').replace(/\/v1$/, '') + '/v1'
  const allowed = new Set([prefix + '/embeddings', prefix + '/chat/completions'])
  return async function forward(request) {
    if (!allowed.has(request.url) || typeof request.body !== 'string' || request.body.length > maximum) {
      return { error: 'REQUEST_REJECTED' }
    }
    try {
      const response = await fetchImpl(request.url, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: 'Bearer ' + config.api_key,
        },
        body: request.body,
        redirect: 'manual',
        signal: AbortSignal.timeout(90_000),
      })
      let size = 0
      const chunks = []
      if (response.body) {
        const reader = response.body.getReader()
        for (;;) {
          const { done, value } = await reader.read()
          if (done) break
          size += value.byteLength
          if (size > maximum) {
            await reader.cancel()
            return { error: 'RESPONSE_TOO_LARGE' }
          }
          chunks.push(Buffer.from(value))
        }
      }
      return { status: response.status, body: Buffer.concat(chunks).toString('base64') }
    } catch (error) {
      const known = new Set([
        'ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT', 'ENOTFOUND',
        'UND_ERR_CONNECT_TIMEOUT', 'UND_ERR_SOCKET', 'CERT_HAS_EXPIRED',
        'ERR_TLS_CERT_ALTNAME_INVALID', 'UNABLE_TO_VERIFY_LEAF_SIGNATURE',
      ])
      const code = error?.cause?.code
      return { error: known.has(code) ? code : 'NETWORK_OR_TIMEOUT' }
    }
  }
}

async function main() {
  const lines = createInterface({ input: process.stdin, crlfDelay: Infinity })
  let forward
  for await (const line of lines) {
    let output
    try {
      if (!forward) {
        forward = createTransport(JSON.parse(line))
        output = { ready: true }
      } else {
        output = await forward(JSON.parse(line))
      }
    } catch {
      output = { error: 'PROTOCOL_INVALID' }
    }
    process.stdout.write(JSON.stringify(output) + '\n')
  }
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  await main()
}
