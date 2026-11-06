import type { TaskItem } from '@/types/api'

/** 一级子任务先处理：批量完成/删除时不让父任务抢在所选子任务之前。 */
export function childFirstBatches(ids: string[], tasks: TaskItem[]): string[][] {
  const childIds = new Set(tasks.filter((task) => task.parentTaskId).map((task) => task.id))
  return [ids.filter((id) => childIds.has(id)), ids.filter((id) => !childIds.has(id))].filter(
    (batch) => batch.length > 0,
  )
}
