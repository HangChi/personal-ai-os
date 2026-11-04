import { spawn } from 'node:child_process'
import { createECDH } from 'node:crypto'
import { createServer } from 'node:http'
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import { once } from 'node:events'
import { parseArgs } from 'node:util'
import { setTimeout as timeout } from 'node:timers/promises'
import { chromeLaunchArgs, gcmSnapshot } from './chrome_push_diagnostics.mjs'
const dir = await mkdtemp(join(tmpdir(), 'personalos-p5-chrome-'))
const {values: arguments_} = parseArgs({options: {report: {type: 'string'}, headed: {type: 'boolean', default: false}}})
let receive
const received = new Promise(resolve => { receive = resolve })
let controlReceive
const controlled = new Promise(resolve => { controlReceive = resolve })
const diagnostics = {controlReceived: false, pushEvents: 0, notificationErrors: 0, workerErrors: 0}
let accepted = false
let delivered = false
let stage = 'STARTUP'
const delay = (ms, ref = true) => timeout(ms, undefined, {ref})
const server = createServer((req, res) => {
  if (req.url === '/received' && req.method === 'POST') {
    req.setEncoding('utf8')
    let data = ''
    req.on('data', chunk => { data += chunk })
    req.on('end', () => {
      if (data === 'P5 browser delivery fixture') receive()
      if (data === 'P8 local control fixture') {
        diagnostics.controlReceived = true
        controlReceive()
      }
      res.writeHead(204).end()
    })
    return
  }
  if (req.url === '/diagnostic/push' && req.method === 'POST') {
    diagnostics.pushEvents++
    res.writeHead(204).end()
    return
  }
  if (req.url === '/diagnostic/notification-error' && req.method === 'POST') {
    diagnostics.notificationErrors++
    res.writeHead(204).end()
    return
  }
  if (req.url === '/sw.js') {
    res.setHeader('Content-Type', 'text/javascript')
    res.end(`self.addEventListener('push', event => {
      const payload = event.data.json()
      event.waitUntil(Promise.all([
        fetch('/diagnostic/push', {method: 'POST'}),
        self.registration.showNotification(payload.title, {tag: payload.tag}).catch(() => fetch('/diagnostic/notification-error', {method: 'POST'})),
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
  chromeLaunchArgs(profile, origin, arguments_.headed),
  {stdio:'ignore'})
let ws
let registrationId
let call
let gcmSession
async function gcmCall(method, params = {}) {
  return Promise.race([
    call(method, params, gcmSession),
    delay(6000, false).then(() => { throw new Error('Disposable GCM diagnostics timed out') }),
  ])
}
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
    if (message.method === 'ServiceWorker.workerRegistrationUpdated') {
      registrationId = message.params.registrations.find(registration => registration.scopeURL === origin + '/' && !registration.isDeleted)?.registrationId ?? registrationId
    }
    if (message.method === 'ServiceWorker.workerErrorReported') diagnostics.workerErrors++
    const reply=pending.get(message.id)
    if (reply) {
      pending.delete(message.id)
      if(message.error) reply.reject(new Error(message.error.message))
      else reply.resolve(message.result)
    }
  }
  call = function (method, params={}, sessionId) {
    const token=++id
    return new Promise((resolve,reject) => {
      const timer = setTimeout(() => {
        pending.delete(token)
        reject(new Error('Disposable Chrome command timed out'))
      }, 35000)
      timer.unref()
      pending.set(token,{resolve: value => { clearTimeout(timer); resolve(value) },
        reject: error => { clearTimeout(timer); reject(error) }})
      ws.send(JSON.stringify({id:token,method,params,...(sessionId ? {sessionId} : {})}))
    })
  }
  await call('Browser.grantPermissions',{origin,permissions:['notifications']})
  await call('ServiceWorker.enable')
  const keys=createECDH('prime256v1')
  keys.generateKeys()
  const publicKey=keys.getPublicKey().toString('base64url')
  const privateRaw=keys.getPrivateKey()
  const privateKey=Buffer.concat([Buffer.alloc(32-privateRaw.length),privateRaw]).toString('base64url')
  stage = 'SUBSCRIPTION'
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
  if (!registrationId) throw new Error('Disposable service worker registration was not observed')
  stage = 'CONTROL'
  // This bypasses FCM and encryption, isolating the worker/callback path from real delivery.
  await call('ServiceWorker.deliverPushMessage', {origin, registrationId,
    data: JSON.stringify({title: 'P8 local control fixture', tag: 'p8-control'})})
  const controlPassed = await Promise.race([controlled.then(() => true), delay(3000, false).then(() => false)])
  if (!controlPassed) throw new Error('Local CDP push control did not reach the disposable worker')
  try {
    const {targetId} = await call('Target.createTarget', {url: 'chrome://gcm-internals/', background: true})
    const attached = await call('Target.attachToTarget', {targetId, flatten: true})
    gcmSession = attached.sessionId
    const recording = await gcmCall('Runtime.evaluate', {awaitPromise: true, returnByValue: true, expression: `(async () => {
      for (let attempt = 0; attempt < 80; attempt++) {
        const button = document.getElementById('recording');
        if (button && !button.disabled) {
          if (button.textContent.trim() === 'Start Recording') button.click();
          return true;
        }
        await new Promise(resolve => setTimeout(resolve, 50));
      }
      return false;
    })()`})
    diagnostics.gcmRecordingStarted = recording.result?.value === true
    const beforeSend = await gcmCall('Runtime.evaluate', {returnByValue: true,
      expression: '(' + gcmSnapshot.toString() + ')(document)'})
    if (beforeSend.exceptionDetails || !beforeSend.result?.value) throw new Error('Unavailable GCM counters')
    diagnostics.gcmBeforeSend = beforeSend.result.value
  } catch {
    diagnostics.gcmRecordingStarted = false
  }
  const fixture = join(dir, 'subscription.json')
  console.log('Chrome subscription host:', new URL(subscription.endpoint).hostname)
  await writeFile(fixture, JSON.stringify({publicKey, privateKey, subscription}), {mode: 0o600})
  stage = 'SEND'
  const tests = spawn('python3', ['scripts/migration/run_go_postgres_tests.py', '--package', 'app',
    '--tls', '--run', '^TestP5BrowserWebPush$'], {
    env: {...process.env, GO_TEST_REMINDER_BROWSER_SUBSCRIPTION: fixture},
    stdio: 'inherit',
  })
  const [status] = await once(tests, 'exit')
  if (status !== 0) throw new Error('Chrome subscription/delivery integration failed')
  accepted = true
  stage = 'DELIVERY'
  const gotPush = await Promise.race([received.then(() => true), delay(15000).then(() => false)])
  if (!gotPush) throw new Error('FCM accepted the notification, but Chrome did not receive it in time')
  delivered = true
  stage = 'COMPLETE'
  console.log('Chrome service worker decrypted and received the reminder')
} finally {
  if (gcmSession) {
    try {
      const snapshot = await gcmCall('Runtime.evaluate', {awaitPromise: true, returnByValue: true, expression: `(async () => {
        document.getElementById('refresh')?.click();
        await new Promise(resolve => setTimeout(resolve, 150));
        return (${gcmSnapshot.toString()})(document);
      })()`})
      if (snapshot.exceptionDetails || !snapshot.result?.value) throw new Error('Unavailable GCM counters')
      diagnostics.gcm = snapshot.result.value
    } catch {
      diagnostics.gcm = {available: false}
    }
  }
  console.log('Push diagnostics (no endpoint or keys):', JSON.stringify(diagnostics))
  ws?.close()
  if (chrome.exitCode === null && chrome.signalCode === null) {
    chrome.kill()
    await once(chrome, 'exit')
  }
  server.close()
  await rm(dir, {recursive: true, force: true})
  if (arguments_.report) await writeFile(arguments_.report, JSON.stringify({
    date: new Date().toISOString(), result: delivered ? 'PASS' : 'FAIL', stage, platformAccepted: accepted,
    browserMode: arguments_.headed ? 'headed' : 'headless', subscriptionDeadlineMs: 30000, controlDeadlineMs: 3000,
    realPushDelivered: delivered, realDeliveryDeadlineMs: 15000, ...diagnostics,
    controlScope: 'CDP synthetic push bypasses FCM and encryption; it verifies only the disposable worker and callback.',
  }, null, 2) + '\n')
}
