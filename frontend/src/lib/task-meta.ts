import type { TaskItem, TaskPriority } from '@/types/api'

/**
 * 任务展示用的元数据（阶段 18 改造时从 TasksView 提出，2026-10-26 再抽到 lib）。
 *
 * <p>表格与看板两个子组件都要用同一套徽标映射：放在各自文件里会出现
 * 「改了一处忘了另一处」—— 这正是看板和表格文案不一致的典型成因。
 */

/** 状态徽标（StatusTag 的 map） */
export const STATUS_MAP: Record<string, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  TODO: { label: '待办', type: 'info' },
  DOING: { label: '进行中', type: 'warning' },
  DONE: { label: '已完成', type: 'success' },
}

/** 优先级徽标 */
export const PRIORITY_MAP: Record<string, { label: string; type: 'success' | 'info' | 'warning' | 'danger' }> = {
  LOW: { label: '低', type: 'info' },
  MEDIUM: { label: '中', type: 'warning' },
  HIGH: { label: '高', type: 'danger' },
}

export const PRIORITY_OPTIONS: Array<{ label: string; value: TaskPriority }> = [
  { label: '低', value: 'LOW' },
  { label: '中', value: 'MEDIUM' },
  { label: '高', value: 'HIGH' },
]

/**
 * 截止时间展示：空值显示破折号，非法时间也显示破折号。
 *
 * <p>实现已收敛到 {@code lib/format}（知识库页的「上传时间」用的是同一套规则），
 * 这里保留别名只是为了任务模块内的调用点读起来更直白。
 */
export { formatDateTime as formatTaskDateTime } from '@/lib/format'

/** 已完成任务的标题样式（表格与看板共用同一个标记类名语义） */
export function isDone(task: TaskItem): boolean {
  return task.status === 'DONE'
}
