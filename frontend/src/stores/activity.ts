import { defineStore } from 'pinia'
import { ref } from 'vue'

import { listToolInvocations, type ToolInvocationQuery } from '@/api/activity'
import type { ToolInvocation, ToolInvocationOutcome } from '@/types/api'

export const useActivityStore = defineStore('activity', () => {
  const invocations = ref<ToolInvocation[]>([])
  const page = ref(0)
  const size = ref(20)
  const total = ref(0)
  const outcome = ref<ToolInvocationOutcome | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function loadInvocations(targetPage: number = page.value): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const params: ToolInvocationQuery = { page: targetPage, size: size.value }
      if (outcome.value !== null) {
        params.outcome = outcome.value
      }
      const result = await listToolInvocations(params)
      invocations.value = result.items
      page.value = result.page
      size.value = result.size
      total.value = result.total
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载工具调用记录失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 切换结果筛选。
   *
   * 页码**必须**归零：筛掉大部分记录后，原来的页码很可能已经越界，
   * 用户会停在一张空表上，以为「这个结果没有记录」，而实际是翻过头了。
   */
  async function setOutcome(value: ToolInvocationOutcome | null): Promise<void> {
    outcome.value = value
    page.value = 0
    await loadInvocations(0)
  }

  async function setPage(target: number): Promise<void> {
    await loadInvocations(target)
  }

  return {
    invocations,
    page,
    size,
    total,
    outcome,
    loading,
    error,
    loadInvocations,
    setOutcome,
    setPage,
  }
})
