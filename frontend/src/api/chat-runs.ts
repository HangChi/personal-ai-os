import { apiBaseUrl, notifyUnauthorized, requestData } from '@/api/http'
import type { StreamChatOptions, StreamHandlers } from '@/api/chat'

export interface RunActivity {
  id: string
  tool: string
  status: string
  durationMs: number
}
export interface AgentQuestion {
  id: string
  runId: string
  question: string
  options: { label: string; description?: string }[]
  status: 'PENDING' | 'ANSWERED'
  answer?: string | null
}
export interface AgentFollowup {
  id: string
  runId: string
  conversationId: string
  mode: 'QUEUE' | 'STEER'
  request: { content: string; attachments?: import('@/types/api').ChatAttachment[] }
  status: 'PENDING' | 'APPLIED' | 'CANCELLED'
}
export interface ChatRun {
  id: string
  conversationId: string
  status: 'RUNNING' | 'COMPLETED' | 'WAITING_CONFIRMATION' | 'WAITING_USER' | 'INTERRUPTED' | 'STOPPED'
  question?: AgentQuestion | null
  followups?: AgentFollowup[]
  events: { id: number; name: string; payload: string }[]
}
export interface RunHandlers extends StreamHandlers {
  onRun: (run: ChatRun) => void
  onActivity: (activity: RunActivity) => void
  onReset: () => void
  onConnection: (reconnecting: boolean) => void
}
const base = (conversationId: string) => `/chat/sessions/${conversationId}/runs`
export const finishConfirmations = (conversationId: string, id: string): Promise<ChatRun> =>
  requestData({ url: `${base(conversationId)}/${id}/confirmations/finish`, method: 'post' })
export const latestRun = (conversationId: string): Promise<ChatRun | null> =>
  requestData({ url: `${base(conversationId)}/latest`, method: 'get' })
export const resumeRun = (conversationId: string, id: string): Promise<ChatRun> =>
  requestData({ url: `${base(conversationId)}/${id}/resume`, method: 'post' })
export const stopRun = (conversationId: string, id: string): Promise<void> =>
  requestData({ url: `${base(conversationId)}/${id}/stop`, method: 'post' })
export const startRun = (
  conversationId: string,
  content: string,
  options?: StreamChatOptions,
  key: string = crypto.randomUUID(),
): Promise<ChatRun> =>
  requestData({
    url: base(conversationId),
    method: 'post',
    headers: { 'Idempotency-Key': key },
    data: { content, ...options },
  })

export const answerQuestion = (
  conversationId: string,
  runId: string,
  questionId: string,
  answer: string,
): Promise<ChatRun> =>
  requestData({
    url: `${base(conversationId)}/${runId}/questions/${questionId}/answer`,
    method: 'post',
    data: { answer },
  })
export const submitFollowup = (
  conversationId: string,
  runId: string,
  mode: 'QUEUE' | 'STEER',
  content: string,
  options?: StreamChatOptions,
  key: string = crypto.randomUUID(),
): Promise<ChatRun> =>
  requestData({
    url: `${base(conversationId)}/${runId}/followups`,
    method: 'post',
    headers: { 'Idempotency-Key': key },
    data: { mode, request: { content, ...options } },
  })
export const cancelFollowup = (conversationId: string, runId: string, inputId: string): Promise<ChatRun> =>
  requestData({ url: `${base(conversationId)}/${runId}/followups/${inputId}`, method: 'delete' })

export const editFollowup = (
  conversationId: string,
  runId: string,
  inputId: string,
  content: string,
): Promise<ChatRun> =>
  requestData({ url: `${base(conversationId)}/${runId}/followups/${inputId}`, method: 'patch', data: { content } })
export const steerFollowup = (conversationId: string, runId: string, inputId: string): Promise<ChatRun> =>
  requestData({ url: `${base(conversationId)}/${runId}/followups/${inputId}/steer`, method: 'post' })

