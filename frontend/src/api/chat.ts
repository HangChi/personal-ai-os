import { apiBaseUrl, notifyUnauthorized, requestData } from '@/api/http'
import { resolveFilename, triggerBlobDownload } from '@/api/export'
import type { ApiResponse } from '@/types/api'
import type {
  ChatAttachment,
  ChatCitation,
  ChatMessage,
  ChatSession,
  PageDto,
  TranscriptionResult,
  TranscriptionStatus,
} from '@/types/api'
import { http } from '@/api/http'
import type { ThinkingLevel } from '@/lib/settings'

/**
 * 聊天接口客户端。
 *
 * 会话与消息走统一的 Axios 实例（自动解包 code != 0 的错误）；
 * 流式对话必须走原生 `fetch` —— 浏览器的 `EventSource` 只支持 GET，
 * 而对话需要 POST 提交消息体（见 specs/002-ai-chat/research.md R-05）。
 */

/** 会话列表查询（阶段 16） */
export interface SessionListQuery {
  /** 标题关键词 */
  keyword?: string
  /** 页码，从 0 开始 */
  page?: number
  /** 每页条数 */
  size?: number
}

/**
 * 分页 + 关键词筛选的会话列表，按最近活动时间倒序。
 *
 * 走 `/sessions/search` 而不是改造 `/sessions`：后者返回全量数组，
 * 被导出链路与既有集成测试依赖（见 specs/016 plan R-01）。
 */
export function listSessions(query: SessionListQuery = {}): Promise<PageDto<ChatSession>> {
  return requestData<PageDto<ChatSession>>({
    url: '/chat/sessions/search',
    method: 'get',
    params: query,
  })
}

/** 创建会话 */
export function createSession(title?: string): Promise<ChatSession> {
  return requestData<ChatSession>({
    url: '/chat/sessions',
    method: 'post',
    data: title ? { title } : {},
  })
}

/** 删除会话及其全部消息 */
export function deleteSession(conversationId: string): Promise<void> {
  return requestData<void>({ url: `/chat/sessions/${conversationId}`, method: 'delete' })
}

/**
 * 重命名会话。
 *
 * 用 PATCH 而非 PUT：只改标题一个字段，是部分更新。
 * 返回完整的会话视图（含 messageCount），调用方据此就地替换列表项，无需二次请求。
 */
export function renameSession(conversationId: string, title: string): Promise<ChatSession> {
  return requestData<ChatSession>({
    url: `/chat/sessions/${conversationId}`,
    method: 'patch',
    data: { title },
  })
}

/**
 * 设置这个会话用哪个对话模型（V23）。
 *
 * <p>传 `null` 表示「改回跟随生效配置的默认模型」。后端刻意不刷新
 * `updatedAt` —— 换模型不是对话活动，把会话顶到列表最前面会让人困惑。
 *
 * <p>返回完整会话视图，调用方据此就地替换列表项，不必再拉一次列表。
 */
export function setSessionModel(conversationId: string, model: string | null): Promise<ChatSession> {
  return requestData<ChatSession>({
    url: `/chat/sessions/${conversationId}/model`,
    method: 'put',
    data: { model },
  })
}

/** 消息分页参数（阶段 21） */
export interface MessageListQuery {
  /** 返回条数（1~200）。省略则返回全部消息（老契约，导出等场景用） */
  limit?: number
  /** 只取顺序号小于它的消息，用于「加载更早」；省略表示取最新的 limit 条 */
  beforeSeq?: number
}

/**
 * 查询会话消息（按顺序正序）。
 *
 * 不带参数时返回全部 —— 与阶段 1 的契约一致；界面请带 `limit`，
 * 并按返回结果中最小顺序号继续向前翻页。
 */
export function listMessages(conversationId: string, query: MessageListQuery = {}): Promise<ChatMessage[]> {
  return requestData<ChatMessage[]>({
    url: `/chat/sessions/${conversationId}/messages`,
    method: 'get',
    params: query,
  })
}

/** 流式对话的回调集合 */
export interface StreamHandlers {
  /** 流建立后触发一次 */
  onStart?: (conversationId: string) => void
  /** 知识库增强开启且检索完成后触发一次（阶段 19）；空数组表示零命中 */
  onCitations?: (citations: ChatCitation[]) => void
  /** 每个内容增量触发一次 */
  onDelta: (content: string) => void
  /** 正常结束，参数为完整回复 */
  onDone: (content: string, context?: { promptTokens: number }) => void
  /** 失败，参数为可直接展示的中文文案 */
  onError: (message: string) => void
}

