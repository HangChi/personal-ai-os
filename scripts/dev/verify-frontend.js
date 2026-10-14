#!/usr/bin/env node
/**
 * 前端真机验证脚本：在真实浏览器里跑一遍关键交互。
 *
 * 为什么需要它：后端的 115 个测试只覆盖服务端；前端此前仅有 `type-check` 与 `build`，
 * 而这两者对「点击之后会发生什么」一无所知。本脚本补上这一段 ——
 * 它会**断言数据库里的值**，而不只是「页面上看起来对」。
 *
 * 覆盖范围（当前）：
 *   1. 任务编辑：弹窗预填 → 保存 → 列表与数据库都更新；未触碰的字段保持不变
 *   2. 日程编辑：编辑基于**原始定义**（而非某次发生实例），因此重复规则与提前量不会被抹掉
 *   3. 控制台无错误
 *
 * 前置（只需一次）：
 *   cd /Users/songhangchi/.workbuddy/binaries/node/workspace && npm install playwright
 *   浏览器使用 ms-playwright 缓存（见下面 CACHED_SHELL），或退回本机安装的 Chrome
 *
 * 运行方式（需先启动后端与前端 dev server）：
 *   # 后端：必须显式指定端口 —— sandbox 的 shell 里存在 SERVER_PORT，会覆盖 application.yml
 *   java -jar backend/target/personal-ai-os-backend-0.1.0-SNAPSHOT.jar --server.port=8080
 *   # 前端：必须显式绑 IPv4 —— Vite 默认绑 localhost，Node 会解析成 ::1，127.0.0.1 打不通
 *   cd frontend && npm run dev -- --host 127.0.0.1
 *   # 然后：
 *   NODE_PATH=<受管 node workspace>/node_modules \
 *   PLAYWRIGHT_BROWSERS_PATH=$HOME/Library/Caches/ms-playwright \
 *   node scripts/dev/verify-frontend.js
 *
 * 注意：脚本会向数据库写入验证数据（标题带时间戳后缀），不会清理 —— 便于事后排查。
 */
const { chromium } = require('playwright')

const BASE = process.env.FRONTEND_BASE_URL ?? 'http://127.0.0.1:5173'
const API = process.env.BACKEND_BASE_URL ?? 'http://127.0.0.1:8080/api/v1'

/** 缓存里的 chromium。Playwright 版本升级后其期望的浏览器编号可能变化，届时改用 channel: 'chrome'。 */
const CACHED_SHELL =
  process.env.PLAYWRIGHT_SHELL_PATH ??
  `${process.env.HOME}/Library/Caches/ms-playwright/chromium_headless_shell-1243/` +
    'chrome-headless-shell-mac-arm64/chrome-headless-shell'

const failures = []

function check(label, actual, expected) {
  const ok = String(actual) === String(expected)
  console.log(`  ${ok ? '✔' : '✘'} ${label}: ${actual}${ok ? '' : ` （期望 ${expected}）`}`)
  if (!ok) {
    failures.push(label)
  }
}

