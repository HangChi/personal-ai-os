import { createPinia, setActivePinia } from 'pinia'

import { check, eq } from './assert.mjs'
import { businessError, handlers } from './stub-api.mjs'

/**
 * 日程 store：区间查询的分页语义。
 *
 * 关键约定（store 注释里写过的「判定顺序」）：
 * 显式传入 from/to 视为「换了查询窗口」，页码归零；不传则沿用当前窗口与页码
 * （增删改后的刷新用）。这条顺序错了，用户翻到第 3 页后改个日期就会回到越界页。
 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useCalendarStore } = await import('@/stores/calendar')

function page(items, total, page = 0, size = 100) {
  return Promise.resolve({ items, page, size, total })
}

// ---- 显式区间 = 换窗口：页码归零 ----

{
  const seen = []
  handlers.set('/calendar/events', (config) => {
    seen.push({ from: config.params.from, to: config.params.to, page: config.params.page })
    return page([], 4, config.params.page, config.params.size)
  })
  freshStore()
  const store = useCalendarStore()

  await store.goToPage(1)
  eq('先翻到第 2 页', store.page, 1)

  await store.loadOccurrences('2026-10-20T00:00:00Z', '2026-10-27T00:00:00Z')
  eq('显式换区间后页码归零', store.page, 0)
  eq('  请求确实以 page=0 发出', seen.at(-1).page, 0)
  eq('  区间参数透传', seen.at(-1).from, '2026-10-20T00:00:00Z')
}

// ---- 不传区间 = 沿用窗口与页码（增删改后的刷新） ----

{
  const seen = []
  handlers.set('/calendar/events', (config) => {
    seen.push(config.params.page)
    return page([], 4, config.params.page, config.params.size)
  })
  freshStore()
  const store = useCalendarStore()

  await store.goToPage(2)
  await store.loadOccurrences()

  eq('无参刷新沿用当前页码', seen.at(-1), 2)
  eq('  store.page 不变', store.page, 2)
}

// ---- 加载：total 报告展开后的全量实例数 ----

{
  handlers.set('/calendar/events', () =>
    page([{ eventId: 'e1', title: '周会', startsAt: '2026-10-21T09:00:00+08:00' }], 4),
  )
  freshStore()
  const store = useCalendarStore()
  await store.loadOccurrences()

  eq('items 来自分页响应', store.occurrences.length, 1)
  eq('total = 区间内全量实例数（重复展开后）', store.total, 4)
  eq('日程默认每页 100', store.size, 100)
}

// ---- 失败路径 ----

{
  handlers.set('/calendar/events', () => Promise.reject(businessError(40000, '区间终点不能早于区间起点')))
  freshStore()
  const store = useCalendarStore()
  await store.loadOccurrences()

  eq('失败时 occurrences 清空', store.occurrences.length, 0)
  eq('失败时 total 归零', store.total, 0)
  check('失败时 error 带上服务端原因', store.error === '区间终点不能早于区间起点')
}

// ---- 阶段 13：无参刷新必须复用上次窗口 ----

{
  const windows = []
  handlers.set('/calendar/events', (config) => {
    windows.push({ from: config.params.from, to: config.params.to })
    return page([], 0)
  })
  freshStore()
  const store = useCalendarStore()

  await store.loadOccurrences('2026-11-01T00:00:00.000Z', '2026-12-01T00:00:00.000Z')
  // 模拟「月视图翻到 11 月后新建一条」触发的刷新
  await store.loadOccurrences()

  eq('首次请求带窗口下界', windows[0].from, '2026-11-01T00:00:00.000Z')
  eq('无参刷新复用同一窗口（否则会跳回默认区间）', windows[1].from, '2026-11-01T00:00:00.000Z')
  eq('无参刷新复用同一窗口上界', windows[1].to, '2026-12-01T00:00:00.000Z')
}

// ---- 阶段 13：按本地日期分组，不是截取 UTC ----

{
  // 本地 10-26 00:30 在东八区是 UTC 10-25 16:30；截取 UTC 会分到 10-25
  const dawn = new Date(2026, 9, 26, 0, 30, 0).toISOString()
  handlers.set('/calendar/events', () => page([{ eventId: 'e1', title: '晨读', startsAt: dawn, endsAt: dawn }], 1))
  freshStore()
  const store = useCalendarStore()
  await store.loadOccurrences()

  eq('凌晨日程按本地日期归组', store.groupedByDay[0].day, '2026-10-26')
}

// ---- 阶段 13：size 可覆盖（月视图一次拉整月） ----

{
  let seenSize = null
  handlers.set('/calendar/events', (config) => {
    seenSize = config.params.size
    return Promise.resolve({ items: [], page: 0, size: config.params.size, total: 0 })
  })
  freshStore()
  const store = useCalendarStore()
  await store.loadOccurrences('2026-11-01T00:00:00.000Z', '2026-12-01T00:00:00.000Z', 0, 500)

  eq('月视图请求 500 条', seenSize, 500)
  eq('store.size 跟随响应', store.size, 500)
}
