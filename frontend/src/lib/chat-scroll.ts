/**
 * 对话区的滚动锚定。
 *
 * <h2>为什么抽成纯函数</h2>
 * 这段逻辑有两个「写错了不报错、只让用户难受」的判据：
 * <ol>
 *   <li><b>该不该自动滚底</b>：判断错了就是「用户在往上翻历史，每来一个 delta 就被拽回底部」；</li>
 *   <li><b>前插补偿多少</b>：补错了就是「点加载更早，视野直接跳走」。</li>
 * </ol>
 * 而它们在视图里时**无法测试**：`scrollHeight` / `scrollTop` / `clientHeight`
 * 在 happy-dom 里全是 0，组件测试断言不出任何东西（写出来的断言只是「0 < 80」恒真）。
 * 抽成接受「三个数字」的纯函数，边界就能直接钉住。
 *
 * <h2>80px 的来历</h2>
 * 不是「一屏的多少比例」，而是「用户明确往上翻了」的最小体感距离：
 * 流式最后一帧的增量通常只有十几像素，若阈值取得太小（如 10px），
 * 正常的自动滚底会把自己判成「用户已上滑」而停止跟随。80px 大约是三行正文。
 */

/** 离底部多近算「在底部」（像素） */
export const NEAR_BOTTOM_THRESHOLD_PX = 80

/** 滚动容器的三个尺寸（从 DOM 元素读出来即可） */
export interface ScrollMetrics {
  scrollHeight: number
  scrollTop: number
  clientHeight: number
}

/**
 * 当前是否贴近底部。
 *
 * <p>**必须在 DOM 更新之前调用**：新内容一进 DOM，`scrollHeight` 就变大了，
 * 而 `scrollTop` 还是旧值 —— 此时算出的距离会凭空多出一屏，本来在底部也会被判成「不在」。
 */
export function isNearBottom(metrics: ScrollMetrics, threshold: number = NEAR_BOTTOM_THRESHOLD_PX): boolean {
  const distance = metrics.scrollHeight - metrics.scrollTop - metrics.clientHeight
  // 用 <= 而不是 <：正好停在阈值上时，用户其实还没离开底部
  return distance <= threshold
}

/**
 * 前插内容后，需要给 `scrollTop` 加多少，才能让视野停在原来那一行。
 *
 * <p>返回 0 表示高度没变（比如加载回来是空页）。负数在数学上可能出现
 * （内容被压缩），直接原样返回让调用方加上去即可 —— 不做 `Math.max(0, …)`
 * 是刻意的：钳制会让「内容变短」的场景把视野多顶一段。
 */
export function scrollDeltaForPrepend(previousHeight: number, nextHeight: number): number {
  return nextHeight - previousHeight
}
