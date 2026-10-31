import { createPinia, setActivePinia } from 'pinia'
import { eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'
import { useChatStore } from '@/stores/chat'

const usage = (modelName, contextWindow) => ({
  promptTokens: 123,
  budgetTokens: 32768,
  contextWindow,
  estimated: false,
  modelName,
  windowSource: 'configured',
})
const fresh = () => {
  setActivePinia(createPinia())
  return useChatStore()
}
function deferred() {
  let resolve
  const promise = new Promise((r) => {
    resolve = r
  })
  return { promise, resolve }
}

{
  const store = fresh()
  store.currentConversationId = 'a'
  const slow = deferred()
  handlers.set('/chat/sessions/a/context', () => slow.promise)
  handlers.set('/chat/sessions/b/context', () => Promise.resolve(usage('large', 1_000_000)))
  const old = store.refreshContextUsage()
  store.currentConversationId = 'b'
  await store.refreshContextUsage()
  slow.resolve(usage('small', 8192))
  await old
  eq('旧会话的慢窗口响应不能覆盖新模型', store.contextUsage.modelName, 'large')
  eq('新模型窗口保留', store.contextUsage.contextWindow, 1_000_000)
}
{
  const store = fresh()
  store.currentConversationId = 'a'
  let payload
  handlers.set('/chat/context-budget', (config) => {
    payload = config.data
    return Promise.resolve(usage('custom', 2_000_000))
  })
  await store.changeContextBudget(1_000_000)
  eq('预算请求带当前会话', payload.conversationId, 'a')
  eq('预算响应保留模型窗口', store.contextUsage.contextWindow, 2_000_000)
  const slow = deferred()
  handlers.set('/chat/context-budget', () => slow.promise)
  const pending = store.changeContextBudget(65536)
  store.currentConversationId = 'b'
  store.contextUsage = usage('new', 8192)
  slow.resolve(usage('old', 1_000_000))
  await pending
  eq('旧会话预算响应不能覆盖新会话', store.contextUsage.modelName, 'new')
}
{
  const store = fresh()
  handlers.set('/chat/context', () => Promise.resolve(usage('default', 2_000_000)))
  await store.refreshContextUsage()
  eq('空对话也查询默认模型窗口', store.contextUsage.modelName, 'default')
  handlers.set('/chat/context', () => Promise.reject(new Error('offline')))
  await store.refreshContextUsage()
  eq('获取失败不冒充旧模型窗口', store.contextUsage.contextWindow, 0)
}
{
  const store = fresh()
  store.currentConversationId = 'a'
  store.contextUsage = usage('current', 131072)
  handlers.set('/agent/tool-invocations', () => Promise.reject(new Error('audit unavailable')))
  await store.loadToolTrail()
  eq('审计失败不清空上下文读数', store.contextUsage.modelName, 'current')
}
{
  const store = fresh()
  let params
  let payload
  handlers.set('/chat/context', (config) => {
    params = config.params
    return Promise.resolve(usage('default', 131072))
  })
  await store.refreshContextUsage('deep')
  eq('按当前思考深度查询预留空间', params.thinkingLevel, 'deep')
  handlers.set('/chat/context-budget', (config) => {
    payload = config.data
    return Promise.resolve(usage('default', 131072))
  })
  await store.changeContextBudget(65536)
  eq('修改预算保留当前思考深度', payload.thinkingLevel, 'deep')
}
handlers.clear()
