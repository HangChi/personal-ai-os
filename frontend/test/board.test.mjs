import { eq, check } from './assert.mjs'

/**
 * 看板分组与默认排序（阶段 18，contracts C-05 / tasks T021）。
 */

const { groupTasksByStatus, sortByDueAsc } = await import('@/lib/board')

function task(id, status, dueAt) {
  return { id, status, dueAt: dueAt ?? null, title: `任务${id}` }
}

// ---- 分组：三列齐全，空列保留 ----

{
  const items = [task('1', 'TODO'), task('2', 'DONE'), task('3', 'DOING')]
  const columns = groupTasksByStatus(items)
  eq('三列齐全（空列也保留）', columns.length, 3)
  eq('列顺序 TODO/DOING/DONE', columns.map((c) => c.key).join(','), 'TODO,DOING,DONE')
  eq('列标题中文', columns.map((c) => c.label).join(','), '待办,进行中,已完成')
  eq('TODO 1 条', columns[0].items.length, 1)
  eq('DOING 1 条', columns[1].items.length, 1)
  eq('DONE 1 条', columns[2].items.length, 1)
}

{
  const columns = groupTasksByStatus([task('1', 'TODO')])
  eq('空列 items 为空数组', columns[1].items.length, 0)
  check('空列 items 是数组不是 undefined', Array.isArray(columns[1].items))
}

// ---- 列内保持输入顺序（看重排）----

{
  const items = [task('a', 'TODO'), task('b', 'TODO'), task('c', 'TODO')]
  const columns = groupTasksByStatus(items)
  eq('列内保持输入顺序', columns[0].items.map((t) => t.id).join(','), 'a,b,c')
}

// ---- 未知状态归入其列（后端枚举封闭，防御只保证不崩）----

{
  const columns = groupTasksByStatus([task('x', 'WEIRD')])
  eq(
    '未知状态不进任何列',
    columns.reduce((n, c) => n + c.items.length, 0),
    0,
  )
}

// ---- 默认排序：截止升序，无截止排最后 ----

{
  const items = [
    task('3', 'TODO', '2026-10-30T00:00:00Z'),
    task('1', 'TODO', '2026-10-26T00:00:00Z'),
    task('4', 'TODO', null),
    task('2', 'TODO', '2026-10-28T00:00:00Z'),
  ]
  const sorted = sortByDueAsc(items)
  eq('按截止升序', sorted.map((t) => t.id).join(','), '1,2,3,4')
  eq('无截止排最后', sorted[sorted.length - 1].id, '4')
}

{
  const items = [task('a', 'TODO', null), task('b', 'TODO', null)]
  const sorted = sortByDueAsc(items)
  eq('都无截止时保持原序（稳定）', sorted.map((t) => t.id).join(','), 'a,b')
}

{
  const items = [task('1', 'TODO', '2026-10-26T00:00:00Z')]
  const sorted = sortByDueAsc(items)
  check('不修改入参（返回新数组）', sorted !== items)
}
