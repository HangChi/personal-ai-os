import { defineStore } from 'pinia'
import { ref } from 'vue'

import { globalSearch } from '@/api/search'
import type { SearchGroupItem } from '@/api/search'

/**
 * 全局搜索状态（阶段 17）。
 *
 * 与各模块自己的搜索分开：这里的职责是「定位到哪个模块」，
 * 不是替代模块内的筛选 —— 所以每模块只取前几条，翻页请去对应页面。
 */
export const useSearchStore = defineStore('search', () => {
  const keyword = ref('')
  const groups = ref<SearchGroupItem[]>([])
  const totalHits = ref(0)
  const loading = ref(false)
  const error = ref<string | null>(null)

  /** 最近一次是否真的发过请求（用于区分「还没搜」与「搜了没结果」） */
  const searched = ref(false)

  async function search(term: string): Promise<void> {
    keyword.value = term
    const trimmed = term.trim()
    if (!trimmed) {
      groups.value = []
      totalHits.value = 0
      searched.value = false
      return
    }

    loading.value = true
    error.value = null
    try {
      const result = await globalSearch(trimmed)
      groups.value = result.groups
      totalHits.value = result.totalHits
      searched.value = true
    } catch (cause) {
      groups.value = []
      totalHits.value = 0
      searched.value = true
      error.value = cause instanceof Error ? cause.message : '搜索失败'
    } finally {
      loading.value = false
    }
  }

  return { keyword, groups, totalHits, loading, error, searched, search }
})
