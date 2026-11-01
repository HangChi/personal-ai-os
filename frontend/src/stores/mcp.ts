import { defineStore } from 'pinia'
import { ref } from 'vue'
import { fetchMcpStatus, fetchPersonalMcpStatus, reconnectMcpServer } from '@/api/mcp'
import type { McpStatus, PersonalMcpStatus } from '@/types/api'

export const useMcpStore = defineStore('mcp', () => {
  const mcp = ref<McpStatus | null>(null)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const personalMcp = ref<PersonalMcpStatus | null>(null)
  const personalLoading = ref(false)
  const personalError = ref<string | null>(null)

  async function loadMcp(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      mcp.value = await fetchMcpStatus()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载连接失败'
    } finally {
      loading.value = false
    }
  }

  async function loadPersonalMcp(): Promise<void> {
    personalLoading.value = true
    personalError.value = null
    try {
      personalMcp.value = await fetchPersonalMcpStatus()
    } catch (cause) {
      personalError.value = cause instanceof Error ? cause.message : '加载本系统服务失败'
    } finally {
      personalLoading.value = false
    }
  }

  async function refresh(): Promise<void> {
    await Promise.all([loadMcp(), loadPersonalMcp()])
  }

  async function reconnectServer(name: string): Promise<void> {
    await reconnectMcpServer(name)
    await loadMcp()
    if (error.value) throw new Error(error.value)
    const server = mcp.value?.servers.find((item) => item.name === name)
    if (!server?.available) throw new Error(server?.failureReason || `${name} 尚未连接`)
  }

  return {
    mcp,
    loading,
    error,
    personalMcp,
    personalLoading,
    personalError,
    loadMcp,
    loadPersonalMcp,
    refresh,
    reconnectServer,
  }
})
