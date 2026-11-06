import type { ChatRun } from '@/api/chat-runs'
import type { ChatMessage, RagRetrieval } from '@/types/api'

/** 只恢复最后一次 reset 之后已落库的回答，缺少完成事件时宁可不绑定。 */
export function restoreReplyRetrieval(run: ChatRun, messages: ChatMessage[]): void {
  let retrieval: RagRetrieval | null = null
  let content: string | null = null
  for (const event of run.events) {
    try {
      if (event.name === 'reset') {
        retrieval = null
        content = null
      } else if (event.name === 'rag') retrieval = JSON.parse(event.payload) as RagRetrieval
      else if (event.name === 'done') content = (JSON.parse(event.payload) as { content: string }).content
    } catch {
      retrieval = null
      content = null
    }
  }
  bindReplyRetrieval(messages, retrieval, content)
}

export function bindReplyRetrieval(
  messages: ChatMessage[],
  retrieval: RagRetrieval | null,
  content: string | null,
): boolean {
  const reply = messages.at(-1)
  if (!retrieval || !content || reply?.role !== 'ASSISTANT' || reply.content !== content) return false
  reply.retrieval = retrieval
  return true
}
