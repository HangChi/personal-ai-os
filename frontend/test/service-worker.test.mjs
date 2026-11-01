import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import { eq } from './assert.mjs'

const handlers = new Map()
const notices = []
const opened = []
const navigated = []
let available = []
const context = {
  URL,
  self: {
    addEventListener: (name, handler) => handlers.set(name, handler),
    skipWaiting: () => Promise.resolve(),
    location: { origin: 'https://example.test' },
    registration: { showNotification: async (title, options) => notices.push({ title, options }) },
  },
  clients: {
    claim: () => Promise.resolve(),
    matchAll: async () => available,
    openWindow: async (url) => opened.push(url),
  },
}
runInNewContext(readFileSync(new URL('../public/sw.js', import.meta.url), 'utf8'), context)

async function emit(name, data) {
  let finished
  handlers.get(name)({ ...data, waitUntil: (promise) => (finished = promise) })
  await finished
}

await emit('push', { data: { json: () => ({ title: '会议', body: '到点', tag: 'r1', url: 'https://evil.test/' }) } })
eq('推送显示提醒标题', notices[0].title, '会议')
eq('推送使用提醒 id 去重', notices[0].options.tag, 'r1')
eq('通知跳转固定在本应用', notices[0].options.data.url, '/calendar')
await emit('push', {
  data: {
    json: () => {
      throw new Error('bad JSON')
    },
  },
})
eq('异常负载仍显示可打开的通知', notices[1].title, '到点提醒')
let closed = false
await emit('notificationclick', { notification: { close: () => (closed = true) } })
eq('点击关闭通知', closed, true)
eq('没有页面时打开日程页', opened[0], 'https://example.test/calendar')
let focused = false
available = [
  {
    url: 'https://example.test/tasks',
    navigate: async (url) => navigated.push(url),
    focus: async () => (focused = true),
  },
]
await emit('notificationclick', { notification: { close: () => {} } })
eq('已有页面时复用页面', navigated[0], 'https://example.test/calendar')
eq('已有页面聚焦', focused, true)
eq('复用页面时不额外开窗口', opened.length, 1)
