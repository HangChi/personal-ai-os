/**
 * 相对时间纯函数（阶段 18，User Story 4）。
 *
 * 用于会话列表副标题、消息时间等「几分钟前」场景。
 * 不引 dayjs：它是 Element Plus 的传递依赖而非直接依赖，为一个 fromNow()
 * 把它提为 direct dep 违反简单优先（research R-09）。
 *
 * 边界（spec User Story 4 / tasks T009 断言口径）：
 *   < 60s            → 刚刚
 *   < 60min          → N 分钟前
 *   < 24h            → N 小时前
 *   < 7d             → N 天前
 *   更早 / 未来超过一天 → MM-DD（zh-CN 习惯用「昨天」由调用方分组处理，这里只出短日期）
 */

/** 未来 1 分钟内也显示「刚刚」（时钟误差容忍） */
const JUST_NOW_TOLERANCE_MS = 60_000

export function relativeTime(iso: string, now: Date = new Date()): string {
  const then = new Date(iso).getTime()
  if (Number.isNaN(then)) {
    return iso
  }
  const diff = now.getTime() - then

  if (diff < -JUST_NOW_TOLERANCE_MS) {
    // 未来且超过容忍窗口：服务端时间领先或脏数据，按短日期展示
    return shortDate(then)
  }
  if (diff < 60_000) {
    return '刚刚'
  }
  if (diff < 3_600_000) {
    return `${Math.floor(diff / 60_000)} 分钟前`
  }
  if (diff < 86_400_000) {
    return `${Math.floor(diff / 3_600_000)} 小时前`
  }
  if (diff < 7 * 86_400_000) {
    return `${Math.floor(diff / 86_400_000)} 天前`
  }
  return shortDate(then)
}

function shortDate(epochMs: number): string {
  const date = new Date(epochMs)
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${month}-${day}`
}
