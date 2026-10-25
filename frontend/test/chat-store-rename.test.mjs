import { createPinia, setActivePinia } from 'pinia'

import { eq } from './assert.mjs'
import { businessError, handlers } from './stub-api.mjs'

/**
 * 会话重命名（阶段 7）。
 *
 * 断言的重点不在「有没有调到接口」，而在**调用之后 store 的状态**：
 * 1. 成功 → 就地替换该项，且列表请求次数不增加（重拉会闪屏，而改名不改顺序，没有信息收益）
 * 2. 失败 → 列表原样不动、error 被设置（没做乐观更新，所以也没有需要回滚的东西）
 * 3. 顺序不变（服务端刻意不刷新 updatedAt）
 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useChatStore } = await import('@/stores/chat')

function session(id, title, updatedAt, messageCount = 0) {
  return { conversationId: id, title, createdAt: '2026-03-01T00:00:00Z', updatedAt, messageCount }
}

// ---- 成功：就地替换，不重拉 ----

{
  const original = [
    session('c-1', '最新', '2026-03-03T00:00:00Z', 5),
    session('c-2', '中间', '2026-03-02T00:00:00Z', 2),
    session('c-3', '最旧', '2026-03-01T00:00:00Z', 0),
  ]
  let listCalls = 0
  let patchConfig = null
  handlers.set('/chat/sessions', () => {
    listCalls += 1
    return Promise.resolve(original)
  })
  handlers.set('/chat/sessions/c-3', (config) => {
    patchConfig = config
    return Promise.resolve({ ...original[2], title: '报销流程' })
  })

  freshStore()
  const store = useChatStore()
  await store.loadSessions()
  const callsAfterLoad = listCalls

  const ok = await store.renameSession('c-3', '报销流程')

  eq('返回成功', ok, true)
  eq('使用 PATCH 方法', patchConfig.method, 'patch')
  eq('请求体只带 title', patchConfig.data.title, '报销流程')
  eq('目标项标题被替换', store.sessions[2].title, '报销流程')
  eq('其余项未受影响', store.sessions[0].title, '最新')
  eq('顺序不变', store.sessions.map((item) => item.conversationId).join(','), 'c-1,c-2,c-3')
  eq('没有重拉列表', listCalls, callsAfterLoad)
  eq('不留下错误', store.error, null)
}

// ---- 失败：列表原样，错误可见 ----

{
  const original = [session('c-1', '最新', '2026-03-03T00:00:00Z', 5)]
  handlers.set('/chat/sessions', () => Promise.resolve(original))
  handlers.set('/chat/sessions/c-1', () => Promise.reject(businessError(40004, '会话不存在：c-1')))

  freshStore()
  const store = useChatStore()
  await store.loadSessions()

  const ok = await store.renameSession('c-1', '改不成')

  eq('返回失败', ok, false)
  eq('标题原样不动', store.sessions[0].title, '最新')
  eq('错误被设置', store.error, '会话不存在：c-1')
}

// ---- 重复提交同一标题（服务端幂等）：本地结果一致且不重复插入 ----

{
  const original = [session('c-9', '旧名', '2026-03-05T00:00:00Z', 1)]
  handlers.set('/chat/sessions', () => Promise.resolve(original))
  handlers.set('/chat/sessions/c-9', () => Promise.resolve({ ...original[0], title: '新名' }))

  freshStore()
  const store = useChatStore()
  await store.loadSessions()

  await store.renameSession('c-9', '新名')
  const afterFirst = store.sessions[0].title
  await store.renameSession('c-9', '新名')

  eq('两次提交结果一致', store.sessions[0].title, afterFirst)
  eq('列表仍只有一项', store.sessions.length, 1)
}
