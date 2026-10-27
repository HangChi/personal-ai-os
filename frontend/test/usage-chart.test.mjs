import { check, eq } from './assert.mjs'
import {
  buildGeometry,
  buildSeries,
  formatTokens,
  localDayKey,
  niceCeil,
  shortDateLabel,
  shiftDays,
} from '@/lib/usage-chart'

/**
 * 用量趋势图的纯逻辑。
 *
 * <p>这里每条断言都对应一个「写错不报错、只显示错」的场景：
 * 补零错 → 趋势图缺柱但没人发现；坐标错 → 柱子画到框外；
 * 日期键用 UTC → 早八点前的用量算到前一天。
 */

const day = (date, total, estimated = false) => ({
  date,
  promptTokens: total,
  completionTokens: 0,
  totalTokens: total,
  turns: 1,
  estimated,
})

// ---------- 日期键必须按本地时区 ----------
// 固定到本地 00:30：若实现改用 toISOString()，在东八区会退到前一天
const earlyMorning = new Date(2026, 9, 27, 0, 30)
eq('日期键取本地日期（不用 UTC）', localDayKey(earlyMorning), '2026-10-27')
eq('月/日补零', localDayKey(new Date(2026, 0, 5)), '2026-01-05')

// ---------- 按天平移 ----------
eq('向前平移跨月', localDayKey(shiftDays(new Date(2026, 9, 1), -1)), '2026-09-30')
eq('向后平移跨年', localDayKey(shiftDays(new Date(2026, 11, 31), 1)), '2027-01-01')

// ---------- 补零 ----------
const today = new Date(2026, 9, 27, 12, 0)
const series = buildSeries([day('2026-10-25', 100), day('2026-10-27', 300)], 3, today)
eq('序列长度等于要求的天数', series.length, 3)
eq('序列按升序排列', series.map((item) => item.date).join(','), '2026-10-25,2026-10-26,2026-10-27')
eq('缺失的日子补零', series[1].totalTokens, 0)
eq('有数据的日子保留原值', series[2].totalTokens, 300)
eq('补零的日子轮次也是 0', series[1].turns, 0)
eq('天数为 0 时返回空序列', buildSeries([], 0, today).length, 0)

// 后端返回了窗口之外的日子（时区边界导致的越界）不应混进来
const outOfRange = buildSeries([day('2026-01-01', 999), day('2026-10-27', 5)], 2, today)
eq('窗口外的数据被忽略', outOfRange.map((item) => item.totalTokens).join(','), '0,5')

// ---------- 坐标轴刻度 ----------
eq('0 的刻度是 0', niceCeil(0), 0)
eq('向上取整到 1', niceCeil(1), 1)
eq('3 取整到 5', niceCeil(3), 5)
eq('7 取整到 10', niceCeil(7), 10)
eq('1234 取整到 2000', niceCeil(1234), 2000)
eq('负数当作 0', niceCeil(-5), 0)

// ---------- 柱状几何 ----------
const geo = buildGeometry([day('2026-10-25', 50), day('2026-10-26', 100)], 20, 100)
eq('宽度原样返回', geo.width, 20)
eq('柱数等于数据点数', geo.xs.length, 2)
eq('最高柱占满高度', geo.heights[1], 100)
eq('半高柱占一半', geo.heights[0], 50)
eq('峰值参考线落在最高柱顶部', geo.peakY, 0)
check(
  '柱子都在绘图区内',
  geo.xs.every((x) => x >= 0 && x + geo.barWidth <= 20.0001),
)

// 全零：不画柱子，也没有峰值线
const zeroGeo = buildGeometry([day('2026-10-25', 0), day('2026-10-26', 0)], 20, 100)
eq('全零时最大刻度为 0', zeroGeo.maxValue, 0)
eq('全零时没有峰值线', zeroGeo.peakY, null)
check(
  '全零时所有柱高为 0',
  zeroGeo.heights.every((h) => h === 0),
)

// 空序列不能除零
const emptyGeo = buildGeometry([], 20, 100)
eq('空序列没有柱', emptyGeo.xs.length, 0)
eq('空序列没有峰值线', emptyGeo.peakY, null)

// ---------- 数字与标签格式 ----------
eq('小数字原样', formatTokens(0), '0')
eq('千位缩写', formatTokens(1500), '1.5K')
eq('整千不留小数点', formatTokens(2000), '2K')
eq('百万缩写', formatTokens(2_500_000), '2.5M')
eq('短日期标签', shortDateLabel('2026-10-27'), '10-27')
eq('非标准长度原样返回', shortDateLabel('10-27'), '10-27')
