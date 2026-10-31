import { createPinia, setActivePinia } from 'pinia'
import { check, eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'
import { useSearchStore } from '@/stores/search'
import { useTaskStore } from '@/stores/task'
import { useCalendarStore } from '@/stores/calendar'
import { useKnowledgeStore } from '@/stores/knowledge'

// 延迟由测试直接控制，不依赖网络或定时器，固定重现逆序返回。
const cases = [
  {
    name: '全局搜索',
    use: useSearchStore,
    url: '/search',
    run: (s) => s.search('term'),
    result: (id) => ({ groups: [{ module: id }], totalHits: 1 }),
    read: (s) => s.groups[0]?.module,
    loading: 'loading',
    error: 'error',
  },
  {
    name: '任务列表',
    use: useTaskStore,
    url: '/tasks',
    run: (s) => s.load(),
    result: (id) => ({ items: [{ id }], total: 1 }),
    read: (s) => s.tasks[0]?.id,
    loading: 'loading',
    error: 'error',
  },
  {
    name: '日程列表',
    use: useCalendarStore,
    url: '/calendar/events',
    run: (s) => s.loadOccurrences(),
    result: (id) => ({ items: [{ id }], total: 1, page: 0, size: 20 }),
    read: (s) => s.occurrences[0]?.id,
    loading: 'loadingOccurrences',
    error: 'error',
  },
  {
    name: '文档列表',
    use: useKnowledgeStore,
    url: '/knowledge/documents/search',
    run: (s) => s.loadDocuments(),
    result: (id) => ({ items: [{ id }], total: 1, page: 0, size: 20 }),
    read: (s) => s.documents[0]?.id,
    loading: 'loadingDocuments',
    error: 'error',
  },
  {
    name: '知识检索',
    use: useKnowledgeStore,
    url: '/knowledge/search',
    run: (s) => s.search('term'),
    result: (id) => ({ hits: [{ id }] }),
    read: (s) => s.hits[0]?.id,
    loading: 'searching',
    error: 'searchError',
  },
  {
    name: '知识问答',
    use: useKnowledgeStore,
    url: '/knowledge/ask',
    run: (s) => s.ask('term'),
    result: (id) => ({ answer: id }),
    read: (s) => s.answer?.answer,
    loading: 'asking',
    error: 'askError',
  },
]

for (const c of cases) {
  setActivePinia(createPinia())
  const s = c.use()
  const pending = []
  handlers.set(c.url, () => new Promise((resolve, reject) => pending.push({ resolve, reject })))
  const old = c.run(s)
  const latest = c.run(s)
  pending[1].resolve(c.result('new'))
  await latest
  pending[0].resolve(c.result('old'))
  await old
  eq(`${c.name}：旧成功响应不能覆盖新结果`, c.read(s), 'new')

  const staleError = c.run(s)
  const current = c.run(s)
  pending[2].reject(new Error('旧请求失败'))
  await staleError
  check(`${c.name}：旧请求结束不能提前清除 loading`, s[c.loading])
  eq(`${c.name}：旧错误不能显示到当前请求`, s[c.error], null)
  pending[3].resolve(c.result('current'))
  await current
  eq(`${c.name}：最新请求完成后清除 loading`, s[c.loading], false)
  handlers.delete(c.url)
}

setActivePinia(createPinia())
const search = useSearchStore()
let finish
handlers.set(
  '/search',
  () =>
    new Promise((resolve) => {
      finish = resolve
    }),
)
const pendingSearch = search.search('old')
await search.search('')
finish({ groups: [{ module: 'old' }], totalHits: 10 })
await pendingSearch
eq('清空搜索使在途响应失效', search.groups.length, 0)
eq('清空搜索退出加载态', search.loading, false)
eq('清空搜索恢复未搜索状态', search.searched, false)
handlers.delete('/search')
