import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  createTask as createTaskApi,
  deleteTask as deleteTaskApi,
  completeTask as completeTaskApi,
  listTasks,
  reopenTask as reopenTaskApi,
} from '@/api/tasks'
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
    toggleCompleted,
    remove,
  }
})
