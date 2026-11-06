/** 自然日边界按浏览器本地时区计算，次日用日历构造而非固定 24 小时。 */
export function localDayWindow(now = new Date()): { from: string; before: string; calendarTo: string; key: string } {
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1)
  return {
    from: start.toISOString(),
    before: end.toISOString(),
    calendarTo: new Date(end.getTime() - 1).toISOString(),
    key: start.toISOString(),
  }
}
export const RUN_STATUS: Record<string, string> = {
  RUNNING: '执行中',
  WAITING_CONFIRMATION: '待确认',
  WAITING_USER: '等待回答',
  INTERRUPTED: '执行中断',
  STOPPED: '已暂停',
}
export const TASK_RECURRENCES = [
  { value: 'NONE', label: '不重复' },
  { value: 'DAILY', label: '每天' },
  { value: 'WEEKDAYS', label: '工作日（周一至周五）' },
  { value: 'WEEKLY', label: '每周' },
  { value: 'MONTHLY', label: '每月' },
] as const
export function recurrenceLabel(value?: string): string {
  return TASK_RECURRENCES.find((r) => r.value === value)?.label ?? '不重复'
}
