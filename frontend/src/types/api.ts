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
  /** 依赖分量状态（阶段 6 技术债清偿新增）：如 database=UP、ai=CONFIGURED */
  components?: Record<string, string>
}

/** 分页响应（阶段 6 技术债清偿新增）：items 是当前页，total 是全量条数 */
export interface PageDto<T> {
  items: T[]
  page: number
  size: number
  total: number
}

/** 会话视图，对应后端 ChatSessionDto */
export interface ChatSession {
  conversationId: string
  title: string
  createdAt: string
  updatedAt: string
  messageCount: number
}

/**
 * 长期记忆。
 *
 * 跨会话生效：每次会话对话都会把它注入系统提示。
 * 注意这里没有分类/标签字段 —— 后端刻意不存没有消费方的东西。
 */
export interface Memory {
  id: string
  content: string
  createdAt: string
  updatedAt: string
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
  /** 标签（0–5 个，顺序由服务端保证稳定） */
  tags: string[]
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

/** 知识文档，对应后端 KnowledgeDocumentDto */
export interface KnowledgeDocument {
  id: string
  title: string
  filename: string
  contentType: string
  sizeBytes: number
  charCount: number
  /** 分块数，等于该文档在向量库中的条数 */
  chunkCount: number
  createdAt: string
}

/** 检索命中，对应后端 KnowledgeHitDto */
export interface KnowledgeHit {
  documentId: string
  documentTitle: string
  filename: string
  chunkIndex: number
  content: string
  /** 向量余弦相似度；仅由关键词通道命中时为 null（没做过嵌入） */
  score: number | null
  /** 命中来源（混合检索）：VECTOR / KEYWORD / BOTH */
  matchedBy?: 'VECTOR' | 'KEYWORD' | 'BOTH'
}

/** 引用：命中 + 编号 */
export interface Citation extends KnowledgeHit {
  index: number
}

/** 知识库问答响应 */
export interface KnowledgeAskResponse {
  question: string
  answer: string
  /** false 表示知识库中没有相关内容（此时 citations 为空，且未调用模型） */
  answered: boolean
  citations: Citation[]
}

/** 检索响应 */
export interface KnowledgeSearchResponse {
  query: string
  hits: KnowledgeHit[]
}

// ---------------------------------------------------------------------------
// 日程与提醒（阶段 4）
// ---------------------------------------------------------------------------

/** 重复类型 */
export type RecurrenceType = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'WEEKDAYS'

/** 提醒来源类型 */
export type ReminderSourceType = 'CALENDAR_EVENT' | 'TASK'

/** 提醒状态 */
export type ReminderStatus = 'PENDING' | 'READ' | 'CANCELLED'

/** 日程的一次发生实例（重复规则已由服务端展开） */
export interface CalendarOccurrence {
  eventId: string
  title: string
  location: string | null
  allDay: boolean
  startsAt: string
  endsAt: string
  recurrenceType: RecurrenceType
  /** 是否为重复日程中的一次 */
  recurring: boolean
  reminderLeadMinutes: number
}

/** 日程的原始定义 */
export interface CalendarEvent {
  id: string
  title: string
  description: string | null
  location: string | null
  allDay: boolean
  startsAt: string
  endsAt: string
  recurrenceType: RecurrenceType
  recurrenceUntil: string | null
  reminderLeadMinutes: number
  createdAt: string
}

/** 提醒 */
export interface Reminder {
  id: string
  sourceType: ReminderSourceType
  sourceId: string
  title: string
  occurrenceAt: string
  remindAt: string
  status: ReminderStatus
  readAt: string | null
}

/** 一次扫描的结果 */
export interface ReminderScanResult {
  windowStart: string
  windowEnd: string
  scannedAt: string
  created: number
  skipped: number
  /** 清理掉的孤儿提醒条数（来源已被删除） */
  pruned: number
}

/** 工具调用的结果状态，与后端 IdempotencyOutcome 对齐 */
export type ToolInvocationOutcome =
  | 'IN_PROGRESS'
  | 'SUCCESS'
  | 'REQUIRES_CONFIRMATION'
  | 'REJECTED'
  | 'DUPLICATE'
  | 'FAILED'

/**
 * 工具调用审计记录（阶段 11）。
 *
 * `durationMs` 为 null 表示这次调用**没有真正执行**（如 REQUIRES_CONFIRMATION），
 * 与「执行了但耗时 0」是两回事，界面上要区分显示。
 */
export interface ToolInvocation {
  id: number
  operation: string
  source: 'TOOL' | 'HTTP'
  outcome: ToolInvocationOutcome
  durationMs: number | null
  conversationId: string | null
  createdAt: string
}

/** 单个 MCP server 的运行期状态（后端刻意不返回 env/command/args） */
export interface McpServerStatus {
  name: string
  available: boolean
  failureReason: string | null
  toolCount: number
  autoTools: string[]
}

/** MCP 接入状态总览 */
export interface McpStatus {
  enabled: boolean
  servers: McpServerStatus[]
}
