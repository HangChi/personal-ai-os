import { apiBaseUrl } from '@/api/http'
import type { Reminder } from '@/types/api'

/** 完整未读快照；服务端重启或重连后不需要恢复内存事件游标。 */
export function subscribeReminders(
  onSnapshot: (reminders: Reminder[]) => void,
  onConnection: (connected: boolean) => void,
): () => void {
  let disposed = false
  let source: EventSource | undefined
  let retry: ReturnType<typeof setTimeout> | undefined
  let failures = 0

  function connect(): void {
    if (disposed || typeof EventSource === 'undefined') return
    try {
      const current = new EventSource(`${apiBaseUrl}/reminders/events`, { withCredentials: true })
      source = current
      current.onopen = () => {
        if (disposed || source !== current) return
        failures = 0
        onConnection(true)
      }
      current.onerror = () => {
        if (!disposed && source === current) reconnect()
      }
      current.addEventListener('reminders', (event) => {
        if (disposed || source !== current) return
        try {
          const data: unknown = JSON.parse((event as MessageEvent<string>).data)
          if (!isSnapshot(data)) throw new Error('无效的提醒快照')
          onSnapshot(data)
        } catch {
          reconnect()
        }
      })
    } catch {
      reconnect()
    }
  }

  function reconnect(): void {
    source?.close()
    source = undefined
    onConnection(false)
    if (disposed || retry !== undefined) return
    const delay = Math.min(30_000, 1000 * 2 ** Math.min(failures++, 5))
    retry = setTimeout(() => {
      retry = undefined
      connect()
    }, delay)
  }

  connect()
  return () => {
    disposed = true
    source?.close()
    source = undefined
    if (retry !== undefined) clearTimeout(retry)
  }
}

function isSnapshot(value: unknown): value is Reminder[] {
  return (
    Array.isArray(value) &&
    value.length <= 500 &&
    value.every(
      (item) =>
        item !== null &&
        typeof item === 'object' &&
        typeof item.id === 'string' &&
        typeof item.sourceId === 'string' &&
        typeof item.title === 'string' &&
        (item.sourceType === 'TASK' || item.sourceType === 'CALENDAR_EVENT') &&
        typeof item.remindAt === 'string' &&
        Number.isFinite(Date.parse(item.remindAt)) &&
        typeof item.occurrenceAt === 'string' &&
        Number.isFinite(Date.parse(item.occurrenceAt)) &&
        item.status === 'PENDING' &&
        item.readAt === null,
    )
  )
}
