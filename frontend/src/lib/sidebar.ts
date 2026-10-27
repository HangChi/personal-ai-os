/**
 * 侧栏拖拽调宽的纯函数（阶段 18 追加，与「UI 优化」同批）。
 *
 * 与 store 的分界同 `theme.ts` / `settings.ts`：本文件只做「数值 → 合法宽度」的
 * 纯计算，不碰 DOM / localStorage / 事件。事件绑定与持久化分别在 App.vue 与
 * stores/ui.ts，纯函数才能被 .mjs 单测覆盖。
 *
 * 一个必须知道的实现细节：el-aside 的 `width` 属性写的是内联 CSS 变量
 * `--el-aside-width`（组件源码用 ns.cssVarBlock），不是 `width` 属性。
 * 样式表里 `width: var(--el-aside-width, 300px)` —— 想改宽度只能走这个属性，
 * 直接给 .app-shell__aside 写 CSS width 会被内联变量压过。
 *
 * 为什么需要「视口感知的上限」而不只是一个固定 420：
 * 1280 宽的笔记本上拖到 420 正好，但把窗口缩到 900 宽时侧栏仍占 420，
 * 主内容只剩 480 —— 表格直接横向溢出。所以上限取 min(420, 视口 - 内容保底宽)。
 */

/** 拖拽下限：再窄就放不下「工作区 / 系统」分组标题 + 菜单文字（实测 180 刚好不换行） */
export const SIDEBAR_MIN_WIDTH = 180
/** 拖拽上限（视口足够宽时） */
export const SIDEBAR_MAX_WIDTH = 420
/** 默认宽度：与 tokens.css 注释里的 224px 一致 */
export const SIDEBAR_DEFAULT_WIDTH = 224
/** 键盘上下方向键的步长 */
export const SIDEBAR_KEYBOARD_STEP = 8
/** 主内容保底宽度：侧栏再宽也不能把内容区挤到这条线以下 */
export const SIDEBAR_MIN_CONTENT_WIDTH = 480
/** localStorage 键名（`os.nav.*` 命名空间与 os.nav.collapsed 同族） */
export const SIDEBAR_WIDTH_STORAGE_KEY = 'os.nav.width'

function isPositiveNumber(value: unknown): value is number {
  return typeof value === 'number' && Number.isFinite(value) && value > 0
}

/**
 * 按视口算当前允许的最大宽度。
 *
 * @param viewportWidth 视口宽（px）；缺省 / 非法值（SSR、单测未传）时退回固定上限
 */
export function resolveSidebarMaxWidth(viewportWidth?: number): number {
  if (!isPositiveNumber(viewportWidth)) {
    return SIDEBAR_MAX_WIDTH
  }
  const byViewport = Math.round(viewportWidth - SIDEBAR_MIN_CONTENT_WIDTH)
  // 视口极窄时 byViewport 会小于下限——此时以下限为准，宁可挤内容也不给出非法区间
  return Math.max(SIDEBAR_MIN_WIDTH, Math.min(SIDEBAR_MAX_WIDTH, byViewport))
}

/**
 * 把任意值收敛成合法侧栏宽度。
 *
 * 脏值（手改 localStorage / 旧版本残留 / NaN）一律回落默认值：
 * 宽度坏掉不该让侧栏消失或铺满屏幕。
 */
export function clampSidebarWidth(value: unknown, viewportWidth?: number): number {
  const max = resolveSidebarMaxWidth(viewportWidth)
  if (!isPositiveNumber(value)) {
    return Math.min(Math.max(SIDEBAR_DEFAULT_WIDTH, SIDEBAR_MIN_WIDTH), max)
  }
  return Math.min(Math.max(Math.round(value), SIDEBAR_MIN_WIDTH), max)
}

/** 解析 localStorage 里存的宽度字符串（存的是十进制整数文本） */
export function parseStoredSidebarWidth(raw: string | null, viewportWidth?: number): number {
  if (raw === null || raw.trim() === '') {
    return clampSidebarWidth(SIDEBAR_DEFAULT_WIDTH, viewportWidth)
  }
  const parsed = Number(raw)
  return clampSidebarWidth(Number.isFinite(parsed) ? parsed : null, viewportWidth)
}

/**
 * 键盘微调：在当前宽度上加减一步。
 *
 * 刻意不复用 {@link clampSidebarWidth} 的舍入而是先算再夹：current 一定是
 * 已收敛值，直接加步长不会累积误差。
 */
export function stepSidebarWidth(current: number, delta: number, viewportWidth?: number): number {
  const base = clampSidebarWidth(current, viewportWidth)
  return clampSidebarWidth(base + delta, viewportWidth)
}
