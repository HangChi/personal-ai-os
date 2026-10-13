/**
 * 后端统一响应契约的 TypeScript 映射。
 *
 * 与服务端契约保持一一对应：
 * backend 侧见 com.personalos.common.api.ApiResponse / ErrorCode，
 * 文档见 specs/001-project-skeleton/contracts/http-api.md。
 */

/** 成功响应的业务码 */
export const API_SUCCESS_CODE = 0

/** 统一响应信封 */
export interface ApiResponse<T> {
  /** 业务码，0 表示成功 */
  code: number
  /** 提示信息 */
  message: string
  /** 数据体，失败时为 null */
  data: T | null
  /** 生成时间（ISO-8601） */
  timestamp: string
}

/** GET /api/v1/health 的返回数据 */
export interface HealthStatus {
  status: string
  application: string
  version: string
  timestamp: string
}

/** 会话视图，对应后端 ChatSessionDto */
export interface ChatSession {
  conversationId: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

/** 消息角色 */
export type ChatRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

/** 消息视图，对应后端 ChatMessageDto */
export interface ChatMessage {
  id: number
  role: ChatRole
  content: string
  sequenceNo: number
  createdAt: string
}

/** 任务状态 */
export type TaskStatus = 'TODO' | 'DOING' | 'DONE'

/** 任务优先级 */
export type TaskPriority = 'LOW' | 'MEDIUM' | 'HIGH'

/** 任务视图，对应后端 TaskDto */
export interface TaskItem {
  id: string
  title: string
  description: string | null
  status: TaskStatus
  priority: TaskPriority
  dueAt: string | null
  completedAt: string | null
  createdAt: string
  updatedAt: string
  /** 由服务端计算，避免各客户端各算各的 */
  overdue: boolean
}

/** 待人工确认的操作，对应后端 TaskConfirmationDto */
export interface TaskConfirmation {
  id: string
  /** 一次性确认令牌 */
  token: string
  action: string
  /** 服务端生成的可读描述 */
  summary: string
  affectedCount: number
  status: string
  expiresAt: string
  createdAt: string
}

/** 确认执行结果 */
export interface ConfirmationExecutionResult {
  executed: boolean
  affectedCount: number
  summary: string
}
