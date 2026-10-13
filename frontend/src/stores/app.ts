import { defineStore } from 'pinia'
import { ref } from 'vue'

import { fetchHealthStatus } from '@/api/health'
import type { HealthStatus } from '@/types/api'

/**
 * 应用级状态：当前只承载「后端连通性」的展示状态。
 *
 * 采用 setup 风格的 store，便于后续阶段按模块扩展。
 */
export const useAppStore = defineStore('app', () => {
  /** 后端返回的健康状态；未成功获取时为 null */
  const health = ref<HealthStatus | null>(null)
  /** 是否正在请求 */
  const loading = ref(false)
  /** 失败原因（可读文案） */
  const error = ref<string | null>(null)

  /**
   * 拉取后端健康状态。
   *
   * 失败时不抛出，而是写入 error，由页面决定如何呈现——避免白屏。
   */
  async function loadHealth(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      health.value = await fetchHealthStatus()
    } catch (cause) {
      health.value = null
      error.value = cause instanceof Error ? cause.message : '未知错误'
    } finally {
      loading.value = false
    }
  }

  return { health, loading, error, loadHealth }
})
