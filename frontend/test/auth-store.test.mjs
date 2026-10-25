import { createPinia, setActivePinia } from 'pinia'

import { eq } from './assert.mjs'
import { businessError, handlers } from './stub-api.mjs'

/**
 * 认证 store（阶段 15）。
 *
 * 两个容易做错的地方：
 * 1. `ready` 是「探测完成」而不是「已登录」—— 否则刷新页面时路由守卫会在探测完成前
 *    判成未登录，把已登录的用户踢到登录页；
 * 2. 探测失败按「认证关闭」放行，让用户看到真实的「连不上后端」，而不是莫名的登录页。
 */

function freshStore() {
  setActivePinia(createPinia())
}

const { useAuthStore } = await import('@/stores/auth')

// ---- 探测：已登录 ----

{
  handlers.set('/auth/me', () => Promise.resolve({ username: 'admin', authEnabled: true }))

  freshStore()
  const store = useAuthStore()
  eq('探测前 ready=false', store.ready, false)
  await store.bootstrap()

  eq('ready 置位', store.ready, true)
  eq('用户名填充', store.username, 'admin')
  eq('认证已启用', store.authEnabled, true)
  eq('判定为已登录', store.isAuthenticated, true)
}

// ---- 探测：未登录（仍是 200）----

{
  handlers.set('/auth/me', () => Promise.resolve({ username: null, authEnabled: true }))

  freshStore()
  const store = useAuthStore()
  await store.bootstrap()

  eq('未登录但探测成功', store.ready, true)
  eq('认证仍标记为启用', store.authEnabled, true)
  eq('判定为未登录', store.isAuthenticated, false)
}

// ---- 探测：认证关闭 ----

{
  handlers.set('/auth/me', () => Promise.resolve({ username: null, authEnabled: false }))

  freshStore()
  const store = useAuthStore()
  await store.bootstrap()

  eq('认证关闭', store.authEnabled, false)
  eq('关闭时不判定为已登录', store.isAuthenticated, false)
}

// ---- 探测：后端不可达 → 放行（视作关闭）----

{
  handlers.set('/auth/me', () => Promise.reject(new Error('无法连接后端服务')))

  freshStore()
  const store = useAuthStore()
  await store.bootstrap()

  eq('探测失败也置 ready（避免守卫卡住）', store.ready, true)
  eq('探测失败不跳登录（按关闭处理）', store.authEnabled, false)
}

// ---- 探测幂等 ----

{
  let calls = 0
  handlers.set('/auth/me', () => {
    calls += 1
    return Promise.resolve({ username: 'admin', authEnabled: true })
  })

  freshStore()
  const store = useAuthStore()
  await store.bootstrap()
  await store.bootstrap()

  eq('ready 之后不再重复探测', calls, 1)
}

// ---- 登录成功 ----

{
  let posted = null
  handlers.set('/auth/login', (config) => {
    posted = config.data
    return Promise.resolve({ username: 'admin', authEnabled: true })
  })

  freshStore()
  const store = useAuthStore()
  const ok = await store.login('admin', 'secret')

  eq('登录成功', ok, true)
  eq('请求体带用户名', posted.username, 'admin')
  eq('请求体带密码', posted.password, 'secret')
  eq('登录后为已认证', store.isAuthenticated, true)
  eq('无错误', store.error, null)
}

// ---- 登录失败 ----

{
  handlers.set('/auth/login', () => Promise.reject(businessError(40100, '用户名或密码错误')))

  freshStore()
  const store = useAuthStore()
  const ok = await store.login('admin', 'wrong')

  eq('登录失败返回 false', ok, false)
  eq('错误文案透传', store.error, '用户名或密码错误')
  eq('失败后仍为未登录', store.isAuthenticated, false)
  eq('loading 复位', store.loading, false)
}

// ---- 登出 ----

{
  handlers.set('/auth/me', () => Promise.resolve({ username: 'admin', authEnabled: true }))
  handlers.set('/auth/logout', () => Promise.resolve(null))

  freshStore()
  const store = useAuthStore()
  await store.bootstrap()
  eq('登出前已登录', store.isAuthenticated, true)

  await store.logout()

  eq('登出后未登录', store.isAuthenticated, false)
  eq('登出后仍记得「认证已启用」（否则会直接放行）', store.authEnabled, true)
}

// ---- 401 拦截时清理 ----

{
  handlers.set('/auth/me', () => Promise.resolve({ username: 'admin', authEnabled: true }))

  freshStore()
  const store = useAuthStore()
  await store.bootstrap()

  store.clear()

  eq('clear 后未登录', store.isAuthenticated, false)
  eq('clear 不改变 authEnabled', store.authEnabled, true)
}
