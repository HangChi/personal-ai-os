import { defineStore } from 'pinia'
import { ref } from 'vue'

import { fetchUsageStats } from '@/api/usage'
import { buildSeries } from '@/lib/usage-chart'
import type { DailyUsage, UsageStats } from '@/types/usage'

/**
 * 用量统计状态（清单第 7 项）。
 *
 * <h2>为什么序列口径（series）放在 store 而不是视图的 computed</h2>
 * 补零依赖「今天」这个会随时间变化的量，而 computed 只会在依赖变化时重算 ——
 * 跨过午夜后页面不刷新，「今天」那一格会停在昨天。放在 store 里，
 * 每次 {@code load()} 都按当时的日期重建，语义更清楚：**一次请求对应一份序列**。
 */
export const useUsageStore = defineStore('usage', () => {
  const stats = ref<UsageStats | null>(null)
  /** 已补零的连续序列，长度等于 stats.days */
  const series = ref<DailyUsage[]>([])
  const days = ref(30)
  const loading = ref(false)
  const error = ref<string | null>(null)

  async function load(targetDays: number = days.value): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await fetchUsageStats(targetDays)
      stats.value = result
      // 后端可能收敛了天数（如传了 9999），以响应里的值为准再补零
      days.value = result.days
      series.value = buildSeries(result.daily, result.days)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '加载用量统计失败'
      // 失败时清空旧数据：留着上一次的趋势图会让用户以为「这就是当前的用量」
      stats.value = null
      series.value = []
    } finally {
      loading.value = false
    }
  }

  /**
   * 切换时间范围。
   *
   * <p>与任务页筛选不同，这里**没有页码概念**，不存在「切范围后页码越界」的问题；
   * 直接重新请求即可。
   */
  async function setDays(value: number): Promise<void> {
    days.value = value
    await load(value)
  }

  return { stats, series, days, loading, error, load, setDays }
})
