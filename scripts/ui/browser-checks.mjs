import assert from 'node:assert/strict'
import { createRequire } from 'node:module'
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

// 仅预览构建产物，所有 API 都使用模拟数据；不读取 .env，也不连接业务后端。
const root = resolve(dirname(fileURLToPath(import.meta.url)), '../..')
const output = resolve(process.argv[2] ?? '/tmp/personalos-ui-review')
const baseline = process.argv.includes('--baseline')
const interactionsOnly = process.argv.includes('--interactions-only')
let apiMode = 'normal'
let failWorkflowSave = false
mkdirSync(output, { recursive: true })
const emptyEnv = mkdtempSync(join(tmpdir(), 'personalos-ui-env-'))
const require = createRequire(import.meta.url)
const { chromium } = require(
  process.env.PLAYWRIGHT_MODULE ?? join(process.env.HOME, '.workbuddy/binaries/node/workspace/node_modules/playwright'),
)
const { preview } = await import(pathToFileURL(join(root, 'frontend/node_modules/vite/dist/node/index.js')))
const server = await preview({
  root: join(root, 'frontend'),
  configFile: false,
  envDir: emptyEnv,
  preview: { host: '127.0.0.1', port: 0 },
})
const origin = `http://127.0.0.1:${server.httpServer.address().port}`
const executablePath = process.env.PLAYWRIGHT_EXECUTABLE_PATH ?? chromium.executablePath()
const now = '2026-11-06T02:00:00Z'
const list = (items) => ({
  items,
  total: items.length,
  page: 0,
  size: 20,
  truncated: false,
})
const task = {
  id: 'task-1',
  title: '整理本周项目进展与下一步计划',
  description: '汇总已完成事项，补充待处理的问题。',
  status: 'TODO',
  priority: 'HIGH',
  dueAt: '2026-11-06T10:00:00Z',
  tags: ['工作'],
  createdAt: now,
  updatedAt: now,
  projectId: 'project-1',
  parentTaskId: null,
  subtaskCount: 0,
  incompleteSubtaskCount: 0,
  recurrenceType: 'NONE',
}
const workflow = {
  id: 'workflow-1',
  name: '每日工作晨报',
  kind: 'MORNING_BRIEF',
  timezone: 'Asia/Shanghai',
  localTime: '09:00',
  weekday: 1,
  enabled: false,
  maxRunsPerDay: 3,
  maxItems: 30,
  maxOutputChars: 10000,
  nextRunAt: '2026-11-07T01:00:00Z',
}
const memory = {
  id: 'memory-1',
  content: '工作日早上优先安排需要专注的任务，输出内容使用中文。',
  source: 'MANUAL',
  pinned: true,
  supersededBy: null,
  hitCount: 12,
  lastUsedAt: now,
  createdAt: now,
  updatedAt: now,
}
let savedWorkflows = [workflow]
const misses = new Set()
function fixture(path, method, body, url) {
  if (path === '/auth/me') return { authEnabled: apiMode === 'login', username: null }
  if (path === '/auth/csrf') return { token: 'ui-fixture', headerName: 'X-CSRF-TOKEN' }
  if (path === '/health')
    return {
      status: 'UP',
      application: 'Personal AI OS',
      version: '0.1.0',
      timestamp: now,
      components: { database: 'UP', ai: 'CONFIGURED' },
    }
  if (path === '/reminders/push') return { enabled: false, publicKey: null }
  if (/reminders.*stream/.test(path)) return null
  if (/reminders/.test(path)) return []
  if (path === '/projects')
    return [
      {
        id: 'project-1',
        name: '工作计划',
        description: '日常项目与待办',
        archived: false,
        createdAt: now,
        updatedAt: now,
      },
    ]
  if (path === '/tasks/stats') return { pending: 3, overdue: 1 }
  if (path === '/tasks/tags') return ['工作', '个人']
  if (path === '/tasks/task-1') return task
  if (path === '/tasks')
    return list([
      task,
      {
        ...task,
        id: 'task-2',
        title: '阅读产品设计文档',
        priority: 'MEDIUM',
        tags: ['个人'],
        dueAt: null,
      },
    ])
  if (path === '/memory' || path === '/memories') return [memory]
  if (path === '/knowledge/documents/search')
    return list([
      {
        id: 'doc-1',
        title: '项目协作指南',
        filename: '团队协作指南.md',
        contentType: 'text/markdown',
        chunkCount: 8,
        charCount: 12400,
        sizeBytes: 18600,
        createdAt: now,
        status: 'READY',
      },
    ])
  if (path === '/knowledge/captures')
    return list([
      {
        id: 'capture-1',
        url: 'https://example.com/project-guide',
        documentId: 'doc-1',
        currentVersion: 1,
        pendingJobId: null,
        status: 'SUCCEEDED',
        lastError: null,
        lastCheckedAt: now,
      },
    ])
  if (path === '/knowledge/captures/capture-1/versions')
    return list([
      {
        version: 1,
        title: '项目协作指南',
        content: '把变更背景、影响范围与下一步写清楚。',
        hash: 'd'.repeat(64),
        status: 'SUCCEEDED',
        capturedAt: now,
      },
    ])
  if (/knowledge.*(sources|spaces|conflicts)/.test(path)) return []
  if (path === '/knowledge/jobs') return []
  if (/knowledge.*index/.test(path)) return list([])
  if (path === '/calendar/events') return list([])
  if (path === '/workspace/runs') return list([])
  if (path === '/workflows' && method === 'GET') return list(savedWorkflows)
  if (path === '/workflows' && method === 'POST') {
    const value = { ...workflow, ...JSON.parse(body), id: 'workflow-2' }
    savedWorkflows.push(value)
    return value
  }
  if (path === '/workflows/workflow-1' && method === 'PATCH') {
    savedWorkflows[0] = { ...savedWorkflows[0], ...JSON.parse(body) }
    return savedWorkflows[0]
  }
  if (/workflows\/.+\/runs/.test(path)) return list([])
  if (path === '/maintenance')
    return {
      generatedAt: now,
      heartbeats: list([
        {
          instanceId: 'local-ui-fixture',
          backend: 'Java',
          role: 'ALL',
          status: 'ACTIVE',
          startedAt: now,
          lastSeenAt: now,
        },
      ]),
      failedIndexJobs: list([]),
      failedDeliveries: list([]),
      failedWorkflows: list([]),
      unfinishedRuns: list([]),
      checks: list([]),
    }
  if (path === '/agent/confirmations') return []
  if (path === '/agent/tool-invocations')
    return list([
      {
        id: 1,
        operation: 'list_tasks',
        source: 'TOOL',
        outcome: 'SUCCESS',
        durationMs: 42,
        createdAt: now,
        conversationId: null,
        argumentsJson: '{}',
        resultSummary: '找到 3 条待办',
      },
    ])
  if (path === '/usage') {
    const days = Number(url.searchParams.get('days') ?? 30)
    return {
      days,
      totalPrompt: 12500,
      totalCompletion: 4200,
      totalTokens: 16700,
      turns: 14,
      actualTokens: 16000,
      estimatedTokens: 700,
      hasData: true,
      daily: [
        {
          date: '2026-11-06',
          promptTokens: 12500,
          completionTokens: 4200,
          totalTokens: 16700,
          turns: 14,
          estimated: false,
        },
      ],
    }
  }
  if (path === '/mcp/servers')
    return {
      enabled: true,
      servers: [
        {
          name: 'filesystem',
          available: true,
          toolCount: 4,
          autoTools: ['read_file'],
          failureReason: null,
        },
      ],
    }
  if (path === '/mcp/server')
    return {
      enabled: true,
      name: 'personal-ai-os',
      version: '0.1.0',
      endpoint: '/mcp',
      tools: [
        {
          name: 'list_tasks',
          description: '查看与检索个人任务',
          readOnly: true,
        },
      ],
    }
  if (/profiles/.test(path))
    return {
      profiles: [
        {
          id: 'profile-1',
          name: '日常助理',
          presetId: null,
          baseUrl: 'https://model.example/v1',
          protocol: 'OPENAI',
          apiKeyMasked: '****demo',
          hasApiKey: true,
          chatModel: 'workspace-chat',
          chatModels: ['workspace-chat', 'workspace-reasoner'],
          embeddingModel: 'workspace-embedding',
          embeddingModels: ['workspace-embedding'],
          audioModel: '',
          audioModels: [],
          contextWindows: { 'workspace-chat': 32000 },
          temperature: 0.7,
          active: true,
          sortOrder: 0,
          revision: 1,
          updatedAt: now,
          sources: {
            baseUrl: 'DATABASE',
            apiKey: 'DATABASE',
            chatModel: 'DATABASE',
            embeddingModel: 'DATABASE',
            audioModel: 'DEFAULT',
            temperature: 'DATABASE',
          },
        },
      ],
      activeId: 'profile-1',
    }
  if (path === '/chat/sessions' || path === '/chat/sessions/search') return list([])
  if (/chat\/(context|context-budget)/.test(path))
    return {
      maxTokens: 32000,
      usedTokens: 0,
      totalTokens: 0,
      components: [],
      messages: [],
      sections: [],
      total: 0,
    }
  if (path === '/chat/transcribe/status') return { enabled: false }
  if (path === '/search')
    return {
      keyword: '项目',
      totalHits: 1,
      groups: [
        {
          module: '任务',
          kind: 'task',
          hits: [
            {
              kind: 'task',
              id: 'task-1',
              title: '整理本周项目进展与下一步计划',
              subtitle: '汇总已完成事项',
              matchedField: '标题',
            },
          ],
        },
      ],
    }
  misses.add(`${method} ${path}`)
  return []
}
const report = {
  result: 'FAIL',
  mode: 'mock-api',
  checks: [],
  errors: [],
  misses: [],
  screenshots: [],
}
let browser
try {
  browser = await chromium.launch(existsSync(executablePath) ? { executablePath } : { channel: 'chrome' })
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    serviceWorkers: 'block',
  })
  const routeRequest = async (route) => {
    const request = route.request(),
      url = new URL(request.url())
    if (url.origin !== origin) return route.abort()
    if (!url.pathname.startsWith('/api/')) return route.continue()
    const path = url.pathname.replace('/api/v1', '')
    if (
      (apiMode === 'error' && path === '/tasks') ||
      (failWorkflowSave && path === '/workflows' && request.method() === 'POST')
    ) {
      failWorkflowSave = false
      return route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({
          code: 503,
          message: '模拟连接暂不可用，请稍后重试',
          data: null,
          timestamp: now,
        }),
      })
    }
    let data = fixture(path, request.method(), request.postData(), url)
    if (apiMode === 'empty' && path === '/tasks') data = list([])
    if (/stream/.test(url.pathname))
      return route.fulfill({
        status: 200,
        contentType: 'text/event-stream',
        body: ': fixture\n\n',
      })
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({ code: 0, message: 'ok', timestamp: now, data }),
    })
  }
  await context.route('**/*', routeRequest)
  const page = await context.newPage()
  page.on('pageerror', (error) => report.errors.push(error.message))
  page.on('console', (message) => {
    if (/Failed to resolve component|Unhandled error during/.test(message.text())) report.errors.push(message.text())
  })
  page.setDefaultTimeout(10000)
  const routes = [
    ['home', '/'],
    ['tasks', '/tasks'],
    ['calendar', '/calendar'],
    ['knowledge', '/knowledge?tab=documents'],
    ['memory', '/memory'],
    ['workflows', '/workflows'],
    ['activity', '/activity'],
    ['usage', '/usage'],
    ['appearance', '/settings?section=appearance'],
    ['settings', '/settings'],
    ['mcp', '/settings?section=mcp'],
    ['maintenance', '/maintenance'],
    ['chat', '/chat'],
    ['search', '/search?q=项目'],
    ['workspace-settings', '/settings?section=workspace'],
    ['chat-settings', '/settings?section=chat'],
    ['calendar-settings', '/settings?section=calendar'],
    ['shortcuts', '/settings?section=shortcuts'],
    ['about', '/settings?section=about'],
    ['knowledge-search', '/knowledge?tab=search'],
    ['knowledge-ask', '/knowledge?tab=ask'],
    ['captures', '/knowledge?tab=captures'],
    ['governance', '/knowledge?tab=governance'],
  ]
  for (const theme of interactionsOnly ? [] : baseline ? ['light'] : ['light', 'dark']) {
    await page.addInitScript((value) => localStorage.setItem('os.theme', value), theme)
    for (const width of baseline ? [1440] : [1440, 390]) {
      await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
      for (const [name, path] of routes) {
        await page.goto(origin + path, { waitUntil: 'networkidle' })
        if (!baseline) {
          const actual = new URL(page.url())
          if (name === 'memory') {
            assert.equal(actual.pathname, '/knowledge')
            assert.equal(actual.searchParams.get('tab'), 'memory')
          }
          if (name === 'usage') {
            assert.equal(actual.pathname, '/activity')
            assert.equal(actual.searchParams.get('view'), 'usage')
          }
          if (name === 'maintenance') {
            assert.equal(actual.pathname, '/settings')
            assert.equal(actual.searchParams.get('section'), 'data')
          }
          assert.equal(await page.locator('h1').count(), 1, `${name} 页面应有唯一主标题`)
          assert.equal(await page.locator('html').getAttribute('data-theme'), theme)
          assert.deepEqual(
            await page
              .locator(
                '.el-alert--error:visible, .el-alert--warning:visible, .today-workspace__state[role="alert"]:visible',
              )
              .allTextContents(),
            [],
            `${name}: 正常夹具不应出现错误`,
          )
        }
        const file = `${name}-${theme}-${width}.png`
        await page.screenshot({
          path: join(output, file),
          fullPage: true,
          animations: 'disabled',
        })
        report.screenshots.push(file)
        assert.equal(
          await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
          true,
          `${name}: ${theme}/${width} 页面横向溢出`,
        )
      }
      report.checks.push(`${theme}/${width}: ${routes.length} 个模块布局`)
    }
  }

  if (!baseline) {
    async function shot(name) {
      await page.screenshot({
        path: join(output, `${name}.png`),
        fullPage: true,
        animations: 'disabled',
      })
      report.screenshots.push(`${name}.png`)
      assert.equal(
        await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        true,
        `${name} 横向溢出`,
      )
    }
    for (const width of [1440, 390]) {
      await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 })
      await page.goto(origin + '/tasks', { waitUntil: 'networkidle' })
      await page.locator('.page-header__actions').getByRole('button', { name: '新建任务', exact: true }).click()
      let dialog = page.getByRole('dialog', { name: '新建任务', exact: true })
      await dialog.getByLabel('任务标题', { exact: true }).fill('保留任务草稿')
      await shot(`task-create-${width}`)
      await dialog.getByRole('button', { name: '取消', exact: true }).click()
      await dialog.waitFor({ state: 'hidden' })
      await page.locator('.page-header__actions').getByRole('button', { name: '新建任务', exact: true }).click()
      assert.equal(await dialog.getByLabel('任务标题', { exact: true }).inputValue(), '保留任务草稿')
      await dialog.getByRole('button', { name: '取消', exact: true }).click()
      await dialog.waitFor({ state: 'hidden' })
      await page.reload({ waitUntil: 'networkidle' })
      await page.getByRole('button', { name: '添加子任务', exact: true }).first().click()
      await dialog.getByLabel('任务标题', { exact: true }).waitFor()
      await dialog.locator('.task-organization .el-select__selected-item').filter({ hasText: task.title }).waitFor()
      await dialog.getByRole('button', { name: '取消', exact: true }).click()
      await dialog.waitFor({ state: 'hidden' })
      report.checks.push(`${width}: 任务新建、取消保留草稿、添加子任务`)

      await page.goto(origin + '/calendar', { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: '新建日程', exact: true }).click()
      dialog = page.getByRole('dialog', { name: '新建日程', exact: true })
      await dialog.getByLabel('日程标题', { exact: true }).fill('产品评审')
      await shot(`calendar-create-${width}`)
      await dialog.getByRole('button', { name: '取消', exact: true }).click()
      await dialog.waitFor({ state: 'hidden' })
      await page.getByRole('button', { name: '日历工具', exact: true }).click()
      await page.getByRole('heading', { name: '检查时间冲突', exact: true }).waitFor()
      await shot(`calendar-tools-${width}`)
      await page.keyboard.press('Escape')
      await page.getByRole('dialog', { name: '日历工具', exact: true }).waitFor({ state: 'hidden' })
      await page.locator('label.el-radio-button').filter({ hasText: /^月$/ }).click()
      const dayButton = page.getByRole('button', { name: /^在 .* 创建日程$/ }).first()
      const day = (await dayButton.getAttribute('aria-label')).split(' ')[1]
      await dayButton.focus()
      await page.keyboard.press('Enter')
      dialog = page.getByRole('dialog', { name: '新建日程', exact: true })
      await dialog.getByLabel('起止时间', { exact: true }).waitFor()
      assert.match(await dialog.getByLabel('起止时间', { exact: true }).inputValue(), new RegExp(day))
      await dialog.getByRole('button', { name: '取消', exact: true }).click()
      await dialog.waitFor({ state: 'hidden' })
      report.checks.push(`${width}: 日程新建、月历日期预填与日历工具分组`)

      await page.goto(origin + '/workflows', { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: '新建计划', exact: true }).click()
      dialog = page.getByRole('dialog', { name: '新建计划', exact: true })
      await dialog.getByLabel('计划名称', { exact: true }).fill(`浏览器晨报-${width}`)
      // 等待打开动画及 opened 聚焦结束，再验证下拉键盘操作。
      await dialog.getByLabel('计划名称', { exact: true }).click()
      assert.equal(await dialog.getByLabel('启用自动执行', { exact: true }).isChecked(), false)
      await dialog.getByRole('combobox', { name: '报告类型', exact: true }).focus()
      await page.keyboard.press('Enter')
      await page.getByRole('option', { name: '周报', exact: true }).click()
      await dialog.getByRole('combobox', { name: '每周执行日', exact: true }).focus()
      await page.keyboard.press('Enter')
      await page.getByRole('option', { name: '周五', exact: true }).click()
      await dialog.getByText('执行与输出限制', { exact: true }).click()
      await shot(`workflow-create-${width}`)
      failWorkflowSave = true
      await dialog.getByRole('button', { name: '保存计划', exact: true }).click()
      await dialog.getByRole('alert').filter({ hasText: '模拟连接暂不可用' }).waitFor()
      assert.equal(await dialog.getByLabel('计划名称', { exact: true }).inputValue(), `浏览器晨报-${width}`)
      await dialog.getByRole('button', { name: '保存计划', exact: true }).click()
      await dialog.waitFor({ state: 'hidden' })
      assert.equal(savedWorkflows.at(-1).kind, 'WEEKLY_REVIEW')
      assert.equal(savedWorkflows.at(-1).weekday, 5)
      await page.getByRole('heading', { name: `浏览器晨报-${width}`, exact: true }).waitFor()
      await page.getByRole('button', { name: '执行历史', exact: true }).first().click()
      await page.getByText('尚无执行记录，可在计划中点击“立即生成”', { exact: true }).waitFor()
      await page.keyboard.press('Escape')
      report.checks.push(`${width}: 计划默认禁用、失败保留草稿、保存与历史抽屉`)

      await page.goto(origin + '/knowledge?tab=documents', {
        waitUntil: 'networkidle',
      })
      await page.getByRole('button', { name: '上传文档', exact: true }).click()
      dialog = page.getByRole('dialog', { name: '上传文档', exact: true })
      await dialog.getByRole('button', { name: '选择文件', exact: true }).waitFor()
      assert.equal(await dialog.getByRole('button', { name: '提交后台索引', exact: true }).isDisabled(), true)
      await shot(`knowledge-upload-${width}`)
      await page.keyboard.press('Escape')
      await page.getByRole('tab', { name: '长期记忆', exact: true }).click()
      assert.equal(new URL(page.url()).searchParams.get('tab'), 'memory')
      await page.goBack()
      await page.getByRole('tab', { name: '文档', exact: true }).waitFor()
      assert.equal(new URL(page.url()).searchParams.get('tab'), 'documents')
      report.checks.push(`${width}: 上传面板、空提交保护与知识标签回退`)

      await page.goto(origin + '/knowledge?tab=captures', { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: '版本历史', exact: true }).click()
      const versions = page.getByRole('dialog', { name: '网页版本历史', exact: true })
      await versions.getByText('把变更背景、影响范围与下一步写清楚。', { exact: true }).waitFor()
      await shot(`capture-history-${width}`)
      await page.keyboard.press('Escape')
      await versions.waitFor({ state: 'hidden' })
      report.checks.push(`${width}: 网页来源与版本历史抽屉`)

      await page.goto(origin + '/settings', { waitUntil: 'networkidle' })
      await page.getByRole('button', { name: '编辑', exact: true }).click()
      dialog = page.getByRole('dialog', { name: '编辑模型配置', exact: true })
      await dialog.getByPlaceholder('留空表示不修改', { exact: true }).waitFor()
      assert.equal(await dialog.getByPlaceholder('留空表示不修改', { exact: true }).inputValue(), '')
      await shot(`model-editor-${width}`)
      await dialog.getByRole('button', { name: '取消', exact: true }).click()
      await dialog.waitFor({ state: 'hidden' })
      await page.goto(origin + '/settings?section=mcp', {
        waitUntil: 'networkidle',
      })
      await page.getByRole('button', { name: /自动执行工具 1 项/ }).click()
      await page.getByRole('dialog').waitFor()
      await shot(`mcp-tools-${width}`)
      await page.keyboard.press('Escape')
      report.checks.push(`${width}: 模型编辑密钥留空与 MCP 工具详情`)

      await page.goto(origin + '/settings?section=data', {
        waitUntil: 'networkidle',
      })
      await page.getByRole('tab', { name: '运行健康', exact: true }).click()
      await page.getByText('local-ui-fixture', { exact: true }).waitFor()
      await shot(`maintenance-status-${width}`)
      await page.getByRole('tab', { name: '恢复演练', exact: true }).click()
      assert.equal(await page.getByRole('button', { name: '记录外部报告', exact: true }).isDisabled(), true)
      await shot(`maintenance-drill-${width}`)
      await page.getByRole('tab', { name: '校验历史', exact: true }).click()
      await page.getByText('选择一份业务 JSON 开始校验', { exact: true }).waitFor()
      report.checks.push(`${width}: 维护健康、演练输入保护与校验历史`)

      await page.goto(origin + '/settings?section=workspace', {
        waitUntil: 'networkidle',
      })
      await page.locator('label.el-radio-button').filter({ hasText: '看板' }).click()
      await page.reload({ waitUntil: 'networkidle' })
      assert.equal(await page.getByRole('radio', { name: '看板', exact: true }).isChecked(), true)
      await page.goto(origin + '/tasks', { waitUntil: 'networkidle' })
      await page.locator('.task-board').waitFor()
      await shot(`task-board-${width}`)
      report.checks.push(`${width}: 设置偏好跨页面与刷新持久化`)
    }
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto(origin + '/chat', { waitUntil: 'networkidle' })
    await page.getByRole('button', { name: '打开会话列表', exact: true }).click()
    await page.getByRole('dialog', { name: '会话列表', exact: true }).waitFor()
    await shot('chat-sessions-mobile')
    await page.keyboard.press('Escape')
    assert.equal(
      await page
        .getByRole('button', { name: '打开会话列表', exact: true })
        .evaluate((el) => el === document.activeElement),
      true,
    )
    report.checks.push('手机会话抽屉与 Escape 焦点返回')
    const touchContext = await browser.newContext({
      viewport: { width: 390, height: 844 },
      hasTouch: true,
      isMobile: true,
      deviceScaleFactor: 1,
      locale: 'zh-CN',
      timezoneId: 'Asia/Shanghai',
      serviceWorkers: 'block',
    })
    await touchContext.route('**/*', routeRequest)
    const touchPage = await touchContext.newPage()
    touchPage.on('pageerror', (error) => report.errors.push(error.message))
    await touchPage.addInitScript(() => localStorage.setItem('os.theme', 'dark'))
    for (const [name, path] of routes.filter(([name]) =>
      ['tasks', 'calendar', 'settings', 'appearance', 'maintenance', 'chat'].includes(name),
    )) {
      await touchPage.goto(origin + path, { waitUntil: 'networkidle' })
      const file = `${name}-touch-390.png`
      await touchPage.screenshot({ path: join(output, file), fullPage: true, animations: 'disabled' })
      report.screenshots.push(file)
      assert.equal(await touchPage.evaluate(() => matchMedia('(pointer: coarse)').matches), true)
      assert.equal(
        await touchPage.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
        true,
        `${name}: 触屏横向溢出`,
      )
    }
    report.checks.push('390: 实际触屏媒体条件下 6 个核心模块')
    await touchContext.close()
    apiMode = 'empty'
    await page.goto(origin + '/tasks', { waitUntil: 'networkidle' })
    await shot('tasks-empty-mobile')
    apiMode = 'error'
    await page.reload({ waitUntil: 'networkidle' })
    await page.getByRole('alert').filter({ hasText: '模拟连接暂不可用' }).waitFor()
    await shot('tasks-error-mobile')
    apiMode = 'login'
    await page.goto(origin + '/login', { waitUntil: 'networkidle' })
    await page.getByRole('heading', { name: '登录工作区', exact: true }).waitFor()
    assert.equal(await page.getByRole('button', { name: '登录', exact: true }).isDisabled(), true)
    await shot('login-mobile')
    report.checks.push('空态、请求失败与登录输入保护')
    assert.deepEqual([...misses], [], '未覆盖的模拟 API')
  }
  assert.deepEqual(report.errors, [], '浏览器运行错误')
  report.result = 'PASS'
} finally {
  report.misses = [...misses]
  writeFileSync(join(output, 'report.json'), JSON.stringify(report, null, 2))
  await browser?.close()
  await new Promise((resolveClose) => server.httpServer.close(resolveClose))
  rmSync(emptyEnv, { recursive: true, force: true })
}
console.log(JSON.stringify(report))
