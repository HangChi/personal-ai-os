import { createPinia, setActivePinia } from 'pinia'

import { eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'

/** 工具调用记录的分页、筛选与错误反馈。 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useActivityStore } = await import('@/stores/activity')

function pageOf(items, page = 0, size = 20, total = items.length) {
  return { items, page, size, total }
}

function invocation(id, outcome = 'SUCCESS') {
  return {
    id,
    operation: 'findTasks',
    source: 'TOOL',
    outcome,
    durationMs: 12,
    conversationId: null,
    createdAt: '2026-10-25T00:00:00Z',
  }
}

// ---- 加载 ----

{
  handlers.set('/agent/tool-invocations', (config) => {
    eq('默认从第 0 页开始', config.params.page, 0)
    return Promise.resolve(pageOf([invocation(1), invocation(2)], 0, 20, 2))
  })
  freshStore()
  const store = useActivityStore()
  await store.loadInvocations(0)

  eq('列表填充', store.invocations.length, 2)
  eq('total 取后端给的值', store.total, 2)
  eq('无错误', store.error, null)
  eq('loading 复位', store.loading, false)
}

// ---- 分页 ----

{
  let requestedPage = null
  handlers.set('/agent/tool-invocations', (config) => {
    requestedPage = config.params.page
    return Promise.resolve(pageOf([invocation(3)], 1, 20, 21))
  })
  freshStore()
  const store = useActivityStore()
  await store.setPage(1)

  eq('请求第 1 页', requestedPage, 1)
  eq('page 与响应同步', store.page, 1)
  eq('total 反映过滤后全量，不是本页条数', store.total, 21)
}

// ---- 筛选：必须重置页码 ----

{
  const calls = []
  handlers.set('/agent/tool-invocations', (config) => {
    calls.push({ page: config.params.page, outcome: config.params.outcome })
    return Promise.resolve(pageOf([invocation(4, 'FAILED')], 0, 20, 1))
  })
  freshStore()
  const store = useActivityStore()

  await store.setPage(1)
  await store.setOutcome('FAILED')

  eq('筛选后请求回到第 0 页', calls[1].page, 0)
  eq('筛选条件随请求发出', calls[1].outcome, 'FAILED')
  eq('store.page 归零', store.page, 0)
  eq('筛选结果生效', store.invocations[0].outcome, 'FAILED')
}

// ---- 清除筛选：不带 outcome 参数 ----

{
  let lastOutcome = 'unset'
  handlers.set('/agent/tool-invocations', (config) => {
    lastOutcome = config.params.outcome
    return Promise.resolve(pageOf([]))
  })
  freshStore()
  const store = useActivityStore()

  await store.setOutcome('FAILED')
  await store.setOutcome(null)

  eq('清空筛选时不传 outcome（避免后端收到空串 enum）', lastOutcome, undefined)
  eq('outcome 状态为 null', store.outcome, null)
}

// ---- 加载失败：错误可读且 loading 复位 ----

{
  handlers.set('/agent/tool-invocations', () => Promise.reject(new Error('后端不可达')))
  freshStore()
  const store = useActivityStore()
  await store.loadInvocations(0)

  eq('错误文案透传', store.error, '后端不可达')
  eq('失败后 loading 复位', store.loading, false)
}
