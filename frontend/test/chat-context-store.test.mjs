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

// 非整 1M 窗口保留常用 256K 档位，无需额外写预算。
{
  const store = fresh()
  store.currentConversationId = 'a'
  store.contextUsage = {
    ...usage('custom', 1_048_576),
    budgetTokens: 262_144,
    inputLimitTokens: 1_043_968,
  }
  let budgetWrites = 0
  handlers.set('/chat/sessions/a/context', () =>
    Promise.resolve({ ...usage('custom', 983_616), budgetTokens: 262_144, inputLimitTokens: 979_008 }),
  )
  handlers.set('/chat/context-budget', () => {
    budgetWrites++
    throw new Error('256K 档位未变，不应修改预算')
  })
  await store.refreshContextUsage()
  eq('非整 1M 窗口保留当前 256K 档', store.contextUsage.budgetTokens, 262_144)
  eq('档位值未变不重复写入预算', budgetWrites, 0)
  store.$dispose()
}

// 端点重新声明安全输入上限后，旧模型的标准档要跟随到新档位，
// 不能继续显示一个菜单里不存在的绝对值。
{
  const store = fresh()
  store.currentConversationId = 'a'
  const oldWindow = 1_048_576
  const oldLimit = 614_400
  const nextWindow = oldWindow
  const nextLimit = 512_000
  store.contextUsage = {
    ...usage('custom', oldWindow),
    budgetTokens: 524_288,
    inputLimitTokens: oldLimit,
  }
  let selectedBudget
  handlers.set('/chat/sessions/a/context', () =>
    Promise.resolve({
      ...usage('custom', nextWindow),
      budgetTokens: 524_288,
      inputLimitTokens: nextLimit,
    }),
  )
  handlers.set('/chat/context-budget', (config) => {
    selectedBudget = config.data.tokens
    return Promise.resolve({
      ...usage('custom', nextWindow),
      budgetTokens: selectedBudget,
      inputLimitTokens: nextLimit,
    })
  })
  await store.refreshContextUsage()
  eq('安全上限变化后标准档跟随新档位', store.contextUsage.budgetTokens, 262_144)
  eq('同步保存新的标准档', selectedBudget, 262_144)
  store.$dispose()
}
handlers.clear()

// 当前回复的工具记录不能混入历史轮次，闲聊也不能沿用上轮统计。
{
  const store = fresh()
  store.currentConversationId = 'trail'
  store.messages = [{ role: 'USER', createdAt: '2026-11-01T06:00:00Z' }]
  handlers.set('/agent/tool-invocations', () =>
    Promise.resolve({
      items: [
        { id: 'new-2', turnId: 'new', createdAt: '2026-11-01T06:00:02Z' },
        { id: 'new-1', turnId: 'new', createdAt: '2026-11-01T06:00:01Z' },
        { id: 'other-turn', turnId: 'old', createdAt: '2026-11-01T06:00:00Z' },
        { id: 'old', turnId: 'old', createdAt: '2026-11-01T05:00:00Z' },
      ],
    }),
  )
  await store.loadToolTrail()
  eq('仅保留最新轮次的工具记录', store.toolTrail.map((item) => item.id).join(','), 'new-2,new-1')
  store.messages.push({ role: 'USER', createdAt: '2026-11-01T07:00:00Z' })
  await store.loadToolTrail()
  eq('本轮未调工具不能显示上轮统计', store.toolTrail.length, 0)
  store.messages = []
  await store.loadToolTrail()
  eq('无用户消息不显示历史工具记录', store.toolTrail.length, 0)
}
{
  const store = fresh()
  store.currentConversationId = 'legacy'
  store.messages = [{ role: 'USER', createdAt: '2026-11-01T06:00:00+08:00' }]
  handlers.set('/agent/tool-invocations', () =>
    Promise.resolve({
      items: [
        { id: 'legacy-new', createdAt: '2026-10-31T22:00:01Z' },
        { id: 'legacy-old', createdAt: '2026-10-31T21:59:59Z' },
      ],
    }),
  )
  await store.loadToolTrail()
  eq(
    '无 turnId 的旧记录按用户消息时间过滤并正确处理时区',
    store.toolTrail.map((item) => item.id).join(','),
    'legacy-new',
  )
  const slow = deferred()
  handlers.set('/agent/tool-invocations', () => slow.promise)
  const pending = store.loadToolTrail()
  store.currentConversationId = 'other'
  store.toolTrail = [{ id: 'other-turn' }]
  slow.resolve({ items: [{ id: 'late', createdAt: '2026-11-01T06:00:00Z' }] })
  await pending
  eq('迟到审计结果不能覆盖新会话', store.toolTrail[0].id, 'other-turn')
  const failure = deferred()
  handlers.set('/agent/tool-invocations', () =>
    failure.promise.then(() => {
      throw new Error('offline')
    }),
  )
  const failed = store.loadToolTrail()
  store.currentConversationId = 'third'
  store.toolTrail = [{ id: 'third-turn' }]
  failure.resolve()
  await failed
  eq('旧会话审计失败不能清空新会话', store.toolTrail[0].id, 'third-turn')
}

