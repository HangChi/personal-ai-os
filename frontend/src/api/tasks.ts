import { requestData } from '@/api/http'
import type { PageDto, TaskItem, TaskPriority, TaskStatus } from '@/types/api'

/**
 * 任务接口客户端。
 *
 * 所有写操作都带 `Idempotency-Key`：网络重试或用户重复点击时，
 * 服务端只会执行一次，并返回与首次一致的响应。
 */
export interface TaskListFilter {
  status?: TaskStatus
  keyword?: string
  /** 标签精确筛选（阶段 14） */
  tag?: string
  /** 页码，从 0 开始（2026-10-14 起 limit 参数改为 page + size） */
  page?: number
  size?: number
}

export interface CreateTaskPayload {
  title: string
  description?: string
  priority?: TaskPriority
  dueAt?: string
  tags?: string[]
}

export interface UpdateTaskPayload {
  title?: string
  description?: string
  priority?: TaskPriority
  dueAt?: string
  /** 显式请求清除截止时间 */
  clearDueAt?: boolean
  status?: TaskStatus
  /** 标签整体替换；传 `[]` 表示清空，不传表示不改（与 dueAt 同一套约定） */
  tags?: string[]
}

/**
 * 生成幂等键。
 *
 * 键在**同一次用户操作**内保持稳定：若将来引入自动重试，重试必须复用同一个键，
 * 否则重试会被当成两次独立操作。
 */
function idempotencyKey(prefix: string): Record<string, string> {
  return { 'Idempotency-Key': `${prefix}-${crypto.randomUUID()}` }
}

export function listTasks(filter: TaskListFilter = {}): Promise<PageDto<TaskItem>> {
  return requestData<PageDto<TaskItem>>({ url: '/tasks', method: 'get', params: filter })
}

/** 全部标签（去重有序），用于筛选下拉 */
export function listTags(): Promise<string[]> {
  return requestData<string[]>({ url: '/tasks/tags', method: 'get' })
}

export function getTask(id: string): Promise<TaskItem> {
  return requestData<TaskItem>({ url: `/tasks/${id}`, method: 'get' })
}

export function createTask(payload: CreateTaskPayload): Promise<TaskItem> {
  return requestData<TaskItem>({
    url: '/tasks',
    method: 'post',
    data: payload,
    headers: idempotencyKey('create-task'),
  })
}

export function updateTask(id: string, payload: UpdateTaskPayload): Promise<TaskItem> {
  return requestData<TaskItem>({
    url: `/tasks/${id}`,
    method: 'patch',
    data: payload,
    headers: idempotencyKey('update-task'),
  })
}

export function completeTask(id: string): Promise<TaskItem> {
  return requestData<TaskItem>({ url: `/tasks/${id}/complete`, method: 'post' })
}

export function reopenTask(id: string): Promise<TaskItem> {
  return requestData<TaskItem>({ url: `/tasks/${id}/reopen`, method: 'post' })
}

/**
 * 删除任务。
 *
 * 人工在界面上直接删除**不需要确认** —— 发起者就是人本身。
 * 只有 Agent 发起的删除才走确认流程。
 */
export function deleteTask(id: string): Promise<{ deleted: boolean }> {
  return requestData<{ deleted: boolean }>({
    url: `/tasks/${id}`,
    method: 'delete',
    headers: idempotencyKey('delete-task'),
  })
}
