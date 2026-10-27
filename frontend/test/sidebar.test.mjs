import { check, eq } from './assert.mjs'

/**
 * 侧栏拖拽调宽（阶段 18 追加）。
 *
 * 断言口径刻意围绕「边界」而不是「典型值」：拖拽这类交互的 bug 几乎全部
 * 出现在两端（拖不动 / 拖飞）和脏存储（手改 localStorage）上。
 */

const {
  clampSidebarWidth,
  parseStoredSidebarWidth,
  resolveSidebarMaxWidth,
  stepSidebarWidth,
  SIDEBAR_DEFAULT_WIDTH,
  SIDEBAR_KEYBOARD_STEP,
  SIDEBAR_MAX_WIDTH,
  SIDEBAR_MIN_CONTENT_WIDTH,
  SIDEBAR_MIN_WIDTH,
} = await import('@/lib/sidebar')

// ---- 常量的自洽性：改动其中任何一个都会打破下面几条断言，先钉住关系 ----
eq('默认宽度落在合法区间内', clampSidebarWidth(SIDEBAR_DEFAULT_WIDTH, 1440), SIDEBAR_DEFAULT_WIDTH)
check('默认宽度大于下限', SIDEBAR_DEFAULT_WIDTH > SIDEBAR_MIN_WIDTH)
check('上限大于下限', SIDEBAR_MAX_WIDTH > SIDEBAR_MIN_WIDTH)
check('键盘步长为正', SIDEBAR_KEYBOARD_STEP > 0)

// ---- resolveSidebarMaxWidth：视口感知 ----

eq('宽视口取固定上限 420', resolveSidebarMaxWidth(1440), SIDEBAR_MAX_WIDTH)
eq('缺省视口取固定上限', resolveSidebarMaxWidth(undefined), SIDEBAR_MAX_WIDTH)
eq('非法视口（NaN）取固定上限', resolveSidebarMaxWidth(Number.NaN), SIDEBAR_MAX_WIDTH)
eq('非法视口（负数）取固定上限', resolveSidebarMaxWidth(-100), SIDEBAR_MAX_WIDTH)

// 1000 - 480 = 520 > 420，仍取 420；即「临界视口」= 420 + 480 = 900
eq('临界视口 900 仍是 420', resolveSidebarMaxWidth(SIDEBAR_MAX_WIDTH + SIDEBAR_MIN_CONTENT_WIDTH), SIDEBAR_MAX_WIDTH)
eq('临界视口下一像素开始收紧', resolveSidebarMaxWidth(899), 899 - SIDEBAR_MIN_CONTENT_WIDTH)
eq('视口 768 → 上限 288', resolveSidebarMaxWidth(768), 288)

// 极窄视口：算出来低于下限时以下限为准，不能给出 max < min 的非法区间
eq('极窄视口 500 仍保下限', resolveSidebarMaxWidth(500), SIDEBAR_MIN_WIDTH)

function maxNeverBelowMin() {
  for (let width = 320; width <= 2560; width += 40) {
    if (resolveSidebarMaxWidth(width) < SIDEBAR_MIN_WIDTH) {
      return false
    }
  }
  return true
}
check('任何视口下 max 都不小于 min', maxNeverBelowMin())

// ---- clampSidebarWidth ----

eq('区间内原样', clampSidebarWidth(300, 1440), 300)
eq('低于下限抬到下限', clampSidebarWidth(50, 1440), SIDEBAR_MIN_WIDTH)
eq('高于上限压到上限', clampSidebarWidth(900, 1440), SIDEBAR_MAX_WIDTH)
eq('等于下限保持', clampSidebarWidth(SIDEBAR_MIN_WIDTH, 1440), SIDEBAR_MIN_WIDTH)
eq('等于上限保持', clampSidebarWidth(SIDEBAR_MAX_WIDTH, 1440), SIDEBAR_MAX_WIDTH)
eq('上限被视口压低时按视口', clampSidebarWidth(SIDEBAR_MAX_WIDTH, 840), 840 - SIDEBAR_MIN_CONTENT_WIDTH)
eq('小数四舍五入', clampSidebarWidth(300.6, 1440), 301)

