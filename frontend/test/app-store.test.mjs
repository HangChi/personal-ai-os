import { createPinia, setActivePinia } from 'pinia'

import { check, eq } from './assert.mjs'
import { handlers } from './stub-api.mjs'

/**
 * 应用 store：首页后端连通性展示。
 *
 * 覆盖 2026-10-14 健康检查改造后的 components 透传与失败路径
 * （数据库挂了用户要看到 DOWN，而不是一片绿）。
 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useAppStore } = await import('@/stores/app')

// ---- 成功：状态与依赖分量都拿到 ----

{
  handlers.set('/health', () => Promise.resolve({
    status: 'UP',
    application: 'personal-ai-os',
    version: '0.1.0-SNAPSHOT',
    timestamp: '2026-10-14T00:00:00Z',
    components: { database: 'UP', ai: 'CONFIGURED' },
  }))
  freshStore()
  const store = useAppStore()
  await store.loadHealth()

  eq('status 透传', store.health?.status, 'UP')
  eq('database 分量透传', store.health?.components?.database, 'UP')
  eq('ai 分量透传', store.health?.components?.ai, 'CONFIGURED')
  eq('成功后 error 为 null', store.error, null)
}

// ---- 失败：不白屏，error 可读 ----

{
  handlers.set('/health', () => Promise.reject(new Error('无法连接后端服务：connect ECONNREFUSED')))
  freshStore()
  const store = useAppStore()
  await store.loadHealth()

  eq('失败时 health 置空', store.health, null)
  check('失败时 error 可读（不静默）', store.error?.includes('无法连接后端服务') === true)
  eq('失败后 loading 复位', store.loading, false)
}
