import { requestData } from '@/api/http'
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
export const latestRun = (conversationId: string): Promise<ChatRun | null> =>
  requestData({ url: `${base(conversationId)}/latest`, method: 'get' })
export const resumeRun = (conversationId: string, id: string): Promise<ChatRun> =>
  requestData({ url: `${base(conversationId)}/${id}/resume`, method: 'post' })
export const stopRun = (conversationId: string, id: string): Promise<void> =>
  requestData({ url: `${base(conversationId)}/${id}/stop`, method: 'post' })
export const startRun = (conversationId: string, content: string, options?: StreamChatOptions): Promise<ChatRun> =>
  requestData({
    url: base(conversationId),
    method: 'post',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
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

/** 只重连只读事件接口；绝不通过重新提交消息重连。游标去重，支持页面刷新后追赶。 */
export async function followRun(initial: ChatRun, handlers: RunHandlers, signal: AbortSignal): Promise<void> {
  let run = initial
  let cursor = 0
  let failures = 0
  while (!signal.aborted) {
    handlers.onRun(run)
    for (const event of run.events) {
      if (event.id <= cursor) continue
      cursor = event.id
      const data = JSON.parse(event.payload)
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
        case 'delta':
          handlers.onDelta(data.content)
          break
        case 'error':
          handlers.onError(data.message)
          break
        case 'done':
          handlers.onDone(data.content, { promptTokens: data.promptTokens })
          break
      }
    }
    // 一批恰好满 200 条时继续追赶，终态也必须把尾部读完。
    if (run.status !== 'RUNNING' && run.events.length < 200) return
    await new Promise<void>((resolve) =>
      setTimeout(resolve, run.events.length === 200 ? 0 : Math.min(300 * 2 ** failures, 5000)),
    )
    if (signal.aborted) return
    try {
      run = await requestData<ChatRun>({
        url: `${base(run.conversationId)}/${run.id}`,
        method: 'get',
        params: { after: cursor },
        signal,
      })
      failures = 0
      handlers.onConnection(false)
    } catch (cause) {
      if (signal.aborted) return
      handlers.onConnection(true)
      if (++failures >= 8) throw cause
      // 下一轮只重试读取，不再次处理上一批事件或提前按终态退出。
      run = { ...run, status: 'RUNNING', events: [] }
    }
  }
}
