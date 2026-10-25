import { createPinia, setActivePinia } from 'pinia'

import { eq } from './assert.mjs'
import { businessError, handlers } from './stub-api.mjs'

/**
 * 长期记忆 store（阶段 8）。
 *
 * 三条路径的刷新策略不同，这是最容易做错的地方：
 * - 新增 → 重拉（后端可能因去重返回既有条目，只有列表能反映真实结果）
 * - 编辑 → 就地替换（集合与顺序不变，重拉只会闪一下）
 * - 删除 → 成功后本地移除；失败时列表必须原样保留
 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useMemoryStore } = await import('@/stores/memory')

function memory(id, content, updatedAt = '2026-10-01T00:00:00Z') {
  return { id, content, createdAt: '2026-09-01T00:00:00Z', updatedAt }
}

// ---- 加载 ----

{
  handlers.set('/memories', () => Promise.resolve([memory('m-1', '周报每周五交')]))
  freshStore()
  const store = useMemoryStore()
  await store.load()

  eq('加载后列表填充', store.memories.length, 1)
  eq('内容正确', store.memories[0].content, '周报每周五交')
  eq('无错误', store.error, null)
}

// ---- 新增：必须重拉（去重可能让「新增」变成「返回既有」）----

{
  let listCalls = 0
  let postCalls = 0
  const first = [memory('m-1', '周报每周五交')]
  const afterCreate = [memory('m-2', '我常住扬州'), memory('m-1', '周报每周五交')]

  handlers.set('/memories', (config) => {
    if (config.method === 'post') {
      postCalls += 1
      eq('新增请求体带内容', config.data.content, '我常住扬州')
      return Promise.resolve(afterCreate[0])
    }
    listCalls += 1
    return Promise.resolve(listCalls === 1 ? first : afterCreate)
  })

  freshStore()
  const store = useMemoryStore()
  await store.load()
  const callsAfterLoad = listCalls

  const ok = await store.create('我常住扬州')

  eq('新增返回成功', ok, true)
  eq('调用了 POST', postCalls, 1)
  eq('新增后重拉了列表', listCalls, callsAfterLoad + 1)
  eq('列表包含新条目', store.memories.length, 2)
}

// ---- 编辑：就地替换，不重拉 ----

{
  let listCalls = 0
  handlers.set('/memories', () => {
    listCalls += 1
    return Promise.resolve([memory('m-1', '周报周五交'), memory('m-2', '我常住扬州')])
  })
  handlers.set('/memories/m-1', (config) => {
    eq('使用 PATCH 方法', config.method, 'patch')
    eq('请求体带新内容', config.data.content, '周报周四交')
    return Promise.resolve(memory('m-1', '周报周四交', '2026-10-02T00:00:00Z'))
  })

  freshStore()
  const store = useMemoryStore()
  await store.load()
  const callsAfterLoad = listCalls

  const ok = await store.update('m-1', '周报周四交')

  eq('编辑返回成功', ok, true)
  eq('目标项被替换', store.memories[0].content, '周报周四交')
  eq('其余项未受影响', store.memories[1].content, '我常住扬州')
  eq('编辑没有重拉列表', listCalls, callsAfterLoad)
}

// ---- 删除：成功才移除 ----

{
  handlers.set('/memories', () => Promise.resolve([memory('m-1', '要删的'), memory('m-2', '留下的')]))
  handlers.set('/memories/m-1', (config) => {
    eq('使用 DELETE 方法', config.method, 'delete')
    return Promise.resolve(undefined)
  })

  freshStore()
  const store = useMemoryStore()
  await store.load()

  const ok = await store.remove('m-1')

  eq('删除返回成功', ok, true)
  eq('只移除目标项', store.memories.length, 1)
  eq('剩下的是另一条', store.memories[0].id, 'm-2')
}

// ---- 失败路径：列表原样，错误可见 ----

{
  handlers.set('/memories', () => Promise.resolve([memory('m-1', '原内容')]))
  handlers.set('/memories/m-1', () => Promise.reject(businessError(40004, '记忆不存在：m-1')))

  freshStore()
  const store = useMemoryStore()
  await store.load()

  const ok = await store.update('m-1', '改不成')

  eq('编辑失败返回 false', ok, false)
  eq('失败时列表原样', store.memories[0].content, '原内容')
  eq('错误被设置', store.error, '记忆不存在：m-1')
}
