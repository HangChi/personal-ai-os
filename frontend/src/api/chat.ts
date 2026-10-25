import { requestData } from '@/api/http'
import type { ChatMessage, ChatSession } from '@/types/api'

/**
 * 聊天接口客户端。
 *
 * 会话与消息走统一的 Axios 实例（自动解包 code != 0 的错误）；
 * 流式对话必须走原生 `fetch` —— 浏览器的 `EventSource` 只支持 GET，
 * 而对话需要 POST 提交消息体（见 specs/002-ai-chat/research.md R-05）。
 */

/** 会话列表，按最近活动时间倒序 */
export function listSessions(): Promise<ChatSession[]> {
  return requestData<ChatSession[]>({ url: '/chat/sessions', method: 'get' })
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

/** 查询会话全部消息（按顺序正序） */
export function listMessages(conversationId: string): Promise<ChatMessage[]> {
  return requestData<ChatMessage[]>({
    url: `/chat/sessions/${conversationId}/messages`,
    method: 'get',
  })
}

/** 流式对话的回调集合 */
export interface StreamHandlers {
  /** 流建立后触发一次 */
  onStart?: (conversationId: string) => void
  /** 每个内容增量触发一次 */
  onDelta: (content: string) => void
  /** 正常结束，参数为完整回复 */
  onDone: (content: string) => void
  /** 失败，参数为可直接展示的中文文案 */
  onError: (message: string) => void
}

function streamUrl(conversationId: string): string {
  const base = import.meta.env.VITE_API_BASE_URL ?? '/api/v1'
  return `${base}/chat/sessions/${conversationId}/stream`
}

/**
 * 发起流式对话并手工解析 SSE 帧。
 *
 * 不使用 `EventSource`：它无法发送 POST 请求体，且会自动重连 ——
 * 对话场景下自动重连意味着**重复提问**，是错误行为。
 *
 * @returns 会话被正常结束或失败时 resolve；被 abort 时 reject
 */
export async function streamChat(
  conversationId: string,
  content: string,
  handlers: StreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  const response = await fetch(streamUrl(conversationId), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' },
    body: JSON.stringify({ content }),
    signal,
  })

  if (!response.ok) {
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
  const dataLines: string[] = []

  const flush = () => {
    if (dataLines.length > 0) {
      dispatchEvent(currentEvent, dataLines.join('\n'), handlers)
      dataLines.length = 0
    }
    currentEvent = ''
  }

  for (;;) {
    const { done, value } = await reader.read()
    if (done) {
      break
    }
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
      newlineIndex = buffer.indexOf('\n')
    }
  }
  flush()
}

function dispatchEvent(eventName: string, payload: string, handlers: StreamHandlers): void {
  let parsed: Record<string, unknown>
  try {
    parsed = JSON.parse(payload) as Record<string, unknown>
  } catch {
    // 非法帧直接丢弃，不因单帧损坏中断整个流
    return
  }

  switch (eventName) {
    case 'start':
      handlers.onStart?.(String(parsed.conversationId ?? ''))
      break
    case 'delta':
      handlers.onDelta(String(parsed.content ?? ''))
      break
    case 'done':
      handlers.onDone(String(parsed.content ?? ''))
      break
    case 'error':
      handlers.onError(String(parsed.message ?? 'AI 服务调用失败'))
      break
    default:
      break
  }
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
