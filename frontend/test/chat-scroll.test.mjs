import { check, eq } from './assert.mjs'

/**
 * 对话区滚动锚定的边界。
 *
 * 这两条判据都属于「错了不报错，只让用户难受」：
 * 自动滚底判断错 → 用户翻历史时被每帧拽回底部；
 * 前插补偿算错 → 点「加载更早」视野直接跳走。
 * 放进视图里时无法测试（happy-dom 的 scrollHeight 恒为 0），所以抽成了纯函数。
 */

const { isNearBottom, scrollDeltaForPrepend, NEAR_BOTTOM_THRESHOLD_PX } = await import('@/lib/chat-scroll')

function metrics(scrollHeight, scrollTop, clientHeight) {
  return { scrollHeight, scrollTop, clientHeight }
}

// ---- 是否在底部 ----

eq('正好贴底 → 在底部', isNearBottom(metrics(2000, 1000, 1000)), true)
eq('离底 58px → 仍在底部（阈值内）', isNearBottom(metrics(2000, 942, 1000)), true)
eq('离底正好等于阈值 → 仍算在底部', isNearBottom(metrics(2000, 2000 - 1000 - NEAR_BOTTOM_THRESHOLD_PX, 1000)), true)
eq('离底刚超阈值 → 已离开底部', isNearBottom(metrics(2000, 2000 - 1000 - 101, 1000)), false)
eq('用户上滑一屏 → 不在底部', isNearBottom(metrics(5000, 0, 1000)), false)
eq('内容比容器短（无滚动条）→ 视为在底部', isNearBottom(metrics(300, 0, 1000)), true)
eq('阈值可注入，便于其它场景复用', isNearBottom(metrics(2000, 900, 1000), 200), true)

// ---- 前插补偿 ----

eq('前插 300px → 视口要下移 300px', scrollDeltaForPrepend(2000, 2300), 300)
eq('高度没变 → 不补偿', scrollDeltaForPrepend(2000, 2000), 0)
eq('内容变短 → 返回负值，交给调用方原样相加', scrollDeltaForPrepend(2300, 2000), -300)

// 组合：前插后把 delta 加上去，视野相对内容的偏移必须保持不变
{
  const before = metrics(2000, 500, 1000)
  const after = metrics(2300, 500, 1000)
  const delta = scrollDeltaForPrepend(before.scrollHeight, after.scrollHeight)
  const restored = { ...after, scrollTop: after.scrollTop + delta }
  check('前插后补偿：距顶部偏移不变（用户看到的那一行没动）', restored.scrollTop - 0 === before.scrollTop + 300)
  check('前插后补偿：仍不在底部，不会被自动滚底钩子拽走', !isNearBottom(restored))
}
