import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  createTask as createTaskApi,
  deleteTask as deleteTaskApi,
  completeTask as completeTaskApi,
  listTasks,
  reopenTask as reopenTaskApi,
  updateTask as updateTaskApi,
} from '@/api/tasks'
import type { UpdateTaskPayload } from '@/api/tasks'
import type { TaskItem, TaskPriority, TaskStatus } from '@/types/api'

/** 任务列表状态 */
export const useTaskStore = defineStore('task', () => {
  const tasks = ref<TaskItem[]>([])
  const loading = ref(false)
  const error = ref<string | null>(null)
  const statusFilter = ref<TaskStatus | ''>('')
  const keyword = ref('')

  const pendingCount = computed(() => tasks.value.filter((task) => task.status !== 'DONE').length)
  const overdueCount = computed(() => tasks.value.filter((task) => task.overdue).length)

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      tasks.value = await listTasks({
        status: statusFilter.value || undefined,
        keyword: keyword.value.trim() || undefined,
      })
    } catch (cause) {
      tasks.value = []
      error.value = cause instanceof Error ? cause.message : '加载任务失败'
    } finally {
      loading.value = false
    }
  }

  async function create(title: string, priority: TaskPriority, dueAt?: string): Promise<void> {
    error.value = null
    try {
      await createTaskApi({ title, priority, dueAt })
      await load()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '创建任务失败'
    }
  }

  /**
   * 修改任务（标题 / 描述 / 优先级 / 截止时间 / 状态）。
   *
   * <p>注意：截止时间的「设置」与「清除」是两件事 ——
   * 后端用 `clearDueAt` 显式表达清除意图，因为字段缺失（null）无法与之区分。
   * 若把「未提供」当成「清除」，一个只改标题的请求会顺手清掉截止时间。
   */
  async function edit(id: string, payload: UpdateTaskPayload): Promise<boolean> {
    error.value = null
    try {
      await updateTaskApi(id, payload)
      await load()
      return true
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '修改任务失败'
      return false
    }
  }

  async function toggleCompleted(task: TaskItem): Promise<void> {
    error.value = null
    try {
      if (task.status === 'DONE') {
        await reopenTaskApi(task.id)
      } else {
        await completeTaskApi(task.id)
      }
      await load()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '更新任务状态失败'
    }
  }

  async function remove(id: string): Promise<void> {
    error.value = null
    try {
      await deleteTaskApi(id)
      await load()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '删除任务失败'
    }
  }

  return {
    tasks,
    loading,
    error,
    statusFilter,
    keyword,
    pendingCount,
    overdueCount,
    load,
    create,
    edit,
    toggleCompleted,
    remove,
  }
})
