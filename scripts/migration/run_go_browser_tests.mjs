// Invoked only by the disposable authenticated P8 runner, never an existing service.
import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const target = new URL(process.env.GO_P8_UI_BASE ?? '')
assert.equal(target.hostname, '127.0.0.1')
assert.equal(target.protocol, 'http:')
assert.ok(process.env.GO_P8_UI_TEMP && process.env.GO_P8_UI_PASSWORD)
const require = createRequire(import.meta.url)
const { chromium } = require(join(process.env.HOME, '.workbuddy/binaries/node/workspace/node_modules/playwright'))
const { preview } = await import(pathToFileURL(join(root, 'frontend/node_modules/vite/dist/node/index.js')))
const output = mkdtempSync(join(tmpdir(), 'personalos-p8-browser-'))
const server = await preview({
  root: join(root, 'frontend'), configFile: false, envDir: process.env.GO_P8_UI_TEMP,
  preview: { host: '127.0.0.1', port: 0, strictPort: true, proxy: { '/api': { target: target.origin } } },
})
const address = server.httpServer.address()
assert.ok(address && typeof address === 'object')
const origin = `http://127.0.0.1:${address.port}`
const executables = [1243, 1234].map(version => join(process.env.HOME,
  `Library/Caches/ms-playwright/chromium_headless_shell-${version}/chrome-headless-shell-mac-arm64/chrome-headless-shell`))