/** 流式对话的可选参数（阶段 19；阶段 22 增加思考程度） */
export interface StreamChatOptions {
  /** 启用知识库增强：先检索再回答，回复带 [n] 引用 */
  useKnowledge?: boolean
  /** 检索条数（1~20，缺省 4；服务端权威收敛） */
  topK?: number
  /** 模型思考程度：off=不思考（缺省），standard/deep 对应 DashScope 的思考预算 */
  thinkingLevel?: ThinkingLevel
  /** 附件名片（阶段 22）：发送前已上传拿到标识，随消息一起持久化 */
  attachments?: ChatAttachment[]
  /**
   * 空闲超时（毫秒），缺省 60 秒。
   *
   * <p>暴露成参数只为两件事：单测要在毫秒级验证超时分支；部署在空闲判定更严格的
   * 反向代理后面时可以整体调小。业务调用方不应传它。
   */
  idleTimeoutMs?: number
}

function streamUrl(conversationId: string): string {
  return `${apiBaseUrl}/chat/sessions/${conversationId}/stream`
}

/**
 * 流式响应的空闲上限：连续这么久没收到**任何字节**即判定连接已死。
 *
 * <p>为什么必须有这条：`reader.read()` 在「连接被中间设备静默丢弃」时不会 reject ——
 * 它会一直挂着，因为 TCP 没收到 RST。界面上表现为「永远转圈」，用户除了刷新别无他法。
 * 服务端每 15 秒发一次注释帧（见 `ChatCompletionService#startHeartbeat`），
 * 因此正常情况下 60 秒必然有字节到达；超过它只能是链接真的断了。
 *
 * <p>取 60 而不是更长：思考型模型的静默期由心跳兜住，这里只需识别「心跳也没了」。
 */
const STREAM_IDLE_TIMEOUT_MILLIS = 60_000

/** 空闲超时的统一文案（与后端 error 事件的语气一致） */
const IDLE_TIMEOUT_MESSAGE = '连接超时：服务器长时间没有响应，请重试'

/**
 * 发起流式对话并手工解析 SSE 帧。
 *
 * 不使用 `EventSource`：它无法发送 POST 请求体，且会自动重连 ——
 * 对话场景下自动重连意味着**重复提问**，是错误行为。
 *
 * @param options 阶段 19：知识库增强参数。省略 useKnowledge 时请求体不带该字段，
 *                服务端按 false 处理（与阶段 1 行为一致）
 * @returns 会话被正常结束或失败时 resolve；被 abort 时 reject
 */
