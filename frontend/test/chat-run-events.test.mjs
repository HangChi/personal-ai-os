import { followRun } from '@/api/chat-runs'
import { eq } from './assert.mjs'

const initial = { id: 'stream', conversationId: 'session', status: 'RUNNING', events: [] }
const event = { id: 1, name: 'delta', payload: JSON.stringify({ content: '你好' }) }
const frames = [
  { ...initial, events: [event] },
  {
    ...initial,
    status: 'COMPLETED',
    events: [event, { id: 2, name: 'done', payload: JSON.stringify({ content: '你好' }) }],
  },
]
const encoder = new TextEncoder()
const calls = []
globalThis.fetch = async (url, options) => {
  calls.push({ url, options })
  const frame = `: ping\r\n\r\nevent:run\r\ndata:${JSON.stringify(frames[calls.length - 1])}\r\n\r\n`
  return new Response(
    new ReadableStream({
      start(controller) {
        const bytes = encoder.encode(frame)
        controller.enqueue(bytes.slice(0, 17))
        controller.enqueue(bytes.slice(17))
        controller.close()
      },
    }),
    { headers: { 'Content-Type': 'text/event-stream' } },
  )
}
const chunks = []
let completed = 0
await followRun(
  initial,
  {
    onRun() {},
    onActivity() {},
    onReset() {},
    onConnection() {},
    onDelta(value) {
      chunks.push(value)
    },
    onDone() {
      completed++
    },
    onError() {},
  },
  new AbortController().signal,
)
eq('断线后重连只读事件流', calls.length, 2)
eq('重连携带最后持久化游标', calls[1].url.endsWith('/events?after=1'), true)
eq('重连不重复输出已处理增量', chunks.join(''), '你好')
eq('处理终态后结束连接', completed, 1)
eq(
  '事件流请求不提交消息',
  calls.every(({ options }) => !options.method || options.method === 'GET'),
  true,
)
