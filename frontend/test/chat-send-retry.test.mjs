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