async function apiJson(method, path, body) {
  const response = await fetch(`${API}${path}`, {
    method,
    headers: { 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  })
  return response.json()
}

async function launchBrowser() {
  const candidates = [{ executablePath: CACHED_SHELL }, { channel: 'chrome' }]
  let lastError
  for (const options of candidates) {
    try {
      return await chromium.launch(options)
    } catch (error) {
      lastError = error
      console.log(`  （启动方式 ${JSON.stringify(options)} 失败，换下一种）`)
    }
  }
  throw lastError
}

;(async () => {
  const browser = await launchBrowser()
  const page = await browser.newPage()
  const consoleErrors = []
  page.on('console', (message) => {
    if (message.type() === 'error') {
      consoleErrors.push(message.text())
    }
  })
  page.on('pageerror', (error) => consoleErrors.push(`pageerror: ${error.message}`))

  // ---------------------------------------------------------------- 种数据
  const suffix = Date.now()
  const taskTitle = `验证任务-${suffix}`
  const taskNewTitle = `验证任务-已改-${suffix}`
  const eventTitle = `验证日程-${suffix}`
  const eventNewTitle = `验证日程-已改-${suffix}`

  console.log('### 种数据')
  const task = await apiJson('POST', '/tasks', { title: taskTitle, priority: 'LOW' })
  const event = await apiJson('POST', '/calendar/events', {
    title: eventTitle,
    startsAt: new Date(Date.now() + 3_600_000).toISOString(),
    endsAt: new Date(Date.now() + 7_200_000).toISOString(),
    recurrenceType: 'WEEKLY',
    reminderLeadMinutes: 20,
  })
  check('任务已创建', task.code, 0)
  check('日程已创建', event.code, 0)

  // ---------------------------------------------------------------- 任务编辑
  console.log('### 任务页：编辑标题')
  await page.goto(`${BASE}/tasks`, { waitUntil: 'networkidle' })
  await page.getByText(taskTitle, { exact: true }).waitFor({ timeout: 10_000 })

  await page
    .locator('.el-table__row', { hasText: taskTitle })
    .getByRole('button', { name: '编辑' })
    .click()
  await page.locator('.el-dialog').getByPlaceholder('任务标题').waitFor({ timeout: 5_000 })
  check('编辑弹窗已打开', await page.locator('.el-dialog').isVisible(), true)
  check(
    '弹窗预填了原标题',
    await page.locator('.el-dialog').getByPlaceholder('任务标题').inputValue(),
    taskTitle,
  )

  await page.locator('.el-dialog').getByPlaceholder('任务标题').fill(taskNewTitle)
  await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
  await page.waitForTimeout(1_200)

  check('弹窗已关闭', await page.locator('.el-dialog').isVisible().catch(() => false), false)
  await page.getByText(taskNewTitle, { exact: true }).waitFor({ timeout: 10_000 })
  check('列表中显示新标题', true, true)

  const taskAfter = await apiJson('GET', `/tasks/${task.data.id}`)
  check('数据库中的标题已更新', taskAfter.data.title, taskNewTitle)
  check('未触碰的优先级保持不变', taskAfter.data.priority, 'LOW')

  // ---------------------------------------------------------------- 日程编辑
  console.log('### 日程页：编辑标题（重复日程）')
  await page.goto(`${BASE}/calendar`, { waitUntil: 'networkidle' })
  await page.getByText(eventTitle, { exact: true }).first().waitFor({ timeout: 10_000 })

  await page
    .locator('.calendar__item', { hasText: eventTitle })
    .first()
    .getByRole('button', { name: '编辑' })
    .click()
  await page.locator('.el-dialog').getByPlaceholder('日程标题').waitFor({ timeout: 8_000 })
  check('编辑弹窗已打开（原始定义已取回）', await page.locator('.el-dialog').isVisible(), true)
  check(
    '弹窗预填了原标题',
    await page.locator('.el-dialog').getByPlaceholder('日程标题').inputValue(),
    eventTitle,
  )

  await page.locator('.el-dialog').getByPlaceholder('日程标题').fill(eventNewTitle)
  await page.locator('.el-dialog').getByRole('button', { name: '保存' }).click()
  await page.waitForTimeout(1_200)

  check('弹窗已关闭', await page.locator('.el-dialog').isVisible().catch(() => false), false)
  await page.getByText(eventNewTitle, { exact: true }).first().waitFor({ timeout: 10_000 })
  check('列表中显示新标题', true, true)

  const eventAfter = await apiJson('GET', `/calendar/events/${event.data.id}`)
  check('数据库中的标题已更新', eventAfter.data.title, eventNewTitle)
  // 关键：编辑基于「原始定义」，重复规则与提前量不该被连带抹掉
  check('重复规则保持不变', eventAfter.data.recurrenceType, 'WEEKLY')
  check('提前提醒量保持不变', eventAfter.data.reminderLeadMinutes, 20)

  await page.screenshot({ path: '/tmp/fe-verify-calendar.png' })

  // ---------------------------------------------------------------- 控制台
  console.log('### 控制台')
  const realErrors = consoleErrors.filter((text) => !text.includes('favicon'))
  check('无控制台错误', realErrors.length === 0 ? '无' : realErrors.join(' | '), '无')

  await browser.close()

  console.log('')
  if (failures.length === 0) {
    console.log('### 结论：全部通过')
    process.exit(0)
  }
  console.log(`### 结论：${failures.length} 项失败 -> ${failures.join('、')}`)
  process.exit(1)
})().catch((error) => {
  console.error('### 脚本异常：', error.message)
  process.exit(2)
})
