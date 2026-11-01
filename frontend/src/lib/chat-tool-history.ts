import type { ChatMessage, ToolInvocation } from '@/types/api'

/** Associate audit records with their own reply, retaining only the latest attempt in each turn. */
export function toolInvocationsByMessage(
  messages: ChatMessage[],
  invocations: ToolInvocation[],
): Map<number, ToolInvocation[]> {
  const result = new Map<number, ToolInvocation[]>()
  let user: ChatMessage | undefined
  let replies: ChatMessage[] = []

  function finish(): void {
    if (!user || replies.length === 0) return
    const start = Date.parse(user.createdAt)
    const end = Date.parse(replies[replies.length - 1]!.createdAt)
    if (!Number.isFinite(start) || !Number.isFinite(end)) return
    const candidates = invocations
      .filter((item) => {
        const time = Date.parse(item.createdAt)
        return time >= start && time <= end
      })
      .sort((a, b) => Date.parse(b.createdAt) - Date.parse(a.createdAt) || b.id - a.id)
    const latest = candidates[0]
    if (!latest) return
    const turn = candidates.filter((item) => (item.turnId || null) === (latest.turnId || null)).reverse()
    const reply = replies.find((message) => Date.parse(message.createdAt) >= Date.parse(latest.createdAt))
    if (reply) result.set(reply.id, turn)
  }

  for (const message of messages) {
    if (message.role === 'USER') {
      finish()
      user = message
      replies = []
    } else if (message.role === 'ASSISTANT') {
      replies.push(message)
    }
  }
  finish()
  return result
}

/** Live approval feedback is separate from the immutable text generated at the time. */
export function confirmationFeedback(items: ToolInvocation[]): string | null {
  const approvals = items.filter((item) => item.outcome === 'REQUIRES_CONFIRMATION')
  if (!approvals.length || approvals.some((item) => !item.confirmationStatus || item.confirmationStatus === 'PENDING'))
    return null
  if (approvals.every((item) => item.confirmationStatus === 'CONFIRMED')) return '人工确认已完成'
  const labels = { CONFIRMED: '已确认', CANCELLED: '已取消', EXPIRED: '已过期', FAILED: '执行失败' }
  const counts = new Map<string, number>()
  for (const item of approvals) {
    const label = labels[item.confirmationStatus as keyof typeof labels]
    if (label) counts.set(label, (counts.get(label) ?? 0) + 1)
  }
  return [...counts].map(([label, count]) => `${label} ${count} 项`).join('，')
}
