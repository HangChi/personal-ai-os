import { eq, check } from './assert.mjs'

/**
 * 会话按日分组（阶段 18，contracts C-04）。
 *
 * 断言口径：本地时间构造 → 分组；保持输入顺序；空组不渲染；
 * 时间戳不可解析时归 earlier 而不是今天。
 */

const { groupSessionsByDay } = await import('@/lib/session-group')

const now = new Date(2026, 9, 26, 12, 0, 0) // 2026-10-26 12:00 本地

function session(title, updatedAt) {
  return { title, updatedAt }
}

// ---- 基本分组 ----

{
  const items = [
    session('今天早上', new Date(2026, 9, 26, 9, 0, 0).toISOString()),
    session('昨天下午', new Date(2026, 9, 25, 15, 0, 0).toISOString()),
    session('上周', new Date(2026, 9, 18, 10, 0, 0).toISOString()),
  ]
  const groups = groupSessionsByDay(items, now)
  eq('三组都在', groups.map((g) => g.key).join(','), 'today,yesterday,earlier')
  eq('组标题中文', groups.map((g) => g.label).join(','), '今天,昨天,更早')
  eq('今天 1 条', groups[0].items.length, 1)
  eq('昨天 1 条', groups[1].items.length, 1)
  eq('更早 1 条', groups[2].items.length, 1)
}

// ---- 空组不出现 ----

{
  const items = [session('只有今天', new Date(2026, 9, 26, 8, 0, 0).toISOString())]
  const groups = groupSessionsByDay(items, now)
  eq('空组不渲染', groups.length, 1)
  eq('唯一组是今天', groups[0].key, 'today')
}

// ---- 顺序保持（不重排）----

{
  const items = [
    session('A', new Date(2026, 9, 26, 9, 0, 0).toISOString()),
    session('B', new Date(2026, 9, 26, 8, 0, 0).toISOString()),
    session('C', new Date(2026, 9, 26, 7, 0, 0).toISOString()),
  ]
  const groups = groupSessionsByDay(items, now)
  eq('组内保持输入顺序', groups[0].items.map((i) => i.title).join(','), 'A,B,C')
}

// ---- 边界：23:59 前一天的会话不算今天；00:01 的算今天 ----

{
  const items = [
    session('昨晚 23:59', new Date(2026, 9, 25, 23, 59, 0).toISOString()),
    session('今早 00:01', new Date(2026, 9, 26, 0, 1, 0).toISOString()),
  ]
  const groups = groupSessionsByDay(items, now)
  eq('跨午夜分界正确', groups.map((g) => g.key).join(','), 'today,yesterday')
}

// ---- 时间不可解析归 earlier ----

{
  const items = [session('脏数据', 'not-a-date')]
  const groups = groupSessionsByDay(items, now)
  eq('脏时间归更早', groups[0].key, 'earlier')
}

// ---- 自定义时间字段 ----

{
  const items = [{ name: 'x', lastActive: new Date(2026, 9, 26, 6, 0, 0).toISOString() }]
  const groups = groupSessionsByDay(items, now, (item) => item.lastActive)
  eq('自定义 timeOf 生效', groups[0].key, 'today')
}

// ---- 不变量：所有条目都恰好出现一次 ----

check(
  '条目守恒（无丢失无重复）',
  (() => {
    const items = [
      session('a', new Date(2026, 9, 26, 9, 0, 0).toISOString()),
      session('b', new Date(2026, 9, 25, 9, 0, 0).toISOString()),
      session('c', new Date(2026, 8, 25, 9, 0, 0).toISOString()),
    ]
    const groups = groupSessionsByDay(items, now)
    const total = groups.reduce((sum, g) => sum + g.items.length, 0)
    return total === items.length
  })(),
)
