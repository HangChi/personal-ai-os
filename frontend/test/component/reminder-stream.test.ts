import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { subscribeReminders } from '@/notifications/reminder-stream'

class FakeEventSource extends EventTarget {
  static instances: FakeEventSource[] = []
  onopen: (() => void) | null = null
  onerror: (() => void) | null = null
  close = vi.fn()
  constructor(
    readonly url: string,
    readonly options: EventSourceInit,
  ) {
    super()
    FakeEventSource.instances.push(this)
  }
  message(value: unknown) {
    this.dispatchEvent(new MessageEvent('reminders', { data: JSON.stringify(value) }))
  }
}

const reminder = {
  id: 'r1',
  sourceId: 's1',
  sourceType: 'TASK',
  title: '提醒',
  status: 'PENDING',
  readAt: null,
  remindAt: '2026-11-02T08:00:00Z',
  occurrenceAt: '2026-11-02T08:00:00Z',
}
let stop: (() => void) | undefined

beforeEach(() => {
  FakeEventSource.instances = []
  vi.stubGlobal('EventSource', FakeEventSource)
  vi.useFakeTimers()
})
afterEach(() => {
  stop?.()
  stop = undefined
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('提醒 SSE 连接', () => {
  it('带会话凭据连接，接收完整快照及空快照', () => {
    const received = vi.fn()
    const connected = vi.fn()
    stop = subscribeReminders(received, connected)
    const source = FakeEventSource.instances[0]!
    expect(source.url).toBe('/api/v1/reminders/events')
    expect(source.options.withCredentials).toBe(true)
    source.onopen!()
    source.message([reminder])
    source.message([])
    expect(connected).toHaveBeenLastCalledWith(true)
    expect(received.mock.calls).toEqual([[[reminder]], [[]]])
  })

  it('断线关闭旧连接并退避重试，旧连接的晚到消息被忽略', async () => {
    const received = vi.fn()
    const connected = vi.fn()
    stop = subscribeReminders(received, connected)
    const first = FakeEventSource.instances[0]!
    first.onerror!()
    expect(first.close).toHaveBeenCalledOnce()
    expect(connected).toHaveBeenLastCalledWith(false)
    first.message([reminder])
    expect(received).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1000)
    expect(FakeEventSource.instances).toHaveLength(2)
    FakeEventSource.instances[1]!.onerror!()
    await vi.advanceTimersByTimeAsync(1999)
    expect(FakeEventSource.instances).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1)
    expect(FakeEventSource.instances).toHaveLength(3)
    FakeEventSource.instances[2]!.onopen!()
    FakeEventSource.instances[2]!.message([reminder])
    expect(received).toHaveBeenCalledExactlyOnceWith([reminder])
  })

  it.each([{}, [{ id: 'incomplete' }], [{ ...reminder, status: 'READ' }], [{ ...reminder, remindAt: 'bad' }]])(
    '异常快照不会清空已有提醒，触发降级重连：%j',
    (invalid) => {
      const received = vi.fn()
      const connected = vi.fn()
      stop = subscribeReminders(received, connected)
      FakeEventSource.instances[0]!.message(invalid)
      expect(received).not.toHaveBeenCalled()
      expect(connected).toHaveBeenLastCalledWith(false)
    },
  )

  it('退出后取消重连，忽略所有晚到回调', async () => {
    const received = vi.fn()
    const connected = vi.fn()
    stop = subscribeReminders(received, connected)
    const first = FakeEventSource.instances[0]!
    first.onerror!()
    stop()
    connected.mockClear()
    first.onopen!()
    first.message([reminder])
    await vi.advanceTimersByTimeAsync(120_000)
    expect(FakeEventSource.instances).toHaveLength(1)
    expect(received).not.toHaveBeenCalled()
    expect(connected).not.toHaveBeenCalled()
  })

  it('不支持 EventSource 时可安全启动及停止，由调用方轮询兜底', () => {
    vi.stubGlobal('EventSource', undefined)
    stop = subscribeReminders(vi.fn(), vi.fn())
    expect(FakeEventSource.instances).toHaveLength(0)
    expect(() => stop!()).not.toThrow()
  })
})
