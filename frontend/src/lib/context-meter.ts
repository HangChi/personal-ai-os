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

/** 档位候选的固定值：整齐、好认，覆盖主流模型的窗口尺寸 */
const BASE_PRESETS = [32_768, 65_536, 131_072, 524_288, 1_048_576]

/** 预算下限，与后端 `ContextBudgetService` 的 `>= 1` 与窗口 CHECK 的 1024 对齐 */
const MIN_PRESET = 1024

/**
 * 该模型窗口下可选的预算档位（升序）。
 *
 * <h3>为什么补一档「窗口的一半」</h3>
 * 窗口是 128K 时，固定档位里天生就有 32K/64K/128K 三档，够用。
 * 但窗口变成每个模型各自之后，切到 32K 的模型（`qwen-max`）时候选只剩
 * 「32K」一项 —— 菜单弹出来只有一个数字，点它等于什么都不做，看起来像坏了。
 *
 * <p>补一半而不再往下细分：预算的意义是「留出回复空间」，一半已经足够保守；
 * 再列 8K/4K 会让菜单变成一份需要思考的列表，而用户通常只要「默认」或「用满」。
 *
 * @param contextWindow 模型窗口（token）；<= 0 表示未知，返回空数组
 * @returns 升序、去重、全部 <= 窗口的档位
 */
export function budgetPresetsFor(contextWindow: number): number[] {
  if (!Number.isFinite(contextWindow) || contextWindow <= 0) {
    return []
  }
  // 满窗保留精确值（例如 128000），不能悄悄丢掉零头。
  const full = Math.floor(contextWindow)
  const half = Math.floor(full / 2 / MIN_PRESET) * MIN_PRESET
  const candidates = [...BASE_PRESETS, full, half]
  return [...new Set(candidates)].filter((value) => value >= MIN_PRESET && value <= contextWindow).sort((a, b) => a - b)
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
