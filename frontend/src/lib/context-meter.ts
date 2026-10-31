/**
 * 上下文读数的纯函数（V25，从 ContextMeter 抽出）。
 *
 * <h2>为什么抽出来</h2>
 * 这三个计算里有两个是**只在特定数据下才出错**的：
 *
 * <ul>
 *   <li>{@link budgetPresetsFor} —— 窗口变成「每个模型各自」之后，32K 的模型
 *       只剩一个档位，菜单点开等于什么都没做；而 1024 这种极端窗口会让
 *       档位表为空，控件直接消失。</li>
 *   <li>{@link meterLevelOf} —— 85% / 100% 两个阈值，越界判断写错的表现是
 *       「明明超了还是蓝色」，用户完全不会把它报成 bug。</li>
 * </ul>
 *
 * <p>留在组件里就只能靠 DOM 断言，而下拉菜单是 popper 渲染的、在测试环境
 * 根本不挂载 —— 实测 `wrapper.findAll('.el-dropdown-menu__item')` 返回空数组，
 * 三条断言全部失效。纯函数没有任何渲染依赖，改起来也没有心理负担。
 */

/**
 * 每个模型统一提供精简、均衡、完整三档。
 * 前两档按模型窗口的 1/4、1/2 取整到 K；完整档保留安全输入上限。
 * 端点限制低于半窗时，按实际可用上限重新分档，避免重复或越界。
 */
export function budgetPresetsFor(contextWindow: number, inputLimit: number = contextWindow): number[] {
  if (!Number.isFinite(contextWindow) || !Number.isFinite(inputLimit)) return []
  const full = Math.floor(Math.min(contextWindow, inputLimit))
  if (full <= 0) return []

  const rounded = (value: number): number =>
    value >= 1024 ? Math.floor(value / 1024) * 1024 : Math.max(1, Math.floor(value))
  const basis = rounded(contextWindow / 2) < full ? contextWindow : full
  return [...new Set([rounded(basis / 4), rounded(basis / 2), full])]
    .filter((value) => value <= full)
    .sort((a, b) => a - b)
}

/**
 * 已用占预算的百分比，向上取整并截断在 100。
 *
 * <p>向上取整：99.2% 时显示 99 会让人以为「还有 1% 的余量」，
 * 而实际只剩不到 0.8% —— 这个读数的用途就是判断「要不要收尾」，
 * 宁可早一点提醒。
 *
 * @param promptTokens 已用 token
 * @param budgetTokens 预算 token；<= 0 时返回 0
 */
export function meterPercentOf(promptTokens: number, budgetTokens: number): number {
  if (!Number.isFinite(budgetTokens) || budgetTokens <= 0) {
    return 0
  }
  const raw = (Math.max(0, promptTokens) / budgetTokens) * 100
  return Math.min(100, Math.ceil(raw))
}

/** 进度条的语义档位：默认主色 / 警告 / 危险 */
export type MeterLevel = 'normal' | 'warn' | 'over'

/**
 * 按百分比判定档位。
 *
 * <p>阈值 85 / 100：100 是硬边界（超了就要裁剪历史），85 给一个提前量 ——
 * 到 85% 时再聊一两轮就会溢出，那时就该考虑开新会话或压缩。
 */
export function meterLevelOf(percent: number): MeterLevel {
  if (percent >= 100) {
    return 'over'
  }
  if (percent >= 85) {
    return 'warn'
  }
  return 'normal'
}

/**
 * token 可读化：按 1024 进制（32768 → 32K，1048576 → 1M）。
 *
 * <p>用 1000 进制会把 32768 显示成「32.8K」—— 档位名带小数点很难看，
 * 而且与模型文档里写的「32K」对不上，用户会怀疑这两个数不是一回事。
 */
export function formatTokens(value: number): string {
  if (!Number.isFinite(value)) {
    return '0'
  }
  if (value >= 1024 * 1024) {
    const m = value / (1024 * 1024)
    return `${Number.isInteger(m) ? m : m.toFixed(1)}M`
  }
  if (value >= 1024) {
    const k = value / 1024
    return `${Number.isInteger(k) ? k : k.toFixed(1)}K`
  }
  return String(value)
}
