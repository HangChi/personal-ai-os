import { eq } from './assert.mjs'

/**
 * 相对时间（阶段 18，User Story 4 / tasks T009 边界口径）。
 *
 * 边界刻意卡在 spec 写死的五个区间分界：59s/60s、59min/60min、23h/24h、6d/7d。
 * now 由测试注入固定值，不依赖真实时钟。
 */

const { relativeTime } = await import('@/lib/relative-time')

const now = new Date(2026, 9, 26, 12, 0, 0) // 2026-10-26 12:00:00 本地

function at(offsetMs) {
  return new Date(now.getTime() - offsetMs).toISOString()
}

eq('0 秒 → 刚刚', relativeTime(at(0), now), '刚刚')
eq('59 秒 → 刚刚', relativeTime(at(59_000), now), '刚刚')
eq('60 秒 → 1 分钟前', relativeTime(at(60_000), now), '1 分钟前')
eq('59 分 59 秒 → 59 分钟前', relativeTime(at(59 * 60_000 + 59_000), now), '59 分钟前')
eq('60 分 → 1 小时前', relativeTime(at(60 * 60_000), now), '1 小时前')
eq('23 小时 → 23 小时前', relativeTime(at(23 * 3_600_000), now), '23 小时前')
eq('24 小时 → 1 天前', relativeTime(at(24 * 3_600_000), now), '1 天前')
eq('6 天 → 6 天前', relativeTime(at(6 * 86_400_000), now), '6 天前')
eq('7 天 → MM-DD 短日期', relativeTime(at(7 * 86_400_000), now), '10-19')
eq('30 天 → MM-DD 短日期', relativeTime(at(30 * 86_400_000), now), '09-26')
eq('未来 30 秒容忍为刚刚', relativeTime(new Date(now.getTime() + 30_000).toISOString(), now), '刚刚')
eq('未来超过容忍窗口 → MM-DD', relativeTime(new Date(now.getTime() + 3_600_000).toISOString(), now), '10-26')
eq('非法时间串原样返回', relativeTime('not-a-date', now), 'not-a-date')