// 脏值一律回落默认：宽度坏掉不该让侧栏消失或铺满屏幕
eq('null 回落默认', clampSidebarWidth(null, 1440), SIDEBAR_DEFAULT_WIDTH)
eq('undefined 回落默认', clampSidebarWidth(undefined, 1440), SIDEBAR_DEFAULT_WIDTH)
eq('NaN 回落默认', clampSidebarWidth(Number.NaN, 1440), SIDEBAR_DEFAULT_WIDTH)
eq('Infinity 回落默认', clampSidebarWidth(Number.POSITIVE_INFINITY, 1440), SIDEBAR_DEFAULT_WIDTH)
eq('负值回落默认', clampSidebarWidth(-20, 1440), SIDEBAR_DEFAULT_WIDTH)
eq('0 回落默认', clampSidebarWidth(0, 1440), SIDEBAR_DEFAULT_WIDTH)
eq('字符串回落默认', clampSidebarWidth('300', 1440), SIDEBAR_DEFAULT_WIDTH)

// 视口极窄时默认值本身也可能越界 → 必须一并收敛，不能返回 224 > max
eq('极窄视口下默认值也被收敛', clampSidebarWidth(SIDEBAR_DEFAULT_WIDTH, 500), SIDEBAR_MIN_WIDTH)

function alwaysInRange() {
  const samples = [null, undefined, Number.NaN, -1, 0, 1, 179, 180, 224, 420, 421, 9999]
  for (const viewport of [500, 768, 900, 1440, undefined]) {
    const max = resolveSidebarMaxWidth(viewport)
    for (const sample of samples) {
      const result = clampSidebarWidth(sample, viewport)
      if (result < SIDEBAR_MIN_WIDTH || result > max || !Number.isInteger(result)) {
        return false
      }
    }
  }
  return true
}
check('收敛结果永远是区间内的整数', alwaysInRange())

// ---- parseStoredSidebarWidth ----

eq('未存过 → 默认值', parseStoredSidebarWidth(null, 1440), SIDEBAR_DEFAULT_WIDTH)
eq('空串 → 默认值', parseStoredSidebarWidth('', 1440), SIDEBAR_DEFAULT_WIDTH)
eq('纯空白 → 默认值', parseStoredSidebarWidth('   ', 1440), SIDEBAR_DEFAULT_WIDTH)
eq('正常存储值正常解析', parseStoredSidebarWidth('320', 1440), 320)
eq('存储值按视口收敛', parseStoredSidebarWidth('420', 840), 360)
eq('手改成文字 → 默认值', parseStoredSidebarWidth('abc', 1440), SIDEBAR_DEFAULT_WIDTH)
eq('手改成负数 → 默认值', parseStoredSidebarWidth('-50', 1440), SIDEBAR_DEFAULT_WIDTH)
eq('存了超大值 → 压到上限', parseStoredSidebarWidth('9999', 1440), SIDEBAR_MAX_WIDTH)

// ---- stepSidebarWidth ----

eq('右移一步', stepSidebarWidth(300, SIDEBAR_KEYBOARD_STEP, 1440), 308)
eq('左移一步', stepSidebarWidth(300, -SIDEBAR_KEYBOARD_STEP, 1440), 292)
eq('到上限不再增长', stepSidebarWidth(SIDEBAR_MAX_WIDTH, SIDEBAR_KEYBOARD_STEP, 1440), SIDEBAR_MAX_WIDTH)
eq('到下限不再减小', stepSidebarWidth(SIDEBAR_MIN_WIDTH, -SIDEBAR_KEYBOARD_STEP, 1440), SIDEBAR_MIN_WIDTH)

// 连续步进不应累积误差或越界：32 次 +8 一定停在上限
function stepsStayInRange() {
  let width = SIDEBAR_DEFAULT_WIDTH
  for (let i = 0; i < 100; i += 1) {
    width = stepSidebarWidth(width, SIDEBAR_KEYBOARD_STEP, 1440)
  }
  const atMax = width === SIDEBAR_MAX_WIDTH
  for (let i = 0; i < 100; i += 1) {
    width = stepSidebarWidth(width, -SIDEBAR_KEYBOARD_STEP, 1440)
  }
  return atMax && width === SIDEBAR_MIN_WIDTH
}
check('连续步进夹在两端且能走到极值', stepsStayInRange())
