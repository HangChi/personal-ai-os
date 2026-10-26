import { eq, check } from './assert.mjs'

/**
 * 日历日期计算（阶段 13）。
 *
 * 这里的断言刻意用「本地时间构造函数 → toISOString() → 再算回本地」的闭环，
 * 而不是写死 `'2026-10-26T07:00:00Z'`：写死 UTC 串会让结果随运行机器的时区变化，
 * 测试在东八区过、在 UTC 环境挂（或反过来）。
 */

const { localDayKey, buildMonthCells, monthRange, shiftMonth, groupByLocalDay } = await import('@/lib/calendar-dates')

function pad(value) {
  return String(value).padStart(2, '0')
}

// ---- localDayKey：必须按本地时区，不能截取 UTC ----

{
  const dawn = new Date(2026, 9, 26, 0, 30, 0) // 本地 10-26 00:30
  eq('凌晨时刻仍属当天（UTC 截取会算成前一天）', localDayKey(dawn.toISOString()), '2026-10-26')
}

{
  const lateNight = new Date(2026, 9, 26, 23, 30, 0)
  eq('深夜时刻属于当天', localDayKey(lateNight.toISOString()), '2026-10-26')
}

{
  // 与「本地字段拼出来的字符串」逐字比对：这是唯一与实现无关的口径
  const moment = new Date(2026, 0, 5, 8, 15, 0)
  const expected = `${moment.getFullYear()}-${pad(moment.getMonth() + 1)}-${pad(moment.getDate())}`
  eq('与本地日期字段一致', localDayKey(moment.toISOString()), expected)
}

{
  eq('无法解析时退化为截取前 10 位', localDayKey('not-a-date'), 'not-a-date')
}

// ---- buildMonthCells：固定 42 格、周一开头 ----

{
  const cursor = new Date(2026, 9, 15) // 2026-10
  const today = new Date(2026, 9, 26)
  const cells = buildMonthCells(cursor, today)

  eq('固定 42 格', cells.length, 42)
  eq('第一格是周一', cells[0].date.getDay(), 1)
  eq('最后一格是周日', cells[41].date.getDay(), 0)

  const offset = (new Date(2026, 9, 1).getDay() + 6) % 7
  eq('1 号落在正确格子', cells[offset].key, '2026-10-01')
  check('1 号属于本月', cells[offset].inMonth)
  check('1 号之前的补位不属于本月', offset > 0 ? !cells[0].inMonth : true)

  const todayCell = cells.find((cell) => cell.isToday)
  check('今天被标记', Boolean(todayCell))
  eq('今天标记正确', todayCell?.key, '2026-10-26')
}

{
  // 2 月（闰年）也要 42 格，且 29 号在列
  const cells = buildMonthCells(new Date(2028, 1, 1), new Date(2028, 1, 15))
  eq('闰年 2 月仍是 42 格', cells.length, 42)
  check(
    '闰年有 29 号',
    cells.some((cell) => cell.inMonth && cell.day === 29),
  )
}

{
  // 1 号恰好是周一时不应有任何前置补位
  const first = new Date(2026, 5, 1) // 2026-06-01
  if (first.getDay() === 1) {
    const cells = buildMonthCells(first, first)
    eq('1 号是周一时首发格就是 1 号', cells[0].key, '2026-06-01')
    check('首发格属于本月', cells[0].inMonth)
  }
}

// ---- monthRange：本月 1 日 00:00 ~ 次月 1 日 00:00 ----

{
  const { from, to } = monthRange(new Date(2026, 9, 15))
  eq('from 是本月 1 日 00:00', new Date(from).getTime(), new Date(2026, 9, 1).getTime())
  eq('to 是次月 1 日 00:00', new Date(to).getTime(), new Date(2026, 10, 1).getTime())
}

{
  // 12 月的上界要跨年
  const { to } = monthRange(new Date(2026, 11, 31))
  eq('12 月的上界是次年 1 月 1 日', new Date(to).getTime(), new Date(2027, 0, 1).getTime())
}

// ---- shiftMonth：处理年边界与月末 ----

{
  const next = shiftMonth(new Date(2026, 11, 20), 1)
  eq('12 月 +1 落到 1 月', next.getMonth(), 0)
  eq('12 月 +1 年份进位', next.getFullYear(), 2027)
}

{
  const prev = shiftMonth(new Date(2026, 2, 31), -1)
  eq('3 月 31 日 -1 落到 2 月 1 日', prev.getMonth(), 1)
  eq('月末不溢出到下下月', prev.getDate(), 1)
}

// ---- groupByLocalDay ----

{
  const groups = groupByLocalDay([
    { id: 'a', startsAt: new Date(2026, 9, 26, 7, 0).toISOString() },
    { id: 'b', startsAt: new Date(2026, 9, 26, 9, 0).toISOString() },
    { id: 'c', startsAt: new Date(2026, 9, 27, 0, 30).toISOString() },
  ])

  eq('同一天归入同组', groups.get('2026-10-26').length, 2)
  eq('次日凌晨归次日', groups.get('2026-10-27').length, 1)
  eq('保持传入顺序', groups.get('2026-10-26')[0].id, 'a')
}