// 编辑验证必须在破坏性回退之前完成；回退失败不能丢掉原消息。
// 确认、取消和失败后都重新关联工具状态；迟到的旧请求不能恢复「待确认」。
for (const decision of ['confirm', 'cancel', 'fail']) {
  const store = fresh()
  store.currentConversationId = 'confirmation'
  store.messages = [{ role: 'USER', createdAt: '2026-11-01T06:00:00Z' }]
  const oldTrail = deferred()
  handlers.set('/agent/tool-invocations', () => oldTrail.promise)
  const oldRequest = store.loadToolTrail()
  const status = decision === 'confirm' ? 'CONFIRMED' : decision === 'cancel' ? 'CANCELLED' : 'FAILED'
  handlers.set('/agent/tool-invocations', () =>
    Promise.resolve({
      items: [
        {
          id: 'audit',
          outcome: 'REQUIRES_CONFIRMATION',
          confirmationStatus: status,
          createdAt: '2026-11-01T06:00:01Z',
        },
      ],
    }),
  )
  handlers.set('/agent/confirmations', () => Promise.resolve([]))
  handlers.set('/agent/confirmations/check/confirm', () =>
    decision === 'fail' ? Promise.reject(new Error('执行失败')) : Promise.resolve({ summary: '处理成功' }),
  )
  handlers.set('/agent/confirmations/check/cancel', () => Promise.resolve())
  const result = await store.resolveConfirmation({ id: 'check', token: 'test' }, decision !== 'cancel')
  eq(`${decision} 后工具状态刷新`, store.toolTrail[0].confirmationStatus, status)
  eq(`${decision} 后移除待确认卡片`, store.pendingConfirmations.length, 0)
  eq(`${decision} 返回对应结果`, result, decision === 'fail' ? null : decision === 'cancel' ? '已取消' : '处理成功')
  oldTrail.resolve({
    items: [
      { id: 'old', outcome: 'REQUIRES_CONFIRMATION', confirmationStatus: 'PENDING', createdAt: '2026-11-01T06:00:01Z' },
    ],
  })
  await oldRequest
  eq(`${decision} 迟到请求不覆盖已处理状态`, store.toolTrail[0].confirmationStatus, status)
}

{
  const store = fresh()
  store.currentConversationId = 'old-confirmation'
  const slow = deferred()
  handlers.set('/agent/confirmations/check/confirm', () => slow.promise)
  const pending = store.resolveConfirmation({ id: 'check', token: 'test' }, true)
  store.currentConversationId = 'new-conversation'
  store.pendingConfirmations = [{ id: 'new' }]
  store.toolTrail = [{ id: 'new' }]
  slow.resolve({ summary: '旧会话处理完成' })
  await pending
  eq('确认完成时已切会话，不改新会话的待确认列表', store.pendingConfirmations[0].id, 'new')
  eq('确认完成时已切会话，不改新会话的工具列表', store.toolTrail[0].id, 'new')
}

// 编辑验证必须在破坏性回退之前完成；回退失败不能丢掉原消息。
{
  const store = fresh()
  store.currentConversationId = 'edit'
  const original = { id: 1, role: 'USER', content: '原消息', sequenceNo: 1 }
  store.messages = [original]
  let rewinds = 0
  handlers.set('/chat/sessions/edit/rewind', () => {
    rewinds++
    return Promise.reject(new Error('回退失败'))
  })
  eq('空白编辑不回退', await store.editAndResend(original, '   '), false)
  eq('超长编辑不回退', await store.editAndResend(original, '字'.repeat(4001)), false)
  eq('助手消息不允许编辑', await store.editAndResend({ ...original, role: 'ASSISTANT' }, '修改'), false)
  eq('验证失败未请求回退', rewinds, 0)
  eq('回退失败明确返回 false', await store.editAndResend(original, '修改'), false)
  eq('只尝试一次回退', rewinds, 1)
  eq('回退失败保留原消息', store.messages[0].content, '原消息')
  eq('回退失败保留错误原因', store.error, '回退失败')
}

// 编辑首条消息时，空尾页不能让已删除的原提问和回答继续留在流式界面。
{
  const store = fresh()
  store.currentConversationId = 'edit-first'
  const original = { id: 1, role: 'USER', content: '原提问', sequenceNo: 1 }
  store.messages = [original, { id: 2, role: 'ASSISTANT', content: '旧回答', sequenceNo: 2 }]
  store.toolTrail = [{ id: 'old-failure', outcome: 'FAILED' }]
  handlers.set('/chat/sessions/edit-first/rewind', () => Promise.resolve({ deleted: 2 }))
  handlers.set('/chat/sessions/edit-first/messages', () => Promise.resolve([]))
  handlers.set('/chat/sessions/search', () => Promise.resolve({ items: [], total: 0, page: 0, size: 20 }))
  handlers.set('/chat/sessions/edit-first/context', () => Promise.resolve(usage('edit', 131072)))
  handlers.set('/agent/tool-invocations', () => Promise.resolve({ items: [] }))
  let contentsDuringStream
  let toolCountDuringStream
  const savedFetch = globalThis.fetch
  globalThis.fetch = async (url) => {
    if (url.endsWith('/auth/csrf')) return Response.json({ data: { token: '' } })
    contentsDuringStream = store.messages.map((item) => item.content).join(',')
    toolCountDuringStream = store.toolTrail.length
    return new Response('event: done\ndata: {"content":"已重发"}\n\n')
  }
  try {
    eq('首条编辑成功重发', await store.editAndResend(original, '新提问'), true)
    eq('重发期间只有修改后的提问', contentsDuringStream, '新提问')
    eq('重试开始就清空旧轮次失败，不等待回复结束', toolCountDuringStream, 0)
  } finally {
    globalThis.fetch = savedFetch
  }
}
