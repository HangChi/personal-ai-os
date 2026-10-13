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
