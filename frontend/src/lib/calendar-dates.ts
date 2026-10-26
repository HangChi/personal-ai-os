/**
 * 日历日期计算（纯函数）。
 *
 * 单独成模块而不是塞进 store：这些函数只依赖 `Date`，与 Pinia、HTTP 无关，
 * 因此可以在 Node 里直接断言 —— 而「日期算错一格」正是最不容易靠肉眼发现的一类 bug。
 *
 * 所有函数都按**浏览器本地时区**计算。后端返回的 `startsAt` 是 UTC ISO 串，
 * 直接 `slice(0, 10)` 取到的是 UTC 日期：东八区下本地 00:00–07:59 的日程会被算到前一天。
 */

/** 补零到两位 */
function pad(value: number): string {
  return String(value).padStart(2, '0')
}

/** 用本地时区字段拼出 `YYYY-MM-DD` */
function formatDayKey(date: Date): string {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

/**
 * 把 ISO 时间串换算成本地时区的日期键 `YYYY-MM-DD`。
 *
 * 解析失败时退化为截取前 10 位（宁可显示一个可疑的日期，也不要抛异常打断整页渲染）。
 */
export function localDayKey(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso.slice(0, 10) : formatDayKey(date)
}

/** 月网格中的一格 */
export interface MonthCell {
  /** 本地日期键，同时用作渲染 key */
  key: string
  /** 该格对应的日期（本地 00:00） */
  date: Date
  /** 日号 */
  day: number
  /** 是否属于当前月份（否则为跨月补位，灰显） */
  inMonth: boolean
  /** 是否今天 */
  isToday: boolean
}

/**
 * 生成一个月的网格：固定 42 格（7 列 × 6 行），周一为第一列。
 *
 * <p>固定 6 行是刻意的：不同月份需要 4–6 行，行数随月份变化会让整页高度在切月时跳动，
 * 把下方的区块顶来顶去。多出来的整行用灰格填满，代价只是一行空白。
 *
 * @param cursor 该月的任意一天（只用年月）
 * @param today  用于标记「今天」，便于测试注入固定日期
 */
export function buildMonthCells(cursor: Date, today: Date = new Date()): MonthCell[] {
  const firstOfMonth = new Date(cursor.getFullYear(), cursor.getMonth(), 1)
  // getDay(): 周日=0 … 周六=6；换成「周一=0」的偏移
  const offset = (firstOfMonth.getDay() + 6) % 7
  const gridStart = new Date(firstOfMonth)
  gridStart.setDate(firstOfMonth.getDate() - offset)

  const todayKey = formatDayKey(today)
  const cells: MonthCell[] = []
  for (let index = 0; index < 42; index += 1) {
    // 用 Date 的构造器做加法，跨月/跨年由它自己处理，避免手写「这个月几天」
    const date = new Date(gridStart.getFullYear(), gridStart.getMonth(), gridStart.getDate() + index)
    const key = formatDayKey(date)
    cells.push({
      key,
      date,
      day: date.getDate(),
      inMonth: date.getFullYear() === firstOfMonth.getFullYear() && date.getMonth() === firstOfMonth.getMonth(),
      isToday: key === todayKey,
    })
  }
  return cells
}

/**
 * 某月的查询区间：本月 1 日 00:00 ~ 次月 1 日 00:00（本地时区）。
 *
 * 上界用「次月 1 日 00:00」而不是「本月最后一天 23:59:59」：前者是精确的排他边界，
 * 后者会漏掉 23:59:59.500 这类时刻，也会在毫秒精度上留下一个月末尾巴。
 */
export function monthRange(cursor: Date): { from: string; to: string } {
  const from = new Date(cursor.getFullYear(), cursor.getMonth(), 1, 0, 0, 0, 0)
  const to = new Date(cursor.getFullYear(), cursor.getMonth() + 1, 1, 0, 0, 0, 0)
  return { from: from.toISOString(), to: to.toISOString() }
}

/** 月份游标移动；用 1 号构造，避免 31 号 +1 月落到「下下月」 */
export function shiftMonth(cursor: Date, delta: number): Date {
  return new Date(cursor.getFullYear(), cursor.getMonth() + delta, 1)
}

/**
 * 按本地日期分组。
 *
 * @returns key 为 `YYYY-MM-DD`，value 保持传入顺序
 */
export function groupByLocalDay<T extends { startsAt: string }>(items: T[]): Map<string, T[]> {
  const groups = new Map<string, T[]>()
  for (const item of items) {
    const key = localDayKey(item.startsAt)
    const bucket = groups.get(key)
    if (bucket) {
      bucket.push(item)
    } else {
      groups.set(key, [item])
    }
  }
  return groups
}
