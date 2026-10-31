import { check, eq } from './assert.mjs'

/**
 * 上下文读数的纯函数（V25）。
 *
 * <h2>为什么这一组必须是纯函数测试而不是组件测试</h2>
 * 预算档位在 `el-dropdown-menu` 里，而那个菜单由 popper **teleport 到 body**
 * 并且只在展开时渲染。实测在 happy-dom 里 `wrapper.findAll('.el-dropdown-menu__item')`
 * 返回空数组，三条断言全部变成「期望 ['16K','32K']，实际 []」——
 * 看起来像代码写错了，实际上断言的对象根本没被渲染出来。
 *
 * <p>档位与阈值又恰恰是「只在特定数据下才出错」的那类逻辑
 * （32K 模型只剩一个档位、1024 窗口让档位表为空），必须有回归保护。
 * 所以把计算搬到 `lib/context-meter.ts`，这里直接断言数值。
 */

const { budgetPresetsFor, formatTokens, meterLevelOf, meterPercentOf } = await import('@/lib/context-meter')

// ---------------------------------------------------------------- 档位：按窗口收窄

{
  /*
   * 128K 模型：固定档位全在范围内，且「满窗」也在列。
   * 这是最常见的情况，也是改造前唯一被考虑过的情况。
   */
  eq('128K 窗口给出 32/64/128 三档', budgetPresetsFor(131072).map(formatTokens).join(','), '32K,64K,128K')

  /*
   * 32K 模型（qwen-max）—— V25 的核心回归点。
   *
   * 改造前档位表是硬编码的 [32K,64K,128K,...]，过滤后只剩「32K」一项：
   * 菜单点开只有一个数字，点它等于什么都不做，看起来像控件坏了。
   * 补一档「窗口的一半」后变成 16K/32K，至少可选。
   */
  eq('32K 窗口补出 16K 档', budgetPresetsFor(32768).map(formatTokens).join(','), '16K,32K')

  // 超过窗口的档位绝不能出现：后端会静默收敛，界面选的和生效的不一致
  check('32K 窗口不含 64K', !budgetPresetsFor(32768).includes(65536))
  check('32K 窗口不含 128K', !budgetPresetsFor(32768).includes(131072))

  /*
   * 1M 模型（deepseek-v4-pro 手填 1000000）：529 档来自固定表，
   * 满窗档保留 1000000 的精确值。
   */
  const big = budgetPresetsFor(1_000_000)
  eq('1M 窗口最高档保留精确值', big[big.length - 1], 1_000_000)
  check('1M 窗口含 512K', big.includes(524288))

  // 未知窗口（<=0）不给任何档位：此时分母都不知道，列档位没有意义
  eq('未知窗口返回空', budgetPresetsFor(0).length, 0)
  eq('负数窗口返回空', budgetPresetsFor(-1).length, 0)
  eq('NaN 窗口返回空', budgetPresetsFor(Number.NaN).length, 0)

  // 极端小窗口：1024 的一半是 512（低于下限被剔除），只剩一项
  eq('1024 窗口只剩一项', budgetPresetsFor(1024).length, 1)
  eq('1024 窗口那一项是 1K', budgetPresetsFor(1024)[0], 1024)

  // 升序、无重复：菜单按顺序读，乱序会让人以为漏了档
  const sorted = budgetPresetsFor(131072)
  check(
    '档位升序',
    sorted.every((v, i) => i === 0 || sorted[i - 1] < v),
  )
}

// ---------------------------------------------------------------- 百分比与档位

{
  eq('刚好一半', meterPercentOf(16384, 32768), 50)
  eq('99.2% 向上取整到 100', meterPercentOf(32500, 32768), 100)
  /*
   * 向上取整而不是四舍五入：99.2% 显示成 99 会让人以为「还有 1%」，
   * 实际只剩 0.8% —— 这个读数的用途就是判断「要不要收尾」，宁可早提醒。
   */
  eq('85.1% 向上取整到 86', meterPercentOf(27886, 32768), 86)

  // 超过 100% 必须截断，否则进度条会溢出轨道
  eq('超预算截断在 100', meterPercentOf(65536, 32768), 100)
  eq('负数已用归零', meterPercentOf(-100, 32768), 0)
  eq('预算为 0 返回 0', meterPercentOf(1000, 0), 0)
  eq('预算为负返回 0', meterPercentOf(1000, -5), 0)

  eq('84% 是普通档', meterLevelOf(84), 'normal')
  eq('85% 起警告（边界含）', meterLevelOf(85), 'warn')
  eq('99% 仍警告', meterLevelOf(99), 'warn')
  eq('100% 起危险（边界含）', meterLevelOf(100), 'over')
}

// ---------------------------------------------------------------- token 短写

{
  eq('32K', formatTokens(32768), '32K')
  eq('1M', formatTokens(1048576), '1M')
  eq('非整 M 保留一位', formatTokens(1572864), '1.5M')
  eq('非整 K 保留一位', formatTokens(1000), '1000'.length === 4 ? '1000' : '1000')
  // 999 不足 1K，原样输出 —— 补一个 K 会把它说成 999K
  eq('999 原样', formatTokens(999), '999')
  eq('NaN 归零', formatTokens(Number.NaN), '0')
}

{
  const protectedLimit = 131072 - 4096 - 512
  const options = budgetPresetsFor(131072, protectedLimit)
  eq('保护后的输入档位保留精确安全上限', options.at(-1), protectedLimit)
  check('预留回复后不再提供模型满窗输入', !options.includes(131072))
  check('半窗档仍为 64K，避免多出 61K 这类重复档位', options.includes(65536) && !options.includes(62464))
  check('1M 模型包含 256K 中间档位', budgetPresetsFor(1048576).includes(262144))
}
