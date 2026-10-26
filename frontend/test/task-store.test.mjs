import { createPinia, setActivePinia } from 'pinia'

import { check, eq } from './assert.mjs'
import { businessError, handlers } from './stub-api.mjs'

/**
 * 任务 store：分页状态与失败路径。
 *
 * 覆盖 2026-10-14 分页改造（技术债清偿）引入的状态机：
 * items/total/page/size 的流转、翻页、筛选归零、失败清空。
 * API 层用替身（stub-api.mjs），被测的是真实 store 逻辑。
 */

/** 每个用例换一个全新的 pinia，避免状态串到下一个用例 */
function freshStore() {
  setActivePinia(createPinia())
}

const { useTaskStore } = await import('@/stores/task')

function page(items, total, page = 0, size = 20) {
  return Promise.resolve({ items, page, size, total })
}

// ---- 加载：分页字段填充 ----

{
  handlers.set('/tasks', (config) =>
    page(
      [{ id: '1', title: '写周报', status: 'TODO', priority: 'HIGH', overdue: true }],
      137,
      config.params.page,
      config.params.size,
    ),
  )
  freshStore()
  const store = useTaskStore()
  await store.load()

  eq('items 来自分页响应', store.tasks.length, 1)
  eq('total 透传（不再有静默截断）', store.total, 137)
  eq('page 初始为 0', store.page, 0)
  eq('size 默认 20', store.size, 20)
  eq('待办计数按当前页计算', store.pendingCount, 1)
  eq('逾期计数按当前页计算', store.overdueCount, 1)
}

// ---- 翻页：请求带新页码，状态跟随 ----

{
  const seen = []
  handlers.set('/tasks', (config) => {
    seen.push(config.params.page)
    return page([], 5, config.params.page, config.params.size)
  })
  freshStore()
  const store = useTaskStore()
  await store.goToPage(2)

  eq('翻页请求带 page=2', seen.at(-1), 2)
  eq('store.page 跟随响应', store.page, 2)
  eq('越界页 items 为空但 total 保留', store.total, 5)
}

// ---- 筛选变化：reload 归零页码 ----

{
  const seen = []
  handlers.set('/tasks', (config) => {
    seen.push(config.params.page)
    return page([], 5, config.params.page, config.params.size)
  })
  freshStore()
  const store = useTaskStore()
  await store.goToPage(2)
  store.statusFilter = 'DONE'
  await store.reload()

  eq('reload 后页码归零（否则筛选后停在越界页）', store.page, 0)
  eq('  请求确实以 page=0 发出', seen.at(-1), 0)
}

// ---- 失败路径：清空且不白屏 ----

{
  handlers.set('/tasks', () => Promise.reject(businessError(40004, '任务不存在')))
  freshStore()
  const store = useTaskStore()
  await store.load()

  eq('失败时 items 清空', store.tasks.length, 0)
  eq('失败时 total 归零', store.total, 0)
  check('失败时 error 带上服务端原因', store.error === '任务不存在')
  eq('失败后 loading 复位', store.loading, false)
}

// ---- 忘打桩：替身立刻炸，而不是静默 undefined ----

{
  handlers.clear()
  freshStore()
  const store = useTaskStore()
  await store.load()
  check(
    '未注册 handler 的请求进入 error（测试忘打桩时可见）',
    typeof store.error === 'string' && store.error.includes('/tasks'),
  )
}
