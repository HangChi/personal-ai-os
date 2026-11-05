// Only invoked by the disposable acceptance runner against a real Java/Go process.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync, mkdirSync, writeFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const target = new URL(process.env.RETRY_UI_BASE ?? '')
assert.equal(target.hostname, '127.0.0.1')
assert.equal(target.protocol, 'http:')
assert.ok(process.env.RETRY_UI_TEMP && process.env.RETRY_UI_PASSWORD)
const output = resolve(process.env.RETRY_UI_OUTPUT)
mkdirSync(output, { recursive: true })
const backend = process.env.RETRY_UI_BACKEND
assert.ok(['java', 'go'].includes(backend))
const require = createRequire(import.meta.url)
const { chromium } = require(join(process.env.HOME, '.workbuddy/binaries/node/workspace/node_modules/playwright'))
const { preview } = await import(pathToFileURL(join(root, 'frontend/node_modules/vite/dist/node/index.js')))
const server = await preview({
  root: join(root, 'frontend'), configFile: false, envDir: process.env.RETRY_UI_TEMP,
  preview: { host: '127.0.0.1', port: 0, strictPort: true, proxy: { '/api': { target: target.origin } } },
})
const address = server.httpServer.address()
assert.ok(address && typeof address === 'object')
const origin = 'http://127.0.0.1:' + address.port
const executablePath = [1243, 1234].map(version => join(process.env.HOME,
  'Library/Caches/ms-playwright/chromium_headless_shell-' + version + '/chrome-headless-shell-mac-arm64/chrome-headless-shell')).find(existsSync)
