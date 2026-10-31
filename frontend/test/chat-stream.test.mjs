import { check, eq } from './assert.mjs'
import { resetUnauthorized, unauthorizedCallCount } from './stub-api.mjs'
import { streamChat } from '@/api/chat'

/**
 * 流式对话客户端测试（2026-10-27）。
 *
 * 覆盖三件在浏览器里极难复现、但线上一定会遇到的事：
 * 1. SSE 帧解析 —— 含服务端心跳的注释帧，以及被 TCP 拆开的不完整帧；
 * 2. 401 是否与普通请求走同一条登出链路（流式用原生 fetch，绕过 axios 拦截器）；
 * 3. 连接被中间设备静默掐断时的空闲超时，以及「用户主动停止」不能被误报成超时。
 */

const encoder = new TextEncoder()

/** 假响应：按帧依次喂出，帧消费完后结束 */
function responseOf(frames) {
  let index = 0
  return {
    ok: true,
    status: 200,
    body: {
      getReader() {
        return {
          read() {
            if (index >= frames.length) {
              return Promise.resolve({ done: true, value: undefined })
            }
            return Promise.resolve({ done: false, value: encoder.encode(frames[index++]) })
          },
        }
      },
    },
  }
}

/**
 * 假响应：连接建立后永远不再有字节。
 *
 * 这模拟的是「TCP 没收到 RST 的静默断连」—— 真实的 fetch 在这种情况下的
 * read() 既不 resolve 也不 reject，只有 abort 才能让它失败。
 */
function hangingResponse(signal) {
  return {
    ok: true,
    status: 200,
    body: {
      getReader() {
        return {
          read() {
            return new Promise((_resolve, reject) => {
              signal.addEventListener('abort', () => {
                reject(new DOMException('aborted', 'AbortError'))
              })
            })
          },
        }
      },
    },
  }
}

/** 收集所有回调，便于逐项断言 */
function recorder() {
  const log = { started: null, deltas: [], done: null, error: null }
  return {
    log,
    handlers: {
      onStart: (id) => {
        log.started = id
      },
      onDelta: (chunk) => {
        log.deltas.push(chunk)
      },
      onDone: (content, context) => {
        log.done = { content, promptTokens: context?.promptTokens }
      },
      onError: (message) => {
        log.error = message
      },
    },
  }
}

let fetchImpl = null
globalThis.fetch = (url, init) => fetchImpl(url, init)

// ---------- 1. 正常流：注释帧必须被忽略，事件顺序保持 ----------
fetchImpl = () =>
  responseOf([
    'event: start\ndata: {"conversationId":"c-1"}\n\n',
    // 服务端每 15 秒发一次的心跳：SSE 规范要求客户端忽略注释帧
    ': ping\n\n',
    'event: delta\ndata: {"content":"你"}\n\n',
    'event: delta\ndata: {"content":"好"}\n\n',
    'event: done\ndata: {"conversationId":"c-1","content":"你好","promptTokens":42}\n\n',
  ])

const happy = recorder()
await streamChat('c-1', '打招呼', happy.handlers)
eq('start 事件解析出会话标识', happy.log.started, 'c-1')
eq('delta 累计为完整文本', happy.log.deltas.join(''), '你好')
eq('done 带回复全文', happy.log.done?.content, '你好')
eq('done 带 token 估算', happy.log.done?.promptTokens, 42)
check('心跳注释帧不产生错误回调', happy.log.error === null)

// ---------- 2. 帧被拆到两个 chunk 里也要能拼回来 ----------
fetchImpl = () =>
  responseOf([
    'event: delta\ndata: {"con',
    'tent":"半帧"}\n\n',
    'event: done\ndata: {"content":"半帧","promptTokens":1}\n\n',
  ])

const split = recorder()
await streamChat('c-2', '拆帧', split.handlers)
eq('跨 chunk 的帧被正确拼接', split.log.deltas.join(''), '半帧')

// ---------- 3. 401 必须走全局登出链路 ----------
resetUnauthorized()
fetchImpl = async () => ({
  ok: false,
  status: 401,
  json: async () => ({ message: '请先登录' }),
})

const unauthorized = recorder()
await streamChat('c-3', 'hi', unauthorized.handlers)
eq('401 触发登出通知', unauthorizedCallCount(), 1)
eq('401 仍给出可读文案', unauthorized.log.error, '请先登录')

// ---------- 4. 静默断连：空闲超时给出提示，而不是永远转圈 ----------
fetchImpl = async (_url, init) => hangingResponse(init.signal)

const idle = recorder()
await streamChat('c-4', 'hi', idle.handlers, undefined, { idleTimeoutMs: 20 })
check('空闲超时给出可读提示', typeof idle.log.error === 'string' && idle.log.error.includes('超时'))

// ---------- 5. 用户主动停止不能被误报成超时 ----------
const controller = new AbortController()
fetchImpl = async (_url, init) => hangingResponse(init.signal)

const stopped = recorder()
const pending = streamChat('c-5', 'hi', stopped.handlers, controller.signal, { idleTimeoutMs: 5000 })
setTimeout(() => controller.abort(), 10)

let aborted = false
try {
  await pending
} catch {
  aborted = true
}
check('主动停止时向上抛 AbortError（由 store 静默处理）', aborted)
check('主动停止不产生超时提示', stopped.log.error === null)

// 提前 EOF 与损坏的结束帧都不能当作正常完成。
for (const frames of [[], ['event: delta\ndata: {"content":"半截回答"}\n\n'], ['event: done\ndata: broken-json\n\n']]) {
  fetchImpl = () => responseOf(frames)
  const interrupted = recorder()
  await streamChat('interrupted', 'hi', interrupted.handlers)
  check('提前 EOF 给出中断提示', interrupted.log.error?.includes('中断'))
  eq('提前 EOF 不调用完成回调', interrupted.log.done, null)
}
fetchImpl = () => responseOf(['event: error\ndata: {"message":"上游错误"}\n\n'])
const serverError = recorder()
await streamChat('error', 'hi', serverError.handlers)
eq('已收到服务端错误时保留原始错误', serverError.log.error, '上游错误')
