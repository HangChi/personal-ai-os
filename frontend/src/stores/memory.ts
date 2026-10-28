import { defineStore } from 'pinia'
import { ref } from 'vue'

import { createMemory, listMemories, pinMemory, removeMemory, updateMemory } from '@/api/memory'
import type { Memory } from '@/types/api'

/**
 * 长期记忆状态。
 *
 * 刷新策略按「集合是否会变」区分：
 * - 新增 → **重拉**（后端可能因去重返回既有条目，只有列表能反映真实结果）
 * - 编辑 → 就地替换（不改变集合与顺序，重拉只会闪一下）
 * - 删除 → 本地移除（失败时保留，不做乐观删除）
 */
export const useMemoryStore = defineStore('memory', () => {
  const memories = ref<Memory[]>([])
  const loading = ref(false)
  const saving = ref(false)
  const error = ref<string | null>(null)

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      memories.value = await listMemories()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载长期记忆失败'
    } finally {
      loading.value = false
    }
  }

  /** @returns 是否成功；失败时 error 已被设置 */
  async function create(content: string): Promise<boolean> {
    saving.value = true
    error.value = null
    try {
      await createMemory(content)
      await load()
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '保存记忆失败'
      return false
    } finally {
      saving.value = false
    }
  }

  /** @returns 是否成功 */
  async function update(id: string, content: string): Promise<boolean> {
    saving.value = true
    error.value = null
    try {
      const updated = await updateMemory(id, content)
      memories.value = memories.value.map((item) => (item.id === id ? updated : item))
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '修改记忆失败'
      return false
    } finally {
      saving.value = false
    }
  }

  /**
   * 置顶 / 取消置顶。
   *
   * 走**重拉**而不是就地替换：置顶会改变列表排序（常驻优先），
   * 就地替换会让页面顺序与后端不一致，而顺序本身是有语义的信息。
   *
   * @returns 是否成功
   */
  async function setPinned(id: string, pinned: boolean): Promise<boolean> {
    error.value = null
    try {
      await pinMemory(id, pinned)
      await load()
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '修改常驻状态失败'
      return false
    }
  }

  /** @returns 是否成功；成功才从列表移除 */
  async function remove(id: string): Promise<boolean> {
    error.value = null
    try {
      await removeMemory(id)
      memories.value = memories.value.filter((item) => item.id !== id)
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '删除记忆失败'
      return false
    }
  }

  return { memories, loading, saving, error, load, create, update, remove, setPinned }
})
