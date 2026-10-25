import { createPinia, setActivePinia } from 'pinia'

import { check, eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'

/**
 * 任务标签（阶段 14）在 store 层的行为。
 *
 * 重点：
 * 1. 标签筛选必须随请求发出，且切换筛选要回到第 1 页（否则会停在越界页看到空表）。
 * 2. 标签清单加载失败**不得**把整个任务列表标成失败 —— 下拉是辅助功能。
 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useTaskStore } = await import('@/stores/task')

function pageOf(items) {
  return { items, page: 0, size: 20, total: items.length }
}

function task(id, tags) {
  return {
    id,
    title: '任务' + id,
    description: null,
    status: 'TODO',
    priority: 'MEDIUM',
    tags,
    dueAt: null,
    completedAt: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
    overdue: false,
  }
}

// ---- 筛选：tag 随请求发出 ----

{
  let seen = null
  handlers.set('/tasks', (config) => {
    seen = config.params
    return Promise.resolve(pageOf([task('1', ['工作'])]))
  })
  handlers.set('/tasks/tags', () => Promise.resolve(['工作']))

  freshStore()
  const store = useTaskStore()
  store.tagFilter = '工作'
  await store.reload()

  eq('tag 参数随请求发出', seen.tag, '工作')
  eq('筛选结果保留标签', store.tasks[0].tags[0], '工作')
}

{
  let seen = null
  handlers.set('/tasks', (config) => {
    seen = config.params
    return Promise.resolve(pageOf([]))
  })

  freshStore()
  const store = useTaskStore()
  store.tagFilter = ''
  await store.reload()

  eq('空筛选不传 tag（避免后端收到空串）', seen.tag, undefined)
}

// ---- 创建：tags 透传 ----

{
  let posted = null
  handlers.set('/tasks', (config) => {
    if (config.method === 'post') {
      posted = config.data
      return Promise.resolve(task('9', posted.tags ?? []))
    }
    return Promise.resolve(pageOf([]))
  })
  handlers.set('/tasks/tags', () => Promise.resolve(['工作', '本周']))

  freshStore()
  const store = useTaskStore()
  await store.create('写周报', 'MEDIUM', undefined, ['工作', '本周'])

  check('创建请求带 tags', Array.isArray(posted.tags))
  eq('标签数量正确', posted.tags.length, 2)
  eq('标签内容正确', posted.tags[0], '工作')
}

// ---- 标签清单：成功填充、失败不污染 ----

{
  handlers.set('/tasks/tags', () => Promise.resolve(['学习', '工作']))

  freshStore()
  const store = useTaskStore()
  await store.refreshTags()

  eq('标签清单填充', store.tags.length, 2)
  eq('顺序保持服务端给的顺序', store.tags[0], '学习')
}

{
  handlers.set('/tasks/tags', () => Promise.reject(new Error('502')))

  freshStore()
  const store = useTaskStore()
  await store.refreshTags()

  eq('清单失败时置空，不残留旧值', store.tags.length, 0)
  eq('清单失败不设置页面级 error', store.error, null)
}
