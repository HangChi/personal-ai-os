import { check, eq } from './assert.mjs'
import { localDayWindow, recurrenceLabel, RUN_STATUS } from '../src/lib/today.ts'
const old = process.env.TZ
try {
  process.env.TZ = 'Asia/Shanghai'
  const today = localDayWindow(new Date('2026-11-06T06:00:00Z'))
  eq('上海自然日开始', today.from, '2026-11-05T16:00:00.000Z')
  eq('上海次日排他边界', today.before, '2026-11-06T16:00:00.000Z')
  eq('日程包含最后毫秒', today.calendarTo, '2026-11-06T15:59:59.999Z')
  process.env.TZ = 'America/New_York'
  const spring = localDayWindow(new Date('2026-03-08T16:00:00Z'))
  eq('夏令时开始自然日为23小时', Date.parse(spring.before) - Date.parse(spring.from), 23 * 3600_000)
  const autumn = localDayWindow(new Date('2026-11-01T16:00:00Z'))
  eq('夏令时结束自然日为25小时', Date.parse(autumn.before) - Date.parse(autumn.from), 25 * 3600_000)
  check('次日窗口精确衔接', localDayWindow(new Date(spring.before)).from === spring.before)
  eq('周期中文标签', recurrenceLabel('WEEKDAYS'), '工作日（周一至周五）')
  eq('旧任务没有周期字段', recurrenceLabel(undefined), '不重复')
  eq('暂停执行可读标签', RUN_STATUS.STOPPED, '已暂停')
} finally {
  if (old === undefined) delete process.env.TZ
  else process.env.TZ = old
}
