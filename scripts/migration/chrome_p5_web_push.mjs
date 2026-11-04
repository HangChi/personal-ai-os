import { spawn } from 'node:child_process'
import { createECDH } from 'node:crypto'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { once } from 'node:events'
import { setTimeout as timeout } from 'node:timers/promises'
const dir = await mkdtemp(join(tmpdir(), 'personalos-p5-chrome-'))
let receive
const received = new Promise(resolve => { receive = resolve })
const delay = (ms, ref = true) => timeout(ms, undefined, {ref})
const server = createServer((req, res) => {
  if (req.url === '/received' && req.method === 'POST') {
    req.setEncoding('utf8')
    let data = ''
    req.on('data', chunk => { data += chunk })
    req.on('end', () => {
      if (data === 'P5 browser delivery fixture') receive()
      res.writeHead(204).end()
    })
    return
  }
  if (req.url === '/sw.js') {
    res.setHeader('Content-Type', 'text/javascript')
    res.end(`self.addEventListener('push', event => {
      const payload = event.data.json()
      event.waitUntil(Promise.all([
        self.registration.showNotification(payload.title, {tag: payload.tag}),
        fetch('/received', {method: 'POST', body: payload.title})
      ]))
    })`)
    return
  }
  res.setHeader('Content-Type', 'text/html')
  res.end('<!doctype html><title>P5 disposable Push test</title>')
})
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
const origin = `http://127.0.0.1:${server.address().port}`
const profile = join(dir, 'profile')
const chrome = spawn(process.env.CHROME_PATH ?? '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
  ['--headless', '--disable-gpu', '--no-first-run', '--remote-debugging-port=0', `--user-data-dir=${profile}`, origin],
  {stdio:'ignore'})
let ws
try {
  let port
  for (let i = 0; i < 100 && !port; i++) {
    try { port = (await readFile(join(profile, 'DevToolsActivePort'), 'utf8')).split('\n')[0] } catch { await delay(100) }
  }
  const target = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find(t => t.type === 'page')
  ws = new WebSocket(target.webSocketDebuggerUrl)
  await new Promise((resolve,reject) => { ws.onopen=resolve; ws.onerror=reject })
  let id=0
  const pending = new Map()
  ws.onmessage = event => {
    const message=JSON.parse(event.data)
    const reply=pending.get(message.id)
    if (reply) {
      pending.delete(message.id)
      if(message.error) reply.reject(new Error(message.error.message))
      else reply.resolve(message.result)
    }
  }
  function call(method, params={}) {
    const token=++id
    return new Promise((resolve,reject) => {
      pending.set(token,{resolve,reject})
      ws.send(JSON.stringify({id:token,method,params}))
    })
  }
  await call('Browser.grantPermissions',{origin,permissions:['notifications']})
  const keys=createECDH('prime256v1')
  keys.generateKeys()
  const publicKey=keys.getPublicKey().toString('base64url')
  const privateRaw=keys.getPrivateKey()
  const privateKey=Buffer.concat([Buffer.alloc(32-privateRaw.length),privateRaw]).toString('base64url')
  const evaluation=await Promise.race([
    call('Runtime.evaluate',{awaitPromise:true,returnByValue:true,expression:`(async () => {
      await navigator.serviceWorker.register('/sw.js');
      const registration = await navigator.serviceWorker.ready;
      const key = Uint8Array.from(atob(${JSON.stringify(publicKey.replace(/-/g,'+').replace(/_/g,'/'))}), c => c.charCodeAt(0));
      const subscription = await registration.pushManager.subscribe({userVisibleOnly:true, applicationServerKey:key});
      return subscription.toJSON();
    })()`}),
    delay(30000, false).then(()=>{throw new Error('Timed out waiting for Chrome PushManager.subscribe')})
  ])
  if (evaluation.exceptionDetails) throw new Error(evaluation.result.description || evaluation.exceptionDetails.text)
  const subscription=evaluation.result.value
  const fixture = join(dir, 'subscription.json')
  console.log('Chrome subscription host:', new URL(subscription.endpoint).hostname)
  await writeFile(fixture, JSON.stringify({publicKey, privateKey, subscription}), {mode: 0o600})
  const tests = spawn('python3', ['scripts/migration/run_go_postgres_tests.py', '--package', 'app',
    '--run', '^TestP5BrowserWebPush$'], {
    env: {...process.env, GO_TEST_REMINDER_BROWSER_SUBSCRIPTION: fixture},
    stdio: 'inherit',
  })
  const [status] = await once(tests, 'exit')
  if (status !== 0) throw new Error('Chrome subscription/delivery integration failed')
  const gotPush = await Promise.race([received.then(() => true), delay(15000).then(() => false)])
  if (!gotPush) throw new Error('FCM accepted the notification, but Chrome did not receive it in time')
  console.log('Chrome service worker decrypted and received the reminder')
} finally {
  ws?.close()
  if (chrome.exitCode === null && chrome.signalCode === null) {
    chrome.kill()
    await once(chrome, 'exit')
  }
  server.close()
  await rm(dir, {recursive: true, force: true})
}
