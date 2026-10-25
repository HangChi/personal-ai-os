import { createPinia, setActivePinia } from 'pinia'

import { eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'

/**
 * 列表分页与站内搜索（阶段 16）。
 *
 * 重点在「搜索词变化必须把页码归零」：否则用户先翻到第 3 页、再输入关键词，
 * 筛选结果可能不足 3 页，页面停在一张空表上 —— 看起来像「什么都没搜到」，
 * 而实际是翻过头了。
 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useChatStore } = await import('@/stores/chat')
const { useKnowledgeStore } = await import('@/stores/knowledge')

function pageOf(items, total) {
  return { items, page: 0, size: 20, total }
}

// ---- 会话：搜索带关键词并归零页码 ----

{
  const seen = []
  handlers.set('/chat/sessions/search', (config) => {
    seen.push(config.params)
    return Promise.resolve(pageOf([], 0))
  })

  freshStore()
  const store = useChatStore()
  store.sessionKeyword = '周报'
  await store.searchSessions()

  eq('搜索请求带 keyword', seen[0].keyword, '周报')
  eq('搜索时页码归零', seen[0].page, 0)
}

{
  const seen = []
  handlers.set('/chat/sessions/search', (config) => {
    seen.push(config.params)
    return Promise.resolve(pageOf([], 0))
  })

  freshStore()
  const store = useChatStore()
  store.sessionKeyword = '   '
  await store.searchSessions()

  eq('纯空白关键词不传 keyword（避免后端收到空串枚举）', seen[0].keyword, undefined)
}

// ---- 会话：翻页 ----

{
  let seenPage = null
  handlers.set('/chat/sessions/search', (config) => {
    seenPage = config.params.page
    return Promise.resolve({ items: [], page: 1, size: 20, total: 40 })
  })

  freshStore()
  const store = useChatStore()
  await store.goToSessionPage(1)

  eq('翻页请求带 page', seenPage, 1)
  eq('store.page 跟随响应', store.sessionsPage, 1)
  eq('store.total 跟随响应', store.sessionsTotal, 40)
}

// ---- 会话：加载失败置空列表 ----

{
  handlers.set('/chat/sessions/search', () => Promise.reject(new Error('后端不可达')))

  freshStore()
  const store = useChatStore()
  await store.loadSessions()

  eq('失败时列表清空', store.sessions.length, 0)
  eq('失败时有错误文案', store.error, '后端不可达')
}

// ---- 知识库：搜索与翻页 ----

{
  const seen = []
  handlers.set('/knowledge/documents/search', (config) => {
    seen.push(config.params)
    return Promise.resolve(pageOf([], 0))
  })

  freshStore()
  const store = useKnowledgeStore()
  store.keyword = 'isolation'
  await store.searchDocuments()

  eq('文档搜索带 keyword', seen[0].keyword, 'isolation')
  eq('文档搜索归零页码', seen[0].page, 0)
}

{
  let seenPage = null
  handlers.set('/knowledge/documents/search', (config) => {
    seenPage = config.params.page
    return Promise.resolve({ items: [], page: 2, size: 20, total: 25 })
  })

  freshStore()
  const store = useKnowledgeStore()
  await store.goToPage(2)

  eq('文档翻页带 page', seenPage, 2)
  eq('文档 store.page 跟随', store.page, 2)
  eq('文档 store.total 跟随', store.total, 25)
}

{
  handlers.set('/knowledge/documents/search', () => Promise.reject(new Error('502')))

  freshStore()
  const store = useKnowledgeStore()
  await store.loadDocuments()

  eq('文档失败时清空', store.documents.length, 0)
  eq('文档失败时 total 归零', store.total, 0)
}
