import { flushPromises } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { useCalendarStore } from '@/stores/calendar'
import type { Reminder } from '@/types/api'

const { subscribeReminders, listReminders, stopStream } = vi.hoisted(() => ({
  subscribeReminders: vi.fn(),
  listReminders: vi.fn(),
  stopStream: vi.fn(),
}))
vi.mock('@/notifications/reminder-stream', () => ({ subscribeReminders }))
vi.mock('@/api/reminders', () => ({ listReminders, markReminderRead: vi.fn(), scanReminders: vi.fn() }))

let snapshot: (reminders: Reminder[]) => void
let connection: (connected: boolean) => void
let store: ReturnType<typeof useCalendarStore>
const reminder: Reminder = {
  id: 'r1',
  sourceId: 's1',
  sourceType: 'TASK',
  title: '提醒',
  status: 'PENDING',
  readAt: null,
  remindAt: '2026-11-02T08:00:00Z',
  occurrenceAt: '2026-11-02T08:00:00Z',
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  listReminders.mockReset().mockResolvedValue([])
  subscribeReminders.mockImplementation((onSnapshot, onConnection) => {
    snapshot = onSnapshot
    connection = onConnection
    return stopStream
  })
  setActivePinia(createPinia())
  store = useCalendarStore()
})
afterEach(() => {
  store.stopReminderPolling()
  vi.useRealTimers()
  vi.restoreAllMocks()
})

describe('提醒实时状态与补查', () => {
  it('晚到的 HTTP 响应不能覆盖较新的 SSE 快照', async () => {
    let finish!: (value: Reminder[]) => void
    listReminders.mockImplementationOnce(
      () =>
        new Promise<Reminder[]>((resolve) => {
          finish = resolve
        }),
    )
    store.startReminderPolling()
    snapshot([reminder])
    finish([])
    await flushPromises()
    expect(store.dueReminders).toEqual([reminder])
    expect(store.inlineNotices).toHaveLength(1)
    snapshot([reminder])
    expect(store.inlineNotices).toHaveLength(1)
    snapshot([])
    expect(store.dueReminders).toHaveLength(0)
  })

  it('连接正常时减少轮询，每两分钟校准一次', async () => {
    store.startReminderPolling()
    connection(true)
    await flushPromises()
    await vi.advanceTimersByTimeAsync(90_000)
    expect(listReminders).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(listReminders).toHaveBeenCalledTimes(2)
  })

  it('断线时立即补查，随后每 30 秒兜底', async () => {
    store.startReminderPolling()
    await flushPromises()
    connection(false)
    await flushPromises()
    expect(listReminders).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(30_000)
    expect(listReminders).toHaveBeenCalledTimes(3)
  })

  it('回到前台立即补查，不等待下一次快照变化', async () => {
    store.startReminderPolling()
    connection(true)
    await flushPromises()
    vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushPromises()
    expect(listReminders).toHaveBeenCalledTimes(2)
  })

  it('停止时清理连接和计时器，晚到的快照及 HTTP 响应不能重新弹框', async () => {
    let finish!: (value: Reminder[]) => void
    listReminders.mockImplementationOnce(
      () =>
        new Promise<Reminder[]>((resolve) => {
          finish = resolve
        }),
    )
    store.startReminderPolling()
    const lateSnapshot = snapshot
    store.stopReminderPolling()
    lateSnapshot([reminder])
    finish([reminder])
    await flushPromises()
    await vi.advanceTimersByTimeAsync(120_000)
    expect(stopStream).toHaveBeenCalledOnce()
    expect(listReminders).toHaveBeenCalledTimes(1)
    expect(store.dueReminders).toHaveLength(0)
    expect(store.inlineNotices).toHaveLength(0)
  })

  it('重复启动不重复订阅，重启后旧请求不覆盖新连接', async () => {
    let finish!: (value: Reminder[]) => void
    listReminders.mockImplementationOnce(
      () =>
        new Promise<Reminder[]>((resolve) => {
          finish = resolve
        }),
    )
    store.startReminderPolling()
    store.startReminderPolling()
    expect(subscribeReminders).toHaveBeenCalledTimes(1)
    const oldSnapshot = snapshot
    store.stopReminderPolling()
    store.startReminderPolling()
    await flushPromises()
    snapshot([reminder])
    oldSnapshot([])
    finish([])
    await flushPromises()
    expect(store.dueReminders).toEqual([reminder])
    expect(subscribeReminders).toHaveBeenCalledTimes(2)
  })
})
