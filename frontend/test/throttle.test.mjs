/* 渲染节流（src/lib/throttle.ts）的纯逻辑测试 */
import { check, eq } from './assert.mjs'
import { createThrottle, DEFAULT_INTERVAL_MS } from '../src/lib/throttle.ts'

/**
 * 可控时间源：测试推进它而不是等真实毫秒。
 *
 * <p>用 `node:test` 的 fake timer 也行，但本仓库的测试统一是「顶层直接跑断言」
 * 的极简风格（见 assert.mjs），注入 `now` 是最小改动的做法 —— 生产代码本来
 * 就接受时间源参数，不需要为测试额外开洞。
 */
function fakeClock() {
  let current = 0
  return {
    now: () => current,
    advance(ms) {
      current += ms
    },
  }
}

// ---- 首次执行 ----
{
  const clock = fakeClock()
  const throttle = createThrottle(80, clock.now)
  const seen = []

  throttle.push('第一帧', (value) => seen.push(value))

  check('首次 push 立即执行：首 token 不该先干等一个窗口', seen.length === 1 && seen[0] === '第一帧')
  check('首次 push 之后没有挂起值', throttle.hasPending === false)
}

// ---- 窗口内合并 ----
{
  const clock = fakeClock()
  const throttle = createThrottle(80, clock.now)
  const seen = []

  throttle.push('a', (value) => seen.push(value))
  clock.advance(10)
  throttle.push('b', (value) => seen.push(value))
  clock.advance(10)
  throttle.push('c', (value) => seen.push(value))

  check('窗口内只有首帧落地，b/c 被合并', seen.length === 1 && seen[0] === 'a')
  check('被合并的值处于挂起状态', throttle.hasPending === true)

  throttle.flush()
  // 落地的是最新的 c 而不是 b：中间值本就会被覆盖，没有保留价值
  check('flush 落地的是最新值 c', seen.length === 2 && seen[1] === 'c')
  check('flush 之后无挂起值', throttle.hasPending === false)
}

// ---- 窗口结束后自动恢复 ----
{
  const clock = fakeClock()
  const throttle = createThrottle(80, clock.now)
  const seen = []

  throttle.push('a', (value) => seen.push(value))
  clock.advance(80)
  throttle.push('b', (value) => seen.push(value))

  check('窗口结束后下一个 push 立即执行（无需手动 flush）', seen.length === 2 && seen[1] === 'b')
}

// ---- flush 的边界 ----
{
  const clock = fakeClock()
  const throttle = createThrottle(80, clock.now)
  const seen = []

  throttle.push('a', (value) => seen.push(value))
  throttle.flush()
  throttle.flush()

  check('flush 在无挂起值时安全且幂等', seen.length === 1)
}

{
  const clock = fakeClock()
  const throttle = createThrottle(80, clock.now)
  const seen = []

  throttle.push('a', (value) => seen.push(value))
  clock.advance(5)
  throttle.push('b', (value) => seen.push(value))
  throttle.flush()
  check('flush 先把挂起值落地', seen.length === 2 && seen[1] === 'b')

  // 若 lastRunAt 没被重置，这里会被判为「窗口内」而不执行
  throttle.push('c', (value) => seen.push(value))
  check('flush 之后窗口重置：下一次 push 立即执行', seen.length === 3 && seen[2] === 'c')
}

// ---- cancel ----
{
  const clock = fakeClock()
  const throttle = createThrottle(80, clock.now)
  const seen = []

  throttle.push('a', (value) => seen.push(value))
  clock.advance(10)
  throttle.push('b', (value) => seen.push(value))
  throttle.cancel()

  throttle.push('c', (value) => seen.push(value))

  check('cancel 后既不该落地挂起值，也不该接受新值', seen.length === 1 && seen[0] === 'a')
  check('cancel 后无挂起值', throttle.hasPending === false)
}

// ---- 定时器路径 ----
/*
 * 上面所有断言走的都是同步分支（立即执行 / 手动 flush），setTimeout 的回调
 * 一次都没被执行过 —— 也就是说「窗口到点自动落地」这条主路径原本是未覆盖的。
 * 这里用真实时间等一次，把那条路径也亮到测试下。
 *
 * 窗口取 30ms、等 200ms：余量足够宽，CI 慢也不至于误判成「没落地」。
 */
{
  const throttle = createThrottle(30)
  const seen = []

  throttle.push('a', (value) => seen.push(value))
  throttle.push('b', (value) => seen.push(value))

  check('定时器尚未触发前只有首帧', seen.length === 1)

  await new Promise((resolve) => setTimeout(resolve, 200))

  check('窗口到点后挂起值自动落地（无需外部 flush）', seen.length === 2 && seen[1] === 'b')
  check('自动落地后不再有挂起值', throttle.hasPending === false)
}

// ---- 常量 ----
eq('默认窗口 80ms：改这个值要意识到它在控制渲染帧率', DEFAULT_INTERVAL_MS, 80)
