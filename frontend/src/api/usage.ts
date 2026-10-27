import { requestData } from '@/api/http'
import type { UsageStats } from '@/types/usage'

/**
 * 用量统计接口（清单第 7 项）。
 *
 * 只读，无分页：按日聚合的结果在 365 天窗口下最多 365 行，一次取完比
 * 「分页 + 前端再聚合」简单得多。
 */

/**
 * 取按日聚合的用量。
 *
 * @param days 覆盖天数（含今天）；后端会收敛到 1~365，
 *             响应里的 `days` 是实际生效值，界面应以它为准
 */
export function fetchUsageStats(days = 30): Promise<UsageStats> {
  return requestData<UsageStats>({ url: '/usage', method: 'get', params: { days } })
}
