import { describe, it, expect, vi, afterEach } from 'vitest'
import { followRun, type ChatRun } from '@/api/chat-runs'
import { requestData } from '@/api/http'
vi.mock('@/api/http', () => ({ requestData: vi.fn(), apiBaseUrl: '/api/v1', notifyUnauthorized: vi.fn() }))
const event = (id: number, name: string, payload: object) => ({ id, name, payload: JSON.stringify(payload) })
const callbacks = () => ({
  onRun: vi.fn(),
  onActivity: vi.fn(),
  onReset: vi.fn(),
  onConnection: vi.fn(),
  onDelta: vi.fn(),
  onDone: vi.fn(),
  onError: vi.fn(),
  onWarning: vi.fn(),
})
afterEach(() => {
  vi.useRealTimers()
  vi.resetAllMocks()
  vi.unstubAllGlobals()
})
describe('执行记录追赶', () => {
  it('断线只重读游标后的记录，不重复 token 或提交消息', async () => {
    const initial: ChatRun = {
      id: 'r',
      conversationId: 's',
      status: 'RUNNING',
      events: [event(1, 'delta', { content: '前' })],
    }
    const recovered = {
      ...initial,
      status: 'COMPLETED',
      events: [
        event(1, 'delta', { content: '前' }),
        event(2, 'delta', { content: '后' }),
        event(3, 'done', { content: '前后', promptTokens: 1 }),
      ],
    }
    const fetcher = vi
      .fn()
      .mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce(
        new Response(`event:run\ndata:${JSON.stringify(recovered)}\n\n`, {
          headers: { 'Content-Type': 'text/event-stream' },
        }),
      )
    vi.stubGlobal('fetch', fetcher)
    const handlers = callbacks()
    const pending = followRun(initial, handlers, new AbortController().signal)
    await pending
    expect(handlers.onDelta.mock.calls).toEqual([['前'], ['后']])
    expect(handlers.onDone).toHaveBeenCalledOnce()
    expect(handlers.onConnection).toHaveBeenCalledWith(true)
    expect(fetcher).toHaveBeenCalledTimes(2)
    expect(fetcher.mock.calls.every(([url]) => url.endsWith('/events?after=1'))).toBe(true)
    expect(requestData).not.toHaveBeenCalled()
  })
  it('重放降级提示后仍继续接收回答，不触发终态错误', async () => {
    const handlers = callbacks()
    const warning = { code: 'KNOWLEDGE_RETRIEVAL_FAILED', message: '知识库检索失败，本轮未使用知识库回答' }
    await followRun(
      {
        id: 'r',
        conversationId: 's',
        status: 'COMPLETED',
        events: [
          event(1, 'warning', warning),
          event(2, 'delta', { content: '普通回答' }),
          event(3, 'done', { content: '普通回答', promptTokens: 1 }),
        ],
      },
      handlers,
      new AbortController().signal,
    )
    expect(handlers.onWarning).toHaveBeenCalledExactlyOnceWith(warning)
    expect(handlers.onDelta).toHaveBeenCalledExactlyOnceWith('普通回答')
    expect(handlers.onDone).toHaveBeenCalledOnce()
    expect(handlers.onError).not.toHaveBeenCalled()
  })
  it('恢复边界清空旧临时回答，随后接收新活动', async () => {
    const handlers = callbacks()
    await followRun(
      {
        id: 'r',
        conversationId: 's',
        status: 'COMPLETED',
        events: [
          event(1, 'error', { message: '已中断' }),
          event(2, 'reset', {}),
          event(3, 'activity', { id: 't', tool: 'query', status: 'OK' }),
        ],
      },
      handlers,
      new AbortController().signal,
    )
    expect(handlers.onReset).toHaveBeenCalledOnce()
    expect(handlers.onActivity).toHaveBeenCalledOnce()
  })
  it('等待用户回答是可恢复状态，不再继续轮询模型执行', async () => {
    const handlers = callbacks()
    const run: ChatRun = {
      id: 'r',
      conversationId: 'c',
      status: 'WAITING_USER',
      events: [],
      question: { id: 'q', runId: 'r', question: '你希望用哪个时间范围？', options: [], status: 'PENDING' },
    }
    await followRun(run, handlers, new AbortController().signal)
    expect(handlers.onRun).toHaveBeenCalledWith(run)
    expect(requestData).not.toHaveBeenCalled()
  })
})