const executablePath = executables.find(existsSync)
let browser, page
const report = { backend: process.env.GO_P8_UI_BACKEND, result: 'FAIL', workflows: [], pages: [], errors: [], screenshots: output }
try {
  browser = await chromium.launch(executablePath ? { executablePath } : { channel: 'chrome' })
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai', acceptDownloads: true })
  await context.route('**/*', route => {
    const url = new URL(route.request().url())
    return url.origin === origin || ['data:', 'blob:'].includes(url.protocol) ? route.continue() : route.abort()
  })
  page = await context.newPage()
  page.setDefaultTimeout(15000)
  page.on('pageerror', error => report.errors.push(String(error).slice(0, 250)))
  page.on('console', message => { if (message.type() === 'error') report.errors.push(message.text().slice(0, 250)) })
  page.on('response', response => { if (response.status() >= 500) report.errors.push(`${response.status()} ${new URL(response.url()).pathname}`) })
  const navigate = async path => {
    await page.goto(origin + path, { waitUntil: 'networkidle' })
    await page.locator('#app').waitFor()
  }
  await navigate('/tasks')
  await page.getByPlaceholder('请输入用户名').fill(process.env.GO_P8_UI_USERNAME)
  await page.getByPlaceholder('请输入密码').fill(process.env.GO_P8_UI_PASSWORD)
  await page.getByRole('button', { name: '登录', exact: true }).click()
  await page.getByLabel('任务标题', { exact: true }).waitFor()
  report.workflows.push('authenticated UI login and CSRF')

  const title = 'P8 browser task'
  await page.getByLabel('任务标题', { exact: true }).fill(title)
  await page.getByRole('button', { name: '新建任务', exact: true }).click()
  let row = page.getByRole('row').filter({ hasText: title })
  await row.getByRole('button', { name: '编辑', exact: true }).click()
  const dialog = page.getByRole('dialog', { name: '编辑任务' })
  await dialog.getByPlaceholder('任务标题', { exact: true }).fill(title + ' edited')
  await dialog.getByRole('button', { name: '保存修改' }).click()
  await dialog.waitFor({ state: 'hidden' })
  row = page.getByRole('row').filter({ hasText: title + ' edited' })
  await row.getByRole('button', { name: '完成', exact: true }).click()
  await row.getByRole('button', { name: '恢复', exact: true }).waitFor()
  await page.screenshot({ path: join(output, 'task-completed.png') })
  await row.getByRole('button', { name: '删除', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: '删除', exact: true }).click()
  await row.waitFor({ state: 'hidden' })
  report.workflows.push('task create, edit, complete, explicit delete')

  await navigate('/chat')
  await page.getByRole('button', { name: '新建会话', exact: true }).click()
  const composer = page.getByRole('textbox', { name: '消息内容', exact: true })
  await composer.fill('请只回复一个简短句子')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByText('这是受控模型依据当前请求生成的测试回复。', { exact: false }).last().waitFor()
  await page.getByRole('button', { name: '发送', exact: true }).waitFor()
  const uploaded = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/chat/uploads') && response.request().method() === 'POST')
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'p8-browser.txt', mimeType: 'text/plain', buffer: Buffer.from('附件的结论是推迟三天。') })
  const upload = await uploaded
  assert.equal(upload.status(), 200)
  const attachment = (await upload.json()).data
  await composer.fill('这个附件的结论是什么？')
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByText('推迟三天', { exact: false }).last().waitFor()
  const content = await context.request.get(origin + '/api/v1/chat/uploads/' + attachment.id + '/content')
  assert.equal(content.status(), 200)
  assert.equal((await content.body()).toString(), '附件的结论是推迟三天。')
  await page.screenshot({ path: join(output, 'chat-attachment.png') })
  report.workflows.push('persisted chat SSE, UI attachment upload, answer context and exact content download')

  await navigate('/knowledge')
  await page.locator('input[type="file"]').first().setInputFiles({ name: 'p8-knowledge.txt', mimeType: 'text/plain', buffer: Buffer.from('P8 browser knowledge document. The internal code is P8-Browser-2026. '.repeat(12)) })
  await page.getByPlaceholder('文档标题（可选）').fill('P8 browser knowledge')
  const submitted = page.waitForResponse(response => new URL(response.url()).pathname.endsWith('/knowledge/jobs') && response.request().method() === 'POST')
  await page.getByRole('button', { name: '提交后台索引', exact: true }).click()
  const submission = await submitted
  assert.equal(submission.status(), 202)
  const indexed = page.getByRole('row').filter({ hasText: 'P8 browser knowledge' })
  for (let attempt = 0; attempt < 25 && !(await indexed.count()); attempt++) {
    const refresh = page.getByRole('button', { name: '刷新进度', exact: true })
    if (await refresh.count()) await refresh.click()
    await page.waitForTimeout(300)
  }
  await indexed.waitFor()
  await page.screenshot({ path: join(output, 'knowledge-indexed.png') })
  report.workflows.push('UI knowledge upload, durable background index and document listing')

  await navigate('/settings')
  await page.getByRole('navigation', { name: '设置分组' }).getByRole('button', { name: '数据', exact: true }).click()
  const downloadEvent = page.waitForEvent('download')
  await page.getByRole('button', { name: '导出', exact: true }).click()
  const download = await downloadEvent
  const exportPath = join(output, 'synthetic-export.json')
  await download.saveAs(exportPath)
  const exported = JSON.parse(readFileSync(exportPath, 'utf8'))
  assert.ok(JSON.stringify(exported).includes('P8 browser knowledge'))
  report.workflows.push('UI export downloads parseable JSON containing indexed knowledge')

  for (const width of [1280, 768, 375]) {
    await page.setViewportSize({ width, height: 900 })
    for (const [name, path] of [['home', '/'], ['chat', '/chat'], ['tasks', '/tasks'], ['calendar', '/calendar'], ['knowledge', '/knowledge'], ['memory', '/memory'], ['activity', '/activity'], ['usage', '/usage'], ['settings', '/settings'], ['search', '/search']]) {
      await navigate(path)
      assert.ok(!new URL(page.url()).pathname.startsWith('/login'), 'Unexpected authentication redirect')
      const dimensions = await page.evaluate(() => ({ width: innerWidth, overflow: document.documentElement.scrollWidth - innerWidth, textLength: document.querySelector('#app').textContent.trim().length }))
      report.pages.push({ name, width, ...dimensions })
      await page.screenshot({ path: join(output, `${name}-${width}.png`) })
      assert.ok(dimensions.textLength > 10, `Blank page ${name}/${width}`)
      assert.ok(dimensions.overflow <= 1, `Horizontal page overflow ${name}/${width}: ${dimensions.overflow}`)
    }
  }
  assert.deepEqual(report.errors, [], 'Browser runtime or console errors')
  report.result = 'PASS'
  console.log('P8 real browser workflows and 30 page/viewport captures passed: ' + output)
} catch (error) {
  report.failure = String(error).replaceAll(process.env.GO_P8_UI_PASSWORD, '[redacted]')
  if (page) {
    await page.screenshot({ path: join(output, 'failure.png') }).catch(() => {})
    report.visibleText = (await page.locator('body').innerText()).slice(-3500)
    const jobs = await page.context().request.get(origin + '/api/v1/knowledge/jobs').catch(() => null)
    if (jobs && jobs.status() === 200) report.indexJobs = await jobs.json()
  }
  throw error
} finally {
  writeFileSync(process.env.GO_P8_UI_REPORT ?? join(root, 'specs/033-go-rewrite/p8-e2e', `browser-${report.backend}.json`), JSON.stringify(report, null, 2) + '\n')
  if (browser) await browser.close()
  await server.close()
}
