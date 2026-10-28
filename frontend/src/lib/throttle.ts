/**
 * 渲染节流：让「数据更新频率」与「DOM 更新频率」解耦。
 *
 * <h2>为什么需要</h2>
 * 流式对话里每个 SSE delta 都会更新一次内容。若直接把它绑到 Markdown 渲染，
 * 模型每秒吐几十个 delta 就等于每秒执行几十次 `marked.parse` + `DOMPurify.sanitize`
 * + 整块 v-html 重写。实测这类开销在长回答上足以让输入框卡顿 ——
 * 而人眼根本读不出「每 16ms 变一次」和「每 80ms 变一次」的区别。
 *
 * <h2>为什么不直接用 lodash.throttle</h2>
 * 这里要的不只是「限制频率」，还要两条容易漏掉的语义：
 * <ol>
 *   <li><b>尾帧必须落地</b>：节流窗口内最后一帧不能被吞掉，否则流结束后
 *       屏幕上的内容会比实际收到的少一截（看着像「回答被截断」）。</li>
 *   <li><b>可以立刻冲刷</b>：流结束时必须能把最后的状态一次性推上去，
 *       而不是还要等一个窗口。</li>
 * </ol>
 * 为此写成一个小对象而不是套用现成 throttle，逻辑全部可单测。
 *
 * <h2>时间源为什么要可注入</h2>
 * 单测里等真实毫秒会让用例变慢且不稳（CI 上偶发超时）。把 `now` 作为参数传入，
 * 测试就能直接推进时间轴，断言「哪个时刻该刷、哪个时刻不该刷」。
 */

/** 默认节流窗口（毫秒）。80ms ≈ 12fps，流式文字够顺，又比逐 delta 少一个量级 */
export const DEFAULT_INTERVAL_MS = 80

/** 节流器接口 */
export interface Throttle<T> {
  /** 推一个新值；窗口外立即执行，窗口内合并到最后一帧 */
  push(value: T, apply: (value: T) => void): void
  /** 立刻冲刷挂起的值并重置窗口（流结束时必须调用） */
  flush(): void
  /** 丢弃挂起值并停止接受新值（组件卸载时用） */
  cancel(): void
  /** 是否有挂起的值尚未推进 */
  readonly hasPending: boolean
}

interface Pending<T> {
  value: T
  apply: (value: T) => void
}

/**
 * 创建一个节流器。
 *
 * @param intervalMs 节流窗口
 * @param now 取当前时间（测试注入，默认 `Date.now`）
 */
export function createThrottle<T>(
  intervalMs: number = DEFAULT_INTERVAL_MS,
  now: () => number = () => Date.now(),
): Throttle<T> {
  /** 最近一次真正执行的时间；null 表示还没执行过 */
  let lastRunAt: number | null = null
  /** 窗口内挂起的值；null 表示没有待推进的内容 */
  let pending: Pending<T> | null = null
  /** 窗口结束时要触发的定时器 */
  let timer: ReturnType<typeof setTimeout> | null = null
  let cancelled = false

  function clearTimer(): void {
    if (timer !== null) {
      clearTimeout(timer)
      timer = null
    }
  }

  function push(value: T, apply: (value: T) => void): void {
    if (cancelled) {
      return
    }
    const current = now()
    // 首次调用立即执行：首 token 该立刻出现在屏幕上，不该先干等 80ms
    if (lastRunAt === null || current - lastRunAt >= intervalMs) {
      clearTimer()
      lastRunAt = current
      pending = null
      apply(value)
      return
    }

    // 窗口内：只记下最新值（中间值本就会被覆盖，没有保留价值）
    pending = { value, apply }
    if (timer !== null) {
      // 已有定时器在等同一个窗口结束，不重复注册
      return
    }
    timer = setTimeout(
      () => {
        timer = null
        // 到点时可能已经 flush 过（pending 被清空），这里再判一次
        if (cancelled || pending === null) {
          return
        }
        const next: Pending<T> = pending
        pending = null
        lastRunAt = now()
        next.apply(next.value)
      },
      intervalMs - (current - (lastRunAt ?? current)),
    )
  }

  /**
   * 立刻冲刷挂起的值并重置窗口。
   *
   * <p>流结束时必须调用：否则最后几十毫秒收到的内容要等定时器，而这些定时器
   * 在组件卸载时会被 cancel —— 屏幕就永远停在少一截的状态。
   */
  function flush(): void {
    clearTimer()
    if (pending === null) {
      lastRunAt = null
      return
    }
    const next: Pending<T> = pending
    pending = null
    lastRunAt = null
    next.apply(next.value)
  }

  /** 取消：丢弃挂起的值（组件卸载时用，避免定时器在卸载后写状态） */
  function cancel(): void {
    cancelled = true
    clearTimer()
    pending = null
    lastRunAt = null
  }

  return {
    push,
    flush,
    cancel,
    get hasPending(): boolean {
      return pending !== null
    },
  }
}
