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
  /**
   * 这个会话选用的对话模型名（V23）。
   *
   * <p>`null` 表示「跟随当前生效配置的默认模型」。刻意不在这里填一份
   * 「当时到底用了哪个」的快照：它是**用户选的**而不是**实际发生的**，
   * 实际用哪个由后端在发请求时解析（配置换了，跟随默认的会话就该跟着换）。
   */
  chatModel: string | null
}

/**
 * 长期记忆。
 *
 * 跨会话生效：每次会话对话都会把它注入系统提示。
 * 注意这里没有分类/标签字段 —— 后端刻意不存没有消费方的东西。
 */
/** 记忆来源：MANUAL = 显式保存，AUTO = 轮末由模型自动沉淀 */
export type MemorySource = 'MANUAL' | 'AUTO'

export interface Memory {
  id: string
  content: string
  source: MemorySource
  createdAt: string
  updatedAt: string
  /** 常驻（V26）：无条件注入、优先占预算 */
  pinned: boolean
  /** 被哪条更新的记忆取代；非空即不再注入，但条目本身保留 */
  supersededBy: string | null
  /** 累计被注入到对话上下文的次数 */
  hitCount: number
  /** 最近一次被注入的时间；null 表示从未被注入 */
  lastUsedAt: string | null
}

/** 计划整体状态（阶段 22） */
export type PlanStatus = 'ACTIVE' | 'COMPLETED' | 'ABORTED'

/** 计划步骤状态 */
export type PlanStepStatus = 'PENDING' | 'RUNNING' | 'DONE' | 'FAILED' | 'SKIPPED'

/** 计划中的一步 */
export interface PlanStep {
  seq: number
  title: string
  status: PlanStepStatus
  /** 验收说明（模型写的「这一步算做完的标准」） */
  detail: string | null
  /** 实际调用到的工具名；由后端执行外壳回填，不是模型自报 */
  toolName: string | null
  toolResult: string | null
  error: string | null
  startedAt: string | null
  finishedAt: string | null
}

/** Agent 执行计划 */
export interface AgentPlan {
  id: string
  conversationId: string
  goal: string
  status: PlanStatus
  steps: PlanStep[]
  createdAt: string
  updatedAt: string
}

/** 聊天附件名片（阶段 22）。文件本体经 url 取，这里只是展示元数据 */
export interface ChatAttachment {
  id: string
  filename: string
  contentType: string
  size: number
}

/** 消息角色 */
export type ChatRole = 'USER' | 'ASSISTANT' | 'SYSTEM'

/**
 * 知识库引用快照（阶段 19），对应后端 ChatCitationDto。
 *
 * 与知识页的检索命中不同：这里**不带** score / matchedBy——存档只需要
 * 「回答当时引用了哪段」。
 */
export interface ChatCitation {
  /** 引用编号 [n]，从 1 开始 */
  index: number
  documentId: string
  documentTitle: string
  filename: string
  /** 片段在文档内的序号（0 起） */
  chunkIndex: number
  content: string
}

