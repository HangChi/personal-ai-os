import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

import {
  createTask as createTaskApi,
  deleteTask as deleteTaskApi,
  completeTask as completeTaskApi,
  listTags as listTagsApi,
  listTasks,
  reopenTask as reopenTaskApi,
  updateTask as updateTaskApi,
} from '@/api/tasks'
import type { UpdateTaskPayload } from '@/api/tasks'
import type { TaskItem, TaskPriority, TaskStatus } from '@/types/api'

/** 任务列表状态 */
export const useTaskStore = defineStore('task', () => {
  const tasks = ref<TaskItem[]>([])
  /** 符合条件的总条数（分页响应带来，不再有静默截断） */
  const total = ref(0)
  /** 当前页码，从 0 开始 */
  const page = ref(0)
  /** 每页条数 */
  const size = ref(20)
  const loading = ref(false)
  const error = ref<string | null>(null)
  const statusFilter = ref<TaskStatus | ''>('')
  const keyword = ref('')
  /** 标签筛选（空串 = 不限） */
  const tagFilter = ref('')
  /** 已用过的标签（去重有序），供筛选下拉使用 */
  const tags = ref<string[]>([])

  const pendingCount = computed(() => tasks.value.filter((task) => task.status !== 'DONE').length)
  const overdueCount = computed(() => tasks.value.filter((task) => task.overdue).length)

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await listTasks({
        status: statusFilter.value || undefined,
        keyword: keyword.value.trim() || undefined,
        tag: tagFilter.value || undefined,
        page: page.value,
        size: size.value,
      })
      tasks.value = result.items
      total.value = result.total
    } catch (cause) {
      tasks.value = []
      total.value = 0
      error.value = cause instanceof Error ? cause.message : '加载任务失败'
    } finally {
      loading.value = false
    }
  }

  /**
   * 刷新标签清单。
   *
   * 失败时**不置 error**：标签下拉是辅助功能，它挂了不该让整个任务列表显示为「加载失败」。
   * 代价是下拉可能为空，但主功能照常可用。
   */
  async function refreshTags(): Promise<void> {
    try {
      tags.value = await listTagsApi()
    } catch {
      tags.value = []
    }
  }

  /** 翻页：任何筛选条件变化时由调用方先把 page 归零，否则会停在越界页 */
  async function goToPage(next: number): Promise<void> {
    page.value = next
    await load()
  }

  /** 筛选条件变化：先归零页码再加载，否则可能停在越界页 */
  async function reload(): Promise<void> {
    page.value = 0
    await load()
  }

  async function create(
    title: string,
    priority: TaskPriority,
    dueAt?: string,
    tagsValue?: string[],
  ): Promise<void> {
    error.value = null
    try {
      await createTaskApi({ title, priority, dueAt, tags: tagsValue })
      await load()
      await refreshTags()
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
      // 标签可能被整体替换或清空，下拉清单要跟着变
      await refreshTags()
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
      // 删掉的是某标签的最后一个使用者时，它应从筛选下拉里消失
      await refreshTags()
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '删除任务失败'
    }
  }

  return {
    tasks,
    total,
    page,
    size,
    loading,
    error,
    statusFilter,
    keyword,
    tagFilter,
    tags,
    pendingCount,
    overdueCount,
    load,
    reload,
    goToPage,
    refreshTags,
    create,
    edit,
    toggleCompleted,
    remove,
  }
})