const report = { backend, result: 'FAIL', workflows: [], errors: [], injectedLosses: [], injectedConsole: [], layouts: [], modelRequests: 0 }
let browser, page, dropNextTool = null
try {
  browser = await chromium.launch(executablePath ? { executablePath } : { channel: 'chrome' })
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
  await context.route('**/*', async route => {
    const request = route.request()
    const url = new URL(request.url())
    if (url.origin !== origin && !['data:', 'blob:'].includes(url.protocol)) return route.abort()
    if (request.method() === 'POST' && /\/chat\/sessions\/[^/]+\/(runs|stream)$/.test(url.pathname)) report.modelRequests++
    if (dropNextTool && request.method() === 'POST' && /\/chat\/sessions\/[^/]+\/tools$/.test(url.pathname)
        && request.postDataJSON().tool === dropNextTool) {
      dropNextTool = null
      // Forward the genuine request, then lose its accepted reply after its side effect.
      const response = await route.fetch()
      const body = await response.json()
      report.injectedLosses.push({ path: url.pathname, key: request.headers()['idempotency-key'],
        tool: request.postDataJSON().tool, status: response.status(), data: body.data })
      return route.abort('connectionreset')
    }
    return route.continue()
  })
  page = await context.newPage()
  page.setDefaultTimeout(15000)
  page.on('pageerror', error => report.errors.push(String(error).slice(0, 350)))
  page.on('console', message => {
    if (message.type() !== 'error' || message.text().includes('net::ERR_CONNECTION_RESET')) return
    if (message.text().startsWith('网络请求失败：') && report.injectedConsole.length < report.injectedLosses.length)
      report.injectedConsole.push('Expected Axios error after committed response was deliberately lost')
    else report.errors.push(message.text().slice(0, 350))
  })
  page.on('response', response => {
    if (response.status() >= 500) report.errors.push(response.status() + ' ' + new URL(response.url()).pathname)
  })
  const composer = () => page.getByRole('textbox', { name: '消息内容', exact: true })
  const navigate = async () => {
    await page.goto(origin + '/chat', { waitUntil: 'domcontentloaded' })
    await composer().waitFor()
    await page.getByRole('button', { name: '新建会话', exact: true }).waitFor()
  }
  await page.goto(origin + '/chat')
  await page.getByPlaceholder('请输入用户名').fill(process.env.RETRY_UI_USERNAME)
  await page.getByPlaceholder('请输入密码').fill(process.env.RETRY_UI_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await composer().waitFor()
  report.workflows.push('real browser login with authentication and CSRF enabled')
  const api = async (method, path, data, key, expected = 200) => {
    const headers = {}
    if (method !== 'GET') {
      const tokenResponse = await context.request.get(origin + '/api/v1/auth/csrf')
      assert.equal(tokenResponse.status(), 200)
      headers['X-CSRF-TOKEN'] = (await tokenResponse.json()).data.token
    }
    if (key) headers['Idempotency-Key'] = key
    const response = await context.request.fetch(origin + '/api/v1' + path, { method, data, headers })
    assert.equal(response.status(), expected, method + ' ' + path + ': ' + (await response.text()).slice(0, 600))
    return (await response.json()).data
  }
  const emptyDraft = () => page.waitForFunction(() => document.querySelector('textarea[aria-label="消息内容"]')?.value === '')
  const local = async input => {
    await composer().fill(input)
    await page.getByRole('button', { name: '执行', exact: true }).click()
    await emptyDraft()
  }
  const command = async input => {
    await composer().fill(input)
    const responsePromise = page.waitForResponse(response => response.request().method() === 'POST'
      && /\/chat\/sessions\/[^/]+\/tools$/.test(new URL(response.url()).pathname))
    await page.getByRole('button', { name: '执行', exact: true }).click()
    const response = await responsePromise
    assert.equal(response.status(), 200, await response.text())
    const execution = (await response.json()).data
    assert.ok(['OK', 'DUPLICATE', 'REQUIRES_CONFIRMATION'].includes(execution.result.status), JSON.stringify(execution))
    await emptyDraft()
    return { execution, key: response.request().headers()['idempotency-key'], path: new URL(response.url()).pathname.replace('/api/v1', '') }
  }
  const taskMatches = async title => (await api('GET', '/tasks?size=100&keyword=' + encodeURIComponent(title))).items.filter(t => t.title === title)
  const loseReply = async (input, tool) => {
    dropNextTool = tool
    await composer().fill(input)
    await page.getByRole('button', { name: '执行', exact: true }).click()
    await page.getByRole('alert').filter({ hasText: '结果未确认' }).last().waitFor()
    assert.equal(await composer().inputValue(), input, 'Unknown result must preserve the current draft')
    assert.equal(dropNextTool, null)
    const accepted = report.injectedLosses.at(-1)
    assert.equal(accepted.status, 200)
    assert.ok(accepted.key)
    return accepted
  }

  await composer().fill('/ta')
  const menu = page.getByRole('listbox', { name: '快捷指令' })
  await menu.waitFor()
  await composer().press('ArrowDown')
  await composer().press('Tab')
  assert.ok(['/task ', '/tasks '].includes(await composer().inputValue()))
  await local('/help')
  await page.getByLabel('快捷操作面板', { exact: true }).getByText('快捷指令', { exact: true }).waitFor()
  await page.getByRole('button', { name: '关闭快捷指令帮助', exact: true }).click()
  const session = page.waitForResponse(response => response.request().method() === 'POST'
    && new URL(response.url()).pathname === '/api/v1/chat/sessions')
  await local('/new')
  const conversation = (await (await session).json()).data.conversationId
  report.workflows.push('slash menu keyboard completion, help panel and /new')

  const title = 'Retry browser ' + backend + ' ' + Date.now()
  const created = await command('/task ' + title)
  const tasks = await taskMatches(title)
  assert.equal(tasks.length, 1)
  const taskId = tasks[0].id
  await command('/tasks ' + title)
  await command('/done ' + taskId)
  assert.equal((await api('GET', '/tasks/' + taskId)).status, 'DONE')
  report.workflows.push('/task, /tasks and /done persist through the real backend')

  const lostTitle = title + ' lost'
  const lostInput = '/task ' + lostTitle
  const acceptedCreate = await loseReply(lostInput, 'createTask')
  assert.equal((await taskMatches(lostTitle)).length, 1)
  await navigate()
  const replayedCreate = await command(lostInput)
  assert.equal(replayedCreate.key, acceptedCreate.key)
  assert.equal(replayedCreate.path, acceptedCreate.path.replace('/api/v1', ''))
  assert.equal(replayedCreate.execution.replayed, true)
  assert.equal((await taskMatches(lostTitle)).length, 1)
  await page.getByText('（原请求回放）', { exact: true }).waitFor()
  report.workflows.push('lost real create response + page reload reuses UUID and replays; one task only')

  const deleteInput = '/delete-task ' + taskId
  const acceptedDelete = await loseReply(deleteInput, 'deleteTask')
  assert.equal(acceptedDelete.data.result.status, 'REQUIRES_CONFIRMATION')
  const confirmationId = acceptedDelete.data.result.confirmationId
  assert.equal((await api('GET', '/tasks/' + taskId)).id, taskId)
  await navigate()
  const replayedDelete = await command(deleteInput)
  assert.equal(replayedDelete.key, acceptedDelete.key)
  assert.equal(replayedDelete.execution.result.confirmationId, confirmationId)
  let pending = await api('GET', '/agent/confirmations?status=PENDING&conversationId=' + conversation)
  assert.equal(pending.length, 1)
  assert.equal(pending[0].id, confirmationId)
  const card = page.getByLabel('待确认：删除任务', { exact: true })
  await card.waitFor()
  const rejection = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/' + confirmationId + '/cancel'))
  await card.getByRole('button', { name: '拒绝', exact: true }).click()
  assert.equal((await rejection).status(), 200)
  await card.waitFor({ state: 'hidden' })
  assert.equal((await api('GET', '/tasks/' + taskId)).id, taskId)
  report.workflows.push('lost confirmation response + reload reuses pending approval; rejecting leaves task intact')

  const nextDelete = await command(deleteInput)
  assert.notEqual(nextDelete.key, acceptedDelete.key, 'An acknowledged rejected intent may be submitted anew')
  pending = await api('GET', '/agent/confirmations?status=PENDING&conversationId=' + conversation)
  assert.equal(pending.length, 1)
  assert.notEqual(pending[0].id, confirmationId)
  const decision = pending[0]
  await card.waitFor()
  const approval = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/' + decision.id + '/confirm'))
  await card.getByRole('button', { name: '允许本次操作', exact: true }).click()
  const approvalResponse = await approval
  assert.equal(approvalResponse.status(), 200)
  assert.equal((await approvalResponse.json()).data.executed, true)
  await card.waitFor({ state: 'hidden' })
  await api('GET', '/tasks/' + taskId, undefined, undefined, 404)
  await api('POST', '/agent/confirmations/' + decision.id + '/confirm', { token: decision.token }, undefined, 409)
  const receipt = await api('POST', created.path, { tool: 'createTask', arguments: { title } }, created.key)
  assert.equal(receipt.replayed, true)
  assert.equal((await taskMatches(title)).length, 0, 'Old creation receipt must not recreate a deleted task')
  report.workflows.push('fresh approval deletes once; duplicate approval conflicts; old create receipt cannot recreate task')

  await command('/calendar 2026-11-05')
  await command('/reminders')
  await command('/tool findTasks {"limit":1}')
  await local('/tools')
  const panel = page.getByLabel('快捷操作面板', { exact: true })
  await panel.getByText('可用工具', { exact: true }).waitFor()
  await panel.locator('summary').filter({ hasText: 'createTask' }).waitFor()
  report.workflows.push('/calendar, /reminders, /tool and live /tools catalog')
  for (const width of [1280, 768, 375]) {
    await page.setViewportSize({ width, height: 900 })
    await composer().fill('/')
    await menu.waitFor()
    const bounds = await menu.boundingBox()
    assert.ok(bounds && bounds.x >= 0 && bounds.x + bounds.width <= width + 1, 'Command menu must fit viewport')
    const overflow = await page.evaluate(() => document.documentElement.scrollWidth - innerWidth)
    assert.ok(overflow <= 1)
    report.layouts.push({ width, overflow, menu: bounds })
    await page.screenshot({ path: join(output, backend + '-commands-' + width + '.png') })
  }
  assert.equal(report.modelRequests, 0, 'Direct command workflows must not invoke a conversation model')
  assert.equal(report.injectedConsole.length, 2)
  assert.deepEqual(report.errors, [])
  report.result = 'PASS'
  console.log(backend + ': real browser command workflows, response loss and three viewport checks passed')
} catch (error) {
  report.failure = String(error).replaceAll(process.env.RETRY_UI_PASSWORD, '[redacted]')
  if (page) {
    await page.screenshot({ path: join(output, backend + '-browser-failure.png') }).catch(() => {})
    report.visibleText = (await page.locator('body').innerText()).slice(-3500)
  }
  throw error
} finally {
  writeFileSync(join(output, backend + '-browser.json'), JSON.stringify(report, null, 2) + '\n')
  if (browser) await browser.close()
  await server.close()
}
