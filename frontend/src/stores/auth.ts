import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import { fetchCurrentUser, login as loginApi, logout as logoutApi } from '@/api/auth'
import type { CurrentUser } from '@/api/auth'

/**
 * 认证状态（阶段 15）。
 *
 * `ready` 是「首次探测是否完成」而不是「是否已登录」：路由守卫要等它，
 * 否则刷新页面时会有一瞬间「未登录」→ 跳登录页 → 探测完成 → 又跳回来，
 * 表现为登录后刷新被踢出。
 */
export const useAuthStore = defineStore('auth', () => {
  const currentUser = ref<CurrentUser | null>(null)
  const ready = ref(false)
  const loading = ref(false)
  const error = ref<string | null>(null)

  const authEnabled = computed(() => currentUser.value?.authEnabled ?? false)
  const isAuthenticated = computed(() => Boolean(currentUser.value?.username))
  const username = computed(() => currentUser.value?.username ?? '')

  /**
   * 探测当前会话。应用启动与路由守卫各调一次（靠 ready 去重）。
   *
   * 探测失败（后端不可达）时按「认证未启用」处理并放行：
   * 让用户看到「连不上后端」的真实错误，而不是被莫名其妙地送到登录页。
   */
  async function bootstrap(): Promise<void> {
    if (ready.value) {
      return
    }
    try {
      currentUser.value = await fetchCurrentUser()
    } catch {
      currentUser.value = null
    } finally {
      ready.value = true
    }
  }

  /** @returns 是否登录成功；失败时 error 已设置 */
  async function login(user: string, password: string): Promise<boolean> {
    loading.value = true
    error.value = null
    try {
      currentUser.value = await loginApi(user, password)
      return Boolean(currentUser.value.username)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '登录失败'
      return false
    } finally {
      loading.value = false
    }
  }

  async function logout(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      await logoutApi()
    } catch {
      // 登出失败也要把本地状态清掉：留着一个「看起来已登录」的界面比登出报错更糟，
      // 服务端的会话会随超时失效
    } finally {
      currentUser.value = currentUser.value
        ? { ...currentUser.value, username: null }
        : { username: null, authEnabled: true }
      loading.value = false
    }
  }

  /** 收到 401 时清空本地登录态（由 http 拦截器经 main.ts 调用） */
  function clear(): void {
    currentUser.value = currentUser.value
      ? { ...currentUser.value, username: null }
      : { username: null, authEnabled: true }
    ready.value = true
  }

  return {
    currentUser,
    ready,
    loading,
    error,
    authEnabled,
    isAuthenticated,
    username,
    bootstrap,
    login,
    logout,
    clear,
  }
})