/** 读取持久化事件流；只重连 GET，游标去重，绝不重新提交消息。 */
export async function followRun(initial: ChatRun, handlers: RunHandlers, signal: AbortSignal): Promise<void> {
  let cursor = 0
  let run = initial
  let failures = 0
  const apply = (value: ChatRun): void => {
    run = value
    handlers.onRun(run)
    for (const event of run.events) {
      if (event.id <= cursor) continue
      const data = JSON.parse(event.payload)
      cursor = event.id
      switch (event.name) {
        case 'reset':
          handlers.onReset()
          break
        case 'activity':
          handlers.onActivity(data)
          break
        case 'citations':
          handlers.onCitations?.(data.citations ?? [])
          break
        case 'rag':
          handlers.onRag?.(data)
          break
        case 'delta':
          handlers.onDelta(data.content)
          break
        case 'warning':
          handlers.onWarning?.({ code: String(data.code ?? ''), message: String(data.message ?? '') })
          break
        case 'error':
          handlers.onError(data.message)
          break
        case 'done':
          handlers.onDone(data.content, { promptTokens: data.promptTokens })
          break
      }
    }
  }
  apply(initial)
  while (!signal.aborted && (run.status === 'RUNNING' || run.events.length === 200)) {
    try {
      await readRunStream(run.conversationId, run.id, cursor, signal, (next) => {
        apply(next)
        failures = 0
        handlers.onConnection(false)
      })
      if (run.status !== 'RUNNING' && run.events.length < 200) return
    } catch (cause) {
      if (signal.aborted) return
      handlers.onConnection(true)
      if (++failures >= 8) throw cause
    }
    // 服务端定期关闭长连接，重连仍从最后持久化事件继续；等待可被停止立即打断。
    await new Promise<void>((resolve) => {
      const finish = (): void => {
        clearTimeout(timer)
        signal.removeEventListener('abort', finish)
        resolve()
      }
      const timer = setTimeout(finish, Math.min(300 * 2 ** failures, 5000))
      signal.addEventListener('abort', finish, { once: true })
      if (signal.aborted) finish()
    })
  }
}

export async function readRunStream(
  conversationId: string,
  id: string,
  after: number,
  signal: AbortSignal,
  onRun: (run: ChatRun) => void,
): Promise<void> {
  const controller = new AbortController()
  const abort = (): void => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  if (signal.aborted) abort()
  let idle = setTimeout(abort, 45_000)
  let reader: ReadableStreamDefaultReader<Uint8Array> | undefined
  try {
    const response = await fetch(`${apiBaseUrl}${base(conversationId)}/${id}/events?after=${after}`, {
      headers: { Accept: 'text/event-stream' },
      credentials: 'same-origin',
      signal: controller.signal,
    })
    if (response.status === 401) notifyUnauthorized()
    if (!response.ok || !response.body) throw new Error('无法读取执行进度，请稍后重连')
    reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    while (!signal.aborted) {
      const { done, value } = await reader.read()
      if (done) return
      clearTimeout(idle)
      idle = setTimeout(abort, 45_000)
      buffer += decoder.decode(value, { stream: true })
      let boundary: RegExpExecArray | null
      while ((boundary = /\r?\n\r?\n/.exec(buffer))) {
        const frame = buffer.slice(0, boundary.index)
        buffer = buffer.slice(boundary.index + boundary[0].length)
        const lines = frame.split(/\r?\n/)
        if (!lines.some((line) => line === 'event:run' || line === 'event: run')) continue
        const data = lines
          .filter((line) => line.startsWith('data:'))
          .map((line) => line.slice(5).trimStart())
          .join('\n')
        if (data) onRun(JSON.parse(data) as ChatRun)
      }
    }
  } finally {
    clearTimeout(idle)
    signal.removeEventListener('abort', abort)
    controller.abort()
    await reader?.cancel().catch(() => {})
    reader?.releaseLock()
  }
}
