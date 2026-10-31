import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { useModelProfileStore } from '@/stores/model-profile'
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
// 不挂载 ChatView：设置页保存当前配置后，持久化的聊天 store 也必须刷新。
{
  const store = fresh()
  const profiles = useModelProfileStore()
  store.currentConversationId = 'a'
  let window = 131072
  let lastThinking
  const profile = () => ({ id: 'p', chatModels: ['custom'], contextWindows: { custom: window } })
  handlers.set('/settings/model/profiles', () => Promise.resolve({ profiles: [profile()], activeId: 'p' }))
  handlers.set('/settings/model/profiles/p', (config) => {
    window = config.data.contextWindows.custom
    return Promise.resolve(profile())
  })
  handlers.set('/chat/sessions/a/context', (config) => {
    lastThinking = config.params.thinkingLevel
    return Promise.resolve(usage('custom', window))
  })
  handlers.set('/chat/context-budget', (config) =>
    Promise.resolve({ ...usage('custom', window), budgetTokens: config.data.tokens }),
  )
  await profiles.load()
  await store.refreshContextUsage('deep')
  eq('保存前窗口', store.contextUsage.contextWindow, 131072)
  await profiles.update('p', { contextWindows: { custom: 1048576 } })
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  eq('对话页未挂载时保存配置也自动刷新窗口', store.contextUsage.contextWindow, 1048576)
  eq('自动刷新保留当前思考档位', lastThinking, 'deep')

  store.streaming = true
  await profiles.update('p', { contextWindows: { custom: 262144 } })
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  eq('流式期间不改变当前轮窗口快照', store.contextUsage.contextWindow, 1048576)
  store.streaming = false
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  eq('流式结束自动补刷新配置', store.contextUsage.contextWindow, 262144)

  // 同一配置对象里的容量变化也要捕捉，不能只比较对象引用。
  window = 524288
  profiles.profiles[0].contextWindows.custom = window
  await nextTick()
  await new Promise((resolve) => setTimeout(resolve, 0))
  eq('同一配置内的窗口变化自动刷新', store.contextUsage.contextWindow, 524288)
  store.$dispose()
}
// 改容量后保持「完整」档：不能把旧安全预算误当作新模型的自定义档。
{
  const store = fresh()
  store.currentConversationId = 'a'
  const oldLimit = 131072 - 4096 - 512
  const newLimit = 1048576 - 4096 - 512
  store.contextUsage = { ...usage('custom', 131072), budgetTokens: oldLimit, inputLimitTokens: oldLimit }
  let selectedBudget
  handlers.set('/chat/sessions/a/context', () =>
    Promise.resolve({
      ...usage('custom', 1048576),
      budgetTokens: oldLimit,
      inputLimitTokens: newLimit,
    }),
  )
  handlers.set('/chat/context-budget', (config) => {
    selectedBudget = config.data.tokens
    return Promise.resolve({ ...usage('custom', 1048576), budgetTokens: newLimit, inputLimitTokens: newLimit })
  })
  await store.refreshContextUsage()
  eq('修改模型容量后完整档自动跟随新容量', store.contextUsage.budgetTokens, newLimit)
  eq('实际预算也同步更新，并保留安全余量', selectedBudget, newLimit)

  selectedBudget = null
  store.contextUsage = { ...usage('custom', 131072), budgetTokens: 45000, inputLimitTokens: oldLimit }
  await store.refreshContextUsage()
  eq('自定义预算不自动迁移档位', selectedBudget, null)
  store.$dispose()
}
handlers.clear()
