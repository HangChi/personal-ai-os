/**
 * 任务看板分组（阶段 18，User Story 6 / contracts C-05）。
 *
 * 纯函数：输入任务数组，输出三列。列内顺序保持输入顺序——
 * 排序是表格的事（默认按截止升序），看板不重排，避免两处排序规则不一致。
 */
import type { TaskItem, TaskStatus } from '@/types/api'

export interface BoardColumn {
  key: TaskStatus
  label: string
  items: TaskItem[]
}

const COLUMN_LABELS: Record<TaskStatus, string> = {
  TODO: '待办',
  DOING: '进行中',
  DONE: '已完成',
}

const COLUMN_ORDER: TaskStatus[] = ['TODO', 'DOING', 'DONE']

export function groupTasksByStatus(tasks: TaskItem[]): BoardColumn[] {
  const buckets = new Map<TaskStatus, TaskItem[]>()
  for (const task of tasks) {
    const bucket = buckets.get(task.status)
    if (bucket) {
      bucket.push(task)
    } else {
      buckets.set(task.status, [task])
    }
  }
  // 空列也保留（看板的空列要有空态，而不是消失——否则三列变两列，布局跳动）
  return COLUMN_ORDER.map((key) => ({
    key,
    label: COLUMN_LABELS[key],
    items: buckets.get(key) ?? [],
  }))
}

/**
 * 表格默认排序：截止时间升序，无截止的排最后。
 *
 * 同为零值的相对次序保持稳定（不引入二级排序键）——排序只是默认视图，
 * 用户没有显式排序时不应该对「同样没有截止时间」的任务指手画脚。
 */
export function sortByDueAsc(tasks: TaskItem[]): TaskItem[] {
  return [...tasks].sort((a, b) => {
    if (a.dueAt === b.dueAt) return 0
    if (!a.dueAt) return 1
    if (!b.dueAt) return -1
    return new Date(a.dueAt).getTime() - new Date(b.dueAt).getTime()
  })
}