export async function streamChat(
  conversationId: string,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
  options?: StreamChatOptions,
): Promise<void> {
  const payload: Record<string, unknown> = { content }
  if (options?.useKnowledge !== undefined) {
    payload.useKnowledge = options.useKnowledge
  }
  if (options?.topK !== undefined) {
    payload.topK = options.topK
  }
  if (options?.thinkingLevel !== undefined) {
    payload.thinkingLevel = options.thinkingLevel
  }
  if (options?.attachments !== undefined && options.attachments.length > 0) {
    payload.attachments = options.attachments
  }
  // 内部 controller：既承载空闲超时的中止，也把调用方的 signal 转发进来。
  // 不直接 abort 外部 signal —— 那是调用方的对象，且我们还需要区分
  // 「用户主动停止」（静默）与「连接超时」（要给提示）两种中止原因。
  const idleController = new AbortController()
  const idleLimit = options?.idleTimeoutMs ?? STREAM_IDLE_TIMEOUT_MILLIS
  let idleTimedOut = false
  let idleTimer: ReturnType<typeof setTimeout> | undefined

  const clearIdleTimer = (): void => {
    if (idleTimer !== undefined) {
      clearTimeout(idleTimer)
      idleTimer = undefined
    }
  }

  /** 重新计时：每收到一批字节就重置，只有「彻底安静」才会触发 */
  const armIdleTimer = (): void => {
    clearIdleTimer()
    idleTimer = setTimeout(() => {
      idleTimedOut = true
      idleController.abort()
    }, idleLimit)
  }

  const forwardAbort = (): void => idleController.abort()
  if (signal) {
    if (signal.aborted) {
      idleController.abort()
    } else {
      signal.addEventListener('abort', forwardAbort, { once: true })
    }
  }

  // 覆盖「等响应头」阶段：服务端在返回 emitter 之前要做校验，
  // 这段时间同样可能被中间设备静默掐断，不能只在读流时才计时。
  armIdleTimer()

  try {
    const response = await fetch(streamUrl(conversationId), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
      body: JSON.stringify(payload),
      signal: idleController.signal,
    })

    if (!response.ok) {
      // 401 必须走与 Axios 拦截器相同的登出链路：本请求用原生 fetch 发起，
      // 不经过 http.ts 的拦截器。不在这里通知的话，会话过期时只有对话页停在原地，
      // 而其它页面都会自动回登录页。
      if (response.status === 401) {
        notifyUnauthorized()
      }
      handlers.onError(await readErrorMessage(response))
      return
    }
    if (!response.body) {
      handlers.onError('当前浏览器不支持流式响应')
      return
    }

    const reader = response.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    let currentEvent = ''
    let terminalEventReceived = false
    const dataLines: string[] = []

    const flush = (): void => {
      if (dataLines.length > 0) {
        terminalEventReceived = dispatchEvent(currentEvent, dataLines.join('\n'), handlers) || terminalEventReceived
        dataLines.length = 0
      }
      currentEvent = ''
    }

    for (;;) {
      const { done, value } = await reader.read()
      if (done) {
        break
      }
      // 任何字节（含服务端心跳的注释帧）都算「链路还活着」
      armIdleTimer()
      buffer += decoder.decode(value, { stream: true })

      let newlineIndex = buffer.indexOf('\n')
      while (newlineIndex >= 0) {
        const line = buffer.slice(0, newlineIndex).replace(/\r$/, '')
        buffer = buffer.slice(newlineIndex + 1)
        if (line === '') {
          // 空行 = 事件边界
          flush()
        } else if (line.startsWith('event:')) {
          currentEvent = line.slice('event:'.length).trim()
        } else if (line.startsWith('data:')) {
          dataLines.push(line.slice('data:'.length).trim())
        }
        // 其余（如心跳的 `: ping` 注释帧）按 SSE 规范忽略
        newlineIndex = buffer.indexOf('\n')
      }
    }
    flush()
    if (!terminalEventReceived && !signal?.aborted) {
      handlers.onError('回答生成中断：连接提前关闭，请重试')
    }
  } catch (cause) {
    if (idleTimedOut) {
      // 是我们自己掐断的：转成可读文案，不把 AbortError 抛给调用方
      handlers.onError(IDLE_TIMEOUT_MESSAGE)
      return
    }
    throw cause
  } finally {
    clearIdleTimer()
    signal?.removeEventListener('abort', forwardAbort)
  }
}

function dispatchEvent(eventName: string, payload: string, handlers: StreamHandlers): boolean {
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(payload) as Record<string, unknown>
  } catch {
    // 非法帧直接丢弃；损坏的结束帧不能算成功。
    return false
  }

  switch (eventName) {
    case 'start':
      handlers.onStart?.(String(parsed.conversationId ?? ''))
      break
    case 'citations': {
      // 阶段 19：知识库检索结果。count 与 citations 长度一致，
      // 载荷里只消费 citations（count 是给日志/排查看的冗余字段）。
      const list = Array.isArray(parsed.citations) ? parsed.citations : []
      handlers.onCitations?.(list as ChatCitation[])
      break
    }
    case 'delta':
      handlers.onDelta(String(parsed.content ?? ''))
      break
    case 'done':
      handlers.onDone(String(parsed.content ?? ''), {
        promptTokens: Number(parsed.promptTokens ?? 0),
      })
      return true
    case 'error':
      handlers.onError(String(parsed.message ?? 'AI 服务调用失败'))
      return true
    default:
      break
  }
  return false
}

/**
 * 回退到某条消息之前（删除该顺序号及其之后的所有消息）。
 *
 * <p>只删除、不发流：调用方随后用 `streamChat` 重发，复用的仍是同一条链路。
 */
export function rewindMessages(conversationId: string, fromSequenceNo: number): Promise<{ deleted: number }> {
  return requestData<{ deleted: number }>({
    url: `/chat/sessions/${conversationId}/rewind`,
    method: 'post',
    params: { fromSequenceNo },
  })
}

/** 从统一错误结构中提取可读文案 */
async function readErrorMessage(response: Response): Promise<string> {
  try {
    const body = (await response.json()) as { message?: string }
    if (body && typeof body.message === 'string' && body.message.length > 0) {
      return body.message
    }
  } catch {
    // 落到下面的兜底文案
  }
  return `请求失败（HTTP ${response.status}）`
}

/**
 * 导出单个会话为 Markdown 并触发下载。
 *
 * <p>与全量导出的区别：那个是给机器读的迁移格式，这个是给人读的（粘笔记、发出去）。
 * 两者共用 {@code triggerBlobDownload}，只在「取什么」上不同。
 *
 * @returns 实际使用的文件名
 */
