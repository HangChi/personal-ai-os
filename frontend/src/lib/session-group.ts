/**
 * 会话按日分组（阶段 18，User Story 4 / contracts C-04）。
 *
 * 分组依据是**本地时区**的日期（东八区下 UTC 日期会把凌晨的会话算到前一天，
 * 这是项目里踩过的坑，见 src/lib/calendar-dates.ts 的 localDayKey）。
 */

export type SessionGroupKey = 'today' | 'yesterday' | 'earlier'

export interface GroupedSessions<T> {
  key: SessionGroupKey
  label: string
  items: T[]
}

const LABELS: Record<SessionGroupKey, string> = {
  today: '今天',
  yesterday: '昨天',
  earlier: '更早',
}

function dayDiff(from: Date, to: Date): number {
  const a = new Date(from.getFullYear(), from.getMonth(), from.getDate()).getTime()
  const b = new Date(to.getFullYear(), to.getMonth(), to.getDate()).getTime()
  return Math.round((a - b) / 86_400_000)
}

/**
 * 把会话按「今天 / 昨天 / 更早」分组。
 *
 * @param items 会话列表（保持接口返回顺序，分组**不重排**——后端已按更新时间倒序）
 * @param now 当前时间（测试注入固定值）
 * @param timeOf 取会话时间的函数（默认读 updatedAt）
 */
export function groupSessionsByDay<T>(
  items: T[],
  now: Date = new Date(),
  timeOf: (item: T) => string = (item) => (item as { updatedAt: string }).updatedAt,
): GroupedSessions<T>[] {
  const groups = new Map<SessionGroupKey, T[]>()

  for (const item of items) {
    const time = new Date(timeOf(item)).getTime()
    let key: SessionGroupKey
    if (Number.isNaN(time)) {
      // 时间不可解析的会话不猜分组，归入更早（而不是今天——猜错比不猜更糟）
      key = 'earlier'
    } else {
      const diff = dayDiff(now, new Date(time))
      key = diff === 0 ? 'today' : diff === 1 ? 'yesterday' : 'earlier'
    }
    const bucket = groups.get(key)
    if (bucket) {
      bucket.push(item)
    } else {
      groups.set(key, [item])
    }
  }

  // 固定顺序输出；空组不出现（C-04）
  const order: SessionGroupKey[] = ['today', 'yesterday', 'earlier']
  return order
    .filter((key) => (groups.get(key)?.length ?? 0) > 0)
    .map((key) => ({ key, label: LABELS[key], items: groups.get(key) as T[] }))
}
