import type { CalendarOccurrence, RecurrenceType } from '@/types/api'

/**
 * 日程展示格式化（2026-10-26 从 CalendarView 提出）。
 *
 * <p>月视图与列表视图各有一套时间显示规则（格子里只有时分、列表里是区间），
 * 但「非法时间怎么显示」必须一致 —— 放在 lib 里才不会各写一份。
 */

/** 重复规则的可选项（新建与编辑两个表单共用） */
export const RECURRENCE_OPTIONS: Array<{ label: string; value: RecurrenceType }> = [
  { label: '不重复', value: 'NONE' },
  { label: '每天', value: 'DAILY' },
  { label: '每周', value: 'WEEKLY' },
  { label: '每月', value: 'MONTHLY' },
  { label: '工作日', value: 'WEEKDAYS' },
]

/** 时分显示（HH:mm）；非法时间返回破折号 */
export function formatTime(value: string): string {
  const date = new Date(value)
  return Number.isNaN(date.getTime())
    ? '—'
    : date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

/** 月视图格子里的时间：全天显示「全天」，否则只显示时分 */
export function shortTime(value: string): string {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    // 兜底：ISO 字符串第 11~16 位恰好是 HH:mm，比显示破折号更有信息量
    return value.slice(11, 16)
  }
  return `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

/** 时间区间：全天事件不显示具体时刻 */
export function formatRange(start: string, end: string, isAllDay: boolean): string {
  if (isAllDay) {
    return '全天'
  }
  return `${formatTime(start)} ~ ${formatTime(end)}`
}

/** 「2026年10月26日 周一」 */
export function formatDayLabel(day: string): string {
  const date = new Date(`${day}T00:00:00`)
  if (Number.isNaN(date.getTime())) {
    return day
  }
  const weekday = ['周日', '周一', '周二', '周三', '周四', '周五', '周六'][date.getDay()]
  return `${date.toLocaleDateString('zh-CN')} ${weekday}`
}

/**
 * 是否跨天。
 *
 * <p>月视图把跨天日程显示在起始日并标注「跨天」—— 不标的话很容易以为
 * 它只到当天结束，从而漏看后半段。
 */
export function isCrossDay(item: CalendarOccurrence): boolean {
  return new Date(item.startsAt).toDateString() !== new Date(item.endsAt).toDateString()
}

/** 提醒来源的中文名 */
export function sourceLabel(sourceType: string): string {
  return sourceType === 'TASK' ? '任务' : '日程'
}