export async function downloadSessionMarkdown(conversationId: string): Promise<string> {
  const response = await http.get(`/chat/sessions/${conversationId}/export`, {
    responseType: 'blob',
    // 会话再长也是一次数据库查询，不需要导出那种分钟级超时
    timeout: 30_000,
  })
  const filename = resolveFilename(
    response.headers['content-disposition'] as string | undefined,
    `会话-${conversationId.slice(0, 8)}.md`,
  )
  triggerBlobDownload(response.data as Blob, filename)
  return filename
}

/**
 * 上传一个聊天附件（阶段 22）。
 *
 * 单独一步而不是随消息一起提交：文件可能较大，先上传拿到标识，
 * 「选文件」失败就不会连带把消息发出去；消息发送也不必等待传输。
 */
export async function uploadAttachment(file: File): Promise<ChatAttachment> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResponse<ChatAttachment>>('/chat/uploads', form)
  const body = response.data
  if (body.code !== 0 || !body.data) {
    throw new Error(body.message || '附件上传失败')
  }
  return body.data
}

/**
 * 查询语音转写能力（走自己的语音模型，还是回落浏览器识别）。
 *
 * <p>前端在**按麦克风之前**问一次：若不可用就直接走 Web Speech API，
 * 用户不必先说一句话、等一次往返，才被告知「没配模型」。
 */
export function fetchTranscriptionStatus(): Promise<TranscriptionStatus> {
  return requestData<TranscriptionStatus>({
    url: '/chat/transcribe/status',
    method: 'get',
  })
}

/**
 * 上传一段录音，拿回转写文本。
 *
 * <p>超时给到 60 秒：后端硬卡 30 秒，前端留一倍余量，确保超时由后端
 * 报出可读原因（「转写超时，请检查端点地址与网络」），而不是前端的
 * AbortError —— 后者对用户毫无信息量。
 *
 * <p>返回值里 {@code unavailable=true} 不是错误：它表示后端没配语音模型，
 * 调用方应回落到浏览器识别。不要在这个函数里 throw。
 */
export async function transcribeAudio(file: File): Promise<TranscriptionResult> {
  const form = new FormData()
  form.append('file', file)
  const response = await http.post<ApiResponse<TranscriptionResult>>('/chat/transcribe', form, {
    timeout: 60_000,
  })
  const body = response.data
  if (body.code !== 0 || !body.data) {
    throw new Error(body.message || '语音转写失败')
  }
  return body.data
}

/**
 * 查询会话的上下文用量（阶段 22 仪表盘）。
 *
 * 刷新页面后拿不到上一轮的 done 事件，选中会话时补查一次才能显示仪表盘。
 */
/** 上下文用量（token 口径）。分母是用户选择的预算，不是模型窗口 */
export interface ContextUsage {
  /** 本轮预计送入模型的 token 数（估算） */
  promptTokens: number
  /** 用户当前选择的预算（token） */
  budgetTokens: number
  /** 模型支持的上下文窗口（token） */
  contextWindow: number
  /** 实际使用的模型；空会话也会解析默认模型 */
  modelName?: string | null
  /** fallback 只是默认保护值，不能当作已识别的模型上限 */
  windowSource?: 'configured' | 'catalog' | 'fallback' | 'endpoint'
  /** 本次运行中，由成功请求的真实 usage 验证的输入下限；不是模型容量 */
  verifiedInputTokens?: number
  inputLimitSource?: 'configured' | 'catalog' | 'fallback' | 'endpoint' | 'adaptive'
  tokenEstimateRatio?: number
  /** 已扣除回复空间和安全余量的输入上限 */
  inputLimitTokens?: number
  outputReserveTokens?: number
  safetyMarginTokens?: number
  /** 是否为估算值 */
  estimated: boolean
}

export function fetchContextUsage(
  conversationId?: string | null,
  thinkingLevel: string = 'off',
): Promise<ContextUsage> {
  return requestData<ContextUsage>({
    url: conversationId ? `/chat/sessions/${conversationId}/context` : '/chat/context',
    method: 'get',
    params: { thinkingLevel },
  })
}

/**
 * 设置上下文预算（token）。
 *
 * 预算同时是窗口裁剪阈值，所以必须落到服务端 —— 前端自己记一份的话，
 * 仪表盘的分母和实际裁剪就不是同一个数了。
 */
export function setContextBudget(
  tokens: number,
  conversationId?: string | null,
  thinkingLevel: string = 'off',
): Promise<ContextUsage> {
  return requestData<ContextUsage>({
    url: '/chat/context-budget',
    method: 'put',
    data: { tokens, conversationId, thinkingLevel },
  })
}
