import { createPinia, setActivePinia } from 'pinia'
import { eq, check } from './assert.mjs'
import { handlers } from './stub-api.mjs'
import { useChatStore } from '@/stores/chat'

setActivePinia(createPinia())
const store = useChatStore()
const id = 'retry-send'
store.currentConversationId = id
const runs = new Map()
const attempts = []
let loseResponse = true
handlers.set(`/chat/sessions/${id}/runs`, (config) => {
  const key = config.headers['Idempotency-Key']
  attempts.push(key)
  if (!runs.has(key)) runs.set(key, { id: key, conversationId: id, status: 'COMPLETED', events: [] })
  if (loseResponse) return Promise.reject(new Error('服务端已接收，但响应丢失'))
  return Promise.resolve(runs.get(key))
})
handlers.set(`/chat/sessions/${id}/messages`, () => Promise.resolve([]))
handlers.set(`/chat/sessions/${id}/context`, () => Promise.resolve({ promptTokens: 0 }))
handlers.set(`/chat/sessions/${id}/runs/latest`, () => Promise.resolve(null))
handlers.set('/chat/sessions/search', () => Promise.resolve({ items: [], page: 0, size: 20, total: 0 }))
handlers.set('/agent/confirmations', () => Promise.resolve([]))
handlers.set('/agent/tool-invocations', () => Promise.resolve({ items: [] }))

await store.send('保存这条要求')
eq('响应丢失后释放生成状态', store.streaming, false)
eq('失败不留下未确认的乐观消息', store.messages.length, 0)
loseResponse = false
await store.send('保存这条要求')
eq('相同发送重试复用幂等键', attempts[0], attempts[1])
eq('响应丢失后重试只有一条服务端执行', runs.size, 1)
await store.send('保存这条要求')
check('成功后再次发送同样内容属于新消息', attempts[2] !== attempts[1])

loseResponse = true
await store.send('原内容')
await store.send('修改后的内容')
check('修改内容后使用新幂等键', attempts[3] !== attempts[4])
// Nonterminal warnings survive completion and refresh, but never leak into another conversation.
const warning = { code: 'KNOWLEDGE_RETRIEVAL_FAILED', message: '知识库检索失败，本轮未使用知识库回答' }
const event = (id, name, data) => ({ id, name, payload: JSON.stringify(data) })
const degradedRun = {
  id: 'degraded',
  conversationId: id,
  status: 'COMPLETED',
  events: [
    event(1, 'warning', warning),
    event(2, 'delta', { content: '普通回答' }),
    event(3, 'done', { content: '普通回答', promptTokens: 1 }),
  ],
}
handlers.set(`/chat/sessions/${id}/runs`, () => Promise.resolve(degradedRun))
handlers.set(`/chat/sessions/${id}/runs/latest`, () => Promise.resolve(degradedRun))
await store.send('降级回答', { useKnowledge: true })
eq('降级回复正常完成', store.streaming, false)
eq('降级提示独立于错误状态', store.error, null)
eq('完成后仍显示降级提示', store.warnings[0]?.code, warning.code)
await store.selectSession(id)
eq('刷新会话从已保存的执行记录恢复提示', store.warnings[0]?.message, warning.message)
const resetRun = {
  ...degradedRun,
  events: [...degradedRun.events, event(4, 'reset', {}), event(5, 'done', { content: '重试成功' })],
}
handlers.set(`/chat/sessions/${id}/runs/latest`, () => Promise.resolve(resetRun))
await store.selectSession(id)
eq('恢复后的成功尝试不显示旧降级提示', store.warnings.length, 0)
handlers.set(`/chat/sessions/${id}/runs/latest`, () => Promise.resolve(null))
await store.selectSession(id)
eq('没有执行记录时清空旧提示', store.warnings.length, 0)
handlers.set(`/chat/sessions/${id}`, () => Promise.resolve())
store.warnings = [warning]
await store.removeSession(id)
eq('删除当前会话后清空降级提示', store.warnings.length, 0)
handlers.delete(`/chat/sessions/${id}`)

for (const path of [
  `/chat/sessions/${id}/runs`,
  `/chat/sessions/${id}/messages`,
  `/chat/sessions/${id}/context`,
  `/chat/sessions/${id}/runs/latest`,
  '/chat/sessions/search',
  '/agent/confirmations',
  '/agent/tool-invocations',
])
  handlers.delete(path)
