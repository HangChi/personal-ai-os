import type { DailyUsage } from '@/types/usage'

/**
 * 用量趋势图的纯计算（清单第 7 项）。
 *
 * <h2>为什么不用现成的图表库</h2>
 * 只需要「按日柱状 + 一条峰值参考线」，而引入 ECharts/Chart.js 会给这个
 * 个人项目增加 200KB+ 的依赖，且它们的主题适配（`--el-*` 变量、暗色模式）
 * 要额外写一遍。这里用纯函数算出坐标，交给 SVG 渲染，样式完全受 CSS 变量控制。
 *
 * <h2>为什么补零的职责在这里而不是后端</h2>
 * 后端只返回**有数据的日子**（SQL 的 GROUP BY 天然如此）。
 * 「连续 30 天每天一根柱子」是展示需求，放在这里可以让后端保持
 * 「数据库里有什么就返回什么」的诚实口径，同时前端仍能画出没有断档的轴。
 */

/** 一天的日期键 yyyy-MM-dd */
export type DayKey = string

/**
 * 把稀疏的按日数据补成连续的 {@code days} 天（含今天，升序）。
 *
 * @param daily 后端返回的按日数据（只含有数据的日子）
 * @param days  需要覆盖的天数
 * @param today 今天（测试可注入固定日期）
 * @returns 长度恰好为 {@code days} 的连续序列，缺失的日子补零
 */
export function buildSeries(daily: DailyUsage[], days: number, today: Date = new Date()): DailyUsage[] {
  const safeDays = Math.max(Math.trunc(days), 0)
  if (safeDays === 0) {
    return []
  }
  const byDate = new Map<DayKey, DailyUsage>()
  for (const item of daily) {
    byDate.set(item.date, item)
  }
  const series: DailyUsage[] = []
  for (let offset = safeDays - 1; offset >= 0; offset -= 1) {
    const key = localDayKey(shiftDays(today, -offset))
    series.push(byDate.get(key) ?? emptyDay(key))
  }
  return series
}

/**
 * 本地时区的日期键。
 *
 * <p><b>不能用 {@code toISOString().slice(0,10)}</b>：那会按 UTC 取日期，
 * 在东八区，每天 08:00 之前的记录会被算到前一天 —— 后端用
 * {@code AT TIME ZONE 'Asia/Shanghai'} 分组，两者口径不同就会出现
 * 「后端说有 12 条，前端一根柱子都没有」。
 *
 * <p>项目已有 `lib/calendar-dates.ts` 的 `localDayKey`，这里刻意不 import：
 * 那个文件的职责是日历业务（含节假日/周起点等概念），用量图只需要一个
 * 纯日期格式化。重复 4 行格式化换掉一个跨模块依赖，比反向耦合划算。
 */
export function localDayKey(date: Date): DayKey {
  const year = date.getFullYear()
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const day = String(date.getDate()).padStart(2, '0')
  return `${year}-${month}-${day}`
}

/** 按天平移，保留本地时区（用毫秒加天数会在夏令时切换日偏一小时） */
export function shiftDays(date: Date, delta: number): Date {
  const shifted = new Date(date.getTime())
  shifted.setDate(shifted.getDate() + delta)
  return shifted
}

function emptyDay(date: DayKey): DailyUsage {
  return { date, promptTokens: 0, completionTokens: 0, totalTokens: 0, turns: 0, estimated: false }
}

/** 图表几何：坐标都按 viewBox 单位算，与 CSS 尺寸解耦 */
export interface ChartGeometry {
  width: number
  height: number
  barWidth: number
  /** 每根柱的 x 坐标 */
  xs: number[]
  /** 每根柱的高度（像素） */
  heights: number[]
  /** y 轴最大值（已取整到「好看」的刻度） */
  maxValue: number
  /** 峰值参考线的 y 坐标；无数据时为 null */
  peakY: number | null
}

/** 坐标轴「好看」的最大值：向上取整到 1/2/5 × 10^n */
export function niceCeil(value: number): number {
  if (value <= 0) {
    return 0
  }
  const exponent = Math.floor(Math.log10(value))
  const magnitude = Math.pow(10, exponent)
  const normalized = value / magnitude
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10
  return step * magnitude
}

/**
 * 计算柱状图几何。
 *
 * @param series 连续序列（通常已补零）
 * @param width  绘图区宽度
 * @param height 绘图区高度
 */
export function buildGeometry(series: DailyUsage[], width: number, height: number): ChartGeometry {
  const count = series.length
  const maxValue = niceCeil(Math.max(0, ...series.map((item) => item.totalTokens)))
  if (count === 0 || width <= 0 || height <= 0) {
    return { width, height, barWidth: 0, xs: [], heights: [], maxValue, peakY: null }
  }
  // 柱宽按「可用宽度 / 柱子数」的 70% 取，留出间隙；最小 1px 保证零星数据也可见
  const slot = width / count
  const barWidth = Math.max(slot * 0.7, 1)
  const xs: number[] = []
  const heights: number[] = []
  let peakIndex = -1
  for (let index = 0; index < count; index += 1) {
    const item = series[index]
    // 柱子在各自槽位居中
    xs.push(slot * index + (slot - barWidth) / 2)
    const ratio = maxValue === 0 ? 0 : item.totalTokens / maxValue
    heights.push(Math.max(ratio * height, item.totalTokens > 0 ? 1 : 0))
    if (item.totalTokens > 0 && (peakIndex === -1 || item.totalTokens > series[peakIndex].totalTokens)) {
      peakIndex = index
    }
  }
  const peakY = peakIndex === -1 ? null : height - heights[peakIndex]
  return { width, height, barWidth, xs, heights, maxValue, peakY }
}

/** 千分位 + 中文单位缩写；统计页的 token 数常在 1 万~百万级 */
export function formatTokens(value: number): string {
  if (!Number.isFinite(value)) {
    return '0'
  }
  const abs = Math.abs(value)
  if (abs >= 1_000_000) {
    return `${trimZero(value / 1_000_000)}M`
  }
  if (abs >= 1_000) {
    return `${trimZero(value / 1_000)}K`
  }
  return String(Math.round(value))
}

function trimZero(value: number): string {
  return value.toFixed(1).replace(/\.0$/, '')
}

/** 日期轴上显示的短标签：MM-DD */
export function shortDateLabel(date: DayKey): string {
  return date.length >= 10 ? date.slice(5) : date
}