/** 消息视图，对应后端 ChatMessageDto */
export interface ChatMessage {
  id: number
  role: ChatRole
  content: string
  sequenceNo: number
  createdAt: string
  /** 知识库引用快照（阶段 19）：仅助手消息可能非空；用户消息恒为空数组 */
  citations: ChatCitation[]
  /** 附件名片（阶段 22）：仅用户消息可能有；用户消息恒为空数组 */
  attachments: ChatAttachment[]
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
  'IN_PROGRESS' | 'SUCCESS' | 'REQUIRES_CONFIRMATION' | 'REJECTED' | 'DUPLICATE' | 'FAILED'

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

/**
 * 配置项的来源（与后端 `ModelConfigService.ConfigSource` 对齐）。
 *
 * `DATABASE` = 设置页保存的值；`ENVIRONMENT` = .env / application.yml；
 * `DEFAULT` = 代码内置默认。界面据此标注「这一项从哪来」——
 * 没有这个标识，用户改了 .env 却不生效时完全无从判断是被设置页覆盖了。
 */
export type ModelConfigSource = 'DATABASE' | 'ENVIRONMENT' | 'DEFAULT'

/**
 * 当前生效的模型配置（后端 `ModelConfigDto`）。
 *
 * **不含明文密钥**：`apiKeyMasked` 形如 `****8f3a`，只保留末 4 位供用户确认
 * 「这就是我上次填的那把」。所以表单的密钥输入框永远以空值 + placeholder 呈现，
 * 用户不填就表示「不改动」。
 */
export interface ModelConfig {
  baseUrl: string
  apiKeyMasked: string
  hasApiKey: boolean
  chatModel: string
  temperature: number | null
  embeddingModel: string
  /** 数据库配置版本；null 表示数据库里没有配置行（全用环境变量） */
  revision: number | null
  updatedAt: string | null
  sources: Record<'baseUrl' | 'apiKey' | 'chatModel' | 'embeddingModel' | 'temperature', ModelConfigSource>
  /** 聊天模型能否热切换（本项目恒为 true） */
  hotReloadable: boolean
  /** 嵌入模型能否热切换。恒为 false：向量维度由 DDL 固定，换了会让写入失败 */
  embeddingHotReloadable: boolean
}

/**
 * 保存模型配置的请求体。
 *
 * `apiKey` 的三态约定见后端 `SaveModelConfigRequest`：
 * `null` / 不传 = 不改动，`""` = 清空并回落到环境变量，非空 = 覆盖。
 */
export interface SaveModelConfigPayload {
  baseUrl?: string | null
  apiKey?: string | null
  chatModel?: string | null
  temperature?: number | null
  embeddingModel?: string | null
}

/** 连通性测试结果（后端 `ModelConnectionTestResult`） */
export interface ModelConnectionTestResult {
  ok: boolean
  latencyMs: number
  model: string | null
  replyPreview: string | null
  /** 失败原因（已由后端翻成中文可操作说明）；成功时为 null */
  error: string | null
}

/**
 * 语音转写结果（后端 `TranscriptionResult`）。
 *
 * `ok=false` 有两种完全不同的含义，靠 `unavailable` 区分：
 * - `unavailable=true` → **不是错误**，是「没配语音模型」。前端应回落到
 *   浏览器自带的 Web Speech API，而不是弹红色报错。
 * - `unavailable=false` → 真失败，`error` 里有可操作的原因。
 *
 * 另有一种容易漏掉的状态：`ok=true` 但 `text` 为空串 —— 用户按了停止
 * 但一句话没说。此时该提示「没听清」而不是「转写失败」，
 * 因为后者会让人以为功能坏了去改配置。
 */
export interface TranscriptionResult {
  ok: boolean
  /** 转写文本；失败或没听清时为空串 */
  text: string
  /** 上游耗时（毫秒）；不可用时为 0 */
  latencyMs: number
  /** 失败原因；成功或不可用时为 null */
  error: string | null
  /** 是否因「没配语音模型」而无法转写（应回落浏览器识别） */
  unavailable: boolean
}

/** 语音转写能力的状态（后端 `AudioTranscriptionController.StatusDto`） */
export interface TranscriptionStatus {
  available: boolean
  /** 不可用的原因；可用时为 null */
  reason: string | null
}

/**
 * 一套模型配置（后端 `ModelProfileDto`，V21 多配置表）。
 *
 * **不含明文密钥**：`apiKeyMasked` 形如 `****8f3a`。卡片上的「编辑」表单
 * 密钥框永远以空值 + placeholder 呈现，用户不填就表示「不改动」。
 *
 * `baseUrl` / `chatModel` 等是**生效值**（已合并环境变量兜底），
 * 而 `sources` 说明每一项到底来自哪里 ——
 * 没有它，用户会以为卡片上显示的值都是这张卡自己填的。
 */
export interface ModelProfile {
  id: string
  name: string
  /** 预设标识；null 表示自定义配置 */
  presetId: string | null
  baseUrl: string
  /** 协议类型，只影响「获取模型列表」时打哪个接口；聊天始终走 OpenAI 兼容 */
  protocol: ModelProtocol
  apiKeyMasked: string
  hasApiKey: boolean
  /** 生效的**默认**对话模型名（= `chatModels` 第一项） */
  chatModel: string
  temperature: number | null
  /** 生效的**默认**嵌入模型名（= `embeddingModels` 第一项） */
  embeddingModel: string
  /**
   * 保存的对话模型清单（V23）。**第一项即默认模型**。
   *
   * 一个端点通常提供几十上百个模型，用户会按任务在推理型/日常型/视觉型之间换着用，
   * 所以清单是主体、单值列只是「默认用哪个」的快照。
   */
  chatModels: string[]
  /** 保存的嵌入模型清单（V23）。第一项即默认 */
  embeddingModels: string[]
  /**
   * 保存的语音模型清单（V24）。第一项即默认。
   *
   * 语音模型只服务聊天页的语音输入（转写），不在聊天模型下拉里出现。
   */
  audioModels: string[]
  /**
   * 模型名 → 上下文窗口（token，V25）。**只含已知的**。
   *
   * 不在表里的模型表示「窗口未知」—— 运行期会回落到全局默认值，
   * 界面上应提示用户可以手填。刻意不用 0 表示未知：0 会被拿去当分母。
   */
  contextWindows: Record<string, number>
  /**
   * 生效的默认语音模型名（= `audioModels` 第一项）；空串表示没配。
   *
   * 没配时聊天页的语音输入回落到浏览器自带的 Web Speech API ——
   * 那条路不需要任何配置，也不消耗 API 额度。
   */
  audioModel: string
  /** 是否当前生效 */
  active: boolean
  sortOrder: number
  /** 配置内容被保存过几次；0 表示还没保存过内容 */
  revision: number
  updatedAt: string
  sources: Record<
    'baseUrl' | 'apiKey' | 'chatModel' | 'embeddingModel' | 'audioModel' | 'temperature',
    ModelConfigSource
  >
}

/**
 * 配置列表 + 当前生效者（后端 `ModelProfileListDto`）。
 *
 * `activeId` 单独给出而不让前端从数组里 find：让前端推断会出现
 * 「两行都是 active」或「零行 active」两种不一致结果，
 * 而后端明确给出唯一答案。
 */
export interface ModelProfileList {
  profiles: ModelProfile[]
  /** 当前生效的配置 id；null 表示没有任何配置生效（回落到环境变量） */
  activeId: string | null
}

/**
 * 新建 / 更新一套模型配置的请求体。
 *
 * `apiKey` 三态：`undefined` / `null` = 不改动，`""` = 清空回落，非空 = 覆盖。
 */
export interface SaveModelProfilePayload {
  name: string
  presetId?: string | null
  baseUrl?: string | null
  protocol?: ModelProtocol | null
  apiKey?: string | null
  chatModel?: string | null
  temperature?: number | null
  embeddingModel?: string | null
  /**
   * 对话模型清单（V23）。**第一项即默认模型**，由后端写进单值列。
   *
   * 与 `chatModel` 同时给时以清单为准 —— 两个来源都生效会造成
   * 「界面显示 A、实际请求用 B」。
   */
  chatModels?: string[] | null
  /** 嵌入模型清单（V23）。第一项即默认 */
  embeddingModels?: string[] | null
  /**
   * 语音模型清单（V24）。第一项即默认，会被后端写进 `audio_model` 单值列。
   *
   * 传空数组表示「这套配置不配语音模型」—— 此时语音输入回落到浏览器
   * 自带识别。不传（undefined）与传空数组在本接口里等价。
   */
  audioModels?: string[] | null
  /**
   * 模型名 → 上下文窗口（token，V25）。
   *
   * 不传（undefined）= 由服务端用内置对照表补；传了则以它为准，
   * 其中值为 null 的项表示「用户清空了这个模型的窗口 = 未知」。
   */
  contextWindows?: Record<string, number | null> | null
}

/**
 * 模型端点协议。
 *
 * **只管「怎么问出模型列表」，不管「怎么聊天」。** 聊天链路统一走
 * OpenAI 兼容协议（DashScope / DeepSeek / Moonshot / 智谱 / OpenRouter /
 * Ollama 的 /v1 都支持）。这个字段只决定探测时打哪个 URL、带什么鉴权头：
 *
 * - `OPENAI`    → `GET {base}/models`，`Authorization: Bearer`
 * - `ANTHROPIC` → `GET {base}/v1/models`，`x-api-key` + `anthropic-version`
 * - `OLLAMA`    → `GET {origin}/api/tags`，无需密钥
 */
export type ModelProtocol = 'OPENAI' | 'ANTHROPIC' | 'OLLAMA'

/**
 * 「获取模型列表」的结果（后端 `ModelListResult`）。
 *
 * `ok=false` 不是接口错误：大量自建与中转端点根本没有 `/models`，
 * 但它们能正常聊天。此时照常手打模型名即可。
 */
export interface ModelListResult {
  ok: boolean
  /** 模型名（已去重、字母序）；失败时为空数组 */
  models: string[]
  latencyMs: number
  /** 失败原因；成功时为 null */
  error: string | null
  /**
   * 失败分类，决定提示语气：
   * - `UNSUPPORTED`：该端点没实现模型列表接口。**正常情况**，不影响聊天，
   *   手填模型名即可 —— 用中性提示，不该吓用户。
   * - `ERROR`：认证失败 / 网络不通 / 前置条件缺失。需要用户处理 —— 用警示色。
   * - `null`：成功。
   */
  kind: ProbeFailureKind | null
  /**
   * 探测到的模型上下文窗口（token），键为模型名。
   *
   * 原生 OpenAI 与 Anthropic 的 `/models` 都不返回此字段，所以这里**经常是空对象**，
   * 空不代表出错 —— 前端只把它当作「预填建议」，用户仍可手改或留空。
   */
  contextWindows?: Record<string, number>
}

export type ProbeFailureKind = 'UNSUPPORTED' | 'ERROR'
